package de.snapmaker2mqtt.printer;

import de.mirranet.snapmaker.discovery.SnapmakerDiscovery;

import java.time.Duration;
import java.util.Map;

/**
 * One entry below {@code printers:} in config.yml. The entry's key is the printer name and becomes
 * part of every MQTT topic for that printer.
 */
public record PrinterSettings(String name,
                              boolean enabled,
                              String host,
                              int port,
                              String apiKey,
                              Duration publishInterval) {

    public static PrinterSettings fromMap(String name, Map<?, ?> m) {
        if (name.isEmpty() || name.contains("/") || name.contains("+") || name.contains("#")) {
            throw new IllegalArgumentException("name must not be empty or contain '/', '+' or '#'");
        }
        boolean enabled = bool(m.get("enabled"), true);
        String host = str(m.get("host"), null);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("'host' is missing");
        }
        long interval = num(m.get("publishInterval"), 1);
        if (interval < 1) {
            throw new IllegalArgumentException("'publishInterval' must be at least 1 second");
        }
        return new PrinterSettings(name, enabled, host.trim(),
                (int) num(m.get("port"), SnapmakerDiscovery.DEFAULT_PORT),
                emptyToNull(str(m.get("apiKey"), null)),
                Duration.ofSeconds(interval));
    }

    // YAML gives Integer, Long, String or Boolean depending on how the value was written,
    // so these helpers accept all of them.

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long num(Object o, long def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + o);
        }
    }

    private static boolean bool(Object o, boolean def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(o).trim());
    }
}
