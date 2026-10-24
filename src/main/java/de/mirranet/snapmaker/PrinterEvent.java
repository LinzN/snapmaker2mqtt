/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

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
