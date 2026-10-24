package de.linzn.snapmaker2mqtt.moonraker;

import de.linzn.snapmaker2mqtt.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON-RPC client for the Moonraker WebSocket API ({@code ws://<host>:7125/websocket}),
 * which the Snapmaker U1 ships out of the box.
 * <p>
 * Handles request/response correlation, notifications and automatic reconnects.
 * All listener callbacks are executed on a single dedicated thread, so listeners
 * never see concurrent calls.
 */
public class MoonrakerClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MoonrakerClient.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final String host;
    private final int port;
    private final String apiKey;
    private final MoonrakerListener listener;
    private final HttpClient httpClient;
    private final ScheduledExecutorService executor;
    private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    private volatile WebSocket webSocket;
    private volatile boolean closed;
    private volatile long reconnectDelayMs = 1000;

    /**
     * @param host     printer IP or host name
     * @param port     Moonraker port, 7125 on the U1
     * @param apiKey   optional Moonraker API key (only needed if [authorization] is enabled), may be null
     * @param listener receives connection changes and notifications
     */
    public MoonrakerClient(String host, int port, String apiKey, MoonrakerListener listener) {
        this.host = host;
        this.port = port;
        this.apiKey = apiKey;
        this.listener = listener;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "moonraker-" + host);
            t.setDaemon(true);
            return t;
        });
    }

    public String getHost() {
        return host;
    }

    public boolean isConnected() {
        return webSocket != null;
    }

    /**
     * Starts connecting in the background. Reconnects automatically until {@link #close()}.
     */
    public void start() {
        executor.execute(this::connect);
    }

    /**
     * Executes a task on the client's event thread (the same thread that runs listener callbacks).
     */
    public void execute(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Task on Moonraker thread failed", e);
            }
        });
    }

    /**
     * Schedules a task on the client's event thread.
     */
    public void schedule(Runnable task, long delay, TimeUnit unit) {
        if (!executor.isShutdown()) {
            executor.schedule(() -> execute(task), delay, unit);
        }
    }

    /**
     * Sends a JSON-RPC request.
     *
     * @param method Moonraker method, e.g. {@code printer.objects.query}
     * @param params parameters (a Map), or {@code null}
     * @return future completed with the {@code result} value, or exceptionally with {@link MoonrakerException}
     */
    public CompletableFuture<Object> call(String method, Map<String, ?> params) {
        WebSocket ws = webSocket;
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (ws == null) {
            future.completeExceptionally(new MoonrakerException("Not connected to Moonraker", -1));
            return future;
        }
        long id = ids.getAndIncrement();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        request.put("id", id);
        pending.put(id, future);
        synchronized (this) {
            ws.sendText(Json.stringify(request), true).exceptionally(e -> {
                CompletableFuture<Object> f = pending.remove(id);
                if (f != null) {
                    f.completeExceptionally(e);
                }
                return null;
            }).join();
        }
        return future.orTimeout(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    pending.remove(id);
                    if (e instanceof TimeoutException) {
                        LOG.warning("Moonraker request '" + method + "' timed out");
                    }
                });
    }

    /**
     * Convenience wrapper that runs a G-code script ({@code printer.gcode.script}).
     */
    public CompletableFuture<Object> runGcode(String script) {
        return call("printer.gcode.script", Map.of("script", script));
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ connection handling

    private void connect() {
        if (closed) {
            return;
        }
        URI uri = URI.create("ws://" + host + ":" + port + "/websocket");
        WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-Api-Key", apiKey);
        }
        builder.buildAsync(uri, new SocketListener()).whenComplete((ws, error) -> {
            if (error != null) {
                LOG.warning("Cannot connect to Moonraker at " + uri + ": " + rootMessage(error)
                        + " (retry in " + reconnectDelayMs / 1000 + "s)");
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000);
        executor.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void onOpened(WebSocket ws) {
        webSocket = ws;
        reconnectDelayMs = 1000;
        LOG.info("Connected to Moonraker at " + host + ":" + port);
        execute(listener::onConnected);
    }

    private void onClosed(WebSocket ws, String reason) {
        if (webSocket != ws) {
            return;
        }
        webSocket = null;
        pending.values().forEach(f -> f.completeExceptionally(
                new MoonrakerException("Connection closed: " + reason, -1)));
        pending.clear();
        if (closed) {
            return;
        }
        LOG.warning("Moonraker connection to " + host + " lost: " + reason);
        execute(listener::onDisconnected);
        scheduleReconnect();
    }

    private void onMessage(String text) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Invalid JSON from Moonraker: " + e.getMessage());
            return;
        }
        Object id = msg.get("id");
        if (id instanceof Number n && (msg.containsKey("result") || msg.containsKey("error"))) {
            CompletableFuture<Object> future = pending.remove(n.longValue());
            if (future == null) {
                return;
            }
            Map<String, Object> error = Json.asMap(msg.get("error"));
            if (error != null) {
                Integer code = Json.asInt(error.get("code"));
                MoonrakerException ex = new MoonrakerException(
                        Json.asString(error.get("message")), code == null ? -1 : code);
                execute(() -> future.completeExceptionally(ex));
            } else {
                // complete off the socket thread so callers may chain blocking work
                Object result = msg.get("result");
                execute(() -> future.complete(result));
            }
            return;
        }
        Object method = msg.get("method");
        if (method instanceof String m) {
            List<Object> params = Json.asList(msg.get("params"));
            execute(() -> listener.onNotification(m, params == null ? List.of() : params));
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private final class SocketListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            onOpened(ws);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                onMessage(text);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            onClosed(ws, "closed by printer (" + statusCode + ")");
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            onClosed(ws, rootMessage(error));
        }
    }
}
