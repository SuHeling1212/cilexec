package com.follarce.effect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkTargetPolicyTest {
    @BeforeAll
    static void allowLoopbackBeforeThePolicyClassLoads() {
        System.setProperty("cilexec.networkAllowPrivateHosts", "127.0.0.1,localhost");
    }
    @Test
    void acceptsIpv6LiteralsWithoutIdnConversion() throws Exception {
        InetAddress address = NetworkTargetPolicy.requirePublicAddress("2606:4700::1");
        assertEquals("2606:4700:0:0:0:0:0:1", address.getHostAddress());

        InetAddress bracketed = NetworkTargetPolicy.requirePublicAddress("[2606:4700::1]");
        assertEquals(address, bracketed);
    }

    @Test
    void rejectsLoopbackIpv6Literal() {
        assertThrows(SecurityException.class,
                () -> NetworkTargetPolicy.requirePublicAddress("::1"));
    }

    @Test
    void hostHeaderKeepsIpv6BracketsExactlyOnce() {
        NetworkTargetPolicy.ResolvedHttpTarget target = new NetworkTargetPolicy.ResolvedHttpTarget(
                URI.create("http://[2001:db8::1]:8080/path"),
                InetAddress.getLoopbackAddress());
        assertEquals("[2001:db8::1]:8080", target.hostHeader());
    }

    @Test
    void hostHeaderHandlesDefaultPortsAndHostnames() {
        NetworkTargetPolicy.ResolvedHttpTarget https =
                new NetworkTargetPolicy.ResolvedHttpTarget(
                        URI.create("https://example.com/path"),
                        InetAddress.getLoopbackAddress());
        assertEquals("example.com", https.hostHeader());

        NetworkTargetPolicy.ResolvedHttpTarget withPort =
                new NetworkTargetPolicy.ResolvedHttpTarget(
                        URI.create("http://localhost:8080/path"),
                        InetAddress.getLoopbackAddress());
        assertEquals("localhost:8080", withPort.hostHeader());
    }

    @Test
    void blocksDeprecatedSixBoneRange() {
        assertThrows(SecurityException.class,
                () -> NetworkTargetPolicy.requirePublicAddress("3ffe::1"));
    }
}
