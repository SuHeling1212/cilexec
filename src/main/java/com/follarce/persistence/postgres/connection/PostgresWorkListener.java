package com.follarce.persistence.postgres.connection;

import com.follarce.persistence.postgres.error.SqlStateClassifier;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One blocking LISTEN connection replaces scheduler/effect queue polling. */
public final class PostgresWorkListener implements AutoCloseable {
    public static final String SCHEDULER = "cilexec_scheduler_work";
    public static final String EFFECT = "cilexec_effect_work";
    public static final String TIMER = "cilexec_timer_work";
    public static final String INTERRUPT = "cilexec_interrupt_work";

    private final DataSource dataSource;
    private final Map<String, Runnable> handlers;
    private final Consumer<Throwable> fatalFailure;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Connection connection;
    private volatile Thread worker;

    public PostgresWorkListener(DataSource dataSource, Runnable scheduler, Runnable effect,
                                Runnable timer, Runnable interrupt,
                                Consumer<Throwable> fatalFailure) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.handlers = Map.of(SCHEDULER, Objects.requireNonNull(scheduler, "scheduler"),
                EFFECT, Objects.requireNonNull(effect, "effect"),
                TIMER, Objects.requireNonNull(timer, "timer"),
                INTERRUPT, Objects.requireNonNull(interrupt, "interrupt"));
        this.fatalFailure = Objects.requireNonNull(fatalFailure, "fatalFailure");
    }

    /** Establishes LISTEN synchronously so no committed wake-up can fall into a startup gap. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("PostgreSQL work listener already started");
        }
        try {
            Connection opened = dataSource.getConnection();
            connection = opened;
            opened.setAutoCommit(true);
            try (Statement statement = opened.createStatement()) {
                statement.execute("LISTEN " + SCHEDULER);
                statement.execute("LISTEN " + EFFECT);
                statement.execute("LISTEN " + TIMER);
                statement.execute("LISTEN " + INTERRUPT);
            }
            worker = Thread.ofVirtual().name("cilexec-work-listener").start(this::listen);
        } catch (SQLException failure) {
            running.set(false);
            closeConnection(false);
            throw SqlStateClassifier.classify("work-listener.start", failure);
        }
    }

    public boolean isRunning() {
        Thread current = worker;
        return running.get() && current != null && current.isAlive();
    }

    private void listen() {
        try {
            PGConnection postgres = connection.unwrap(PGConnection.class);
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                PGNotification[] notifications = postgres.getNotifications(0);
                if (notifications == null) continue;
                for (PGNotification notification : notifications) {
                    Runnable handler = handlers.get(notification.getName());
                    if (handler != null) handler.run();
                }
            }
        } catch (SQLException failure) {
            if (running.compareAndSet(true, false)) {
                fatalFailure.accept(SqlStateClassifier.classify("work-listener.receive", failure));
            }
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        // A pooled Connection.close() merely returns the socket to Hikari and does not unblock
        // PGConnection.getNotifications(0). Abort first so that socket can never be reused while
        // the listener still owns its blocking read.
        closeConnection(true);
        Thread current = worker;
        if (current != null) {
            if (current == Thread.currentThread()) {
                worker = null;
                return;
            }
            current.interrupt();
            try {
                current.join(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    private void closeConnection(boolean abort) {
        Connection current = connection;
        connection = null;
        if (current == null) return;
        if (abort) {
            try {
                current.abort(Runnable::run);
            } catch (SQLException ignored) {
                // Fall through to the normal close path.
            }
        }
        try {
            current.close();
        } catch (SQLException ignored) {
            // Shutdown is already in progress.
        }
    }
}
