package com.follarce.persistence.postgres.transaction;

import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.EffectRepository;
import com.follarce.domain.port.EnvironmentRepository;
import com.follarce.domain.port.IpcRepository;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.ProgramRepository;
import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.port.TerminalRepository;
import com.follarce.domain.port.TimerRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.VfsRepository;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import com.follarce.persistence.postgres.repository.JdbcAuditRepository;
import com.follarce.persistence.postgres.repository.JdbcAuthRepository;
import com.follarce.persistence.postgres.repository.JdbcEffectRepository;
import com.follarce.persistence.postgres.repository.JdbcEnvironmentRepository;
import com.follarce.persistence.postgres.repository.JdbcIpcRepository;
import com.follarce.persistence.postgres.repository.JdbcPackageRepository;
import com.follarce.persistence.postgres.repository.JdbcProcessRepository;
import com.follarce.persistence.postgres.repository.JdbcProgramRepository;
import com.follarce.persistence.postgres.repository.JdbcSchedulerRepository;
import com.follarce.persistence.postgres.repository.JdbcTerminalRepository;
import com.follarce.persistence.postgres.repository.JdbcTimerRepository;
import com.follarce.persistence.postgres.repository.JdbcVfsRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

/** One physical connection shared by every repository participating in an operation. */
public final class JdbcTransactionContext implements TransactionContext {
    private final Connection connection;
    private final ProgramRepository programs;
    private final ProcessRepository processes;
    private final SchedulerRepository scheduler;
    private final IpcRepository ipc;
    private final TimerRepository timers;
    private final VfsRepository vfs;
    private final PackageRepository packages;
    private final EffectRepository effects;
    private final AuthRepository auth;
    private final AuditRepository audit;
    private final TerminalRepository terminal;
    private final EnvironmentRepository environment;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    JdbcTransactionContext(Connection connection, JsonCodec json) {
        this.connection = connection;
        programs = new JdbcProgramRepository(connection);
        processes = new JdbcProcessRepository(connection, json);
        scheduler = new JdbcSchedulerRepository(connection);
        ipc = new JdbcIpcRepository(connection);
        timers = new JdbcTimerRepository(connection, json);
        vfs = new JdbcVfsRepository(connection, json);
        packages = new JdbcPackageRepository(connection, json);
        effects = new JdbcEffectRepository(connection, json);
        auth = new JdbcAuthRepository(connection);
        audit = new JdbcAuditRepository(connection, json);
        terminal = new JdbcTerminalRepository(connection);
        environment = new JdbcEnvironmentRepository(connection);
    }

    @Override public ProgramRepository programs() { return programs; }
    @Override public ProcessRepository processes() { return processes; }
    @Override public SchedulerRepository scheduler() { return scheduler; }
    @Override public IpcRepository ipc() { return ipc; }
    @Override public TimerRepository timers() { return timers; }
    @Override public VfsRepository vfs() { return vfs; }
    @Override public PackageRepository packages() { return packages; }
    @Override public EffectRepository effects() { return effects; }
    @Override public AuthRepository auth() { return auth; }
    @Override public AuditRepository audit() { return audit; }
    @Override public TerminalRepository terminal() { return terminal; }
    @Override public EnvironmentRepository environment() { return environment; }

    /** Adapter-only fault-injection hook; deliberately not part of {@link TransactionContext}. */
    public void setLocalSetting(String name, String value) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config(?,?,true)")) {
            statement.setString(1, name);
            statement.setString(2, value);
            statement.execute();
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("transaction.setLocalSetting", exception);
        }
    }

    @Override
    public void commit() {
        if (!state.compareAndSet(State.OPEN, State.COMMITTED)) {
            throw new IllegalStateException("Transaction is already " + state.get());
        }
        try {
            connection.commit();
        } catch (SQLException exception) {
            state.set(State.FAILED);
            RuntimeException failure = SqlStateClassifier.classify(
                    "transaction.commit", exception);
            try {
                rollbackConnection();
                state.set(State.ROLLED_BACK);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    @Override
    public void rollback() {
        State current = state.get();
        if (current == State.ROLLED_BACK || current == State.COMMITTED || current == State.CLOSED) {
            return;
        }
        state.set(State.ROLLED_BACK);
        rollbackConnection();
    }

    @Override
    public void close() {
        State current = state.get();
        if (current == State.CLOSED) {
            return;
        }
        RuntimeException failure = null;
        if (current == State.OPEN || current == State.FAILED) try {
            rollback();
        } catch (RuntimeException rollbackFailure) {
            failure = rollbackFailure;
        }
        state.set(State.CLOSED);
        try {
            connection.close();
        } catch (SQLException exception) {
            RuntimeException closeFailure = SqlStateClassifier.classify(
                    "transaction.close", exception);
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    boolean isOpen() {
        return state.get() == State.OPEN;
    }

    private void rollbackConnection() {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("transaction.rollback", exception);
        }
    }

    private enum State { OPEN, COMMITTED, ROLLED_BACK, FAILED, CLOSED }
}
