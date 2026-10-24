package de.linzn.snapmaker2mqtt.bridge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Bridge configuration loaded from a properties file. Every key can be overridden by an
 * environment variable: upper case, dots replaced by underscores, prefixed with {@code S2M_}
 * (e.g. {@code mqtt.host} → {@code S2M_MQTT_HOST}).
 */
public final class BridgeConfig {

    /**
     * Connection settings of one printer.
     */
    public record PrinterEntry(String id, String host, int port, String apiKey) {
    }

    private final Properties properties;

    private BridgeConfig(Properties properties) {
        this.properties = properties;
    }

    public static BridgeConfig load(Path file) throws IOException {
        Properties p = new Properties();
        if (file != null && Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        return new BridgeConfig(p);
    }

    public static BridgeConfig of(Properties properties) {
        return new BridgeConfig(properties);
    }

    public String get(String key, String defaultValue) {
        String env = System.getenv("S2M_" + key.toUpperCase().replace('.', '_'));
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key, null);
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Config key '" + key + "' must be a number, got '" + value + "'");
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    // ------------------------------------------------------------------ typed settings

    public String mqttHost() {
        return get("mqtt.host", "localhost");
    }

    public boolean mqttTls() {
        return getBoolean("mqtt.tls", false);
    }

    public int mqttPort() {
        return getInt("mqtt.port", mqttTls() ? 8883 : 1883);
    }

    public String mqttUsername() {
        return get("mqtt.username", null);
    }

    public String mqttPassword() {
        return get("mqtt.password", null);
    }

    public String mqttClientId() {
        return get("mqtt.client_id", "snapmaker2mqtt");
    }

    public String topicPrefix() {
        String prefix = get("mqtt.topic_prefix", "snapmaker");
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    public int mqttQos() {
        return getInt("mqtt.qos", 0);
    }

    public int keepAliveSeconds() {
        return getInt("mqtt.keepalive", 30);
    }

    /** Minimum time between two status publications while printing. */
    public int publishIntervalMs() {
        return getInt("publish.interval_ms", 1000);
    }

    /** Status is republished at least this often even without changes. */
    public int heartbeatSeconds() {
        return getInt("publish.heartbeat_seconds", 60);
    }

    /** Publish single values to {@code <base>/state/...} in addition to the JSON status. */
    public boolean publishFlat() {
        return getBoolean("publish.flat_topics", true);
    }

    /** Publish raw Klipper object states to {@code <base>/raw/<object>}. */
    public boolean publishRaw() {
        return getBoolean("publish.raw", false);
    }

    /** Publish Klipper console output to {@code <base>/console}. */
    public boolean publishConsole() {
        return getBoolean("publish.console", false);
    }

    public int progressStep() {
        return getInt("events.progress_step", 10);
    }

    public boolean layerEvents() {
        return getBoolean("events.layer", true);
    }

    public boolean machineActionEvents() {
        return getBoolean("events.machine_action", true);
    }

    public boolean commandsEnabled() {
        return getBoolean("commands.enabled", true);
    }

    public boolean gcodeCommandEnabled() {
        return getBoolean("commands.gcode", true);
    }

    /**
     * Printers either as a list ({@code printers=u1,u1b} with {@code printer.<id>.host} etc.)
     * or a single printer ({@code printer.host}, optional {@code printer.id}).
     */
    public List<PrinterEntry> printers() {
        List<PrinterEntry> list = new ArrayList<>();
        String ids = get("printers", null);
        if (ids != null) {
            for (String raw : ids.split(",")) {
                String id = raw.trim();
                if (id.isEmpty()) {
                    continue;
                }
                String host = get("printer." + id + ".host", null);
                if (host == null) {
                    throw new IllegalArgumentException("Missing printer." + id + ".host");
                }
                list.add(new PrinterEntry(id, host, getInt("printer." + id + ".port", 7125),
                        get("printer." + id + ".api_key", null)));
            }
        } else {
            String host = get("printer.host", null);
            if (host != null) {
                list.add(new PrinterEntry(get("printer.id", "u1"), host, getInt("printer.port", 7125),
                        get("printer.api_key", null)));
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("No printer configured (set printer.host)");
        }
        for (PrinterEntry entry : list) {
            if (!entry.id().matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Printer id '" + entry.id()
                        + "' may only contain letters, digits, '_' and '-'");
            }
        }
        return list;
    }
}
