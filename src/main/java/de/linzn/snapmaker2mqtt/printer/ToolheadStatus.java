package de.linzn.snapmaker2mqtt.printer;

import de.linzn.snapmaker2mqtt.json.JsonSerializable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State of one of the U1's four toolheads ({@code extruder}, {@code extruder1} ... {@code extruder3})
 * including the filament loaded into it (reported by the U1 via RFID or set on the touchscreen).
 *
 * @param index          physical toolhead index 0..3
 * @param name           Klipper object name
 * @param temperature    current nozzle temperature in °C
 * @param target         target temperature in °C
 * @param power          heater power 0..1
 * @param active         true if this is the currently selected toolhead
 * @param filamentLoaded filament present in this toolhead
 * @param filamentVendor e.g. "Snapmaker", or "NONE"
 * @param filamentType   e.g. "PLA"
 * @param filamentSubType e.g. "Matte"
 * @param filamentColor  color as {@code #RRGGBB} hex string
 * @param filamentOfficial true for official RFID spools
 */
public record ToolheadStatus(int index,
                             String name,
                             Double temperature,
                             Double target,
                             Double power,
                             boolean active,
                             Boolean filamentLoaded,
                             String filamentVendor,
                             String filamentType,
                             String filamentSubType,
                             String filamentColor,
                             Boolean filamentOfficial) implements JsonSerializable {

    @Override
    public Object toJsonValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", index);
        map.put("name", name);
        map.put("temperature", PrinterStatus.round(temperature));
        map.put("target", PrinterStatus.round(target));
        map.put("power", PrinterStatus.round(power));
        map.put("active", active);
        Map<String, Object> filament = new LinkedHashMap<>();
        filament.put("loaded", filamentLoaded);
        filament.put("vendor", filamentVendor);
        filament.put("type", filamentType);
        filament.put("sub_type", filamentSubType);
        filament.put("color", filamentColor);
        filament.put("official", filamentOfficial);
        map.put("filament", filament);
        return map;
    }
}
