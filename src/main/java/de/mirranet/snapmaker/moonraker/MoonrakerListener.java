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
