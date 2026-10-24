package de.mirranet.snapmaker;

import java.time.Instant;
import java.util.Map;

/**
 * An event detected on a printer.
 *
 * @param type      event type
 * @param printerId name of the printer the event belongs to
 * @param status    printer status at the time of the event
 * @param details   event specific details (e.g. toolhead index, previous value)
 * @param timestamp time of detection
 */
public record PrinterEvent(PrinterEventType type,
                           String printerId,
                           PrinterStatus status,
                           Map<String, Object> details,
                           Instant timestamp) {
}
