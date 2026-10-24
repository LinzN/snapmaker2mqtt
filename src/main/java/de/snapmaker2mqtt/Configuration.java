package de.snapmaker2mqtt;

import de.linzn.simplyConfiguration.FileConfiguration;
import de.linzn.simplyConfiguration.provider.YamlConfiguration;
import de.mirranet.snapmaker.discovery.DiscoveredPrinter;
import de.snapmaker2mqtt.printer.PrinterSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads {@code config.yml}. Missing keys are filled with defaults and written back, so the first
 * start produces a complete file with an example printer (disabled) to edit.
 */
public class Configuration {

    private static final Logger LOG = Logger.getLogger(Configuration.class.getName());

    private final FileConfiguration fileConfiguration;

    public final String hostname;
    public final int port;
    public final String username;
    public final String password;
    public final String clientId;
    public final String baseTopic;
    public final boolean retain;
    public final boolean publishAttributes;
    public final int progressStep;
    public final boolean layerEvents;
    public final boolean machineActionEvents;
    public final boolean allowGcode;
    public final List<PrinterSettings> printers;

    /** Name and host of the example printer written on the first start. */
    private static final String EXAMPLE_NAME = "u1";
    private static final String EXAMPLE_HOST = "192.168.1.50";

    public Configuration(File file) {
        fileConfiguration = loadWithDefaults(file);
        this.hostname = fileConfiguration.getString("mqtt.ip");
        this.port = fileConfiguration.getInt("mqtt.port");
        this.username = fileConfiguration.getString("mqtt.username");
        this.password = fileConfiguration.getString("mqtt.password");
        this.clientId = fileConfiguration.getString("mqtt.clientId");
        this.baseTopic = stripSlashes(fileConfiguration.getString("mqtt.baseTopic"));
        this.retain = fileConfiguration.getBoolean("mqtt.retain");
        this.publishAttributes = fileConfiguration.getBoolean("mqtt.publishAttributes");
        this.progressStep = fileConfiguration.getInt("events.progressStep");
        this.layerEvents = fileConfiguration.getBoolean("events.layer");
        this.machineActionEvents = fileConfiguration.getBoolean("events.machineAction");
        this.allowGcode = fileConfiguration.getBoolean("commands.allowGcode");
        this.printers = parsePrinters(fileConfiguration.get("printers"));
    }

    public FileConfiguration getFileConfiguration() {
        return this.fileConfiguration;
    }

    /** Loads the file, fills in every missing key with its default and saves it. */
    private static FileConfiguration loadWithDefaults(File file) {
        FileConfiguration fc = YamlConfiguration.loadConfiguration(file);
        fc.getString("mqtt.ip", "127.0.0.1");
        fc.getInt("mqtt.port", 1883);
        fc.getString("mqtt.username", "");
        fc.getString("mqtt.password", "");
        fc.getString("mqtt.clientId", "snapmaker2mqtt");
        fc.getString("mqtt.baseTopic", "snapmaker2mqtt");
        fc.getBoolean("mqtt.retain", true);
        fc.getBoolean("mqtt.publishAttributes", false);
        fc.getInt("events.progressStep", 10);
        fc.getBoolean("events.layer", true);
        fc.getBoolean("events.machineAction", true);
        fc.getBoolean("commands.allowGcode", true);
        if (!(fc.get("printers") instanceof Map<?, ?>)) {
            Map<String, Object> printers = new LinkedHashMap<>();
            printers.put(EXAMPLE_NAME, printerEntry(false, EXAMPLE_HOST, 7125));
            fc.set("printers", printers);
        }
        fc.save();
        return fc;
    }

    /**
     * Writes discovered printers into the {@code printers} section. A printer whose host is already
     * configured overwrites that entry (keeping its name and API key); new printers are named after
     * the host name they report. The untouched example printer is removed.
     *
     * @return printer name to discovered printer, in the order they were written
     */
    @SuppressWarnings("unchecked")
    public static Map<String, DiscoveredPrinter> writeDiscoveredPrinters(File file, List<DiscoveredPrinter> found) {
        FileConfiguration fc = loadWithDefaults(file);
        Map<String, Object> printers = (Map<String, Object>) fc.get("printers");

        Object example = printers.get(EXAMPLE_NAME);
        if (example instanceof Map<?, ?> m && EXAMPLE_HOST.equals(String.valueOf(m.get("host")))
                && Boolean.FALSE.equals(m.get("enabled"))) {
            printers.remove(EXAMPLE_NAME);
        }

        Map<String, DiscoveredPrinter> written = new LinkedHashMap<>();
        for (DiscoveredPrinter printer : found) {
            String name = null;
            String apiKey = "";
            for (Map.Entry<String, Object> e : printers.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m && printer.host().equals(String.valueOf(m.get("host")).trim())) {
                    name = e.getKey();
                    Object key = m.get("apiKey");
                    apiKey = key == null ? "" : String.valueOf(key);
                    break;
                }
            }
            if (name == null) {
                name = uniqueName(printers, nameFor(printer));
            }
            Map<String, Object> entry = printerEntry(true, printer.host(), printer.port());
            entry.put("apiKey", apiKey);
            printers.put(name, entry);
            written.put(name, printer);
        }
        fc.save();
        return written;
    }

    private static String nameFor(DiscoveredPrinter printer) {
        String hostname = printer.hostname() == null ? "" : printer.hostname().toLowerCase()
                .replaceAll("[^a-z0-9_-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (!hostname.isEmpty()) {
            return hostname;
        }
        return "u1-" + printer.host().substring(printer.host().lastIndexOf('.') + 1);
    }

    private static String uniqueName(Map<String, Object> printers, String name) {
        String candidate = name;
        for (int i = 2; printers.containsKey(candidate); i++) {
            candidate = name + "-" + i;
        }
        return candidate;
    }

    private static Map<String, Object> printerEntry(boolean enabled, String host, int port) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("enabled", enabled);
        entry.put("host", host);
        entry.put("port", port);
        entry.put("apiKey", "");
        entry.put("publishInterval", 1);
        return entry;
    }

    private static List<PrinterSettings> parsePrinters(Object section) {
        List<PrinterSettings> result = new ArrayList<>();
        if (!(section instanceof Map<?, ?> map)) {
            LOG.warning("config.yml: 'printers' is not a section, no printers loaded");
            return result;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> values)) {
                LOG.warning("config.yml: printer '" + name + "' is not a section, skipped");
                continue;
            }
            try {
                PrinterSettings settings = PrinterSettings.fromMap(name, values);
                if (settings.enabled()) {
                    result.add(settings);
                } else {
                    LOG.info("Printer '" + name + "' is disabled in config.yml");
                }
            } catch (IllegalArgumentException ex) {
                LOG.warning("config.yml: printer '" + name + "' skipped: " + ex.getMessage());
            }
        }
        return result;
    }

    private static String stripSlashes(String topic) {
        String t = topic.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        return t;
    }
}
