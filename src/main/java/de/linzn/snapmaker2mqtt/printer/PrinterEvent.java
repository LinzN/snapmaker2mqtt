package de.linzn.snapmaker2mqtt.printer;

import de.linzn.snapmaker2mqtt.json.JsonSerializable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An event detected on a printer.
 *
 * @param type      event type
 * @param printerId printer the event belongs to
 * @param status    printer status at the time of the event
 * @param details   event specific details (e.g. toolhead index, previous value)
 * @param timestamp time of detection
 */
public record PrinterEvent(PrinterEventType type,
                           String printerId,
                           PrinterStatus status,
                           Map<String, Object> details,
                           Instant timestamp) implements JsonSerializable {

    @Override
    public Object toJsonValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event", type.key());
        map.put("printer", printerId);
        map.put("timestamp", timestamp.toString());
        if (status != null) {
            map.put("state", status.printState().key());
            map.put("filename", status.filename());
            map.put("progress", PrinterStatus.round(status.progress()));
            map.put("print_duration", PrinterStatus.roundInt(status.printDuration()));
            map.put("total_duration", PrinterStatus.roundInt(status.totalDuration()));
            map.put("filament_used_mm", PrinterStatus.round(status.filamentUsedMm()));
            map.put("message", status.message());
        }
        map.put("details", details);
        return map;
    }
}
