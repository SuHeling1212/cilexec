package com.follarce.app;

import com.follarce.application.ProcessStatementExecutor;
import com.follarce.audit.AuditRetentionService;
import com.follarce.config.CilExecConfig;
import com.follarce.effect.EffectHandler;
import com.follarce.effect.EffectHandlerRegistry;
import com.follarce.effect.EffectWorkerService;
import com.follarce.effect.BuiltinEffectHandlers;
import com.follarce.health.HealthServer;
import com.follarce.health.HealthState;
import com.follarce.persistence.postgres.connection.ControlLock;
import com.follarce.persistence.postgres.connection.DataSourceFactory;
import com.follarce.persistence.postgres.connection.DatabaseHealth;
import com.follarce.persistence.postgres.connection.SchemaVerifier;
import com.follarce.persistence.postgres.repository.RecoveryCoordinator;
import com.follarce.persistence.postgres.repository.RuntimeMetadataStore;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.scheduler.ClaimedProcessHandler;
import com.follarce.scheduler.SchedulerService;
import com.follarce.timer.TimerService;
import com.follarce.terminal.DatabaseTerminalControl;
import com.follarce.terminal.TerminalAccessConsole;
import com.follarce.terminal.TerminalAccessService;
import com.follarce.terminal.TerminalBootstrap;
import com.follarce.terminal.TerminalInput;
import com.follarce.terminal.TerminalSettings;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Explicit production assembly for the single active Runtime. */
public final class RuntimeBootstrap {
    private RuntimeBootstrap() {
    }

    public static RuntimeLifecycle assemble(CilExecConfig config, BuildInfo buildInfo) {
        return assemble(config, buildInfo, ProcessStatementExecutor::new,
                BuiltinEffectHandlers.defaults());
    }

    public static RuntimeLifecycle assembleTerminal(CilExecConfig config, BuildInfo buildInfo,
                                                    TerminalSettings settings,
                                                    InputStream input, OutputStream output) {
        ProductionHooks hooks = new ProductionHooks(config, buildInfo,
                ProcessStatementExecutor::new, BuiltinEffectHandlers.defaults(), settings,
                input, output);
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(hooks, config.shutdownGrace());
        hooks.terminalShutdown = () -> lifecycle.shutdown("terminal closed");
        return lifecycle;
    }

    public static RuntimeLifecycle assemble(
            CilExecConfig config,
            BuildInfo buildInfo,
            Function<JdbcTransactionExecutor, ? extends ClaimedProcessHandler> handlerFactory,
            List<? extends EffectHandler> effectHandlers
    ) {
        ProductionHooks hooks = new ProductionHooks(config, buildInfo, handlerFactory,
                effectHandlers, null, null, null);
        return new RuntimeLifecycle(hooks, config.shutdownGrace());
    }

    private static final class ProductionHooks implements RuntimeLifecycle.Hooks {
        private static final int TIMER_BATCH = 100;
        private static final int AUDIT_PURGE_BATCH = 100;

        private final CilExecConfig config;
        private final BuildInfo buildInfo;
        private final HealthState health = new HealthState();
        private final HikariDataSource runtimeDataSource;
        private final JdbcTransactionExecutor runtimeTransactions;
        private final ClaimedProcessHandler processHandler;
        private final List<? extends EffectHandler> effectHandlers;
        private final RuntimeMetadataStore metadata;
        private final RecoveryCoordinator recovery;
        private final TerminalSettings terminalSettings;
        private final InputStream terminalInput;
        private final OutputStream terminalOutput;
        private final AtomicBoolean resourcesClosed = new AtomicBoolean();

        private volatile Consumer<Throwable> fence;
        private volatile ControlLock control;
        private volatile RuntimeMetadataStore.BootIdentity boot;
        private volatile HealthServer healthServer;
        private volatile SchedulerService scheduler;
        private volatile HikariDataSource effectDataSource;
        private volatile EffectWorkerService effectWorkers;
        private volatile TimerLoop timerLoop;
        private volatile Thread terminalThread;
        private volatile Runnable terminalShutdown = () -> { };

