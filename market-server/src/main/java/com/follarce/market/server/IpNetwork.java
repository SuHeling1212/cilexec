package com.follarce.market.server;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

record IpNetwork(byte[] network, int prefixBits) {
    IpNetwork {
        network = network.clone();
        int maximum = network.length * 8;
        if (prefixBits < 0 || prefixBits > maximum) {
            throw new IllegalArgumentException("Invalid network prefix");
        }
        mask(network, prefixBits);
    }

    static IpNetwork parse(String source) {
        String[] pieces = source.split("/", -1);
        if (pieces.length != 2) throw new IllegalArgumentException("Invalid CIDR: " + source);
        if (!(pieces[0].matches("[0-9.]+") || pieces[0].matches("[0-9A-Fa-f:]+"))) {
            throw new IllegalArgumentException("CIDR address must be an IP literal: " + source);
        }
        try {
            InetAddress address = InetAddress.getByName(pieces[0]);
            int prefix = Integer.parseInt(pieces[1]);
            int maximum = address.getAddress().length * 8;
            if (prefix < 0 || prefix > maximum) throw new NumberFormatException();
            byte[] original = address.getAddress();
            byte[] canonical = original.clone();
            mask(canonical, prefix);
            if (!Arrays.equals(original, canonical)) {
                throw new IllegalArgumentException("CIDR has host bits set: " + source);
            }
            return new IpNetwork(canonical, prefix);
        } catch (UnknownHostException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid CIDR: " + source, invalid);
        }
    }

    boolean contains(InetAddress address) {
        byte[] candidate = normalized(address);
        if (candidate.length != network.length) return false;
        mask(candidate, prefixBits);
        return Arrays.equals(candidate, network);
    }

    private static byte[] normalized(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address && isMappedIpv4(bytes)) {
            return Arrays.copyOfRange(bytes, 12, 16);
        }
        return bytes;
    }

    private static boolean isMappedIpv4(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        for (int index = 0; index < 10; index++) if (bytes[index] != 0) return false;
        return true;
    }

    private static void mask(byte[] bytes, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remaining = prefixBits % 8;
        if (remaining != 0 && fullBytes < bytes.length) {
            bytes[fullBytes] &= (byte) (0xff << (8 - remaining));
            fullBytes++;
        }
        Arrays.fill(bytes, fullBytes, bytes.length, (byte) 0);
    }

    @Override public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixBits;
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
