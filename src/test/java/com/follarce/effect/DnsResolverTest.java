package com.follarce.effect;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsResolverTest {
    @Test
    void resolvesEveryReturnedAddressThroughTheInjectedLookup() throws Exception {
        InetAddress first = InetAddress.getLoopbackAddress();
        InetAddress second = InetAddress.getByName("127.0.0.2");

        InetAddress[] resolved = DnsResolver.resolveAll("example.test",
                host -> new InetAddress[]{first, second}, TimeUnit.SECONDS.toNanos(5));

        assertArrayEquals(new InetAddress[]{first, second}, resolved);
    }

    @Test
    void failsWithinTheDeadlineWhenTheLookupBlocks() {
        CountDownLatch started = new CountDownLatch(1);
        long elapsedNanos = TimeUnit.MILLISECONDS.toNanos(150);
        long deadlineNanos = System.nanoTime() + elapsedNanos;
        assertThrows(UnknownHostException.class, () -> DnsResolver.resolveAll("blocked.test",
                host -> {
                    started.countDown();
                    while (true) {
                        // A stuck resolver never returns; the bounded wait must give up.
                        Thread.onSpinWait();
                    }
                }, elapsedNanos));
        long waited = System.nanoTime() - deadlineNanos + elapsedNanos;
        assertTrue(waited < TimeUnit.SECONDS.toNanos(5),
                "resolution must fail near the configured deadline");
        assertTrue(started.getCount() == 0, "the injected lookup must have started");
    }

    @Test
    void propagatesTheLookupUnknownHostFailure() {
        assertThrows(UnknownHostException.class, () -> DnsResolver.resolveAll("missing.test",
                host -> {
                    throw new UnknownHostException("no such host");
                }, TimeUnit.SECONDS.toNanos(5)));
    }

    @Test
    void rejectsBlankHostsWithoutStartingALookup() {
        assertThrows(UnknownHostException.class, () -> DnsResolver.resolveAll("  ",
                host -> {
                    throw new AssertionError("lookup must not run for a blank host");
                }, TimeUnit.SECONDS.toNanos(5)));
    }
}