        private ProductionHooks(
                CilExecConfig config,
                BuildInfo buildInfo,
                Function<JdbcTransactionExecutor, ? extends ClaimedProcessHandler> handlerFactory,
                List<? extends EffectHandler> effectHandlers,
                TerminalSettings terminalSettings,
                InputStream terminalInput,
                OutputStream terminalOutput
        ) {
            this.config = Objects.requireNonNull(config, "config");
            this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo");
            this.effectHandlers = List.copyOf(effectHandlers);
            this.terminalSettings = terminalSettings;
            this.terminalInput = terminalInput;
            this.terminalOutput = terminalOutput;
            runtimeDataSource = DataSourceFactory.create(config.runtimeDatabase());
            runtimeTransactions = new JdbcTransactionExecutor(runtimeDataSource);
            processHandler = Objects.requireNonNull(handlerFactory, "handlerFactory")
                    .apply(runtimeTransactions);
            metadata = new RuntimeMetadataStore(runtimeDataSource);
            recovery = new RecoveryCoordinator(runtimeDataSource);
        }

        @Override
        public void phase(HealthState.RuntimePhase phase) {
            health.phase(phase);
        }

        @Override
        public int verifySchema() {
            boolean available = new DatabaseHealth(runtimeDataSource).isAvailable();
            health.database(available);
            if (!available) throw new IllegalStateException("Runtime database is unavailable");
            int version = new SchemaVerifier(runtimeDataSource, buildInfo.minimumSchema(),
                    buildInfo.maximumSchema()).verify();
            health.schema(true);
            return version;
        }

        @Override
        public void acquireControl(Consumer<Throwable> onFence) {
            fence = Objects.requireNonNull(onFence, "onFence");
            control = ControlLock.acquire(config.runtimeDatabase(), config.advisoryLockKey());
            health.controlLock(true);
            control.monitor(config.heartbeatInterval(), failure -> {
                health.controlLock(false);
                health.database(false);
                fence.accept(failure);
            });
        }

        @Override
        public void beginBoot(int schemaVersion) {
            boot = metadata.beginBoot(config.instanceName(), config.advisoryLockKey(),
                    buildInfo.applicationVersion() + "+" + buildInfo.revision(), schemaVersion,
                    buildInfo.fclRuntimeFormat(),
                    Objects.requireNonNull(control, "control lock has not been acquired")
                            .identity());
        }

        @Override
        public void markRecovering() {
            metadata.markRecovering(requireBoot());
            health.recovery(false);
        }

        @Override
        public void recover() {
            recovery.recover(requireBoot().bootId(), Instant.now());
            health.recovery(true);
        }

        @Override
        public void startHealth() {
            healthServer = new HealthServer(config.healthPort(), health);
            healthServer.start();
        }

        @Override
        public void startScheduler() {
            scheduler = new SchedulerService(runtimeTransactions, processHandler,
                    requireBoot().bootId(), config.schedulerWorkers(), config.leaseDuration(),
                    config.schedulerIdlePoll(), requireFence());
            scheduler.start();
            health.schedulerLoop(true);
        }

        @Override
        public void startEffectWorkers() {
            effectDataSource = DataSourceFactory.create(config.effectDatabase());
            JdbcTransactionExecutor effectTransactions =
                    new JdbcTransactionExecutor(effectDataSource);
            effectWorkers = new EffectWorkerService(effectTransactions, runtimeTransactions,
                    requireBoot().bootId(), new EffectHandlerRegistry(effectHandlers), config.effectWorkers(),
                    config.heartbeatInterval(), Clock.systemUTC(), requireFence());
            effectWorkers.start();
        }

