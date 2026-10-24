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
