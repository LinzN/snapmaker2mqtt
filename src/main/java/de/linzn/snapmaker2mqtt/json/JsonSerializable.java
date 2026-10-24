package de.linzn.snapmaker2mqtt.json;

/**
 * Implemented by model classes that can be converted to a JSON-compatible value
 * (typically a {@link java.util.Map}) for {@link Json#stringify(Object)}.
 */
public interface JsonSerializable {

    /**
     * @return a value made only of Map, Collection, String, Number, Boolean or null
     */
    Object toJsonValue();
}
