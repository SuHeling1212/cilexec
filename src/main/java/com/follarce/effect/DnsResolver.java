package com.follarce.effect;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded DNS resolution. {@link InetAddress#getAllByName} blocks the calling thread
 * indefinitely on a stuck resolver, which would permanently consume an effect worker.
 * Lookups run on a disposable virtual thread with a hard timeout; when the deadline
 * passes the worker is released even if the native resolver never returns.
 */
final class DnsResolver {
    static final long DNS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);

    private DnsResolver() { }

    static InetAddress[] resolveAll(String host) throws UnknownHostException {
        return resolveAll(host, InetAddress::getAllByName, DNS_TIMEOUT_NANOS);
    }

    /** Test seam: bounded resolution against an injected lookup. */
    @FunctionalInterface
    interface Lookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    static InetAddress[] resolveAll(String host, Lookup lookup, long timeoutNanos)
            throws UnknownHostException {
        if (host == null || host.isBlank()) throw new UnknownHostException("missing host");
        FutureTask<InetAddress[]> task = new FutureTask<>(
                () -> lookup.lookup(host));
        Thread.ofVirtual().name("cilexec-dns").start(task);
        try {
            return task.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            throw new UnknownHostException("DNS resolution timed out after 15 seconds: " + host);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof UnknownHostException unknown) throw unknown;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new UnknownHostException("DNS resolution failed for " + host);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("DNS resolution interrupted: " + host);
        }
    }
}
