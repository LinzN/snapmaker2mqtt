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

package de.snapmaker2mqtt.printer;

import de.mirranet.snapmaker.PrinterStatus;
import de.mirranet.snapmaker.SnapmakerPrinter;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Translates commands from MQTT into API calls.
 *
 * <p>A {@code set} command is parsed and validated completely first, so a typo or an out-of-range
 * value rejects the command before anything is sent to the printer.
 */
public final class CommandMapper {

    /** Writable keys with their allowed range. */
    private static final Map<String, double[]> RANGES = new LinkedHashMap<>();

    static {
        RANGES.put("bedTarget", new double[]{0, 150});
        for (int i = 0; i < PrinterStatus.TOOLHEADS; i++) {
            RANGES.put("toolhead" + i + "Target", new double[]{0, 350});
        }
        RANGES.put("partFanSpeed", new double[]{0, 100});
    }

    /** Values accepted on {@code .../command}. */
    static final List<String> ACTIONS = List.of("pause", "resume", "cancel", "emergency_stop", "firmware_restart");

    private CommandMapper() {
    }

    /**
     * Validates and executes a {@code set} command.
     *
     * @return one future per value sent
     * @throws IllegalArgumentException if any key or value is invalid; nothing is sent then
     */
    public static List<CompletableFuture<Object>> set(SnapmakerPrinter printer, JSONObject command) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : command.keySet()) {
            double[] range = RANGES.get(key);
            if (range == null) {
                throw new IllegalArgumentException("unknown key '" + key + "', writable are "
                        + String.join(", ", new TreeSet<>(RANGES.keySet())));
            }
            double value = number(key, command.get(key));
            if (value < range[0] || value > range[1]) {
                throw new IllegalArgumentException("'" + key + "': " + format(value) + " is outside "
                        + format(range[0]) + " to " + format(range[1]));
            }
            values.put(key, value);
        }

        List<CompletableFuture<Object>> results = new ArrayList<>();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            String key = e.getKey();
            double value = e.getValue();
            if (key.equals("bedTarget")) {
                results.add(printer.setTemperature("heater_bed", value));
            } else if (key.equals("partFanSpeed")) {
                results.add(printer.setPartFanSpeed(value));
            } else {
                int toolhead = Integer.parseInt(key.substring("toolhead".length(), key.length() - "Target".length()));
                results.add(printer.setToolheadTemperature(toolhead, value));
            }
        }
        return results;
    }

    /**
     * Executes an action from {@code .../command}.
     *
     * @throws IllegalArgumentException for an unknown action
     */
    public static CompletableFuture<Object> action(SnapmakerPrinter printer, String payload) {
        String action = payload.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (action) {
            case "pause" -> printer.pause();
            case "resume" -> printer.resume();
            case "cancel" -> printer.cancel();
            case "emergency_stop" -> printer.emergencyStop();
            case "firmware_restart" -> printer.firmwareRestart();
            default -> throw new IllegalArgumentException("'" + payload.trim() + "' is not one of "
                    + String.join(", ", ACTIONS));
        };
    }

    private static double number(String key, Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (s.equals("off")) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "': '" + value + "' is not a number");
        }
    }

    private static String format(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
