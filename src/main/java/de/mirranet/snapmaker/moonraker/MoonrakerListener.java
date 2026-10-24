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

import org.json.JSONArray;

/**
 * Receives events from a {@link MoonrakerClient}. All methods are called on the client's
 * single event thread.
 */
public interface MoonrakerListener {

    /** WebSocket connection to Moonraker established. */
    void onConnected();

    /** WebSocket connection to Moonraker lost. The client reconnects automatically. */
    void onDisconnected();

    /**
     * A JSON-RPC notification was received, e.g. {@code notify_status_update}.
     *
     * @param method notification name
     * @param params notification parameters (never null)
     */
    void onNotification(String method, JSONArray params);
}
