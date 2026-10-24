package de.mirranet.snapmaker.discovery;

/**
 * A Moonraker host found by {@link SnapmakerDiscovery}.
 *
 * @param host         IP address
 * @param port         Moonraker port
 * @param hostname     host name the printer reports, may be null
 * @param klippyState  Klipper state at the time of the scan, may be null
 * @param snapmakerU1  true if the printer has the U1 specific Klipper objects
 * @param authRequired true if Moonraker answered but needs an API key
 */
public record DiscoveredPrinter(String host,
                                int port,
                                String hostname,
                                String klippyState,
                                boolean snapmakerU1,
                                boolean authRequired) {
}
