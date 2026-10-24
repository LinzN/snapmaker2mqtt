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

package de.mirranet.snapmaker.moonraker;

/**
 * Error returned by Moonraker, or raised by the client itself (code {@code -1}).
 */
public class MoonrakerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public MoonrakerException(String message, int code) {
        super(message);
        this.code = code;
    }

    /**
     * @return JSON-RPC error code, {@code -1} for client side errors
     */
    public int getCode() {
        return code;
    }
}
