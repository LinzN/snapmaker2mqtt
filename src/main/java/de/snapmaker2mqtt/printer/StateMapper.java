package de.snapmaker2mqtt.printer;

import de.mirranet.snapmaker.PrinterEvent;
import de.mirranet.snapmaker.PrinterStatus;
import de.mirranet.snapmaker.ToolheadStatus;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Map;

/**
 * Turns snapshots from the API into the JSON published on MQTT.
 *
 * <p>The state is flat, so every key can also be published on its own topic. Every key that can
 * be written through {@code .../set} uses the same name and value format here, so a value read
 * from {@code .../state} can be sent back unchanged. Values the printer has not reported are left
 * out instead of being null.
 */
public final class StateMapper {

    private StateMapper() {
    }

    public static JSONObject state(PrinterStatus s) {
        JSONObject j = new JSONObject();
        j.put("available", s.online());
        j.put("lastUpdate", s.timestamp().toString());
        put(j, "klippyState", s.klippyState());
        j.put("printState", s.printState().key());
        put(j, "machineState", s.machineState());
        put(j, "machineAction", s.machineAction());
        put(j, "message", s.message());

        // job
        put(j, "filename", s.filename());
        put(j, "progress", round(s.progress()));
        put(j, "printDuration", seconds(s.printDuration()));
        put(j, "totalDuration", seconds(s.totalDuration()));
        put(j, "remaining", seconds(s.remainingSeconds()));
        Instant finish = s.estimatedFinish();
        put(j, "eta", finish == null ? null : finish.toString());
        put(j, "slicerEstimate", seconds(s.slicerEstimate()));
        put(j, "filamentUsed", round(s.filamentUsedMm()));
        put(j, "currentLayer", s.currentLayer());
        put(j, "totalLayers", s.totalLayers());

        // temperatures, writable: bedTarget, toolhead<n>Target
        put(j, "bedTemperature", round(s.bedTemperature()));
        put(j, "bedTarget", round(s.bedTarget()));
        j.put("activeToolhead", s.activeToolhead());
        for (ToolheadStatus t : s.toolheads()) {
            String p = "toolhead" + t.index();
            put(j, p + "Temperature", round(t.temperature()));
            put(j, p + "Target", round(t.target()));
            j.put(p + "Active", t.active());
            put(j, p + "FilamentLoaded", t.filamentLoaded());
            put(j, p + "FilamentVendor", t.filamentVendor());
            put(j, p + "FilamentType", t.filamentType());
            put(j, p + "FilamentSubType", t.filamentSubType());
            put(j, p + "FilamentColor", t.filamentColor());
            put(j, p + "FilamentOfficial", t.filamentOfficial());
        }
        for (Map.Entry<String, Double> sensor : s.sensors().entrySet()) {
            put(j, camel(sensor.getKey()) + "Temperature", round(sensor.getValue()));
        }
        // fans in percent, writable: partFanSpeed
        for (Map.Entry<String, Double> fan : s.fans().entrySet()) {
            put(j, camel(fan.getKey()) + "FanSpeed", round(fan.getValue() * 100));
        }
        return j;
    }

    public static JSONObject event(PrinterEvent e) {
        JSONObject j = new JSONObject();
        j.put("event", e.type().key());
        j.put("printer", e.printerId());
        j.put("timestamp", e.timestamp().toString());
        PrinterStatus s = e.status();
        if (s != null) {
            j.put("printState", s.printState().key());
            put(j, "filename", s.filename());
            put(j, "progress", round(s.progress()));
            put(j, "printDuration", seconds(s.printDuration()));
            put(j, "totalDuration", seconds(s.totalDuration()));
            put(j, "filamentUsed", round(s.filamentUsedMm()));
            put(j, "message", s.message());
        }
        JSONObject details = new JSONObject();
        e.details().forEach((key, value) -> put(details, key, value));
        j.put("details", details);
        return j;
    }

    private static void put(JSONObject j, String key, Object value) {
        if (value != null) {
            j.put(key, value);
        }
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }

    private static Long seconds(Double value) {
        return value == null ? null : Math.round(value);
    }

    /** {@code cavity} → {@code cavity}, {@code chamber_fan} or {@code chamber fan} → {@code chamberFan}. */
    static String camel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_' || c == ' ' || c == '-') {
                upper = sb.length() > 0;
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : sb.length() == 0 ? Character.toLowerCase(c) : c);
                upper = false;
            }
        }
        return sb.length() == 0 ? "unnamed" : sb.toString();
    }
}
