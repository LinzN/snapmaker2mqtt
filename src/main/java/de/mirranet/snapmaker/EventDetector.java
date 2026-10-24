package de.mirranet.snapmaker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derives {@link PrinterEvent}s by comparing two consecutive {@link PrinterStatus} snapshots.
 * Stateless apart from the configured options.
 */
public class EventDetector {

    private final int progressStep;
    private final boolean layerEvents;
    private final boolean machineActionEvents;

    /**
     * @param progressStep        emit {@link PrinterEventType#PROGRESS} every n percent, 0 to disable
     * @param layerEvents         emit {@link PrinterEventType#LAYER_CHANGED}
     * @param machineActionEvents emit {@link PrinterEventType#MACHINE_ACTION_CHANGED}
     */
    public EventDetector(int progressStep, boolean layerEvents, boolean machineActionEvents) {
        this.progressStep = progressStep;
        this.layerEvents = layerEvents;
        this.machineActionEvents = machineActionEvents;
    }

    /**
     * Compares two snapshots taken while the printer was online. Connectivity events
     * ({@link PrinterEventType#PRINTER_ONLINE}/{@link PrinterEventType#PRINTER_OFFLINE}) are produced by
     * {@link SnapmakerPrinter}; after an outage it passes the last online snapshot as {@code previous},
     * so a job that finished while the bridge was disconnected is still reported.
     *
     * @param previous last online status, {@code null} if there is none yet (no events are produced then)
     * @param current  new online status
     * @return detected events in a sensible order
     */
    public List<PrinterEvent> detect(PrinterStatus previous, PrinterStatus current) {
        List<PrinterEvent> events = new ArrayList<>();
        if (previous == null || !previous.online() || !current.online()) {
            return events;
        }
        detectJobTransitions(previous, current, events);
        detectProgress(previous, current, events);
        detectToolheads(previous, current, events);

        if (machineActionEvents && current.machineAction() != null
                && previous.machineAction() != null
                && !current.machineAction().equals(previous.machineAction())) {
            events.add(event(PrinterEventType.MACHINE_ACTION_CHANGED, current, details(
                    "action", current.machineAction(), "previous", previous.machineAction(),
                    "machineState", current.machineState())));
        }

        if (current.printState() == PrintState.ERROR && current.message() != null
                && !Objects.equals(current.message(), previous.message())) {
            events.add(event(PrinterEventType.ERROR_MESSAGE, current, details("message", current.message())));
        }
        return events;
    }

    private void detectJobTransitions(PrinterStatus previous, PrinterStatus current, List<PrinterEvent> events) {
        PrintState from = previous.printState();
        PrintState to = current.printState();
        boolean sameJob = Objects.equals(previous.filename(), current.filename());

        if (from == to) {
            // A new job may start directly after the previous one without passing another state
            if (to == PrintState.PRINTING && !sameJob && current.filename() != null) {
                events.add(event(PrinterEventType.PRINT_STARTED, current, Map.of()));
            }
            return;
        }
        Map<String, Object> d = details("previousState", from.key());
        switch (to) {
            case PRINTING -> events.add(event(from == PrintState.PAUSED && sameJob
                    ? PrinterEventType.PRINT_RESUMED : PrinterEventType.PRINT_STARTED, current, d));
            case PAUSED -> {
                if (from == PrintState.PRINTING) {
                    events.add(event(PrinterEventType.PRINT_PAUSED, current, d));
                }
            }
            case COMPLETE -> {
                if (from.isActive()) {
                    events.add(event(PrinterEventType.PRINT_FINISHED, current, d));
                }
            }
            case CANCELLED -> {
                if (from.isActive()) {
                    events.add(event(PrinterEventType.PRINT_CANCELLED, current, d));
                }
            }
            case ERROR -> {
                if (from.isActive()) {
                    events.add(event(PrinterEventType.PRINT_FAILED, current, d));
                }
            }
            default -> {
                // active -> standby without a final state is resolved via Moonraker's job history
            }
        }
    }

    private void detectProgress(PrinterStatus previous, PrinterStatus current, List<PrinterEvent> events) {
        if (current.printState() == PrintState.PRINTING && current.progress() != null) {
            double prev = previous.progress() == null || !previous.printState().isActive()
                    ? 0 : previous.progress();
            if (progressStep > 0) {
                int prevStep = (int) Math.floor(prev / progressStep);
                int currStep = (int) Math.floor(current.progress() / progressStep);
                if (currStep > prevStep && currStep * progressStep < 100) {
                    events.add(event(PrinterEventType.PROGRESS, current,
                            details("percent", currStep * progressStep)));
                }
            }
        }
        if (layerEvents && current.printState().isActive() && current.currentLayer() != null
                && !Objects.equals(previous.currentLayer(), current.currentLayer())) {
            events.add(event(PrinterEventType.LAYER_CHANGED, current, details(
                    "layer", current.currentLayer(), "totalLayers", current.totalLayers())));
        }
    }

    private void detectToolheads(PrinterStatus previous, PrinterStatus current, List<PrinterEvent> events) {
        if (previous.activeToolhead() != current.activeToolhead()
                && current.activeToolhead() >= 0) {
            events.add(event(PrinterEventType.TOOLHEAD_CHANGED, current, details(
                    "toolhead", current.activeToolhead(), "previous", previous.activeToolhead())));
        }
        for (ToolheadStatus now : current.toolheads()) {
            ToolheadStatus before = previous.toolhead(now.index());
            if (before == null || before.filamentLoaded() == null || now.filamentLoaded() == null) {
                continue;
            }
            Map<String, Object> d = details("toolhead", now.index(), "type", now.filamentType(),
                    "color", now.filamentColor(), "vendor", now.filamentVendor());
            if (!before.filamentLoaded() && now.filamentLoaded()) {
                events.add(event(PrinterEventType.FILAMENT_LOADED, current, d));
            } else if (before.filamentLoaded() && !now.filamentLoaded()) {
                events.add(event(PrinterEventType.FILAMENT_UNLOADED, current, d));
            } else if (now.filamentLoaded() && (!Objects.equals(before.filamentType(), now.filamentType())
                    || !Objects.equals(before.filamentColor(), now.filamentColor())
                    || !Objects.equals(before.filamentSubType(), now.filamentSubType()))) {
                d.put("previousType", before.filamentType());
                d.put("previousColor", before.filamentColor());
                events.add(event(PrinterEventType.FILAMENT_CHANGED, current, d));
            }
        }
    }

    static PrinterEvent event(PrinterEventType type, PrinterStatus status, Map<String, Object> details) {
        return new PrinterEvent(type, status.printerId(), status, details, Instant.now());
    }

    static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
