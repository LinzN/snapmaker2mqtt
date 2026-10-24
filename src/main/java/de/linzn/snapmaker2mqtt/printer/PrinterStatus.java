package de.linzn.snapmaker2mqtt.printer;

import de.linzn.snapmaker2mqtt.json.Json;
import de.linzn.snapmaker2mqtt.json.JsonSerializable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable, interpreted snapshot of a Snapmaker U1.
 * Built from the raw Klipper object states by {@link #fromRaw}.
 *
 * @param printerId        id of the printer as configured in the bridge
 * @param online           Moonraker reachable and Klippy ready
 * @param klippyState      Klippy state: ready, startup, shutdown, error, disconnected
 * @param printState       job state
 * @param message          message from {@code display_status} (M117) or Klippy state message
 * @param filename         currently loaded G-code file
 * @param progress         job progress in percent (0..100)
 * @param printDuration    seconds actually spent printing (without pauses)
 * @param totalDuration    seconds since the job started (including pauses)
 * @param filamentUsedMm   filament used by the current job in mm
 * @param currentLayer     current layer (if the slicer emits SET_PRINT_STATS_INFO)
 * @param totalLayers      total layers
 * @param slicerEstimate   total print time estimated by the slicer in seconds
 * @param remainingSeconds estimated remaining time in seconds
 * @param bedTemperature   bed temperature in °C
 * @param bedTarget        bed target temperature in °C
 * @param toolheads        the four toolheads with their loaded filament
 * @param activeToolhead   index of the active toolhead, or -1
 * @param sensors          additional temperature sensors (e.g. chamber) by name
 * @param fans             fan speeds 0..1 by name
 * @param machineState     U1 main state (idle, printing, auto_load, ...)
 * @param machineAction    U1 action (homing, auto_clean_nozzle, ...)
 * @param timestamp        creation time of this snapshot
 */
public record PrinterStatus(String printerId,
                            boolean online,
                            String klippyState,
                            PrintState printState,
                            String message,
                            String filename,
                            Double progress,
                            Double printDuration,
                            Double totalDuration,
                            Double filamentUsedMm,
                            Integer currentLayer,
                            Integer totalLayers,
                            Double slicerEstimate,
                            Double remainingSeconds,
                            Double bedTemperature,
                            Double bedTarget,
                            List<ToolheadStatus> toolheads,
                            int activeToolhead,
                            Map<String, Double> sensors,
                            Map<String, Double> fans,
                            String machineState,
                            String machineAction,
                            Instant timestamp) implements JsonSerializable {

    /**
     * Creates the status for a printer that is unreachable or whose Klippy is not ready.
     */
    public static PrinterStatus offline(String printerId, String klippyState, String message, PrinterStatus last) {
        return new PrinterStatus(printerId, false, klippyState, PrintState.OFFLINE, message,
                last == null ? null : last.filename(), null, null, null, null, null, null, null, null,
                null, null, last == null ? List.of() : last.toolheads(), -1, Map.of(), Map.of(),
                null, null, Instant.now());
    }

    /**
     * Interprets the raw Klipper object states.
     *
     * @param printerId      configured printer id
     * @param klippyState    current Klippy state
     * @param raw            merged object states (object name → field map)
     * @param slicerEstimate slicer time estimate of the current file in seconds, may be null
     */
    public static PrinterStatus fromRaw(String printerId, String klippyState, Map<String, Map<String, Object>> raw,
                                        Double slicerEstimate) {
        Map<String, Object> printStats = obj(raw, "print_stats");
        Map<String, Object> toolhead = obj(raw, "toolhead");
        Map<String, Object> taskConfig = obj(raw, "print_task_config");
        Map<String, Object> machine = obj(raw, "machine_state_manager");

        PrintState state = PrintState.fromKlipper(Json.asString(printStats.get("state")));
        String filename = emptyToNull(Json.asString(printStats.get("filename")));

        String message = emptyToNull(Json.asString(obj(raw, "display_status").get("message")));
        if (message == null) {
            message = emptyToNull(Json.asString(printStats.get("message")));
        }

        // display_status.progress follows M73 from the slicer (time based), virtual_sdcard is file position based
        Double progress = Json.asDouble(obj(raw, "display_status").get("progress"));
        if (progress == null || progress <= 0) {
            progress = Json.asDouble(obj(raw, "virtual_sdcard").get("progress"));
        }
        if (state == PrintState.COMPLETE) {
            progress = 1.0;
        }
        Double printDuration = Json.asDouble(printStats.get("print_duration"));
        Double totalDuration = Json.asDouble(printStats.get("total_duration"));
        Double remaining = estimateRemaining(state, progress, printDuration, slicerEstimate);

        String activeName = Json.asString(toolhead.get("extruder"));
        List<ToolheadStatus> toolheads = new ArrayList<>();
        int activeIndex = -1;
        for (int i = 0; i < 16; i++) {
            String name = i == 0 ? "extruder" : "extruder" + i;
            if (!raw.containsKey(name)) {
                if (i >= 4) {
                    break;
                }
                continue;
            }
            Map<String, Object> ex = raw.get(name);
            boolean active = name.equals(activeName);
            if (active) {
                activeIndex = i;
            }
            toolheads.add(new ToolheadStatus(i, name,
                    Json.asDouble(ex.get("temperature")),
                    Json.asDouble(ex.get("target")),
                    Json.asDouble(ex.get("power")),
                    active,
                    Json.asBoolean(listItem(taskConfig, "filament_exist", i)),
                    Json.asString(listItem(taskConfig, "filament_vendor", i)),
                    Json.asString(listItem(taskConfig, "filament_type", i)),
                    Json.asString(listItem(taskConfig, "filament_sub_type", i)),
                    toHexColor(listItem(taskConfig, "filament_color_rgba", i)),
                    Json.asBoolean(listItem(taskConfig, "filament_official", i))));
        }

        Map<String, Double> sensors = new TreeMap<>();
        Map<String, Double> fans = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : raw.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> value = entry.getValue();
            if (key.startsWith("temperature_sensor ") || key.startsWith("temperature_fan ")
                    || key.startsWith("heater_generic ")) {
                Double temp = Json.asDouble(value.get("temperature"));
                if (temp != null) {
                    sensors.put(key.substring(key.indexOf(' ') + 1), temp);
                }
            }
            if (key.equals("fan") || key.startsWith("fan_generic ") || key.startsWith("heater_fan ")
                    || key.startsWith("controller_fan ") || key.startsWith("temperature_fan ")) {
                Double speed = Json.asDouble(value.get("speed"));
                if (speed != null) {
                    fans.put(key.equals("fan") ? "part" : key.substring(key.indexOf(' ') + 1), speed);
                }
            }
        }

        Map<String, Object> bed = obj(raw, "heater_bed");
        return new PrinterStatus(printerId, true, klippyState, state, message, filename,
                progress == null ? null : progress * 100.0,
                printDuration, totalDuration,
                Json.asDouble(printStats.get("filament_used")),
                Json.asInt(Json.get(printStats, "info", "current_layer")),
                Json.asInt(Json.get(printStats, "info", "total_layer")),
                slicerEstimate, remaining,
                Json.asDouble(bed.get("temperature")),
                Json.asDouble(bed.get("target")),
                Collections.unmodifiableList(toolheads), activeIndex,
                Collections.unmodifiableMap(sensors), Collections.unmodifiableMap(fans),
                U1Codes.mainState(Json.asInt(machine.get("main_state"))),
                U1Codes.actionCode(Json.asInt(machine.get("action_code"))),
                Instant.now());
    }

    /**
     * Remaining time: average of the slicer based and the progress based estimate.
     * Early in the job (below 5 %) only the slicer estimate is used because the
     * progress based extrapolation is still unreliable.
     */
    private static Double estimateRemaining(PrintState state, Double progress, Double printDuration,
                                            Double slicerEstimate) {
        if (!state.isActive() || printDuration == null) {
            return null;
        }
        Double bySlicer = slicerEstimate != null && slicerEstimate > 0
                ? Math.max(0, slicerEstimate - printDuration) : null;
        Double byProgress = progress != null && progress > 0.05
                ? Math.max(0, printDuration / progress - printDuration) : null;
        if (bySlicer != null && byProgress != null) {
            return (bySlicer + byProgress) / 2;
        }
        return bySlicer != null ? bySlicer : byProgress;
    }

    // ------------------------------------------------------------------ convenience

    public ToolheadStatus toolhead(int index) {
        for (ToolheadStatus t : toolheads) {
            if (t.index() == index) {
                return t;
            }
        }
        return null;
    }

    /**
     * @return estimated wall clock time at which the job finishes, or null
     */
    public Instant estimatedFinish() {
        return remainingSeconds == null ? null : timestamp.plusSeconds(Math.round(remainingSeconds));
    }

    @Override
    public Object toJsonValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("printer", printerId);
        map.put("online", online);
        map.put("klippy_state", klippyState);
        map.put("state", printState.key());
        map.put("message", message);
        map.put("machine_state", machineState);
        map.put("machine_action", machineAction);

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("filename", filename);
        job.put("progress", round(progress));
        job.put("print_duration", roundInt(printDuration));
        job.put("total_duration", roundInt(totalDuration));
        job.put("remaining", roundInt(remainingSeconds));
        Instant finish = estimatedFinish();
        job.put("eta", finish == null ? null : finish.toString());
        job.put("slicer_estimate", roundInt(slicerEstimate));
        job.put("filament_used_mm", round(filamentUsedMm));
        job.put("current_layer", currentLayer);
        job.put("total_layers", totalLayers);
        map.put("job", job);

        Map<String, Object> bed = new LinkedHashMap<>();
        bed.put("temperature", round(bedTemperature));
        bed.put("target", round(bedTarget));
        map.put("bed", bed);

        map.put("active_toolhead", activeToolhead);
        map.put("toolheads", toolheads);
        Map<String, Object> roundedSensors = new LinkedHashMap<>();
        sensors.forEach((k, v) -> roundedSensors.put(k, round(v)));
        map.put("sensors", roundedSensors);
        Map<String, Object> roundedFans = new LinkedHashMap<>();
        fans.forEach((k, v) -> roundedFans.put(k, round(v)));
        map.put("fans", roundedFans);
        map.put("timestamp", timestamp.toString());
        return map;
    }

    static Double round(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }

    static Long roundInt(Double value) {
        return value == null ? null : Math.round(value);
    }

    private static Map<String, Object> obj(Map<String, Map<String, Object>> raw, String name) {
        Map<String, Object> value = raw.get(name);
        return value == null ? Map.of() : value;
    }

    private static Object listItem(Map<String, Object> map, String key, int index) {
        List<Object> list = Json.asList(map.get(key));
        return list == null || index >= list.size() ? null : list.get(index);
    }

    private static String toHexColor(Object rgba) {
        String s = Json.asString(rgba);
        if (s == null || s.length() < 6) {
            return null;
        }
        return "#" + s.substring(0, 6).toUpperCase();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
