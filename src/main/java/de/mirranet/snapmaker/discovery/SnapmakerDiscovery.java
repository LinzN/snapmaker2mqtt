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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Finds Snapmaker U1 printers (and other Moonraker hosts) on the local network.
 *
 * <p>The U1 does not answer a broadcast, so every address of the local IPv4 networks (at most the
 * /24 around each own address) is probed on the Moonraker port. Hosts that answer are asked for
 * {@code /printer/info} and {@code /printer/objects/list}; the U1 is recognized by its
 * {@code print_task_config} object.
 */
public final class SnapmakerDiscovery {

    private static final Logger LOG = Logger.getLogger(SnapmakerDiscovery.class.getName());

    /** Default Moonraker port of the U1. */
    public static final int DEFAULT_PORT = 7125;

    private SnapmakerDiscovery() {
    }

    /**
     * Scans all local IPv4 networks.
     *
     * @param timeout connect timeout per address
     * @return found hosts, sorted by address
     */
    public static List<DiscoveredPrinter> discover(Duration timeout) {
        Set<String> candidates = new LinkedHashSet<>();
        for (InterfaceAddress address : localAddresses()) {
            byte[] own = address.getAddress().getAddress();
            int prefix = Math.max(address.getNetworkPrefixLength(), 24);
            int hostBits = 32 - prefix;
            int base = toInt(own) & (0xFFFFFFFF << hostBits);
            for (int i = 1; i < (1 << hostBits) - 1; i++) {
                String ip = toIp(base + i);
                if (!ip.equals(address.getAddress().getHostAddress())) {
                    candidates.add(ip);
                }
            }
        }
        LOG.fine(() -> "Probing " + candidates.size() + " addresses on port " + DEFAULT_PORT);
        return probe(candidates, DEFAULT_PORT, timeout);
    }

    /**
     * Asks one address, e.g. in another VLAN.
     *
     * @return the printer, or null if no Moonraker answers there
     */
    public static DiscoveredPrinter discover(String host, int port, Duration timeout) {
        List<DiscoveredPrinter> found = probe(List.of(host), port, timeout);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<DiscoveredPrinter> probe(Iterable<String> hosts, int port, Duration timeout) {
        ExecutorService pool = Executors.newFixedThreadPool(64, r -> {
            Thread t = new Thread(r, "snapmaker-discovery");
            t.setDaemon(true);
            return t;
        });
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        try {
            List<Future<DiscoveredPrinter>> futures = new ArrayList<>();
            for (String host : hosts) {
                futures.add(pool.submit(() -> isPortOpen(host, port, timeout) ? query(http, host, port, timeout) : null));
            }
            List<DiscoveredPrinter> result = new ArrayList<>();
            for (Future<DiscoveredPrinter> future : futures) {
                try {
                    DiscoveredPrinter printer = future.get();
                    if (printer != null) {
                        result.add(printer);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            result.sort(Comparator.comparingLong(p -> sortKey(p.host())));
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isPortOpen(String host, int port, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static DiscoveredPrinter query(HttpClient http, String host, int port, Duration timeout) {
        String base = "http://" + host + ":" + port;
        try {
            HttpResponse<String> info = get(http, base + "/printer/info", timeout);
            if (info.statusCode() == 401 || info.statusCode() == 403) {
                return new DiscoveredPrinter(host, port, null, null, false, true);
            }
            if (info.statusCode() != 200) {
                return null;
            }
            JSONObject result = new JSONObject(info.body()).optJSONObject("result");
            if (result == null) {
                return null;
            }
            boolean u1 = false;
            HttpResponse<String> objects = get(http, base + "/printer/objects/list", timeout);
            if (objects.statusCode() == 200) {
                JSONObject objectsResult = new JSONObject(objects.body()).optJSONObject("result");
                JSONArray list = objectsResult == null ? null : objectsResult.optJSONArray("objects");
                u1 = list != null && list.toList().contains("print_task_config");
            }
            return new DiscoveredPrinter(host, port, result.optString("hostname", null),
                    result.optString("state", null), u1, false);
        } catch (IOException | JSONException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static HttpResponse<String> get(HttpClient http, String url, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout.plusSeconds(2)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<InterfaceAddress> localAddresses() {
        List<InterfaceAddress> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isPointToPoint()) {
                    continue;
                }
                for (InterfaceAddress address : nif.getInterfaceAddresses()) {
                    InetAddress inet = address.getAddress();
                    if (inet instanceof Inet4Address && !inet.isLinkLocalAddress()) {
                        result.add(address);
                    }
                }
            }
        } catch (SocketException e) {
            LOG.warning("Cannot list network interfaces: " + e.getMessage());
        }
        return result;
    }

    private static int toInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static String toIp(int value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "." + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }

    private static long sortKey(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return Long.MAX_VALUE;
        }
        long key = 0;
        for (String part : parts) {
            try {
                key = key * 256 + Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }
        return key;
    }
}
