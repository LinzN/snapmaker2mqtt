package de.linzn.snapmaker2mqtt.mqtt;

/**
 * Callback for messages received on subscribed topics.
 */
@FunctionalInterface
public interface MqttMessageHandler {

    void onMessage(String topic, byte[] payload, boolean retained);
}
