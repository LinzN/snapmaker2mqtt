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
 * Job state as reported by Klipper's {@code print_stats.state}.
 */
public enum PrintState {
    STANDBY,
    PRINTING,
    PAUSED,
    COMPLETE,
    CANCELLED,
    ERROR,
    /**
     * Klippy is not ready or the printer is unreachable.
     */
    OFFLINE,
    UNKNOWN;

    public static PrintState fromKlipper(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value.toLowerCase()) {
            case "standby" -> STANDBY;
            case "printing" -> PRINTING;
            case "paused" -> PAUSED;
            case "complete" -> COMPLETE;
            case "cancelled" -> CANCELLED;
            case "error" -> ERROR;
            default -> UNKNOWN;
        };
    }

    /**
     * @return true while a job is loaded and not finished (printing or paused)
     */
    public boolean isActive() {
        return this == PRINTING || this == PAUSED;
    }

    public String key() {
        return name().toLowerCase();
    }
}
