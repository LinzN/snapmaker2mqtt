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

import org.json.JSONObject;

/**
 * Slicer metadata of a G-code file as reported by Moonraker ({@code server.files.metadata}).
 * Values Moonraker could not extract are {@code null}.
 *
 * @param filename       file name relative to the gcodes root
 * @param estimatedTime  print time estimated by the slicer in seconds
 * @param gcodeStartByte byte offset of the first G-code command (after header and thumbnails)
 * @param gcodeEndByte   byte offset of the last G-code command (before the slicer config block)
 * @param filamentTotal  total filament of the job in mm
 */
public record FileMetadata(String filename,
                           Double estimatedTime,
                           Long gcodeStartByte,
                           Long gcodeEndByte,
                           Double filamentTotal) {

    public static FileMetadata fromJson(String filename, JSONObject json) {
        return new FileMetadata(filename,
                positive(json.optDouble("estimated_time", 0)),
                json.has("gcode_start_byte") ? json.optLong("gcode_start_byte") : null,
                json.has("gcode_end_byte") ? json.optLong("gcode_end_byte") : null,
                positive(json.optDouble("filament_total", 0)));
    }

    private static Double positive(double d) {
        return Double.isNaN(d) || d <= 0 ? null : d;
    }
}
