package de.mirranet.snapmaker;

/**
 * Receives status updates and events of a {@link SnapmakerPrinter}.
 * All callbacks run on the printer's single event thread; keep them short.
 */
public interface SnapmakerListener {

    /**
     * Called for every status change (may be several times per second while printing).
     */
    default void onStatus(PrinterStatus status) {
    }

    /**
     * Called for every detected event, e.g. {@link PrinterEventType#PRINT_FINISHED}.
     */
    default void onEvent(PrinterEvent event) {
    }

    /**
     * Called for console output of Klipper ({@code notify_gcode_response}).
     */
    default void onConsole(String line) {
    }
}
