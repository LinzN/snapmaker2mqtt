package de.linzn.snapmaker2mqtt.moonraker;

/**
 * Error returned by Moonraker or raised by the client (code {@code -1}).
 */
public class MoonrakerException extends RuntimeException {

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
