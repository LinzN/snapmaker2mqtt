# snapmaker2mqtt

A standalone Java bridge between the **Snapmaker U1** and a **local MQTT broker**. It publishes the state of your
printers and events such as *print finished*, *filament loaded* or *toolhead changed* to MQTT, and it takes commands
from MQTT (pause, resume, cancel, temperatures, G-code). Everything stays on your LAN: no cloud, no Home Assistant
required. Works with Home Assistant, Node-RED, [STEM](https://github.com/LinzN/Stem) or anything else that speaks MQTT.

The U1 runs Snapmaker's fork of Klipper and Moonraker. The bridge talks to Moonraker's WebSocket API on port `7125`,
so nothing has to be installed or changed on the printer.

---

## How It Works

```
                            state / events (JSON) ──►
Snapmaker U1  ◄── LAN ──►  snapmaker2mqtt  ◄──►  Local MQTT Broker  ◄──►  Home Assistant / Node-RED / STEM
                            ◄── commands
```

The bridge subscribes to the printer's Klipper objects, so every change arrives immediately without polling. The state
is published to `snapmaker2mqtt/<printer>/state` at most once per `publishInterval` (a new print state right away),
events are published the moment they are detected. Messages on the command topics are translated into Moonraker
calls.

U1 specifics are covered: the four toolheads, the filament per toolhead (type, color, vendor from the RFID spools or
the touchscreen) and the U1 machine state and action (homing, nozzle cleaning, auto feeding, ...).

---

## Requirements

- Java 21 or higher
- Maven 3.x
- A running local MQTT broker (e.g. [Mosquitto](https://mosquitto.org/))
- The printer on the same network, ideally with a fixed IP (DHCP reservation in your router)

---

## Setup

### Step 1 — Build and find your printers

```bash
git clone https://github.com/LinzN/snapmaker2mqtt.git
cd snapmaker2mqtt
mvn clean package
```

This builds `target/snapmaker2mqtt.jar` and copies all libraries to `target/lib/`. The jar loads them from the `lib`
folder next to it, so when you move the bridge somewhere else, copy `snapmaker2mqtt.jar` together with the whole `lib`
folder.

Let it find your printers and write them into `config.yml`:

```bash
java -jar target/snapmaker2mqtt.jar discover-config
```

| Option            | Default      | Description                                                        |
|-------------------|--------------|--------------------------------------------------------------------|
| `--ip IP`         | scan the LAN | ask one address instead of scanning (works across VLANs)           |
| `--timeout N`     | `1`          | seconds to wait per address                                        |
| `--config FILE`   | `config.yml` | config file to write                                               |

The U1 does not answer broadcasts, so the bridge probes every address of your local networks (the /24 around each of
your own addresses) on port 7125 and recognizes the U1 by its Klipper objects. Every printer found is written under
`printers`, named after the host name it reports. A printer whose host is already in the config **overwrites** that
entry (name and API key are kept). The example printer is removed. `java -jar target/snapmaker2mqtt.jar discover` only
lists the printers without touching the config.

You can also look up the IP on the U1 touchscreen in the network settings and enter it yourself.

### Step 2 — First run & configuration

If you skipped `discover-config`, the first run creates a `config.yml` in the working directory with an example
printer that is disabled. Either way, check the file before starting the bridge for real:

```bash
java -jar target/snapmaker2mqtt.jar
nano config.yml
```

```yaml
mqtt:
  ip: 127.0.0.1
  port: 1883
  username: ''
  password: ''
  clientId: snapmaker2mqtt
  baseTopic: snapmaker2mqtt
  retain: true
  publishAttributes: false
events:
  progressStep: 10            # progress event every n percent, 0 = off
  layer: true                 # layer_changed events
  machineAction: true         # machine_action_changed events
commands:
  allowGcode: true            # accept G-code on .../gcode
printers:
  u1:                         # printer name, used in the topics
    enabled: true
    host: 192.168.1.50
    port: 7125
    apiKey: ''                # only if [authorization] is enabled in moonraker.conf
    publishInterval: 1        # seconds between state messages while printing
```

| Parameter                | Description                                                                 |
|--------------------------|-----------------------------------------------------------------------------|
| `mqtt.ip` / `mqtt.port`  | Your local MQTT broker                                                      |
| `mqtt.username/password` | Broker credentials, leave empty for anonymous                               |
| `mqtt.clientId`          | MQTT client id, must be unique on the broker                                |
| `mqtt.baseTopic`         | Prefix of all topics                                                        |
| `mqtt.retain`            | Publish state, availability and the last event as retained messages        |
| `mqtt.publishAttributes` | Additionally publish every state value on its own topic (`.../state/<key>`) |

Add as many printers as you like under `printers`. Use a different config file with `--config /path/to/config.yml`.

### Step 3 — Run

```bash
java -jar target/snapmaker2mqtt.jar
```

To run it as a service, copy the jar, the `lib` folder and `config.yml` to `/opt/snapmaker2mqtt/` and install
[`snapmaker2mqtt.service`](snapmaker2mqtt.service).

---

## MQTT Topics

`<base>` is `mqtt.baseTopic` (default `snapmaker2mqtt`), `<printer>` is the name from `config.yml`.

| Topic                            | Direction | Content                                                         |
|----------------------------------|-----------|-----------------------------------------------------------------|
| `<base>/bridge/status`           | read      | `online` / `offline` (retained, also the MQTT last will)        |
| `<base>/<printer>/availability`  | read      | `online` / `offline`, printer reachable and Klipper ready       |
| `<base>/<printer>/state`         | read      | full state as JSON                                              |
| `<base>/<printer>/state/<key>`   | read      | single value, only with `publishAttributes: true`               |
| `<base>/<printer>/event`         | read      | every event as JSON (not retained)                              |
| `<base>/<printer>/event/<type>`  | read      | the same, one topic per event type, e.g. `event/print_finished` |
| `<base>/<printer>/event/last`    | read      | the most recent event (retained)                                |
| `<base>/<printer>/error`         | read      | why a command failed, JSON (not retained)                       |
| `<base>/<printer>/set`           | write     | settings as JSON object, any number of keys                     |
| `<base>/<printer>/set/<key>`     | write     | one value as plain text, e.g. `set/bedTarget` ← `60`            |
| `<base>/<printer>/command`       | write     | `pause`, `resume`, `cancel`, `emergency_stop`, `firmware_restart` |
| `<base>/<printer>/print`         | write     | file name on the printer to start                               |
| `<base>/<printer>/gcode`         | write     | G-code script (disable with `commands.allowGcode: false`)       |
| `<base>/<printer>/get`           | write     | any payload: publish the state now                              |

If the bridge itself dies, the broker sets `bridge/status` to `offline`, but the printer `availability` keeps its last
value. Consumers should check both.

Publish commands **without** the retain flag. A retained command would run again on every reconnect, so the bridge
ignores retained messages on the command topics.

### Reading

Example `state` (shortened, toolheads 1–3 look like toolhead 0):

```json
{
  "available": true,
  "lastUpdate": "2026-10-24T07:52:47Z",
  "klippyState": "ready",
  "printState": "printing",
  "machineState": "printing",
  "machineAction": "idle",
  "filename": "benchy_4color.gcode",
  "progress": 35,
  "printDuration": 42,
  "totalDuration": 45,
  "remaining": 78,
  "eta": "2026-10-24T07:54:05Z",
  "slicerEstimate": 120,
  "filamentUsed": 289.1,
  "currentLayer": 7,
  "totalLayers": 20,
  "bedTemperature": 59.5,
  "bedTarget": 60,
  "activeToolhead": 0,
  "toolhead0Temperature": 215,
  "toolhead0Target": 215,
  "toolhead0Active": true,
  "toolhead0FilamentLoaded": true,
  "toolhead0FilamentVendor": "Snapmaker",
  "toolhead0FilamentType": "PLA",
  "toolhead0FilamentSubType": "Basic",
  "toolhead0FilamentColor": "#FF0000",
  "toolhead0FilamentOfficial": true,
  "cavityTemperature": 28.5,
  "partFanSpeed": 100
}
```

| Key                      | Description                                                                         |
|--------------------------|-------------------------------------------------------------------------------------|
| `printState`             | `standby`, `printing`, `paused`, `complete`, `cancelled`, `error`                   |
| `klippyState`            | `ready`, `startup`, `shutdown`, `error`                                             |
| `machineState`           | U1 main state: `idle`, `printing`, `auto_load`, `bed_leveling`, `upgrading`, ...    |
| `machineAction`          | U1 action: `homing`, `auto_clean_nozzle`, `print_auto_feeding`, `auto_loading`, ... |
| `progress`               | percent, read position within the G-code part of the file (same as the U1 web UI) |
| `remaining` / `eta`      | seconds left / expected end, from the slicer estimate and the progress so far       |
| `printDuration`          | seconds actually printed, `totalDuration` includes pauses                           |
| `filamentUsed`           | mm of filament used by the current job                                              |
| `currentLayer`           | only if the slicer writes layer info (`SET_PRINT_STATS_INFO`)                       |
| `<sensor>Temperature`    | additional sensors, e.g. `cavityTemperature` for the chamber                        |
| `<fan>FanSpeed`          | fans in percent, `partFanSpeed` is the part cooling fan                             |

Temperatures are in °C, durations in seconds. Values the printer doesn't report are left out rather than sent as
`null`.

### Events

| Event                                                  | When                                                    |
|--------------------------------------------------------|---------------------------------------------------------|
| `printer_online` / `printer_offline`                   | printer reachable and Klipper ready / not               |
| `print_started`, `print_paused`, `print_resumed`       | job state changes                                       |
| `print_finished`                                       | job completed successfully                              |
| `print_cancelled`, `print_failed`                      | job cancelled / ended with an error                     |
| `progress`                                             | every `events.progressStep` percent                     |
| `layer_changed`                                        | new layer (requires layer info from the slicer)         |
| `toolhead_changed`                                     | another toolhead became active                          |
| `filament_loaded`, `filament_unloaded`, `filament_changed` | filament in a toolhead changed                      |
| `machine_action_changed`                               | U1 action changed                                       |
| `error_message`                                        | Klipper reported a new error                            |

```json
{
  "event": "print_finished",
  "printer": "u1",
  "timestamp": "2026-10-24T07:52:53Z",
  "printState": "complete",
  "filename": "benchy_4color.gcode",
  "progress": 100,
  "printDuration": 120,
  "totalDuration": 123,
  "filamentUsed": 826,
  "details": { "previousState": "printing" }
}
```

A job end is also detected when the bridge was disconnected meanwhile, and as a fallback from Moonraker's job
history if Klipper skips the final state.

### Writing

Every writable key uses the **same name and format as in `state`**, so you can send back what you read.

```bash
# several settings in one command
mosquitto_pub -t snapmaker2mqtt/u1/set -m '{"bedTarget":60,"toolhead2Target":220}'

# a single value
mosquitto_pub -t snapmaker2mqtt/u1/set/partFanSpeed -m 50

# job control
mosquitto_pub -t snapmaker2mqtt/u1/command -m pause
mosquitto_pub -t snapmaker2mqtt/u1/print -m benchy_4color.gcode
mosquitto_pub -t snapmaker2mqtt/u1/gcode -m 'G28'
```

| Key                     | Values                                      |
|-------------------------|---------------------------------------------|
| `bedTarget`             | 0 to 150 °C, `0` or `off` switches it off   |
| `toolhead0Target` … `toolhead3Target` | 0 to 350 °C                   |
| `partFanSpeed`          | 0 to 100 percent                            |

The whole `set` command is validated before anything is sent: an unknown key or an invalid value rejects it and
nothing reaches the printer. Klipper still applies its own limits (e.g. the heater's `max_temp`). Errors are
published to `<base>/<printer>/error`:

```json
{"topic":"snapmaker2mqtt/u1/set","payload":"{\"toolhead2Target\": 999}","error":"'toolhead2Target': 999 is outside 0 to 350"}
```

Anyone who can publish to these topics can control the printer, so secure your broker.

---

## Use the API without MQTT

The printer API in `de.mirranet.snapmaker` has no MQTT code and can be used on its own:

```java
SnapmakerPrinter printer = new SnapmakerPrinter("u1", "192.168.1.50", 7125, null);
printer.addListener(new SnapmakerListener() {
    @Override
    public void onEvent(PrinterEvent event) {
        if (event.type() == PrinterEventType.PRINT_FINISHED) {
            System.out.println("Finished: " + event.status().filename());
        }
    }
});
printer.start();

printer.getStatus().toolhead(0).filamentType();         // "PLA"
printer.pause();
printer.setToolheadTemperature(2, 220);
printer.getClient().call("server.files.list", null);   // any Moonraker JSON-RPC method
```

---

## Logging

Output goes through `java.util.logging`. To see more, raise the level of the `de.mirranet.snapmaker` logger, e.g.
with a `logging.properties`:

```properties
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=FINEST
de.mirranet.snapmaker.level=FINEST
```

```bash
java -Djava.util.logging.config.file=logging.properties -jar target/snapmaker2mqtt.jar
```

---

## Project Structure

```
snapmaker2mqtt/
├── src/main/java/
│   ├── de/snapmaker2mqtt/
│   │   ├── SnapmakerApp.java          # entry point, wires MQTT and printers, discovery commands
│   │   ├── Configuration.java         # config.yml
│   │   ├── mqtt/MqttManager.java      # broker connection, topics, last will, command routing
│   │   └── printer/
│   │       ├── PrinterSettings.java   # one printer entry from config.yml
│   │       ├── PrinterBridge.java     # one printer: status/events -> topics, commands -> API
│   │       ├── StateMapper.java       # PrinterStatus / PrinterEvent -> JSON
│   │       └── CommandMapper.java     # MQTT commands -> API calls, validation
│   └── de/mirranet/snapmaker/         # Snapmaker U1 API, no MQTT
│       ├── SnapmakerPrinter.java      # connection, status, events, commands
│       ├── PrinterStatus.java         # interpreted snapshot incl. toolheads and filament
│       ├── EventDetector.java         # events from status changes
│       ├── moonraker/                 # Moonraker WebSocket JSON-RPC client
│       └── discovery/                 # finds printers on the LAN
├── config.yml                         # auto-generated on first run
└── pom.xml
```

---

## Related Projects

- [midea2mqtt](https://github.com/LinzN/midea2mqtt) – the same idea for Midea air conditioners
- [eufymake2mqtt](https://github.com/LinzN/eufymake2mqtt) – the same idea for eufyMake printers
- [STEM](https://github.com/LinzN/Stem) – Smart Technology Framework for Enhanced Home Management

---

## Author

**LinzN** – [GitHub](https://github.com/LinzN)

---

## Disclaimer

This project is not affiliated with or endorsed by Snapmaker. Snapmaker and Snapmaker U1 are trademarks of their
owners. Use at your own risk.
