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
 * Events detected from state changes of the printer.
 */
public enum PrinterEventType {
    /** Moonraker connection established and Klippy ready. */
    PRINTER_ONLINE,
    /** Moonraker unreachable or Klippy not ready (shutdown, error, firmware restart). */
    PRINTER_OFFLINE,

    PRINT_STARTED,
    PRINT_PAUSED,
    PRINT_RESUMED,
    /** Job finished successfully. */
    PRINT_FINISHED,
    PRINT_CANCELLED,
    /** Job ended with an error. */
    PRINT_FAILED,

    /** Progress crossed a configured step (e.g. every 10 %). */
    PROGRESS,
    /** Layer number changed (requires layer info from the slicer). */
    LAYER_CHANGED,
    /** Another toolhead became active. */
    TOOLHEAD_CHANGED,
    /** Filament was inserted into a toolhead. */
    FILAMENT_LOADED,
    /** Filament was removed from a toolhead. */
    FILAMENT_UNLOADED,
    /** Type or color of the filament in a toolhead changed. */
    FILAMENT_CHANGED,
    /** U1 machine action changed (homing, nozzle cleaning, auto feeding, ...). */
    MACHINE_ACTION_CHANGED,
    /** Klipper reported a new error message. */
    ERROR_MESSAGE;

    public String key() {
        return name().toLowerCase();
    }
}
