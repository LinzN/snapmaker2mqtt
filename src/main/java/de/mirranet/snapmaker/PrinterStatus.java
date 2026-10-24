package de.mirranet.snapmaker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable, interpreted snapshot of a Snapmaker U1, built from the raw Klipper object states by
 * {@link #fromRaw}. Values the printer has not reported are {@code null}.
 *
 * @param printerId        name of the printer
 * @param online           Moonraker reachable and Klipper ready
 * @param klippyState      Klipper state: ready, startup, shutdown, error, disconnected
 * @param printState       job state
 * @param message          message from {@code display_status} (M117), the job or Klipper
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
 * @param toolheads        the toolheads with their loaded filament
 * @param activeToolhead   index of the active toolhead, or -1
 * @param sensors          additional temperature sensors (e.g. the chamber) by name
 * @param fans             fan speeds 0..1 by name; the part cooling fan is {@code part}
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
                            Instant timestamp) {

    /** Number of toolheads of the U1. */
    public static final int TOOLHEADS = 4;

    /**
     * Status of a printer that is unreachable or whose Klipper is not ready. The filament
     * information of the last known status is kept.
     */
    public static PrinterStatus offline(String printerId, String klippyState, String message, PrinterStatus last) {
        return new PrinterStatus(printerId, false, klippyState, PrintState.OFFLINE, message,
                null, null, null, null, null, null, null, null, null, null, null,
                last == null ? List.of() : last.toolheads(), -1, Map.of(), Map.of(),
                null, null, Instant.now());
    }

    /**
     * Interprets the raw Klipper object states.
     *
     * @param printerId      name of the printer
     * @param klippyState    current Klipper state
     * @param raw            object name → fields, as received from Moonraker
     * @param slicerEstimate slicer time estimate of the current file in seconds, may be null
     */
    public static PrinterStatus fromRaw(String printerId, String klippyState, JSONObject raw, Double slicerEstimate) {
        JSONObject printStats = obj(raw, "print_stats");
        JSONObject displayStatus = obj(raw, "display_status");
        JSONObject taskConfig = obj(raw, "print_task_config");
        JSONObject machine = obj(raw, "machine_state_manager");

        PrintState state = PrintState.fromKlipper(str(printStats, "state"));
        String message = str(displayStatus, "message");
        if (message == null) {
            message = str(printStats, "message");
        }

        // display_status.progress follows M73 from the slicer (time based), virtual_sdcard the file position
        Double progress = dbl(displayStatus, "progress");
        if (progress == null || progress <= 0) {
            progress = dbl(obj(raw, "virtual_sdcard"), "progress");
        }
        if (state == PrintState.COMPLETE) {
            progress = 1.0;
        }
        Double printDuration = dbl(printStats, "print_duration");

        String activeName = str(obj(raw, "toolhead"), "extruder");
        List<ToolheadStatus> toolheads = new ArrayList<>();
        int activeIndex = -1;
        for (int i = 0; i < TOOLHEADS; i++) {
            String name = i == 0 ? "extruder" : "extruder" + i;
            if (!raw.has(name)) {
                continue;
            }
            JSONObject ex = raw.getJSONObject(name);
            boolean active = name.equals(activeName);
            if (active) {
                activeIndex = i;
            }
            toolheads.add(new ToolheadStatus(i, name,
                    dbl(ex, "temperature"), dbl(ex, "target"), dbl(ex, "power"), active,
                    bool(item(taskConfig, "filament_exist", i)),
                    str(item(taskConfig, "filament_vendor", i)),
                    str(item(taskConfig, "filament_type", i)),
                    str(item(taskConfig, "filament_sub_type", i)),
                    hexColor(item(taskConfig, "filament_color_rgba", i)),
                    bool(item(taskConfig, "filament_official", i))));
        }

        Map<String, Double> sensors = new TreeMap<>();
        Map<String, Double> fans = new TreeMap<>();
        for (String key : raw.keySet()) {
            JSONObject value = raw.optJSONObject(key);
            if (value == null) {
                continue;
            }
            String shortName = key.substring(key.indexOf(' ') + 1);
            if (key.startsWith("temperature_sensor ") || key.startsWith("temperature_fan ")
                    || key.startsWith("heater_generic ")) {
                Double temp = dbl(value, "temperature");
                if (temp != null) {
                    sensors.put(shortName, temp);
                }
            }
            if (key.equals("fan") || key.startsWith("fan_generic ") || key.startsWith("heater_fan ")
                    || key.startsWith("controller_fan ") || key.startsWith("temperature_fan ")) {
                Double speed = dbl(value, "speed");
                if (speed != null) {
                    fans.put(key.equals("fan") ? "part" : shortName, speed);
                }
            }
        }

        JSONObject bed = obj(raw, "heater_bed");
        Integer mainState = integer(dbl(machine, "main_state"));
        Integer actionCode = integer(dbl(machine, "action_code"));
        return new PrinterStatus(printerId, true, klippyState, state, message, str(printStats, "filename"),
                progress == null ? null : progress * 100.0,
                printDuration,
                dbl(printStats, "total_duration"),
                dbl(printStats, "filament_used"),
                integer(dbl(obj(printStats, "info"), "current_layer")),
                integer(dbl(obj(printStats, "info"), "total_layer")),
                slicerEstimate,
                estimateRemaining(state, progress, printDuration, slicerEstimate),
                dbl(bed, "temperature"), dbl(bed, "target"),
                Collections.unmodifiableList(toolheads), activeIndex,
                Collections.unmodifiableMap(sensors), Collections.unmodifiableMap(fans),
                U1Codes.mainState(mainState), U1Codes.actionCode(actionCode),
                Instant.now());
    }

    /**
     * Remaining time: average of the slicer based and the progress based estimate. Below 5 %
     * progress only the slicer estimate is used, the extrapolation is still unreliable there.
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

    // ---- convenience

    /**
     * @return the toolhead with the given index, or null
     */
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

    // ---- JSON helpers; Moonraker sends null for unset values, which org.json keeps as JSONObject.NULL

    private static JSONObject obj(JSONObject parent, String key) {
        JSONObject o = parent.optJSONObject(key);
        return o == null ? new JSONObject() : o;
    }

    private static Object item(JSONObject parent, String key, int index) {
        JSONArray array = parent.optJSONArray(key);
        return array == null || index >= array.length() ? null : array.opt(index);
    }

    private static String str(JSONObject parent, String key) {
        return str(parent.opt(key));
    }

    private static String str(Object o) {
        if (o == null || o == JSONObject.NULL) {
            return null;
        }
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static Double dbl(JSONObject parent, String key) {
        Object o = parent.opt(key);
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) ? null : d;
        }
        return null;
    }

    private static Integer integer(Double d) {
        return d == null ? null : (int) Math.round(d);
    }

    private static Boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        return null;
    }

    private static String hexColor(Object rgba) {
        String s = str(rgba);
        if (s == null || s.length() < 6) {
            return null;
        }
        return "#" + s.substring(0, 6).toUpperCase();
    }
}
