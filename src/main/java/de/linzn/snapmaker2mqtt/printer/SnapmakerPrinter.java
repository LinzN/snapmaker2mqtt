package de.linzn.snapmaker2mqtt.printer;

import de.linzn.snapmaker2mqtt.json.Json;
import de.linzn.snapmaker2mqtt.moonraker.MoonrakerClient;
import de.linzn.snapmaker2mqtt.moonraker.MoonrakerListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High level API for one Snapmaker U1.
 * <p>
 * Connects to the printer's Moonraker, subscribes to all relevant Klipper objects,
 * keeps an interpreted {@link PrinterStatus}, detects {@link PrinterEvent}s and offers
 * simple control commands.
 *
 * <pre>{@code
 * SnapmakerPrinter printer = new SnapmakerPrinter("u1", "192.168.1.50", 7125, null);
 * printer.addListener(new SnapmakerListener() {
 *     public void onEvent(PrinterEvent event) {
 *         if (event.type() == PrinterEventType.PRINT_FINISHED) {
 *             System.out.println("Done: " + event.status().filename());
 *         }
 *     }
 * });
 * printer.start();
 * }</pre>
 */
public class SnapmakerPrinter implements MoonrakerListener, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SnapmakerPrinter.class.getName());
    private static final String VERSION = "1.0.0";

    /** Objects subscribed with all their fields if the printer provides them. */
    private static final Set<String> FIXED_OBJECTS = Set.of(
            "print_stats", "virtual_sdcard", "display_status", "toolhead", "heater_bed", "webhooks",
            "idle_timeout", "fan", "print_task_config", "machine_state_manager");

    /** Object name prefixes subscribed with all their fields. */
    private static final List<String> OBJECT_PREFIXES = List.of(
            "extruder", "temperature_sensor ", "temperature_fan ", "heater_generic ", "heater_fan ",
            "fan_generic ", "controller_fan ", "filament_switch_sensor ", "filament_motion_sensor ");

    private final String id;
    private final MoonrakerClient client;
    private final EventDetector detector;
    private final List<SnapmakerListener> listeners = new CopyOnWriteArrayList<>();

    // all fields below are only accessed on the client's event thread
    private final Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
    private String klippyState = "disconnected";
    private volatile PrinterStatus status;
    private PrinterStatus lastOnlineStatus;
    private String metadataFile;
    private Double slicerEstimate;
    private boolean jobEndReported = true;
    private boolean initPending;
    private int initGeneration;

    /**
     * Creates a printer with default event options (progress every 10 %, layer and action events on).
     */
    public SnapmakerPrinter(String id, String host, int port, String apiKey) {
        this(id, host, port, apiKey, new EventDetector(10, true, true));
    }

    /**
     * @param id       free choosable id, used in MQTT topics
     * @param host     printer IP / host name
     * @param port     Moonraker port (7125)
     * @param apiKey   Moonraker API key or {@code null}
     * @param detector event detection options
     */
    public SnapmakerPrinter(String id, String host, int port, String apiKey, EventDetector detector) {
        this.id = id;
        this.detector = detector;
        this.client = new MoonrakerClient(host, port, apiKey, this);
        this.status = PrinterStatus.offline(id, klippyState, "Not connected yet", null);
    }

    public String getId() {
        return id;
    }

    /**
     * @return latest status snapshot (never null)
     */
    public PrinterStatus getStatus() {
        return status;
    }

    /**
     * @return low level Moonraker client for custom JSON-RPC calls
     */
    public MoonrakerClient getClient() {
        return client;
    }

    /**
     * @return a copy of the raw Klipper object states as received from Moonraker
     */
    public CompletableFuture<Map<String, Map<String, Object>>> getRawState() {
        CompletableFuture<Map<String, Map<String, Object>>> future = new CompletableFuture<>();
        client.execute(() -> {
            Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
            raw.forEach((k, v) -> copy.put(k, new LinkedHashMap<>(v)));
            future.complete(copy);
        });
        return future;
    }

    public void addListener(SnapmakerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SnapmakerListener listener) {
        listeners.remove(listener);
    }

    /**
     * Connects to the printer in the background (with automatic reconnect).
     */
    public void start() {
        client.start();
    }

    @Override
    public void close() {
        client.close();
    }

    // ------------------------------------------------------------------ commands

    public CompletableFuture<Object> pause() {
        return client.call("printer.print.pause", null);
    }

    public CompletableFuture<Object> resume() {
        return client.call("printer.print.resume", null);
    }

    public CompletableFuture<Object> cancel() {
        return client.call("printer.print.cancel", null);
    }

    /**
     * Immediately halts the printer (M112). Requires a firmware restart afterwards.
     */
    public CompletableFuture<Object> emergencyStop() {
        return client.call("printer.emergency_stop", null);
    }

    public CompletableFuture<Object> firmwareRestart() {
        return client.call("printer.firmware_restart", null);
    }

    /**
     * Starts printing a file that already exists on the printer.
     */
    public CompletableFuture<Object> startPrint(String filename) {
        return client.call("printer.print.start", Map.of("filename", filename));
    }

    public CompletableFuture<Object> runGcode(String script) {
        return client.runGcode(script);
    }

    /**
     * Sets a heater target temperature.
     *
     * @param heater Klipper heater name, e.g. {@code heater_bed}, {@code extruder}, {@code extruder2}
     * @param target target in °C, 0 to switch off
     */
    public CompletableFuture<Object> setTemperature(String heater, double target) {
        if (!heater.matches("[A-Za-z0-9_ ]+")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid heater name"));
        }
        return client.runGcode("SET_HEATER_TEMPERATURE HEATER=" + heater.trim() + " TARGET=" + target);
    }

    // ------------------------------------------------------------------ Moonraker events

    @Override
    public void onConnected() {
        Map<String, Object> identify = new LinkedHashMap<>();
        identify.put("client_name", "Snapmaker2MQTT");
        identify.put("version", VERSION);
        identify.put("type", "other");
        identify.put("url", "https://github.com/LinzN/snapmaker2mqtt");
        client.call("server.connection.identify", identify)
                .whenComplete((r, e) -> initKlippy());
    }

    @Override
    public void onDisconnected() {
        klippyState = "disconnected";
        goOffline("Printer not reachable");
    }

    @Override
    public void onNotification(String method, List<Object> params) {
        switch (method) {
            case "notify_status_update" -> {
                Map<String, Object> diff = params.isEmpty() ? null : Json.asMap(params.get(0));
                if (diff != null && "ready".equals(klippyState)) {
                    merge(diff);
                    update();
                }
            }
            case "notify_klippy_ready" -> initKlippy();
            case "notify_klippy_shutdown" -> {
                klippyState = "shutdown";
                goOffline("Klipper shutdown");
                scheduleInit();
            }
            case "notify_klippy_disconnected" -> {
                klippyState = "disconnected";
                goOffline("Klipper disconnected");
                scheduleInit();
            }
            case "notify_gcode_response" -> {
                String line = params.isEmpty() ? null : Json.asString(params.get(0));
                if (line != null) {
                    listeners.forEach(l -> safe(() -> l.onConsole(line)));
                }
            }
            case "notify_history_changed" -> handleHistory(params);
            default -> {
                // other notifications are not relevant here
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private void initKlippy() {
        initPending = false;
        int generation = ++initGeneration;
        client.call("printer.info", null).thenAccept(result -> {
            String state = Json.asString(Json.get(result, "state"));
            klippyState = state == null ? "unknown" : state;
            if (!"ready".equals(klippyState)) {
                goOffline(Json.asString(Json.get(result, "state_message")));
                scheduleInit();
                return;
            }
            if (generation == initGeneration) {
                subscribeObjects();
            }
        }).exceptionally(e -> {
            LOG.fine("printer.info failed: " + e.getMessage());
            client.execute(this::scheduleInit);
            return null;
        });
    }

    private void scheduleInit() {
        if (!initPending && client.isConnected()) {
            initPending = true;
            int generation = initGeneration;
            client.schedule(() -> {
                // skip if another initialization (e.g. notify_klippy_ready) happened meanwhile
                if (initPending && generation == initGeneration) {
                    initKlippy();
                }
            }, 2, TimeUnit.SECONDS);
        }
    }

    private void subscribeObjects() {
        client.call("printer.objects.list", null).thenCompose(result -> {
            List<Object> available = Json.asList(Json.get(result, "objects"));
            Map<String, Object> objects = new LinkedHashMap<>();
            if (available != null) {
                for (Object o : available) {
                    String name = String.valueOf(o);
                    if (FIXED_OBJECTS.contains(name) || OBJECT_PREFIXES.stream().anyMatch(name::startsWith)) {
                        objects.put(name, null);
                    }
                }
            }
            LOG.info("[" + id + "] Subscribing to " + objects.size() + " printer objects");
            return client.call("printer.objects.subscribe", Map.of("objects", objects));
        }).thenAccept(result -> {
            raw.clear();
            Map<String, Object> initial = Json.asMap(Json.get(result, "status"));
            if (initial != null) {
                merge(initial);
            }
            update();
        }).exceptionally(e -> {
            LOG.warning("[" + id + "] Subscribing to printer objects failed: " + e.getMessage());
            client.execute(this::scheduleInit);
            return null;
        });
    }

    private void merge(Map<String, Object> diff) {
        for (Map.Entry<String, Object> entry : diff.entrySet()) {
            Map<String, Object> fields = Json.asMap(entry.getValue());
            if (fields == null) {
                continue;
            }
            deepMerge(raw.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>()), fields);
        }
    }

    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Map<String, Object> sourceMap = Json.asMap(entry.getValue());
            Map<String, Object> targetMap = Json.asMap(target.get(entry.getKey()));
            if (sourceMap != null && targetMap != null) {
                deepMerge(targetMap, sourceMap);
            } else {
                target.put(entry.getKey(), sourceMap != null ? new LinkedHashMap<>(sourceMap) : entry.getValue());
            }
        }
    }

    private void update() {
        String filename = Json.asString(Json.get(raw, "print_stats", "filename"));
        if (filename != null && !filename.isBlank() && !filename.equals(metadataFile)) {
            metadataFile = filename;
            slicerEstimate = null;
            fetchMetadata(filename);
        }
        PrinterStatus next = PrinterStatus.fromRaw(id, klippyState, raw, slicerEstimate);
        PrinterStatus previous = status;
        status = next;

        List<PrinterEvent> events = new ArrayList<>();
        if (previous == null || !previous.online()) {
            events.add(EventDetector.event(PrinterEventType.PRINTER_ONLINE, next,
                    EventDetector.details("klippy_state", klippyState)));
        }
        events.addAll(detector.detect(lastOnlineStatus, next));
        lastOnlineStatus = next;

        listeners.forEach(l -> safe(() -> l.onStatus(next)));
        for (PrinterEvent event : events) {
            switch (event.type()) {
                case PRINT_STARTED -> jobEndReported = false;
                case PRINT_FINISHED, PRINT_CANCELLED, PRINT_FAILED -> jobEndReported = true;
                default -> {
                }
            }
            fire(event);
        }
    }

    private void fetchMetadata(String filename) {
        client.call("server.files.metadata", Map.of("filename", filename)).thenAccept(result -> {
            Double estimate = Json.asDouble(Json.get(result, "estimated_time"));
            if (filename.equals(metadataFile) && estimate != null && estimate > 0) {
                slicerEstimate = estimate;
                if ("ready".equals(klippyState)) {
                    update();
                }
            }
        }).exceptionally(e -> {
            LOG.fine("No metadata for " + filename + ": " + e.getMessage());
            return null;
        });
    }

    /**
     * Fallback for job ends that are not visible as a print_stats state
     * (e.g. Klipper went from printing straight to standby or shut down during a print).
     */
    private void handleHistory(List<Object> params) {
        Map<String, Object> payload = params.isEmpty() ? null : Json.asMap(params.get(0));
        if (payload == null || !"finished".equals(payload.get("action")) || jobEndReported) {
            return;
        }
        String jobStatus = Json.asString(Json.get(payload, "job", "status"));
        PrinterEventType type;
        if ("completed".equals(jobStatus)) {
            type = PrinterEventType.PRINT_FINISHED;
        } else if ("cancelled".equals(jobStatus)) {
            type = PrinterEventType.PRINT_CANCELLED;
        } else {
            type = PrinterEventType.PRINT_FAILED;
        }
        jobEndReported = true;
        fire(EventDetector.event(type, status, EventDetector.details(
                "source", "job_history", "job_status", jobStatus,
                "filename", Json.get(payload, "job", "filename"))));
    }

    private void goOffline(String message) {
        PrinterStatus previous = status;
        raw.clear();
        status = PrinterStatus.offline(id, klippyState, message, lastOnlineStatus);
        PrinterStatus offline = status;
        listeners.forEach(l -> safe(() -> l.onStatus(offline)));
        if (previous.online()) {
            fire(EventDetector.event(PrinterEventType.PRINTER_OFFLINE, offline,
                    EventDetector.details("klippy_state", klippyState, "message", message)));
        }
    }

    private void fire(PrinterEvent event) {
        LOG.info("[" + id + "] Event " + event.type().key()
                + (event.details().isEmpty() ? "" : " " + Json.stringify(event.details())));
        listeners.forEach(l -> safe(() -> l.onEvent(event)));
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Listener failed", e);
        }
    }
}
