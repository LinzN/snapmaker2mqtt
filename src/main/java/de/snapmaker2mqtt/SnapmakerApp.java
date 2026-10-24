package de.snapmaker2mqtt;

import de.mirranet.snapmaker.discovery.DiscoveredPrinter;
import de.mirranet.snapmaker.discovery.SnapmakerDiscovery;
import de.snapmaker2mqtt.mqtt.MqttManager;
import de.snapmaker2mqtt.printer.PrinterBridge;
import de.snapmaker2mqtt.printer.PrinterSettings;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Entry point.
 *
 * <pre>
 * java -jar snapmaker2mqtt.jar [--config config.yml]   run the bridge
 * java -jar snapmaker2mqtt.jar discover-config         find printers and write them into config.yml
 * java -jar snapmaker2mqtt.jar discover                find printers on the LAN (print only)
 * </pre>
 */
public class SnapmakerApp {

    static {
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
        }
    }

    private static final Logger LOG = Logger.getLogger(SnapmakerApp.class.getName());

    private final Configuration configuration;
    private final MqttManager mqttManager;
    private final Map<String, PrinterBridge> printers = new LinkedHashMap<>();

    public SnapmakerApp(File configFile) {
        this.configuration = new Configuration(configFile);
        this.mqttManager = new MqttManager(configuration);
        for (PrinterSettings settings : configuration.printers) {
            printers.put(settings.name(), new PrinterBridge(settings, configuration, mqttManager));
        }
        mqttManager.setCommandListener(this::onCommand);
        mqttManager.setOnConnected(() -> printers.values().forEach(PrinterBridge::republish));
    }

    public void start() {
        if (printers.isEmpty()) {
            LOG.warning("No enabled printers in config.yml - add one under 'printers' (see README) and restart");
        }
        mqttManager.connect();
        printers.values().forEach(PrinterBridge::start);
        LOG.info("snapmaker2mqtt running with " + printers.size() + " printer(s), base topic '"
                + configuration.baseTopic + "'");
    }

    public void stop() {
        // java.util.logging resets itself in its own shutdown hook, so this goes to stdout directly
        System.out.println("snapmaker2mqtt shutting down");
        printers.values().forEach(PrinterBridge::stop);
        mqttManager.disconnect();
    }

    private void onCommand(String printer, String subTopic, String payload) {
        PrinterBridge bridge = printers.get(printer);
        if (bridge == null) {
            LOG.fine(() -> "Command for unknown printer '" + printer + "' ignored");
            return;
        }
        bridge.handle(subTopic, payload);
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public MqttManager getMqttManager() {
        return mqttManager;
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 && (args[0].equals("discover") || args[0].equals("discover-config"))
                ? args[0] : null;
        File configFile = new File("config.yml");
        String ip = null;
        int timeout = 1;
        for (int i = mode != null ? 1 : 0; i < args.length; i++) {
            if ((args[i].equals("--config") || args[i].equals("-c")) && i + 1 < args.length) {
                configFile = new File(args[++i]);
            } else if (mode != null && args[i].equals("--ip") && i + 1 < args.length) {
                ip = args[++i];
            } else if (mode != null && args[i].equals("--timeout") && i + 1 < args.length) {
                timeout = Integer.parseInt(args[++i]);
            } else {
                System.err.println("Unknown argument: " + args[i]);
                printUsage();
                System.exit(2);
            }
        }
        if (mode != null) {
            System.exit(discover(configFile, ip, timeout, mode.equals("discover-config")));
        }

        SnapmakerApp app = new SnapmakerApp(configFile);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            stopped.countDown();
        }, "snapmaker2mqtt-shutdown"));
        app.start();
        stopped.await();
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar snapmaker2mqtt.jar [--config config.yml]");
        System.err.println("       java -jar snapmaker2mqtt.jar discover-config [--ip IP] [--timeout 1] [--config config.yml]");
        System.err.println("       java -jar snapmaker2mqtt.jar discover [--ip IP] [--timeout 1]");
    }

    /**
     * Finds printers on the LAN (or asks one address) and prints them; with {@code write} they are
     * also written into config.yml. Printers that are already configured (same host) are overwritten.
     */
    private static int discover(File configFile, String ip, int timeoutSeconds, boolean write) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        System.out.println(ip == null
                ? "Searching for Snapmaker printers on the LAN..."
                : "Asking " + ip + "...");
        List<DiscoveredPrinter> found = new ArrayList<>();
        if (ip == null) {
            found.addAll(SnapmakerDiscovery.discover(timeout));
        } else {
            DiscoveredPrinter printer = SnapmakerDiscovery.discover(ip, SnapmakerDiscovery.DEFAULT_PORT, timeout);
            if (printer != null) {
                found.add(printer);
            }
        }
        for (DiscoveredPrinter p : found) {
            System.out.printf("  %-15s %-20s %-10s %s%n", p.host(), p.hostname() == null ? "-" : p.hostname(),
                    p.klippyState() == null ? "-" : p.klippyState(),
                    p.authRequired() ? "Moonraker, needs API key" : p.snapmakerU1() ? "Snapmaker U1" : "other Klipper printer");
        }
        found.removeIf(p -> !p.snapmakerU1() && !p.authRequired());
        if (found.isEmpty()) {
            System.out.println("No Snapmaker U1 found" + (write ? ", " + configFile + " left unchanged." : "."));
            return 1;
        }
        if (!write) {
            return 0;
        }
        Map<String, DiscoveredPrinter> written = Configuration.writeDiscoveredPrinters(configFile, found);
        System.out.println("Wrote " + written.size() + " printer(s) to " + configFile + ":");
        for (Map.Entry<String, DiscoveredPrinter> e : written.entrySet()) {
            System.out.printf("  %-24s %s:%d%n", e.getKey(), e.getValue().host(), e.getValue().port());
            if (e.getValue().authRequired()) {
                System.out.println("    needs 'apiKey' in config.yml (Moonraker [authorization] is enabled)");
            }
        }
        System.out.println("Tip: give the printers a fixed IP (DHCP reservation in your router).");
        return 0;
    }
}
