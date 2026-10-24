/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.mirranet.snapmaker;

import de.mirranet.snapmaker.moonraker.MoonrakerClient;
import de.mirranet.snapmaker.moonraker.MoonrakerListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * API for one Snapmaker U1.
 *
 * <p>Connects to the printer's Moonraker, subscribes to all relevant Klipper objects, keeps an
 * interpreted {@link PrinterStatus}, detects {@link PrinterEvent}s and offers control commands.
 * Reconnects on its own when the printer is switched off or Klipper restarts.
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

    /** Objects subscribed with all their fields if the printer provides them. */
    private static final Set<String> FIXED_OBJECTS = Set.of(
            "print_stats", "virtual_sdcard", "display_status", "toolhead", "heater_bed", "webhooks",
            "idle_timeout", "fan", "print_task_config", "machine_state_manager");

    /** Object name prefixes subscribed with all their fields. */
    private static final List<String> OBJECT_PREFIXES = List.of(
            "extruder", "temperature_sensor ", "temperature_fan ", "heater_generic ", "heater_fan ",
            "fan_generic ", "controller_fan ", "filament_switch_sensor ", "filament_motion_sensor ");

    private final String name;
    private final MoonrakerClient client;
    private final EventDetector detector;
    private final List<SnapmakerListener> listeners = new CopyOnWriteArrayList<>();

    // only accessed on the client's event thread
    private final JSONObject raw = new JSONObject();
    private String klippyState = "disconnected";
    private PrinterStatus lastOnlineStatus;
    private String metadataFile;
    private Double slicerEstimate;
    private boolean jobEndReported = true;
    private boolean initPending;
    private int initGeneration;

    private volatile PrinterStatus status;

    /**
     * Creates a printer with the default event options (progress every 10 %, layer and machine
     * action events on).
     */
    public SnapmakerPrinter(String name, String host, int port, String apiKey) {
        this(name, host, port, apiKey, new EventDetector(10, true, true));
    }

    /**
     * @param name     free name of the printer, reported in every status and event
     * @param host     printer IP or host name
     * @param port     Moonraker port (7125)
     * @param apiKey   Moonraker API key or {@code null}
     * @param detector event detection options
     */
    public SnapmakerPrinter(String name, String host, int port, String apiKey, EventDetector detector) {
        this.name = name;
        this.detector = detector;
        this.client = new MoonrakerClient(host, port, apiKey, this);
        this.status = PrinterStatus.offline(name, klippyState, "not connected yet", null);
    }

    public String getName() {
        return name;
    }

    /**
     * @return the latest status (never null)
     */
    public PrinterStatus getStatus() {
        return status;
    }

    /**
     * @return the Moonraker client, for any other JSON-RPC method
     */
    public MoonrakerClient getClient() {
        return client;
    }

    /**
     * @return a copy of the raw Klipper object states as received from Moonraker
     */
    public CompletableFuture<JSONObject> getRawState() {
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        client.execute(() -> future.complete(new JSONObject(raw.toString())));
        return future;
    }

    public void addListener(SnapmakerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SnapmakerListener listener) {
        listeners.remove(listener);
    }

    /**
     * Connects to the printer in the background.
     */
    public void start() {
        client.start();
    }

    @Override
    public void close() {
        client.close();
    }

    // ---- commands

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
     * Halts the printer immediately (M112). Klipper needs a firmware restart afterwards.
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
        return client.call("printer.print.start", new JSONObject().put("filename", filename));
    }

    public CompletableFuture<Object> runGcode(String script) {
        return client.runGcode(script);
    }

    /**
     * Sets a heater target temperature.
     *
     * @param heater Klipper heater name: {@code heater_bed}, {@code extruder}, {@code extruder1}, ...
     * @param target target in °C, 0 switches the heater off
     */
    public CompletableFuture<Object> setTemperature(String heater, double target) {
        if (!heater.matches("[A-Za-z0-9_]+")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid heater name: " + heater));
        }
        return client.runGcode("SET_HEATER_TEMPERATURE HEATER=" + heater + " TARGET=" + target);
    }

    /**
     * Sets a toolhead's nozzle target temperature.
     *
     * @param toolhead toolhead index 0..3
     */
    public CompletableFuture<Object> setToolheadTemperature(int toolhead, double target) {
        return setTemperature(toolhead == 0 ? "extruder" : "extruder" + toolhead, target);
    }

    /**
     * Sets the part cooling fan speed.
     *
     * @param percent 0..100
     */
    public CompletableFuture<Object> setPartFanSpeed(double percent) {
        long value = Math.round(Math.max(0, Math.min(100, percent)) * 2.55);
        return client.runGcode("M106 S" + value);
    }

    // ---- Moonraker events

    @Override
    public void onConnected() {
        JSONObject identify = new JSONObject()
                .put("client_name", "snapmaker2mqtt")
                .put("version", "1.0.0")
                .put("type", "other")
                .put("url", "https://github.com/LinzN/snapmaker2mqtt");
        client.call("server.connection.identify", identify).whenComplete((r, e) -> client.execute(this::initKlippy));
    }

    @Override
    public void onDisconnected() {
        klippyState = "disconnected";
        goOffline("printer not reachable");
    }

    @Override
    public void onNotification(String method, JSONArray params) {
        switch (method) {
            case "notify_status_update" -> {
                JSONObject diff = params.optJSONObject(0);
                if (diff != null && "ready".equals(klippyState)) {
                    merge(raw, diff);
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
                String line = params.optString(0, null);
                if (line != null) {
                    listeners.forEach(l -> safe(() -> l.onConsole(line)));
                }
            }
            case "notify_history_changed" -> handleHistory(params.optJSONObject(0));
            default -> {
                // not relevant
            }
        }
    }

    // ---- internals

    private void initKlippy() {
        initPending = false;
        int generation = ++initGeneration;
        client.call("printer.info", null).thenAccept(result -> {
            JSONObject info = result instanceof JSONObject o ? o : new JSONObject();
            klippyState = info.optString("state", "unknown");
            if (!"ready".equals(klippyState)) {
                goOffline(info.optString("state_message", "Klipper not ready"));
                scheduleInit();
            } else if (generation == initGeneration) {
                subscribeObjects();
            }
        }).exceptionally(e -> {
            LOG.fine("printer.info failed: " + e.getMessage());
            client.execute(this::scheduleInit);
            return null;
        });
    }

    /** Asks again in 2 s whether Klipper is ready, unless something else triggered that already. */
    private void scheduleInit() {
        if (!initPending && client.isConnected()) {
            initPending = true;
            int generation = initGeneration;
            client.schedule(() -> {
                if (initPending && generation == initGeneration) {
                    initKlippy();
                }
            }, 2, TimeUnit.SECONDS);
        }
    }

    private void subscribeObjects() {
        client.call("printer.objects.list", null).thenCompose(result -> {
            JSONArray available = result instanceof JSONObject o ? o.optJSONArray("objects") : null;
            JSONObject objects = new JSONObject();
            if (available != null) {
                for (int i = 0; i < available.length(); i++) {
                    String object = available.optString(i);
                    if (FIXED_OBJECTS.contains(object) || OBJECT_PREFIXES.stream().anyMatch(object::startsWith)) {
                        objects.put(object, JSONObject.NULL);
                    }
                }
            }
            LOG.info("Printer '" + name + "': subscribing to " + objects.length() + " Klipper objects");
            return client.call("printer.objects.subscribe", new JSONObject().put("objects", objects));
        }).thenAccept(result -> {
            raw.clear();
            JSONObject initial = result instanceof JSONObject o ? o.optJSONObject("status") : null;
            if (initial != null) {
                merge(raw, initial);
            }
            update();
        }).exceptionally(e -> {
            LOG.warning("Printer '" + name + "': subscribing failed: " + e.getMessage());
            client.execute(this::scheduleInit);
            return null;
        });
    }

    /** Moonraker only sends changed fields, so updates are merged into the known state. */
    private static void merge(JSONObject target, JSONObject source) {
        for (String key : source.keySet()) {
            Object value = source.get(key);
            JSONObject existing = target.optJSONObject(key);
            if (value instanceof JSONObject sourceObject && existing != null) {
                merge(existing, sourceObject);
            } else {
                target.put(key, value);
            }
        }
    }

    private void update() {
        JSONObject printStats = raw.optJSONObject("print_stats");
        String filename = printStats == null ? null : printStats.optString("filename", "");
        if (filename != null && !filename.isBlank() && !filename.equals(metadataFile)) {
            metadataFile = filename;
            slicerEstimate = null;
            fetchMetadata(filename);
        }
        PrinterStatus next = PrinterStatus.fromRaw(name, klippyState, raw, slicerEstimate);
        PrinterStatus previous = status;
        status = next;

        List<PrinterEvent> events = new ArrayList<>();
        if (!previous.online()) {
            events.add(EventDetector.event(PrinterEventType.PRINTER_ONLINE, next,
                    EventDetector.details("klippyState", klippyState)));
        }
        // after an outage compare with the last online state, so a job that ended meanwhile is reported
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
        client.call("server.files.metadata", new JSONObject().put("filename", filename)).thenAccept(result -> {
            double estimate = result instanceof JSONObject o ? o.optDouble("estimated_time", 0) : 0;
            if (filename.equals(metadataFile) && estimate > 0) {
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
     * Fallback for job ends that do not show up as a print_stats state, e.g. Klipper went from
     * printing straight to standby or shut down during a print.
     */
    private void handleHistory(JSONObject payload) {
        if (payload == null || !"finished".equals(payload.optString("action")) || jobEndReported) {
            return;
        }
        JSONObject job = payload.optJSONObject("job");
        String jobStatus = job == null ? null : job.optString("status", null);
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
                "source", "jobHistory", "jobStatus", jobStatus,
                "filename", job == null ? null : job.optString("filename", null))));
    }

    private void goOffline(String message) {
        PrinterStatus previous = status;
        raw.clear();
        PrinterStatus offline = PrinterStatus.offline(name, klippyState, message, lastOnlineStatus);
        status = offline;
        listeners.forEach(l -> safe(() -> l.onStatus(offline)));
        if (previous.online()) {
            fire(EventDetector.event(PrinterEventType.PRINTER_OFFLINE, offline,
                    EventDetector.details("klippyState", klippyState, "message", message)));
        }
    }

    private void fire(PrinterEvent event) {
        LOG.info("Printer '" + name + "': " + event.type().key()
                + (event.details().isEmpty() ? "" : " " + event.details()));
        listeners.forEach(l -> safe(() -> l.onEvent(event)));
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Listener failed", e);
        }
    }
}
