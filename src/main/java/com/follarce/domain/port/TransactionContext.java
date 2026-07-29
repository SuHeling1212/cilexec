package com.follarce.domain.port;

/**
 * One atomic persistence boundary. Repositories obtained here deliberately have no
 * transaction-control methods; only the owning application service may finish it.
 */
public interface TransactionContext extends AutoCloseable {
    ProgramRepository programs();

    ProcessRepository processes();

    SchedulerRepository scheduler();

    IpcRepository ipc();

    TimerRepository timers();

    VfsRepository vfs();

    PackageRepository packages();

    EffectRepository effects();

    AuthRepository auth();

    AuditRepository audit();

    TerminalRepository terminal();

    default EnvironmentRepository environment() {
        throw new UnsupportedOperationException("Environment repository is not implemented");
    }

    void commit();

    void rollback();

    @Override
    void close();
}
