FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -B package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/snapmaker2mqtt.jar /app/snapmaker2mqtt.jar
# configure via environment (S2M_MQTT_HOST, S2M_PRINTER_HOST, ...) or mount /app/snapmaker2mqtt.properties
ENTRYPOINT ["java", "-jar", "/app/snapmaker2mqtt.jar", "/app/snapmaker2mqtt.properties"]
