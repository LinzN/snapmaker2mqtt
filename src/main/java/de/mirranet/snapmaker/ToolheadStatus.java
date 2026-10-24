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
 * State of one of the U1's four toolheads ({@code extruder}, {@code extruder1} ... {@code extruder3})
 * including the filament loaded into it, as reported by the U1 via RFID or set on the touchscreen.
 * Values the printer has not reported are {@code null}.
 *
 * @param index            physical toolhead index 0..3
 * @param name             Klipper object name
 * @param temperature      current nozzle temperature in °C
 * @param target           target temperature in °C
 * @param power            heater power 0..1
 * @param active           true if this is the currently selected toolhead
 * @param filamentLoaded   filament present in this toolhead
 * @param filamentVendor   e.g. "Snapmaker", "NONE" if unknown
 * @param filamentType     e.g. "PLA"
 * @param filamentSubType  e.g. "Matte"
 * @param filamentColor    color as {@code #RRGGBB}
 * @param filamentOfficial true for official Snapmaker RFID spools
 */
public record ToolheadStatus(int index,
                             String name,
                             Double temperature,
                             Double target,
                             Double power,
                             boolean active,
                             Boolean filamentLoaded,
                             String filamentVendor,
                             String filamentType,
                             String filamentSubType,
                             String filamentColor,
                             Boolean filamentOfficial) {
}
