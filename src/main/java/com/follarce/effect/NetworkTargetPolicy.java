package com.follarce.effect;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Default-deny policy for addresses that could reach the host or an internal service. */
final class NetworkTargetPolicy {
    private static final Set<String> EXPLICIT_PRIVATE_HOSTS = configuredPrivateHosts();
    private static final Set<String> EXPLICIT_PRIVATE_HTTP_ORIGINS =
            configuredPrivateHttpOrigins();

    private NetworkTargetPolicy() { }

    static ResolvedHttpTarget resolveHttpTarget(URI uri) throws UnknownHostException {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL must contain a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new SecurityException("URL user information is not allowed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported");
        }
        String origin = normalizedHttpOrigin(uri);
        InetAddress address = requireAddress(uri.getHost(),
                EXPLICIT_PRIVATE_HTTP_ORIGINS.contains(origin));
        return new ResolvedHttpTarget(uri, address);
    }

    static InetAddress requirePublicAddress(String suppliedHost) throws UnknownHostException {
        return requireAddress(suppliedHost, false);
    }

    private static InetAddress requireAddress(String suppliedHost,
                                              boolean explicitlyAllowedOrigin)
            throws UnknownHostException {
        if (suppliedHost == null || suppliedHost.isBlank() || suppliedHost.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Network host is missing or invalid");
        }
        String host = asciiHost(suppliedHost);
        boolean explicitlyAllowed = explicitlyAllowedOrigin
                || EXPLICIT_PRIVATE_HOSTS.contains(host);
        if (!explicitlyAllowed && (host.equals("localhost") || host.endsWith(".localhost")
                || host.equals("host.docker.internal"))) {
            throw new SecurityException("Local and Docker host targets are blocked");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new UnknownHostException(host);
        for (InetAddress address : addresses) {
            if (!explicitlyAllowed && blocked(address)) {
                throw new SecurityException("Private or special-use network target is blocked");
            }
        }
        // Pin socket calls to an address that was actually checked.
        return addresses[0];
    }

    private static String normalizedHttpOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = asciiHost(uri.getHost());
        int port = uri.getPort();
        int effectivePort = port >= 0 ? port : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + host + ":" + effectivePort;
    }

    /** IDN-encodes a DNS name; IPv6 literals (bare or bracketed) skip IDN entirely. */
    private static String asciiHost(String supplied) {
        String candidate = supplied.trim();
        if (candidate.indexOf(':') >= 0) {
            if (candidate.startsWith("[") && candidate.endsWith("]")) {
                candidate = candidate.substring(1, candidate.length() - 1);
            }
            return candidate.toLowerCase(Locale.ROOT);
        }
        return IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT);
    }

    private static boolean blocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0 || first == 10 || first == 127 || first >= 224) return true;
            if (first == 100 && second >= 64 && second <= 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            if (first == 192 && second == 168) return true;
            if (first == 198 && (second == 18 || second == 19)) return true;
            return documentationV4(first, second, bytes[2] & 0xff);
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true; // fc00::/7 unique local
            if (prefix(bytes, new byte[]{0x00, 0x64, (byte) 0xff, (byte) 0x9b}, 32)
                    || prefix(bytes, new byte[]{0x00, 0x64, (byte) 0xff, (byte) 0x9b,
                    0x00, 0x01}, 48)) return true; // NAT64 translation prefixes
            if (prefix(bytes, new byte[]{0x01, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00}, 64)) return true; // discard-only
            if (prefix(bytes, new byte[]{0x20, 0x01, 0x00}, 23)) return true;
            if (prefix(bytes, new byte[]{0x20, 0x02}, 16)) return true; // 6to4
            if (first == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) return true;
            if (prefix(bytes, new byte[]{0x3f, (byte) 0xfe}, 16)) return true; // 6bone deprecated
            // Reject IPv4-mapped addresses when their embedded address is not public.
            byte[] prefix = Arrays.copyOf(bytes, 12);
            boolean mapped = Arrays.equals(prefix,
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff});
            if (mapped) {
                try {
                    return blocked(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException impossible) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean prefix(byte[] address, byte[] prefix, int bits) {
        int completeBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int index = 0; index < completeBytes; index++) {
            if (address[index] != prefix[index]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (address[completeBytes] & mask) == (prefix[completeBytes] & mask);
    }

    private static boolean documentationV4(int first, int second, int third) {
        return (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113);
    }

    private static Set<String> configuredPrivateHosts() {
        String property = System.getProperty("cilexec.networkAllowPrivateHosts");
        String configured = property == null
                ? System.getenv("CILEXEC_NETWORK_ALLOW_PRIVATE_HOSTS") : property;
        if (configured == null || configured.isBlank()) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String item : configured.split(",")) {
            String value = item.trim();
            if (!value.isEmpty()) {
                result.add(asciiHost(value));
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> configuredPrivateHttpOrigins() {
        String property = System.getProperty("cilexec.networkAllowPrivateHttpOrigins");
        String configured = property == null
                ? System.getenv("CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS") : property;
        if (configured == null || configured.isBlank()) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String item : configured.split(",")) {
            URI origin;
            try {
                origin = URI.create(item.trim());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid private HTTP origin allowlist", invalid);
            }
            if (origin.getHost() == null || origin.getRawUserInfo() != null
                    || origin.getRawPath() != null && !origin.getRawPath().isEmpty()
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !("http".equalsIgnoreCase(origin.getScheme())
                    || "https".equalsIgnoreCase(origin.getScheme()))) {
                throw new IllegalArgumentException(
                        "Private HTTP allowlist entries must be origins without paths");
            }
            result.add(normalizedHttpOrigin(origin));
        }
        return Set.copyOf(result);
    }

    /** One policy-approved address that an HTTP transport must use without resolving again. */
    record ResolvedHttpTarget(URI originalUri, InetAddress address) {
        ResolvedHttpTarget {
            java.util.Objects.requireNonNull(originalUri, "originalUri");
            java.util.Objects.requireNonNull(address, "address");
        }

        URI pinnedUri() {
            try {
                String path = originalUri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                return new URI(originalUri.getScheme(), null, address.getHostAddress(),
                        originalUri.getPort(), path, originalUri.getRawQuery(), null);
            } catch (java.net.URISyntaxException impossible) {
                throw new IllegalArgumentException("Cannot pin validated HTTP target", impossible);
            }
        }

        String hostHeader() {
            String host = originalUri.getHost();
            // JDK 22+ already returns IPv6 literals with brackets; never wrap twice.
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            int port = originalUri.getPort();
            boolean defaultPort = port < 0
                    || originalUri.getScheme().equalsIgnoreCase("http") && port == 80
                    || originalUri.getScheme().equalsIgnoreCase("https") && port == 443;
            return defaultPort ? host : host + ":" + port;
        }
    }
}
