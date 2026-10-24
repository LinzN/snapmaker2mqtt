package de.linzn.snapmaker2mqtt.printer;

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
