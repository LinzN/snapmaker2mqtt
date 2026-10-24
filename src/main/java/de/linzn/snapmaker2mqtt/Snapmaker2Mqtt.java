package de.linzn.snapmaker2mqtt;

import de.linzn.snapmaker2mqtt.bridge.BridgeConfig;
import de.linzn.snapmaker2mqtt.bridge.Snapmaker2MqttBridge;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Command line entry point.
 * <pre>
 * java -jar snapmaker2mqtt.jar [config.properties]
 * </pre>
 */
public final class Snapmaker2Mqtt {

    private Snapmaker2Mqtt() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$-7s [%3$s] %5$s%6$s%n");
        Path configFile = Path.of(args.length > 0 ? args[0] : "snapmaker2mqtt.properties");
        BridgeConfig config = BridgeConfig.load(configFile);
        configureLogging(Level.parse(config.get("log.level", "INFO").toUpperCase()));

        Logger log = Logger.getLogger("snapmaker2mqtt");
        log.info("Starting Snapmaker2MQTT with config " + configFile.toAbsolutePath()
                + " -> MQTT " + config.mqttHost() + ":" + config.mqttPort());

        Snapmaker2MqttBridge bridge = new Snapmaker2MqttBridge(config);
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            bridge.close();
            stop.countDown();
        }, "shutdown"));
        bridge.start();
        stop.await();
    }

    private static void configureLogging(Level level) {
        Logger root = Logger.getLogger("");
        for (var handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(level);
        root.addHandler(handler);
        root.setLevel(Level.INFO);
        Logger.getLogger("de.linzn.snapmaker2mqtt").setLevel(level);
        Logger.getLogger("snapmaker2mqtt").setLevel(level);
    }
}
