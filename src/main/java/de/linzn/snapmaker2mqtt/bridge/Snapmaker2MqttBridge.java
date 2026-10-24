package de.linzn.snapmaker2mqtt.bridge;

import de.linzn.snapmaker2mqtt.json.Json;
import de.linzn.snapmaker2mqtt.mqtt.MqttClient;
import de.linzn.snapmaker2mqtt.mqtt.MqttOptions;
import de.linzn.snapmaker2mqtt.printer.EventDetector;
import de.linzn.snapmaker2mqtt.printer.PrinterEvent;
import de.linzn.snapmaker2mqtt.printer.PrinterStatus;
import de.linzn.snapmaker2mqtt.printer.SnapmakerListener;
import de.linzn.snapmaker2mqtt.printer.SnapmakerPrinter;
import de.linzn.snapmaker2mqtt.printer.ToolheadStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Connects one or more {@link SnapmakerPrinter}s to an MQTT broker.
 *
 * <h2>Topics</h2>
 * With prefix {@code snapmaker} and printer id {@code u1}:
 * <ul>
 *   <li>{@code snapmaker/bridge/availability} – {@code online}/{@code offline} (last will, retained)</li>
 *   <li>{@code snapmaker/u1/availability} – printer reachable and Klipper ready (retained)</li>
 *   <li>{@code snapmaker/u1/status} – full status as JSON (retained)</li>
 *   <li>{@code snapmaker/u1/state/...} – single values, e.g. {@code state/progress} (retained)</li>
 *   <li>{@code snapmaker/u1/event} and {@code snapmaker/u1/event/<type>} – events as JSON</li>
 *   <li>{@code snapmaker/u1/last_event} – the most recent event (retained)</li>
 *   <li>{@code snapmaker/u1/command/<name>} – commands, answered on {@code snapmaker/u1/command/result}</li>
 * </ul>
 */
