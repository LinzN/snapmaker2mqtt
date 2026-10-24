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

import java.util.Map;

/**
 * Human readable names for the Snapmaker U1 specific {@code machine_state_manager} codes.
 * Taken from Snapmaker's published Klipper fork (klippy/extras/machine_state_manager.py).
 */
public final class U1Codes {

    private static final Map<Integer, String> MAIN_STATES = Map.ofEntries(
            Map.entry(0, "idle"),
            Map.entry(1, "printing"),
            Map.entry(2, "xyz_offset_calibrate"),
            Map.entry(3, "bed_leveling"),
            Map.entry(4, "flow_calibration"),
            Map.entry(5, "shaper_calibrate"),
            Map.entry(6, "upgrading"),
            Map.entry(7, "abnormal"),
            Map.entry(8, "screws_tilt_adjust"),
            Map.entry(9, "auto_load"),
            Map.entry(10, "auto_unload"),
            Map.entry(11, "manual_load"),
            Map.entry(12, "park_point_manual_calibration"),
            Map.entry(13, "homing_origin_calibration"));

    private static final Map<Integer, String> ACTION_CODES = Map.ofEntries(
            Map.entry(0, "idle"),
            Map.entry(1, "homing"),
            Map.entry(2, "detect_plate"),
            Map.entry(3, "preheat_chamber"),
            Map.entry(128, "print_power_loss_restore"),
            Map.entry(129, "print_paused"),
            Map.entry(130, "print_resuming"),
            Map.entry(131, "print_replenishing"),
            Map.entry(132, "print_switch_checking"),
            Map.entry(133, "print_auto_feeding"),
            Map.entry(134, "print_preextruding"),
            Map.entry(135, "print_auto_unloading"),
            Map.entry(136, "print_bed_detecting"),
            Map.entry(192, "manual_clean_extruder"),
            Map.entry(196, "extruder_xyz_offset_probe"),
            Map.entry(200, "auto_clean_nozzle"),
            Map.entry(201, "wait_nozzle_cooling"),
            Map.entry(256, "bed_leveling"),
            Map.entry(257, "bed_preheating"),
            Map.entry(258, "bed_prescanning"),
            Map.entry(320, "extruder_flow_calibrating"),
            Map.entry(384, "shaper_calibrating"),
            Map.entry(512, "reset_to_initial"),
            Map.entry(513, "probe_reference_points"),
            Map.entry(514, "manual_tuning"),
            Map.entry(515, "probing_adjust_verify"),
            Map.entry(576, "auto_loading"),
            Map.entry(640, "auto_unloading"),
            Map.entry(704, "manual_loading"));

    private U1Codes() {
    }

    public static String mainState(Integer code) {
        return code == null ? null : MAIN_STATES.getOrDefault(code, "unknown_" + code);
    }

    public static String actionCode(Integer code) {
        return code == null ? null : ACTION_CODES.getOrDefault(code, "unknown_" + code);
    }
}
