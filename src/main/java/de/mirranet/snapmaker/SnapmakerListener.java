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
