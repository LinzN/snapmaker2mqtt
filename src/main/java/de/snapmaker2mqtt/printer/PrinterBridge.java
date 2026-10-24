package de.snapmaker2mqtt.printer;

import de.mirranet.snapmaker.EventDetector;
import de.mirranet.snapmaker.PrintState;
import de.mirranet.snapmaker.PrinterEvent;
import de.mirranet.snapmaker.PrinterStatus;
import de.mirranet.snapmaker.SnapmakerListener;
import de.mirranet.snapmaker.SnapmakerPrinter;
import de.snapmaker2mqtt.Configuration;
import de.snapmaker2mqtt.mqtt.MqttManager;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects one printer to MQTT.
 *
 * <p>Read path: the API pushes every status change; the state is published at most once per
 * {@code publishInterval}, changes of the print state immediately. Events are published as they
 * happen. Write path: messages on the command topics are handled on this printer's own worker
 * thread, so a slow or unreachable printer never blocks the MQTT client or the other printers.
 */
public class PrinterBridge implements SnapmakerListener {

    private static final Logger LOG = Logger.getLogger(PrinterBridge.class.getName());

    private final PrinterSettings settings;
    private final MqttManager mqtt;
    private final boolean allowGcode;
    private final SnapmakerPrinter printer;
    private final ScheduledExecutorService worker;
    private final String topic;

    private volatile Boolean lastAvailable;
    private volatile PrinterStatus pending;
    private volatile PrinterStatus lastPublished;
    private volatile String lastEvent;

    public PrinterBridge(PrinterSettings settings, Configuration config, MqttManager mqtt) {
        this.settings = settings;
        this.mqtt = mqtt;
        this.allowGcode = config.allowGcode;
        this.topic = mqtt.printerTopic(settings.name());
        EventDetector detector = new EventDetector(config.progressStep, config.layerEvents, config.machineActionEvents);
        this.printer = new SnapmakerPrinter(settings.name(), settings.host(), settings.port(), settings.apiKey(), detector);
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapmaker2mqtt-" + settings.name());
            t.setDaemon(true);
            return t;
        });
    }

    public String getName() {
        return settings.name();
    }

    public SnapmakerPrinter getPrinter() {
        return printer;
    }

    public void start() {
        LOG.info("Starting printer '" + settings.name() + "' (" + settings.host() + ":" + settings.port() + ")");
        printer.addListener(this);
        long interval = settings.publishInterval().toMillis();
        worker.scheduleWithFixedDelay(this::publishPending, interval, interval, TimeUnit.MILLISECONDS);
        printer.start();
    }

    public void stop() {
        worker.shutdownNow();
        printer.close();
        mqtt.publishRetained(topic + "/availability", "offline");
    }

    // ---- read path

    @Override
    public void onStatus(PrinterStatus status) {
        boolean available = status.online();
        if (lastAvailable == null || lastAvailable != available) {
            lastAvailable = available;
            mqtt.publishRetained(topic + "/availability", available ? "online" : "offline");
            LOG.info("Printer '" + settings.name() + "' is " + (available ? "online" : "offline"));
        }
        if (!available) {
            return;
        }
        pending = status;
        PrinterStatus last = lastPublished;
        // a new print state or job is published right away instead of waiting for the next interval
        if (last == null || last.printState() != status.printState()
                || !Objects.equals(last.filename(), status.filename())) {
            submit(this::publishPending);
        }
    }

    @Override
    public void onEvent(PrinterEvent event) {
        submit(() -> {
            // the state that caused the event is visible before the event itself
            publishPending();
            String json = StateMapper.event(event).toString();
            mqtt.publish(topic + "/event", json, false);
            mqtt.publish(topic + "/event/" + event.type().key(), json, false);
            mqtt.publishRetained(topic + "/event/last", json);
            lastEvent = json;
        });
    }

    private synchronized void publishPending() {
        PrinterStatus status = pending;
        if (status == null) {
            return;
        }
        pending = null;
        mqtt.publishState(topic + "/state", StateMapper.state(status));
        lastPublished = status;
    }

    /** Re-publishes everything retained, e.g. after the broker connection came back. */
    public void republish() {
        Boolean available = lastAvailable;
        if (available != null) {
            mqtt.publishRetained(topic + "/availability", available ? "online" : "offline");
        }
        if (Boolean.TRUE.equals(available)) {
            mqtt.publishState(topic + "/state", StateMapper.state(printer.getStatus()));
        }
        String event = lastEvent;
        if (event != null) {
            mqtt.publishRetained(topic + "/event/last", event);
        }
    }

    // ---- write path

    /**
     * Handles a message below this printer's topic.
     *
     * @param subTopic the part after {@code <base>/<printer>/}, e.g. {@code set} or {@code command}
     */
    public void handle(String subTopic, String payload) {
        submit(() -> process(subTopic, payload));
    }

    private void process(String subTopic, String payload) {
        try {
            switch (subTopic) {
                case "get" -> {
                    PrinterStatus status = printer.getStatus();
                    if (status.online()) {
                        pending = status;
                        publishPending();
                    } else {
                        publishError(subTopic, payload, "printer is offline: " + status.message());
                    }
                }
                case "command" -> {
                    LOG.info("Printer '" + settings.name() + "' command: " + payload.trim());
                    report(subTopic, payload, List.of(CommandMapper.action(printer, payload)));
                }
                case "print" -> {
                    String file = payload.trim();
                    if (file.isEmpty()) {
                        throw new IllegalArgumentException("payload must be the file name to print");
                    }
                    if (printer.getStatus().printState() == PrintState.PRINTING) {
                        throw new IllegalArgumentException("printer is already printing");
                    }
                    LOG.info("Printer '" + settings.name() + "' print: " + file);
                    report(subTopic, payload, List.of(printer.startPrint(file)));
                }
                case "gcode" -> {
                    if (!allowGcode) {
                        throw new IllegalArgumentException("G-code is disabled (commands.allowGcode)");
                    }
                    LOG.info("Printer '" + settings.name() + "' gcode: " + payload.trim());
                    report(subTopic, payload, List.of(printer.runGcode(payload)));
                }
                case "set" -> {
                    JSONObject command;
                    try {
                        command = new JSONObject(payload);
                    } catch (JSONException e) {
                        throw new IllegalArgumentException("payload is not a JSON object: " + e.getMessage());
                    }
                    LOG.info("Printer '" + settings.name() + "' set: " + command);
                    report(subTopic, payload, CommandMapper.set(printer, command));
                }
                default -> {
                    if (subTopic.startsWith("set/") && subTopic.indexOf('/', 4) < 0) {
                        JSONObject command = new JSONObject().put(subTopic.substring(4), payload.trim());
                        LOG.info("Printer '" + settings.name() + "' set: " + command);
                        report(subTopic, payload, CommandMapper.set(printer, command));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            publishError(subTopic, payload, e.getMessage());
        }
    }

    /** Publishes an error for every request the printer rejects. */
    private void report(String subTopic, String payload, List<CompletableFuture<Object>> results) {
        for (CompletableFuture<Object> result : results) {
            result.whenComplete((r, e) -> {
                if (e != null) {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    publishError(subTopic, payload, cause.getMessage());
                }
            });
        }
    }

    private void publishError(String subTopic, String payload, String message) {
        LOG.warning("Printer '" + settings.name() + "' " + subTopic + " failed: " + message);
        JSONObject j = new JSONObject();
        j.put("topic", topic + "/" + subTopic);
        j.put("payload", payload);
        j.put("error", message);
        try {
            mqtt.publish(topic + "/error", j.toString(), false);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not publish error", e);
        }
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }
}
