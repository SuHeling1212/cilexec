package com.follarce.domain;

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
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.port.VfsRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortContractTest {
    private static final List<Class<?>> REPOSITORIES = List.of(
            ProgramRepository.class,
            ProcessRepository.class,
            SchedulerRepository.class,
            IpcRepository.class,
            TimerRepository.class,
            VfsRepository.class,
            PackageRepository.class,
            EffectRepository.class,
            AuthRepository.class,
            AuditRepository.class,
            TerminalRepository.class,
            EnvironmentRepository.class);

    @Test
    void transactionContextExposesEveryRepositoryAndOwnsCompletion() {
        Set<String> methods = names(TransactionContext.class);

        Set<String> expected = Set.of(
                "programs", "processes", "scheduler", "ipc", "timers", "vfs",
                "packages", "effects", "auth", "audit", "terminal", "environment",
                "commit", "rollback", "close");
        assertTrue(methods.containsAll(expected));
        assertEquals(expected.size(), methods.size());
    }

    @Test
    void repositoriesCannotCommitOrRollbackTheirOwnTransactions() {
        for (Class<?> repository : REPOSITORIES) {
            Set<String> methods = names(repository);
            assertFalse(methods.contains("commit"), repository.getName());
            assertFalse(methods.contains("rollback"), repository.getName());
            assertTrue(repository.isInterface(), repository.getName());
        }
    }

    @Test
    void transactionExecutorHasOneGenericApplicationBoundary() {
        List<Method> methods = List.of(TransactionExecutor.class.getDeclaredMethods());

        assertEquals(1, methods.size());
        assertEquals("inTransaction", methods.getFirst().getName());
        assertEquals(1, methods.getFirst().getTypeParameters().length);
    }

    private static Set<String> names(Class<?> type) {
        return List.of(type.getDeclaredMethods()).stream()
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
