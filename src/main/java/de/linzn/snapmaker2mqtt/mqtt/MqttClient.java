package de.linzn.snapmaker2mqtt.mqtt;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small, dependency-free MQTT 3.1.1 client.
 * <p>
 * Features: username/password, TLS, last will, QoS 0/1 publish, QoS 0/1 subscribe,
 * keep-alive and automatic reconnect with re-subscription.
 * Messages published while disconnected are dropped (the bridge republishes its full
 * state on every reconnect, so no offline queue is required).
 */
public class MqttClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MqttClient.class.getName());

    private static final int CONNECT = 0x10;
    private static final int CONNACK = 0x20;
    private static final int PUBLISH = 0x30;
    private static final int PUBACK = 0x40;
    private static final int SUBSCRIBE = 0x82;
    private static final int SUBACK = 0x90;
    private static final int PINGREQ = 0xC0;
    private static final int PINGRESP = 0xD0;
    private static final int DISCONNECT = 0xE0;

    private final MqttOptions options;
    private final Map<String, Integer> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger packetIds = new AtomicInteger(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final Object writeLock = new Object();

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile long lastPingResponse;
    private volatile long reconnectDelayMs = 1000;

    private volatile MqttMessageHandler messageHandler = (topic, payload, retained) -> { };
    private volatile Consumer<Boolean> connectionListener = state -> { };

    public MqttClient(MqttOptions options) {
        this.options = options;
    }

    /**
     * Sets the handler for incoming messages on subscribed topics.
     */
    public void setMessageHandler(MqttMessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * Sets a listener that is called with {@code true} after every (re)connect and
     * {@code false} after a connection loss. Called from the client's internal threads.
     */
    public void setConnectionListener(Consumer<Boolean> listener) {
        this.connectionListener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Starts connecting in the background. Reconnects automatically until {@link #close()} is called.
     */
    public void start() {
        scheduler.execute(this::connectLoop);
    }

    /**
     * Subscribes to a topic filter. The subscription is restored after every reconnect.
     *
     * @param topicFilter topic filter, wildcards {@code +} and {@code #} allowed
     * @param qos         0 or 1
     */
    public void subscribe(String topicFilter, int qos) {
        subscriptions.put(topicFilter, Math.min(qos, 1));
        if (connected) {
            try {
                sendSubscribe(topicFilter, Math.min(qos, 1));
            } catch (IOException e) {
                handleConnectionLoss(e);
            }
        }
    }

    public boolean publish(String topic, String payload, int qos, boolean retain) {
        return publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, retain);
    }

    /**
     * Publishes a message.
     *
     * @return {@code false} if the client is currently not connected (message dropped)
     */
    public boolean publish(String topic, byte[] payload, int qos, boolean retain) {
        if (!connected) {
            return false;
        }
        qos = Math.min(Math.max(qos, 0), 1);
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeString(body, topic);
            if (qos > 0) {
                writeShort(body, nextPacketId());
            }
            body.write(payload);
            sendPacket(PUBLISH | (qos << 1) | (retain ? 1 : 0), body.toByteArray());
            return true;
        } catch (IOException e) {
            handleConnectionLoss(e);
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (connected) {
            try {
                sendPacket(DISCONNECT, new byte[0]);
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        connected = false;
        closeSocket();
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ connection handling

    private void connectLoop() {
        if (closed || connected) {
            return;
        }
        try {
            connectOnce();
            reconnectDelayMs = 1000;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MQTT connect to " + options.host() + ":" + options.port()
                    + " failed: " + e.getMessage() + " (retry in " + reconnectDelayMs / 1000 + "s)");
            closeSocket();
            if (!closed) {
                scheduler.schedule(this::connectLoop, reconnectDelayMs, TimeUnit.MILLISECONDS);
                reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000);
            }
        }
    }

    private void connectOnce() throws IOException {
        SocketFactory factory = options.tls() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        Socket s = factory.createSocket();
        s.connect(new InetSocketAddress(options.host(), options.port()), 10_000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(10_000);
        socket = s;
        out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());

        sendPacket(CONNECT, buildConnectPayload());

        int header = in.readUnsignedByte();
        int length = readRemainingLength(in);
        byte[] ack = in.readNBytes(length);
        if ((header & 0xF0) != CONNACK || ack.length < 2) {
            throw new IOException("Unexpected answer to CONNECT");
        }
        if (ack[1] != 0) {
            throw new IOException("Broker refused connection, return code " + ack[1]
                    + describeConnackCode(ack[1]));
        }

        s.setSoTimeout(0);
        connected = true;
        lastPingResponse = System.currentTimeMillis();
        LOG.info("MQTT connected to " + options.host() + ":" + options.port());

        Thread reader = new Thread(() -> readLoop(in, s), "mqtt-reader");
        reader.setDaemon(true);
        reader.start();

        for (Map.Entry<String, Integer> sub : subscriptions.entrySet()) {
            sendSubscribe(sub.getKey(), sub.getValue());
        }
        long interval = Math.max(1, options.keepAliveSeconds() / 2);
        scheduler.schedule(() -> keepAlive(s, interval), interval, TimeUnit.SECONDS);
        safeNotify(true);
    }

    private static String describeConnackCode(int code) {
        return switch (code) {
            case 1 -> " (unacceptable protocol version)";
            case 2 -> " (identifier rejected)";
            case 3 -> " (server unavailable)";
            case 4 -> " (bad username or password)";
            case 5 -> " (not authorized)";
            default -> "";
        };
    }

    private byte[] buildConnectPayload() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT");
        body.write(4); // protocol level 3.1.1
        int flags = 0x02; // clean session
        if (options.willTopic() != null) {
            flags |= 0x04;
            flags |= (Math.min(options.willQos(), 1) << 3);
            if (options.willRetain()) {
                flags |= 0x20;
            }
        }
        if (options.username() != null && !options.username().isEmpty()) {
            flags |= 0x80;
            if (options.password() != null) {
                flags |= 0x40;
            }
        }
        body.write(flags);
        writeShort(body, options.keepAliveSeconds());
        writeString(body, options.clientId());
        if (options.willTopic() != null) {
            writeString(body, options.willTopic());
            byte[] willPayload = options.willPayload() == null ? new byte[0]
                    : options.willPayload().getBytes(StandardCharsets.UTF_8);
            writeShort(body, willPayload.length);
            body.write(willPayload);
        }
        if ((flags & 0x80) != 0) {
            writeString(body, options.username());
            if ((flags & 0x40) != 0) {
                writeString(body, options.password());
            }
        }
        return body.toByteArray();
    }

    private void keepAlive(Socket s, long intervalSeconds) {
        if (closed || !connected || socket != s) {
            return;
        }
        long silence = System.currentTimeMillis() - lastPingResponse;
        if (silence > options.keepAliveSeconds() * 1500L + intervalSeconds * 1000L) {
            handleConnectionLoss(new IOException("No PINGRESP from broker"));
            return;
        }
        try {
            sendPacket(PINGREQ, new byte[0]);
        } catch (IOException e) {
            handleConnectionLoss(e);
            return;
        }
        scheduler.schedule(() -> keepAlive(s, intervalSeconds), intervalSeconds, TimeUnit.SECONDS);
    }

    private void readLoop(DataInputStream in, Socket s) {
        try {
            while (!closed && socket == s) {
                int header = in.readUnsignedByte();
                int length = readRemainingLength(in);
                byte[] body = in.readNBytes(length);
                if (body.length != length) {
                    throw new EOFException("Connection closed by broker");
                }
                switch (header & 0xF0) {
                    case PUBLISH -> handlePublish(header, body);
                    case PINGRESP -> lastPingResponse = System.currentTimeMillis();
                    case PUBACK, SUBACK -> {
                        // acknowledgements need no further handling in this client
                    }
                    default -> LOG.fine("Ignoring MQTT packet type 0x" + Integer.toHexString(header));
                }
            }
        } catch (IOException e) {
            if (socket == s) {
                handleConnectionLoss(e);
            }
        }
    }

    private void handlePublish(int header, byte[] body) throws IOException {
        int qos = (header >> 1) & 0x03;
        boolean retained = (header & 0x01) != 0;
        int topicLength = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        String topic = new String(body, 2, topicLength, StandardCharsets.UTF_8);
        int offset = 2 + topicLength;
        if (qos > 0) {
            int packetId = ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
            offset += 2;
            ByteArrayOutputStream ack = new ByteArrayOutputStream();
            writeShort(ack, packetId);
            sendPacket(PUBACK, ack.toByteArray());
        }
        byte[] payload = new byte[body.length - offset];
        System.arraycopy(body, offset, payload, 0, payload.length);
        try {
            messageHandler.onMessage(topic, payload, retained);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MQTT message handler failed for topic " + topic, e);
        }
    }

    private void handleConnectionLoss(Exception cause) {
        synchronized (this) {
            if (!connected) {
                return;
            }
            connected = false;
        }
        LOG.warning("MQTT connection lost: " + (cause.getMessage() != null
                ? cause.getMessage() : cause.getClass().getSimpleName()));
        closeSocket();
        safeNotify(false);
        if (!closed) {
            scheduler.schedule(this::connectLoop, reconnectDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void safeNotify(boolean state) {
        try {
            connectionListener.accept(state);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MQTT connection listener failed", e);
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    // ------------------------------------------------------------------ wire format

    private void sendSubscribe(String topicFilter, int qos) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeShort(body, nextPacketId());
        writeString(body, topicFilter);
        body.write(qos);
        sendPacket(SUBSCRIBE, body.toByteArray());
    }

    private void sendPacket(int header, byte[] body) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream(body.length + 5);
        packet.write(header);
        int length = body.length;
        do {
            int digit = length % 128;
            length /= 128;
            if (length > 0) {
                digit |= 0x80;
            }
            packet.write(digit);
        } while (length > 0);
        packet.write(body);
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) {
                throw new IOException("Not connected");
            }
            o.write(packet.toByteArray());
            o.flush();
        }
    }

    private static int readRemainingLength(DataInputStream in) throws IOException {
        int multiplier = 1;
        int value = 0;
        int digit;
        int count = 0;
        do {
            digit = in.readUnsignedByte();
            value += (digit & 0x7F) * multiplier;
            multiplier *= 128;
            if (++count > 4) {
                throw new IOException("Malformed remaining length");
            }
        } while ((digit & 0x80) != 0);
        return value;
    }

    private int nextPacketId() {
        return packetIds.getAndUpdate(id -> id >= 0xFFFF ? 1 : id + 1);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeShort(out, bytes.length);
        out.write(bytes);
    }
}
