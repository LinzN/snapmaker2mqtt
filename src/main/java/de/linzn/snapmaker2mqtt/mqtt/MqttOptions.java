package de.linzn.snapmaker2mqtt.mqtt;

/**
 * Connection settings for {@link MqttClient}.
 *
 * @param host             broker host name or IP
 * @param port             broker port (1883 plain, 8883 TLS)
 * @param tls              use TLS with the JVM default trust store
 * @param clientId         MQTT client id, must be unique per broker
 * @param username         optional username, {@code null} for anonymous
 * @param password         optional password
 * @param keepAliveSeconds keep-alive interval announced to the broker
 * @param willTopic        optional last-will topic, {@code null} to disable
 * @param willPayload      last-will payload
 * @param willQos          last-will QoS (0 or 1)
 * @param willRetain       retain flag of the last will
 */
public record MqttOptions(String host,
                          int port,
                          boolean tls,
                          String clientId,
                          String username,
                          String password,
                          int keepAliveSeconds,
                          String willTopic,
                          String willPayload,
                          int willQos,
                          boolean willRetain) {
}
