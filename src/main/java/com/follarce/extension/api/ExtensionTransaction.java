package com.follarce.extension.api;

import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.EffectRepository;
import com.follarce.domain.port.IpcRepository;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.ProgramRepository;
import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.port.TerminalRepository;
import com.follarce.domain.port.TimerRepository;
import com.follarce.domain.port.VfsRepository;

/**
 * Repository view of the current statement transaction.
 *
 * <p>It deliberately exposes no commit, rollback, close, connection, or thread control. All
 * writes made through these repositories commit atomically with the FCL continuation.</p>
 */
public interface ExtensionTransaction {
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
}
