# Snapmaker2MQTT

Dependency-free Java bridge (and library) that publishes the status and events of a
**Snapmaker U1** to an MQTT broker – e.g. "print finished", "filament loaded", "toolhead changed".

The U1 runs Snapmaker's fork of Klipper + Moonraker. Snapmaker2MQTT connects to Moonraker's
WebSocket API on port `7125` – nothing has to be installed or changed on the printer.

- Pure Java 17, **no external dependencies** (own MQTT 3.1.1 client and JSON parser)
- Live updates via Moonraker subscriptions (no polling)
- U1 specifics: four toolheads, RFID filament info per toolhead, machine state / action codes
- Events with reliable job-end detection (falls back to Moonraker's job history)
- Commands via MQTT (pause, resume, cancel, G-code, temperatures, ...)
- Several printers per bridge instance
- Automatic reconnect to printer and broker, last will for availability

## Quick start

```bash
mvn package
cp snapmaker2mqtt.properties.example snapmaker2mqtt.properties   # set printer.host and mqtt.host
java -jar target/snapmaker2mqtt.jar snapmaker2mqtt.properties
```

The printer's IP is shown on the U1 touchscreen under the network settings.
If `[authorization]` is enabled in `moonraker.conf`, set `printer.api_key`.

### Docker

```bash
docker build -t snapmaker2mqtt .
docker run -d --restart=always --name snapmaker2mqtt \
  -e S2M_MQTT_HOST=192.168.1.10 -e S2M_PRINTER_HOST=192.168.1.50 snapmaker2mqtt
```

### systemd

Copy the jar and config to `/opt/snapmaker2mqtt/`, then install `snapmaker2mqtt.service`.

## Configuration

See [`snapmaker2mqtt.properties.example`](snapmaker2mqtt.properties.example). Every key can be
overridden by an environment variable: `S2M_` + key in upper case with dots replaced by underscores
(`mqtt.host` → `S2M_MQTT_HOST`).

## MQTT topics

Prefix `snapmaker`, printer id `u1`:

| Topic | Retained | Content |
|---|---|---|
| `snapmaker/bridge/availability` | yes | `online` / `offline` (last will of the bridge) |
| `snapmaker/u1/availability` | yes | `online` when Moonraker is reachable and Klipper is ready |
| `snapmaker/u1/status` | yes | full status as JSON (see below) |
| `snapmaker/u1/state/<value>` | yes | single values, only published when changed |
| `snapmaker/u1/event` | no | every event as JSON |
| `snapmaker/u1/event/<type>` | no | the same, one topic per event type |
| `snapmaker/u1/last_event` | yes | the most recent event |
| `snapmaker/u1/command/<name>` | – | commands (see below) |
| `snapmaker/u1/command/result` | no | result of every command |
| `snapmaker/u1/console` | no | Klipper console output (`publish.console=true`) |
| `snapmaker/u1/raw/<object>` | yes | raw Klipper objects (`publish.raw=true`) |

### Single value topics (`state/...`)

`state`, `klippy_state`, `message`, `machine_state`, `machine_action`, `filename`, `progress`,
`print_duration`, `remaining`, `eta`, `current_layer`, `total_layers`, `filament_used_mm`,
`bed/temperature`, `bed/target`, `active_toolhead`,
`toolhead/<0-3>/temperature|target|active|filament_loaded|filament_type|filament_color|filament_vendor`,
`sensor/<name>` (e.g. chamber), `fan/<name>` (percent).

`state` is one of `standby`, `printing`, `paused`, `complete`, `cancelled`, `error`, `offline`.

### Status JSON

```json
{
  "printer": "u1", "online": true, "klippy_state": "ready", "state": "printing",
  "message": null, "machine_state": "printing", "machine_action": "idle",
  "job": {
    "filename": "benchy_4color.gcode", "progress": 35.0, "print_duration": 42, "total_duration": 45,
    "remaining": 78, "eta": "2026-10-24T07:23:38Z", "slicer_estimate": 120,
    "filament_used_mm": 289.1, "current_layer": 7, "total_layers": 20
  },
  "bed": { "temperature": 59.5, "target": 60.0 },
  "active_toolhead": 2,
  "toolheads": [
    { "index": 0, "name": "extruder", "temperature": 215.0, "target": 215.0, "power": 0.4, "active": false,
      "filament": { "loaded": true, "vendor": "Snapmaker", "type": "PLA", "sub_type": "Basic",
                    "color": "#FF0000", "official": true } }
  ],
  "sensors": { "cavity": 28.5 },
  "fans": { "part": 1.0 },
  "timestamp": "2026-10-24T07:22:20Z"
}
```

`remaining` combines the slicer estimate with the progress based extrapolation.
`progress` uses `M73` progress from the slicer when available, otherwise the file position.

### Events

| Event | When |
|---|---|
| `printer_online` / `printer_offline` | Moonraker reachable and Klipper ready / not |
| `print_started`, `print_paused`, `print_resumed` | job state changes |
| `print_finished` | job completed successfully |
| `print_cancelled`, `print_failed` | job cancelled / ended with an error |
| `progress` | every `events.progress_step` percent |
| `layer_changed` | new layer (requires layer info from the slicer) |
| `toolhead_changed` | another toolhead became active |
| `filament_loaded`, `filament_unloaded`, `filament_changed` | filament in a toolhead changed |
| `machine_action_changed` | U1 action, e.g. `homing`, `auto_clean_nozzle`, `auto_loading` |
| `error_message` | Klipper reported a new error |

```json
{
  "event": "print_finished", "printer": "u1", "timestamp": "2026-10-24T07:22:25Z",
  "state": "complete", "filename": "benchy_4color.gcode", "progress": 100,
  "print_duration": 120, "total_duration": 123, "filament_used_mm": 826, "message": null,
  "details": { "previous_state": "printing" }
}
```

### Commands

Publish to `snapmaker/u1/command/<name>` (retained messages are ignored):

| Command | Payload |
|---|---|
| `pause`, `resume`, `cancel` | – |
| `emergency_stop`, `firmware_restart` | – |
| `start` | file name on the printer |
| `gcode` | G-code script (disable with `commands.gcode=false`) |
| `temperature/<heater>` | target in °C, e.g. `command/temperature/extruder2` → `210` |

Disable all commands with `commands.enabled=false`. Secure your broker – anyone who can publish
to these topics can control the printer.

## Use as a library

```java
SnapmakerPrinter printer = new SnapmakerPrinter("u1", "192.168.1.50", 7125, null);
printer.addListener(new SnapmakerListener() {
    @Override
    public void onEvent(PrinterEvent event) {
        if (event.type() == PrinterEventType.PRINT_FINISHED) {
            System.out.println("Finished: " + event.status().filename());
        }
    }

    @Override
    public void onStatus(PrinterStatus status) {
        // called on every change
    }
});
printer.start();

printer.getStatus().toolhead(0).filamentType();   // "PLA"
printer.pause();
printer.setTemperature("heater_bed", 60);
printer.getClient().call("server.files.list", null); // any Moonraker JSON-RPC method
```

`MqttClient` and `Json` can be used on their own as well.

## Project layout

```
de.linzn.snapmaker2mqtt
├── Snapmaker2Mqtt          command line entry point
├── bridge                  config + MQTT topic mapping and commands
├── printer                 SnapmakerPrinter API, status model, event detection
├── moonraker               Moonraker WebSocket JSON-RPC client
├── mqtt                    MQTT 3.1.1 client
└── json                    JSON parser / serializer
```

## License

MIT