        @Override
        public void startTimerLoop() {
            TimerService timers = new TimerService(runtimeTransactions, runtimeTransactions,
                    Clock.systemUTC());
            AuditRetentionService auditRetention = new AuditRetentionService(
                    runtimeTransactions, Clock.systemUTC());
            UUID runnerId = UUID.randomUUID();
            timerLoop = new TimerLoop(() -> {
                int released = runtimeTransactions.inTransaction(
                        com.follarce.domain.port.Isolation.READ_COMMITTED,
                        transaction -> transaction.scheduler().releaseExpired(Instant.now()));
                int fired = timers.fireDue(runnerId, TIMER_BATCH);
                int purged = auditRetention.purgeExpired(AUDIT_PURGE_BATCH);
                return released + fired + purged;
            },
                    config.heartbeatInterval(), requireFence());
            timerLoop.start();
        }

        @Override
        public void markReady() {
            metadata.markReady(requireBoot());
            if (terminalSettings != null) startTerminal();
        }

        private void startTerminal() {
            Clock clock = Clock.systemUTC();
            new TerminalBootstrap(runtimeTransactions, clock).ensure(terminalSettings)
                    .ifPresent(_ -> {});
            TerminalInput input = TerminalInput.system(
                    Objects.requireNonNull(terminalInput, "terminal input"));
            PrintWriter output = new PrintWriter(new OutputStreamWriter(
                    Objects.requireNonNull(terminalOutput, "terminal output"),
                    StandardCharsets.UTF_8), true);
            var access = new TerminalAccessService(runtimeTransactions,
                    config.runtimeDatabase().jdbcUrl(), clock, terminalSettings.username());
            com.follarce.application.FclRuntimeFunctions.setPasswordVerifier(
                    password -> access.login(terminalSettings.username(), password).isPresent());
            var console = new TerminalAccessConsole(input, output, access,
                    account -> new DatabaseTerminalControl(runtimeTransactions, account,
                            terminalShutdown), terminalSettings.username());
            terminalThread = Thread.ofPlatform().daemon(true).name("cilexec-terminal").start(() -> {
                try {
                    console.run();
                } finally {
                    terminalShutdown.run();
                }
            });
        }

        @Override
        public synchronized void stopScheduler() {
            health.schedulerLoop(false);
            if (scheduler != null) {
                scheduler.close();
                scheduler = null;
            }
        }

        @Override
        public synchronized void stopEffectWorkers() {
            if (effectWorkers != null) {
                effectWorkers.close();
                effectWorkers = null;
            }
        }

        @Override
        public synchronized void stopTimerLoop() {
            if (timerLoop != null) {
                timerLoop.close();
                timerLoop = null;
            }
        }

        @Override
        public synchronized void stopHealth() {
            if (healthServer != null) {
                healthServer.close();
                healthServer = null;
            }
        }

        @Override
        public void markClean(String reason) {
            metadata.markClean(requireBoot(), reason);
        }

        @Override
        public void markFenced(String reason) {
            metadata.markFenced(requireBoot(), reason);
        }

        @Override
        public synchronized void releaseControl() {
            health.controlLock(false);
            if (control != null) {
                control.close();
                control = null;
            }
        }

        @Override
        public void closeResources() {
            if (!resourcesClosed.compareAndSet(false, true)) return;
            HikariDataSource effects = effectDataSource;
            if (effects != null) effects.close();
            runtimeDataSource.close();
            health.database(false);
        }

        @Override
        public void forceClose() {
            stopScheduler();
            stopEffectWorkers();
            stopTimerLoop();
            stopHealth();
            releaseControl();
            closeResources();
        }

        private RuntimeMetadataStore.BootIdentity requireBoot() {
            return Objects.requireNonNull(boot, "boot metadata has not been created");
        }

        private Consumer<Throwable> requireFence() {
            return Objects.requireNonNull(fence, "control lock has not been acquired");
        }
    }
}