public class Snapmaker2MqttBridge implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Snapmaker2MqttBridge.class.getName());

    private final BridgeConfig config;
    private final MqttClient mqtt;
    private final String prefix;
    private final List<PrinterContext> printers = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bridge-publisher");
        t.setDaemon(true);
        return t;
    });

    public Snapmaker2MqttBridge(BridgeConfig config) {
        this.config = config;
        this.prefix = config.topicPrefix();
        this.mqtt = new MqttClient(new MqttOptions(
                config.mqttHost(), config.mqttPort(), config.mqttTls(), config.mqttClientId(),
                config.mqttUsername(), config.mqttPassword(), config.keepAliveSeconds(),
                prefix + "/bridge/availability", "offline", 1, true));

        for (BridgeConfig.PrinterEntry entry : config.printers()) {
            EventDetector detector = new EventDetector(config.progressStep(), config.layerEvents(),
                    config.machineActionEvents());
            SnapmakerPrinter printer = new SnapmakerPrinter(entry.id(), entry.host(), entry.port(),
                    entry.apiKey(), detector);
            printers.add(new PrinterContext(printer, prefix + "/" + entry.id()));
        }
    }

    public List<SnapmakerPrinter> getPrinters() {
        return printers.stream().map(c -> c.printer).toList();
    }

    public void start() {
        mqtt.setConnectionListener(connected -> {
            if (connected) {
                mqtt.publish(prefix + "/bridge/availability", "online", 1, true);
                // the broker may have lost retained values, publish everything again
                for (PrinterContext ctx : printers) {
                    scheduler.execute(ctx::republishAll);
                }
            }
        });
        mqtt.setMessageHandler(this::handleCommand);
        if (config.commandsEnabled()) {
            for (PrinterContext ctx : printers) {
                mqtt.subscribe(ctx.base + "/command/#", 1);
            }
        }
        mqtt.start();
        for (PrinterContext ctx : printers) {
            ctx.printer.addListener(ctx);
            ctx.printer.start();
        }
        long tick = Math.max(100, config.publishIntervalMs());
        scheduler.scheduleAtFixedRate(() -> printers.forEach(PrinterContext::tick), tick, tick, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        for (PrinterContext ctx : printers) {
            ctx.printer.close();
        }
        mqtt.publish(prefix + "/bridge/availability", "offline", 1, true);
        mqtt.close();
    }

    // ------------------------------------------------------------------ commands

    private void handleCommand(String topic, byte[] payloadBytes, boolean retained) {
        if (retained) {
            // never execute stale retained commands after a reconnect
            return;
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8).trim();
        for (PrinterContext ctx : printers) {
            String commandPrefix = ctx.base + "/command/";
            if (!topic.startsWith(commandPrefix)) {
                continue;
            }
            String command = topic.substring(commandPrefix.length());
            if (command.equals("result")) {
                return;
            }
            CompletableFuture<Object> result;
            try {
                result = executeCommand(ctx.printer, command, payload);
            } catch (Exception e) {
                result = CompletableFuture.failedFuture(e);
            }
            result.whenComplete((r, e) -> {
                Map<String, Object> answer = new LinkedHashMap<>();
                answer.put("command", command);
                answer.put("payload", payload);
                answer.put("success", e == null);
                if (e != null) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    answer.put("error", cause.getMessage());
                    LOG.warning("[" + ctx.printer.getId() + "] Command '" + command + "' failed: " + cause.getMessage());
                } else {
                    LOG.info("[" + ctx.printer.getId() + "] Command '" + command + "' executed");
                }
                answer.put("timestamp", Instant.now().toString());
                mqtt.publish(ctx.base + "/command/result", Json.stringify(answer), config.mqttQos(), false);
            });
            return;
        }
    }

    private CompletableFuture<Object> executeCommand(SnapmakerPrinter printer, String command, String payload) {
        return switch (command) {
            case "pause" -> printer.pause();
            case "resume" -> printer.resume();
            case "cancel" -> printer.cancel();
            case "emergency_stop" -> printer.emergencyStop();
            case "firmware_restart" -> printer.firmwareRestart();
            case "start" -> printer.startPrint(requirePayload(payload));
            case "gcode" -> {
                if (!config.gcodeCommandEnabled()) {
                    throw new IllegalStateException("G-code command is disabled (commands.gcode=false)");
                }
                yield printer.runGcode(requirePayload(payload));
            }
            default -> {
                if (command.startsWith("temperature/")) {
                    yield printer.setTemperature(command.substring("temperature/".length()),
                            Double.parseDouble(requirePayload(payload)));
                }
                throw new IllegalArgumentException("Unknown command '" + command + "'");
            }
        };
    }

    private static String requirePayload(String payload) {
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("Payload required");
        }
        return payload;
    }

    // ------------------------------------------------------------------ per printer publishing

    private final class PrinterContext implements SnapmakerListener {
        private final SnapmakerPrinter printer;
        private final String base;
        private final Map<String, String> published = new ConcurrentHashMap<>();
        private final Map<String, String> publishedRaw = new ConcurrentHashMap<>();
        private volatile PrinterStatus pending;
        private volatile PrinterStatus lastPublished;
        private volatile long lastPublishMillis;

        PrinterContext(SnapmakerPrinter printer, String base) {
            this.printer = printer;
            this.base = base;
        }

        @Override
        public void onStatus(PrinterStatus status) {
            PrinterStatus last = lastPublished;
            pending = status;
            // publish important changes immediately instead of waiting for the next tick
            if (last == null || last.online() != status.online() || last.printState() != status.printState()
                    || !Objects.equals(last.filename(), status.filename())) {
                scheduler.execute(this::publishPending);
            }
        }

        @Override
        public void onEvent(PrinterEvent event) {
            scheduler.execute(() -> {
                // make sure the status that caused the event is visible before the event itself
                publishPending();
                String json = Json.stringify(event);
                int qos = Math.max(1, config.mqttQos());
                mqtt.publish(base + "/event", json, qos, false);
                mqtt.publish(base + "/event/" + event.type().key(), json, qos, false);
                mqtt.publish(base + "/last_event", json, qos, true);
            });
        }

        @Override
        public void onConsole(String line) {
            if (config.publishConsole()) {
                mqtt.publish(base + "/console", line, 0, false);
            }
        }

        void tick() {
            PrinterStatus last = lastPublished;
            boolean heartbeatDue = System.currentTimeMillis() - lastPublishMillis
                    > config.heartbeatSeconds() * 1000L;
            if (pending != null || (heartbeatDue && last != null)) {
                if (pending == null) {
                    pending = last;
                }
                publishPending();
            }
        }

        void republishAll() {
            published.clear();
            publishedRaw.clear();
            if (pending == null) {
                pending = lastPublished != null ? lastPublished : printer.getStatus();
            }
            publishPending();
        }

        synchronized void publishPending() {
            PrinterStatus status = pending;
            if (status == null || !mqtt.isConnected()) {
                return;
            }
            pending = null;
            int qos = config.mqttQos();
            publishIfChanged(base + "/availability", status.online() ? "online" : "offline", Math.max(1, qos));
            mqtt.publish(base + "/status", Json.stringify(status), qos, true);
            if (config.publishFlat()) {
                flatten(status).forEach((key, value) ->
                        publishIfChanged(base + "/state/" + key, value == null ? "" : String.valueOf(value), qos));
            }
            if (config.publishRaw() && status.online()) {
                printer.getRawState().thenAccept(raw -> raw.forEach((name, fields) ->
                        publishRawIfChanged(base + "/raw/" + name.replace(' ', '_'), Json.stringify(fields), qos)));
            }
            lastPublished = status;
            lastPublishMillis = System.currentTimeMillis();
        }

        private void publishIfChanged(String topic, String payload, int qos) {
            if (!payload.equals(published.get(topic)) && mqtt.publish(topic, payload, qos, true)) {
                published.put(topic, payload);
            }
        }

        private void publishRawIfChanged(String topic, String payload, int qos) {
            if (!payload.equals(publishedRaw.get(topic)) && mqtt.publish(topic, payload, qos, true)) {
                publishedRaw.put(topic, payload);
            }
        }

        private Map<String, Object> flatten(PrinterStatus s) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("state", s.printState().key());
            map.put("klippy_state", s.klippyState());
            map.put("message", s.message());
            map.put("machine_state", s.machineState());
            map.put("machine_action", s.machineAction());
            map.put("filename", s.filename());
            map.put("progress", fmt(s.progress()));
            map.put("print_duration", fmtInt(s.printDuration()));
            map.put("remaining", fmtInt(s.remainingSeconds()));
            map.put("eta", s.estimatedFinish() == null ? null : s.estimatedFinish().toString());
            map.put("current_layer", s.currentLayer());
            map.put("total_layers", s.totalLayers());
            map.put("filament_used_mm", fmt(s.filamentUsedMm()));
            map.put("bed/temperature", fmt(s.bedTemperature()));
            map.put("bed/target", fmt(s.bedTarget()));
            map.put("active_toolhead", s.activeToolhead());
            for (ToolheadStatus t : s.toolheads()) {
                String p = "toolhead/" + t.index() + "/";
                map.put(p + "temperature", fmt(t.temperature()));
                map.put(p + "target", fmt(t.target()));
                map.put(p + "active", t.active());
                map.put(p + "filament_loaded", t.filamentLoaded());
                map.put(p + "filament_type", t.filamentType());
                map.put(p + "filament_color", t.filamentColor());
                map.put(p + "filament_vendor", t.filamentVendor());
            }
            s.sensors().forEach((k, v) -> map.put("sensor/" + k.replace(' ', '_'), fmt(v)));
            s.fans().forEach((k, v) -> map.put("fan/" + k.replace(' ', '_'), fmt(v * 100)));
            return map;
        }

        private static String fmt(Double v) {
            return v == null ? null : String.valueOf(Math.round(v * 10.0) / 10.0);
        }

        private static String fmtInt(Double v) {
            return v == null ? null : String.valueOf(Math.round(v));
        }
    }
}
