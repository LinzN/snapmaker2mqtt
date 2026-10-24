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
