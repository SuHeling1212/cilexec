package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.effect.BuiltinEffectHandlers;
import com.follarce.effect.EffectHandlerRegistry;
import com.follarce.effect.EffectWorkerService;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.scheduler.SchedulerService;
import com.follarce.timer.TimerService;
import com.follarce.timer.TimerWorkerService;
import com.follarce.vfs.AdminVfsService;
import com.follarce.vfs.VfsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "cilexec.external.jdbc", matches = ".+")
class FclSystemFunctionsExternalIT {
    @Test
    void executesVfsSwapProcessAndAdministratorFunctionsFromFcl() {
        execute(transactions());
    }

    /**
     * Exercises a swap pool through separate persisted processes and separate JDBC
     * transactions.  This deliberately does not share an FCL continuation or a JDBC
     * connection between the producer and consumers.
     */
    @Test
    void exchangesDataAcrossIndependentFclProcesses() throws Exception {
        executeSwapPoolAcrossProcesses(transactions());
    }

    static void execute(JdbcTransactionExecutor transactions) {
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthService auth = new AuthService(transactions, clock);
        UserAccount owner = auth.create("fcl-owner-" + suffix,
                "owner-password-123".toCharArray(), Set.of(Capability.VFS_READ,
                        Capability.VFS_WRITE, Capability.PROCESS_CREATE,
                        Capability.PROCESS_CONTROL_OWN, Capability.EFFECT_REQUEST,
                        Capability.PACKAGE_IMPORT, Capability.PACKAGE_BIND));
        UserAccount administrator = auth.create("fcl-admin-" + suffix,
                "admin-password-123".toCharArray(), Set.of(Capability.SYSTEM_ADMIN,
                        Capability.VFS_READ, Capability.PROCESS_CREATE,
                        Capability.PROCESS_CONTROL_OWN));
        UserAccount local = auth.create("local", "local-password-123".toCharArray(),
                Set.of(Capability.SYSTEM_ADMIN, Capability.VFS_READ, Capability.VFS_WRITE,
                        Capability.PROCESS_CREATE, Capability.PROCESS_CONTROL_OWN));
        UserAccount removable = auth.create("fcl-removable-" + suffix,
                "removable-password-123".toCharArray(), Set.of(Capability.VFS_READ));
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode ownerRoot = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                        .findChild(owner.userId(), Optional.empty(), "/").orElseThrow());
        VfsNode privateFile = vfs.createFile(owner.userId(), ownerRoot.nodeId(), "private.txt",
                "secret".getBytes(StandardCharsets.UTF_8), "text/plain", Set.of(), false);
        VfsNode packageSource = vfs.createDirectory(owner.userId(),
                Optional.of(ownerRoot.nodeId()), "pkg", Set.of());
        String manifest = """
                {
                  "namespace":"demo",
                  "name":"hello",
                  "version":"1.0.0",
                  "languageVersion":"fcl-0.0.2",
                  "kind":"application",
                  "modules":[{"name":"main","path":"main.fcl"}],
                  "entrypoints":[{"name":"run","module":"main","function":"run"}],
                  "exports":[{"name":"greet","module":"main","symbol":"greet"}]
                }
                """;
        vfs.createFile(owner.userId(), packageSource.nodeId(), "package.json",
                manifest.getBytes(StandardCharsets.UTF_8), "application/json", Set.of(), false);
        vfs.createFile(owner.userId(), packageSource.nodeId(), "main.fcl", """
                func greet(value) { return "Hello, " + value }
                func run() { return greet("package") }
                """.getBytes(StandardCharsets.UTF_8), "text/x-fcl", Set.of(), false);

        String source = """
                file.write("/note.txt", "hello")
                content = file.read("/note.txt")
                createdLink = file.link("/note-link.txt", "/note.txt")
                linkedContent = file.read("/note-link.txt")
                chain = file.link("/note-chain.txt", "/note-link.txt")
                chainedContent = file.read("/note-chain.txt")
                removedLink = file.remove("/note-link.txt")
                removedChain = file.remove("/note-chain.txt")
                swapPool.create("shared")
                swapPool.add("message:ready", "shared", "type:sync")
                received = swapPool.get("shared", "message")
                environmentUser = env.get("USER")
                environmentUserId = env.get("USER_ID")
                validatesOwnUsername = user.validateUser(environmentUser)
                pid = process.getPID()
                ownProcesses = process.getList()
                functions = system.ls()
                builtPackage = package.build("/pkg/package.json", "/hello.db")
                installedPackage = package.install("/hello.db")
                packageCheck = package.verify("demo/hello/1.0.0")
                launchedPackage = package.run(installedPackage["sha256"])
                foreignHomeVisible = file.exists("/Users/local")
                """;
        var ownerProgram = new ProgramService(transactions).create(owner.userId(), source);
        CilProcess ownerProcess = new ProcessService(transactions).create(owner.userId(),
                ownerProgram, Optional.empty());
        FclContinuation ownerRuntime = run(transactions, ownerProcess, ownerProgram, source);
        assertEquals("hello", ownerRuntime.scope().get("content"));
        assertEquals("hello", ownerRuntime.scope().get("linkedContent"));
        assertEquals("hello", ownerRuntime.scope().get("chainedContent"));
        assertEquals(true, ownerRuntime.scope().get("removedLink"));
        assertEquals(true, ownerRuntime.scope().get("removedChain"));
        assertEquals("ready", ownerRuntime.scope().get("received"));
        assertEquals(owner.username(), ownerRuntime.scope().get("environmentUser"));
        assertEquals(owner.userId().toString(), ownerRuntime.scope().get("environmentUserId"));
        assertEquals(true, ownerRuntime.scope().get("validatesOwnUsername"));
        assertEquals(false, ownerRuntime.scope().get("foreignHomeVisible"));
        assertEquals(ownerProcess.identity().pid(), ownerRuntime.scope().get("pid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ownProcesses =
                (List<Map<String, Object>>) ownerRuntime.scope().get("ownProcesses");
        assertTrue(ownProcesses.stream().allMatch(item ->
                owner.userId().toString().equals(item.get("ownerId"))));
        @SuppressWarnings("unchecked")
        List<Object> names = (List<Object>) ownerRuntime.scope().get("functions");
        assertTrue(names.contains("file.read"));
        assertFalse(names.stream().anyMatch(name -> String.valueOf(name).startsWith("file.admin")));
        assertTrue(names.contains("network.httpGet"));
        assertTrue(names.contains("package.install"));
        assertTrue(names.contains("market.install"));
        assertTrue(names.contains("socket.connect"));
        @SuppressWarnings("unchecked")
        Map<String, Object> installedPackage =
                (Map<String, Object>) ownerRuntime.scope().get("installedPackage");
        assertEquals("demo/hello/1.0.0", installedPackage.get("coordinate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> packageCheck =
                (Map<String, Object>) ownerRuntime.scope().get("packageCheck");
        assertEquals(true, packageCheck.get("valid"));
        assertEquals("application", packageCheck.get("kind"));
        assertEquals(List.of(), packageCheck.get("dependencies"));
        assertTrue(packageCheck.get("entrypoints") instanceof List<?> entrypoints
                && entrypoints.stream().anyMatch(value -> value instanceof Map<?, ?> entrypoint
                && "run".equals(entrypoint.get("name"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> launchedPackage =
                (Map<String, Object>) ownerRuntime.scope().get("launchedPackage");
        long launchedPid = ((Number) launchedPackage.get("pid")).longValue();
        assertTrue(launchedPid > 0);
        CilProcess launchedChild = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.processes()
                        .findByPid(launchedPid).orElseThrow());
        assertEquals(CilProcess.Status.READY, launchedChild.status());
        String exactImport = (String) installedPackage.get("sha256");
        assertTrue(launchedChild.continuation().packageBindings().containsKey(exactImport));
        boolean bindingPersisted = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED,
                transaction -> transaction.packages().findProcessBinding(
                        launchedChild.identity().processUid(), exactImport).isPresent());
        assertTrue(bindingPersisted);

        // process.kill must clear a durable wait state before changing a waiting process to a
        // terminal status. PostgreSQL rejects a terminal row that still owns wait_state data.
        String waitingSource = "util.sleep(5000)\n";
        var waitingProgram = new ProgramService(transactions).create(owner.userId(), waitingSource);
        CilProcess waitingProcess = new ProcessService(transactions).create(owner.userId(),
                waitingProgram, Optional.empty());
        FclContinuation waitingRuntime = new FclContinuation();
        waitingRuntime.waitFor("timer:" + UUID.randomUUID(), Map.of());
        var waitingBridge = new FclPersistenceBridge(new FclContinuationCodec());
        CilProcess claimedWaiting = waitingProcess.claim(
                waitingProcess.executionEpoch() + 1, Instant.now());
        assertEquals(com.follarce.domain.port.ProcessRepository.UpdateResult.UPDATED,
                transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                        transaction -> transaction.processes().update(claimedWaiting,
                                waitingProcess.stateVersion(), waitingProcess.executionEpoch())));
        var waitingContinuation = waitingBridge.persist(waitingProcess.identity().processUid(),
                waitingProgram, waitingProcess.continuation(), waitingRuntime);
        CilProcess durableWaiting = claimedWaiting.commitStatement(waitingContinuation,
                CilProcess.Status.WAITING_TIMER, claimedWaiting.stateVersion(),
                claimedWaiting.executionEpoch(), Instant.now());
        assertEquals(com.follarce.domain.port.ProcessRepository.UpdateResult.UPDATED,
                transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                        transaction -> transaction.processes().update(durableWaiting,
                                claimedWaiting.stateVersion(), claimedWaiting.executionEpoch())));

        String adminSource = """
                data = file.read("/private.txt", "%s")
                written = file.write("/private.txt", "changed", "%s")
                managed = file.createDir("/managed", "%s")
                createdFile = file.createFile("/managed/new.txt", "body", "%s")
                renamed = file.rename("/managed/new.txt", "renamed.txt", "%s")
                nodes = file.listdir("/", "%s")
                deleted = file.remove("/managed/renamed.txt", "%s")
                users = user.getListOfUsers()
                valid = user.validateUser("%s")
                removed = user.remove("%s")
                processes = process.getList()
                paused = process.pause(%s)
                continued = process.continue(%s)
                killed = process.kill(%s)
                finished = process.waitPID(%s)
                killedWaiting = process.kill(%s)
                finishedWaiting = process.waitPID(%s)
                created = user.create("%s", "created-password-123")
                createdAdmin = user.create("%s", "created-admin-pass-1", ["%s", "%s"])
                """.formatted(
                owner.userId(), owner.userId(), owner.userId(), owner.userId(),
                owner.userId(), owner.userId(), owner.userId(), removable.userId(),
                removable.userId(), ownerProcess.identity().pid(), ownerProcess.identity().pid(),
                ownerProcess.identity().pid(), ownerProcess.identity().pid(),
                waitingProcess.identity().pid(), waitingProcess.identity().pid(),
                "fcl-created-" + suffix, "fcl-created-admin-" + suffix,
                local.username(), "local-password-123");
        var adminProgram = new ProgramService(transactions).create(administrator.userId(),
                adminSource);
        CilProcess adminProcess = new ProcessService(transactions).create(administrator.userId(),
                adminProgram, Optional.empty());
        FclContinuation adminRuntime = run(transactions, adminProcess, adminProgram, adminSource);
        assertEquals("secret", adminRuntime.scope().get("data"));
        assertEquals(true, adminRuntime.scope().get("deleted"));
        assertEquals(true, adminRuntime.scope().get("valid"));
        assertEquals(true, adminRuntime.scope().get("paused"));
        assertEquals(true, adminRuntime.scope().get("continued"));
        assertEquals(true, adminRuntime.scope().get("killed"));
        assertEquals(true, adminRuntime.scope().get("killedWaiting"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes =
                (List<Map<String, Object>>) adminRuntime.scope().get("processes");
        assertTrue(processes.stream().anyMatch(item ->
                owner.userId().toString().equals(item.get("ownerId"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> finished = (Map<String, Object>) adminRuntime.scope().get("finished");
        assertEquals("TERMINATED", finished.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> finishedWaiting =
                (Map<String, Object>) adminRuntime.scope().get("finishedWaiting");
        assertEquals("TERMINATED", finishedWaiting.get("status"));
        assertEquals(true, adminRuntime.scope().get("removed"));
        boolean removableUserDeleted = transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(removable.userId()).isEmpty());
        assertTrue(removableUserDeleted);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) adminRuntime.scope().get("created");
        assertEquals("ACTIVE", created.get("status"));
        assertEquals("fcl-created-" + suffix, created.get("username"));
        UUID createdId = UUID.fromString((String) created.get("userId"));
        Set<Capability> createdCapabilities = transactions.inUserTransaction(createdId,
                Isolation.READ_COMMITTED,
                transaction -> transaction.auth().capabilities(createdId));
        assertTrue(createdCapabilities.contains(Capability.PROCESS_CREATE));
        assertFalse(createdCapabilities.contains(Capability.SYSTEM_ADMIN));
        @SuppressWarnings("unchecked")
        Map<String, Object> createdAdmin =
                (Map<String, Object>) adminRuntime.scope().get("createdAdmin");
        assertEquals("ACTIVE", createdAdmin.get("status"));
        UUID createdAdminId = UUID.fromString((String) createdAdmin.get("userId"));
        assertTrue(transactions.inUserTransaction(createdAdminId,
                Isolation.READ_COMMITTED,
                transaction -> transaction.auth().capabilities(createdAdminId))
                .contains(Capability.SYSTEM_ADMIN));
        AdminVfsService administratorVfs = new AdminVfsService(transactions, clock);
        assertEquals("changed", new String(administratorVfs
                .readFile(administrator.userId(), owner.userId(), privateFile.nodeId())
                .content().bytes(), StandardCharsets.UTF_8));
        assertTrue(administratorVfs.listNodes(administrator.userId(), owner.userId()).stream()
                .anyMatch(node -> node.name().equals("managed")));

        String localSource = """
                mountedContent = file.read("/Users/%s/private.txt")
                mountedWrite = file.write("/Users/%s/from-local.txt", "mounted")
                homes = file.listdir("/Users")
                """.formatted(owner.username(), owner.username());
        var localProgram = new ProgramService(transactions).create(local.userId(), localSource);
        CilProcess localProcess = new ProcessService(transactions).create(local.userId(),
                localProgram, Optional.empty());
        FclContinuation localRuntime = run(transactions, localProcess, localProgram, localSource);
        assertEquals("changed", localRuntime.scope().get("mountedContent"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> homes =
                (List<Map<String, Object>>) localRuntime.scope().get("homes");
        assertTrue(homes.stream().anyMatch(home -> owner.username().equals(home.get("name"))));
        VfsNode mountedFile = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.vfs()
                        .findChild(owner.userId(), Optional.of(ownerRoot.nodeId()),
                                "from-local.txt").orElseThrow());
        assertEquals("mounted", new String(administratorVfs
                .readFile(local.userId(), owner.userId(), mountedFile.nodeId())
                .content().bytes(), StandardCharsets.UTF_8));

        // A symbolic-link cycle must fail the reading process instead of looping forever.
        assertFailsWithDurableError(transactions, owner.userId(), """
                file.write("/cycle-source.txt", "x")
                file.link("/cycle-a.txt", "/cycle-b.txt")
                file.link("/cycle-b.txt", "/cycle-a.txt")
                value = file.read("/cycle-a.txt")
                """);
        // A chain longer than the 16-link limit must also fail the reading process.
        StringBuilder deep = new StringBuilder("file.write(\"/deep-0.txt\", \"deep\")\n");
        for (int i = 1; i <= 17; i++) {
            deep.append("file.link(\"/deep-").append(i)
                    .append(".txt\", \"/deep-").append(i - 1).append(".txt\")\n");
        }
        deep.append("value = file.read(\"/deep-17.txt\")\n");
        assertFailsWithDurableError(transactions, owner.userId(), deep.toString());
        // An ordinary user may self-register a normal account through FCL, but creating
        // an administrator requires valid credentials of a current SYSTEM_ADMIN holder.
        String selfUsername = "fcl-self-" + suffix;
        String selfSource = "created = user.create(\"%s\", \"self-password-1\")"
                .formatted(selfUsername);
        CilProcess selfProcess = process(transactions, owner.userId(), selfSource);
        var selfProgram = program(transactions, selfProcess);
        FclContinuation selfRuntime = run(transactions, selfProcess, selfProgram, selfSource);
        @SuppressWarnings("unchecked")
        Map<String, Object> selfCreated =
                (Map<String, Object>) selfRuntime.scope().get("created");
        assertEquals("ACTIVE", selfCreated.get("status"));
        assertTrue(transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser(selfUsername)).isPresent());
        // Wrong administrator password (an ordinary user's own known password).
        assertFailsWithDurableError(transactions, owner.userId(), """
                user.create("fcl-denied-%s", "denied-password-1", ["%s", "%s"])
                """.formatted(suffix, owner.username(), "owner-password-123"));
        // An administrator whose password is valid but SYSTEM_ADMIN is revoked must
        // not be able to create another administrator either.
        transactions.inTransaction(Isolation.READ_COMMITTED, transaction -> {
            transaction.auth().replaceCapabilities(local.userId(),
                    com.follarce.terminal.TerminalAccessService.USER_CAPABILITIES);
            return null;
        });
        assertFailsWithDurableError(transactions, owner.userId(), """
                user.create("fcl-denied2-%s", "denied-password-1", ["%s", "%s"])
                """.formatted(suffix, local.username(), "local-password-123"));
        assertTrue(transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser("fcl-denied-" + suffix)).isEmpty());
        assertTrue(transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.auth().findUser("fcl-denied2-" + suffix)).isEmpty());
    }

    private static void assertFailsWithDurableError(JdbcTransactionExecutor transactions,
                                                    UUID ownerId, String source) {
        CilProcess failedProcess = process(transactions, ownerId, source);
        FclContinuation failedContinuation = new FclContinuation();
        var compiled = new FclCompiler().compile(source);
        com.follarce.fcl.FclStepResult last = null;
        int steps = 0;
        while (!failedContinuation.halted() && steps++ < 100) {
            last = step(transactions, failedProcess,
                    program(transactions, failedProcess), compiled, failedContinuation);
        }
        assertTrue(failedContinuation.halted(), source);
        com.follarce.fcl.FclStepResult terminal = last;
        assertFalse(terminal == null || terminal.status() != com.follarce.fcl.FclStepResult.Status.FAILED,
                () -> String.valueOf(terminal == null ? "no steps" : terminal.value()));
        assertTrue(failedContinuation.failed(), source);
    }

    static void executeSwapPoolAcrossProcesses(JdbcTransactionExecutor transactions)
            throws Exception {
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = new AuthService(transactions, clock).create("swap-owner-" + suffix,
                "owner-password-123".toCharArray(), Set.of(Capability.PROCESS_CREATE));

        String producerSource = """
                created = swapPool.create("bus")
                added = swapPool.add("message:initial", "bus", "type:sync")
                """;
        CilProcess producer = process(transactions, owner.userId(), producerSource);
        var producerProgram = program(transactions, producer);
        FclContinuation producerRuntime = run(transactions, producer, producerProgram,
                producerSource);
        assertEquals(true, producerRuntime.scope().get("created"));
        assertEquals(true, producerRuntime.scope().get("added"));

        String consumerSource = """
                first = swapPool.get("bus", "message")
                second = swapPool.get("bus", "message")
                plainAdded = swapPool.add("plain:hello", "bus")
                firstPlain = swapPool.get("bus", "plain")
                secondPlain = swapPool.get("bus", "plain")
                reAdded = swapPool.add("plain:hello", "bus")
                """;
        CilProcess consumer = process(transactions, owner.userId(), consumerSource);
        var consumerProgram = program(transactions, consumer);
        FclContinuation consumerRuntime = run(transactions, consumer, consumerProgram,
                consumerSource);
        assertEquals("initial", consumerRuntime.scope().get("first"));
        assertEquals(null, consumerRuntime.scope().get("second"));
        assertEquals(true, consumerRuntime.scope().get("plainAdded"));
        assertEquals("hello", consumerRuntime.scope().get("firstPlain"));
        assertEquals("hello", consumerRuntime.scope().get("secondPlain"));
        assertEquals(false, consumerRuntime.scope().get("reAdded"));

        String lockerSource = """
                lock = swapPool.lock("bus", "message", 30000)
                token = lock["fencingToken"]
                updated = swapPool.update("bus", "message", "approved", token)
                released = swapPool.unlock("bus", "message", token)
                """;
        CilProcess locker = process(transactions, owner.userId(), lockerSource);
        var lockerProgram = program(transactions, locker);
        var lockerCompiled = new FclCompiler().compile(lockerSource);
        FclContinuation lockerRuntime = new FclContinuation();
        assertEquals(FclStepResult.Status.ADVANCED,
                step(transactions, locker, lockerProgram, lockerCompiled, lockerRuntime).status());
        assertEquals(FclStepResult.Status.ADVANCED,
                step(transactions, locker, lockerProgram, lockerCompiled, lockerRuntime).status());
        assertTrue(lockerRuntime.scope().get("lock") instanceof Map<?, ?>);

        String contenderSource = "changed = swapPool.update(\"bus\", \"message\", \"intruder\")\n";
        CilProcess contender = process(transactions, owner.userId(), contenderSource);
        var contenderProgram = program(transactions, contender);
        FclContinuation contenderRuntime = run(transactions, contender, contenderProgram, contenderSource);
        assertEquals(false, contenderRuntime.scope().get("changed"));

        // A scheduler claim advances the execution epoch even though this remains the same
        // logical process. The durable lock and fencing token must survive that ordinary
        // reschedule; only a different process or a different token loses ownership.
        CilProcess rescheduledLocker = new CilProcess(locker.identity(), locker.ownerId(),
                locker.status(), locker.stateVersion(), locker.executionEpoch() + 1,
                locker.continuation(), locker.parentProcessUid(), locker.createdAt(),
                locker.updatedAt());
        assertEquals(com.follarce.domain.port.ProcessRepository.UpdateResult.UPDATED,
                transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                        transaction -> transaction.processes().update(rescheduledLocker,
                                locker.stateVersion(), locker.executionEpoch())));
        while (!lockerRuntime.halted()) {
            FclStepResult result = step(transactions, rescheduledLocker, lockerProgram,
                    lockerCompiled, lockerRuntime);
            assertFalse(result.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
        }
        assertEquals(true, lockerRuntime.scope().get("updated"));
        assertEquals(true, lockerRuntime.scope().get("released"));

        assertExactlyOneConcurrentConsumerGetsTheSyncValue(transactions, owner.userId(),
                rescheduledLocker, "bus", "message", "approved");
        assertEveryConcurrentConsumerGetsThePlainValue(transactions, owner.userId(),
                "bus", "plain-race", "hello");
    }

    private static void assertEveryConcurrentConsumerGetsThePlainValue(
            JdbcTransactionExecutor transactions, UUID ownerId, String pool, String variable,
            String expected) throws Exception {
        FclContinuationCodec codec = new FclContinuationCodec();
        Instant now = Instant.now();
        assertEquals(true, transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.ipc().addSwapValue(ownerId, pool, variable,
                        new com.follarce.domain.process.Continuation.PersistedValue("string",
                                codec.valueToJson(expected)), "ALWAYS", Optional.empty(), now)));
        assertEveryConcurrentConsumerGets(transactions, ownerId, pool, variable, expected, codec);
    }

    private static void assertExactlyOneConcurrentConsumerGetsTheSyncValue(
            JdbcTransactionExecutor transactions, UUID ownerId, CilProcess process, String pool,
            String variable, String expected) throws Exception {
        FclContinuationCodec codec = new FclContinuationCodec();
        Instant now = Instant.now();
        assertEquals(true, transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.ipc().updateSwapValue(ownerId, pool, variable,
                        new com.follarce.domain.process.Continuation.PersistedValue("string",
                                codec.valueToJson(expected)), process.identity().processUid(),
                        process.executionEpoch(), Optional.empty(), now)));
        assertExactlyOneConcurrentConsumerGets(transactions, ownerId, pool, variable, expected,
                codec);
    }

    private static void assertExactlyOneConcurrentConsumerGets(
            JdbcTransactionExecutor transactions, UUID ownerId, String pool, String variable,
            String expected, FclContinuationCodec codec) throws Exception {

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Optional<com.follarce.domain.process.Continuation.PersistedValue>> first =
                    workers.submit(() -> consumeAfterStart(transactions, ownerId, pool, variable,
                            ready, start));
            Future<Optional<com.follarce.domain.process.Continuation.PersistedValue>> second =
                    workers.submit(() -> consumeAfterStart(transactions, ownerId, pool, variable,
                            ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Optional<com.follarce.domain.process.Continuation.PersistedValue> firstValue =
                    first.get(10, TimeUnit.SECONDS);
            Optional<com.follarce.domain.process.Continuation.PersistedValue> secondValue =
                    second.get(10, TimeUnit.SECONDS);
            assertEquals(1, (firstValue.isPresent() ? 1 : 0) + (secondValue.isPresent() ? 1 : 0));
            Optional<com.follarce.domain.process.Continuation.PersistedValue> delivered =
                    firstValue.isPresent() ? firstValue : secondValue;
            assertEquals(expected, codec.valueFromJson(delivered.orElseThrow().canonicalPayload()));
        }
    }

    private static void assertEveryConcurrentConsumerGets(
            JdbcTransactionExecutor transactions, UUID ownerId, String pool, String variable,
            String expected, FclContinuationCodec codec) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Optional<com.follarce.domain.process.Continuation.PersistedValue>> first =
                    workers.submit(() -> consumeAfterStart(transactions, ownerId, pool, variable,
                            ready, start));
            Future<Optional<com.follarce.domain.process.Continuation.PersistedValue>> second =
                    workers.submit(() -> consumeAfterStart(transactions, ownerId, pool, variable,
                            ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Optional<com.follarce.domain.process.Continuation.PersistedValue> firstValue =
                    first.get(10, TimeUnit.SECONDS);
            Optional<com.follarce.domain.process.Continuation.PersistedValue> secondValue =
                    second.get(10, TimeUnit.SECONDS);
            assertTrue(firstValue.isPresent());
            assertTrue(secondValue.isPresent());
            assertEquals(expected, codec.valueFromJson(firstValue.orElseThrow().canonicalPayload()));
            assertEquals(expected, codec.valueFromJson(secondValue.orElseThrow().canonicalPayload()));
        }
    }

    private static Optional<com.follarce.domain.process.Continuation.PersistedValue>
            consumeAfterStart(JdbcTransactionExecutor transactions, UUID ownerId, String pool,
                              String variable, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent start timed out");
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                transaction -> transaction.ipc().consumeSwapValue(ownerId, pool, variable,
                        Instant.now()));
    }

    private static CilProcess process(JdbcTransactionExecutor transactions, UUID ownerId,
                                      String source) {
        return new ProcessService(transactions).create(ownerId,
                new ProgramService(transactions).create(ownerId, source), Optional.empty());
    }

    private static com.follarce.domain.program.Program program(
            JdbcTransactionExecutor transactions, CilProcess process) {
        return transactions.inUserTransaction(process.ownerId(), Isolation.READ_COMMITTED,
                transaction -> transaction.programs().findById(process.continuation().programId())
                        .orElseThrow());
    }

    private static FclStepResult step(JdbcTransactionExecutor transactions, CilProcess process,
                                      com.follarce.domain.program.Program program,
                                      com.follarce.fcl.FclProgram compiled,
                                      FclContinuation continuation) {
        return transactions.inUserTransaction(process.ownerId(), Isolation.READ_COMMITTED,
                transaction -> new FclRuntime(FclRuntimeFunctions.create(transaction, process,
                        program, continuation, Clock.systemUTC().instant()))
                        .executeOne(compiled, continuation));
    }

    private static FclContinuation run(JdbcTransactionExecutor transactions, CilProcess process,
                                       com.follarce.domain.program.Program program, String source) {
        FclContinuation continuation = new FclContinuation();
        var compiled = new FclCompiler().compile(source);
        int steps = 0;
        while (!continuation.halted() && steps++ < 100) {
            FclContinuation current = continuation;
            FclStepResult result = transactions.inUserTransaction(process.ownerId(),
                    Isolation.READ_COMMITTED, transaction -> new FclRuntime(
                            FclRuntimeFunctions.create(transaction, process, program,
                                    current, Clock.systemUTC().instant()))
                            .executeOne(compiled, current));
            assertFalse(result.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
        }
        assertTrue(continuation.halted());
        return continuation;
    }

    /**
     * Runs a real download through the durable effect pipeline: the scheduler slices the
     * FCL process, the effect worker performs the bounded HTTP range exchange, and the
     * resumed slice assembles the VFS object before the process terminates.
     */
    static void executeNetworkDownloads(JdbcTransactionExecutor transactions) throws Exception {
        System.setProperty("cilexec.networkAllowPrivateHosts", "127.0.0.1,localhost");
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = new AuthService(transactions, clock).create("fcl-net-" + suffix,
                "owner-password-123".toCharArray(), Set.of(Capability.VFS_READ,
                        Capability.VFS_WRITE, Capability.PROCESS_CREATE,
                        Capability.PROCESS_CONTROL_OWN, Capability.EFFECT_REQUEST));
        byte[] file = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

        com.sun.net.httpserver.HttpServer empty = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        empty.createContext("/empty.db", exchange -> {
            exchange.getResponseHeaders().set("Content-Range", "bytes */0");
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
        });
        empty.start();

        AtomicInteger rangeRequests = new AtomicInteger();
        com.sun.net.httpserver.HttpServer large = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        large.createContext("/large.db", exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"stable-etag\"");
            String range = exchange.getRequestHeaders().getFirst("Range");
            int from = Integer.parseInt(range.substring("bytes=".length(), range.indexOf('-')));
            String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
            int requestNumber = rangeRequests.getAndIncrement();
            if (requestNumber == 0) {
                assertNull(ifRange, "the first range probe has no validator yet");
            } else {
                assertEquals("\"stable-etag\"", ifRange,
                        "the resuming request must carry the If-Range validator");
            }
            int to = Math.min(from + 7, file.length - 1);
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + from + "-" + to + "/" + file.length);
            exchange.sendResponseHeaders(206, to - from + 1);
            exchange.getResponseBody().write(file, from, to - from + 1);
            exchange.close();
        });
        large.start();

        com.sun.net.httpserver.HttpServer unprotected = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        unprotected.createContext("/unprotected.db", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int from = Integer.parseInt(range.substring("bytes=".length(), range.indexOf('-')));
            int to = Math.min(from + 2, file.length - 1);
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + from + "-" + to + "/" + file.length);
            exchange.sendResponseHeaders(206, to - from + 1);
            exchange.getResponseBody().write(file, from, to - from + 1);
            exchange.close();
        });
        unprotected.start();

        long controlKey = 0x51A7C0DE5L;
        long proofKey = controlKey ^ 0x9e3779b97f4a7c15L;
        Connection controlConnection = transactions.dataSource().getConnection();
        try (PreparedStatement lock = controlConnection.prepareStatement(
                "SELECT pg_try_advisory_lock(?)")) {
            lock.setLong(1, controlKey);
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("control advisory lock unavailable");
                }
            }
        }
        try (PreparedStatement proof = controlConnection.prepareStatement(
                "SELECT pg_try_advisory_lock(?)")) {
            proof.setLong(1, proofKey);
            try (ResultSet result = proof.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("control proof lock unavailable");
                }
            }
        }
        com.follarce.persistence.postgres.connection.ControlLock.ControlIdentity control;
        try (PreparedStatement identity = controlConnection.prepareStatement(
                "SELECT pid, backend_start FROM pg_catalog.pg_stat_activity "
                        + "WHERE pid = pg_backend_pid()");
             ResultSet identityRow = identity.executeQuery()) {
            if (!identityRow.next()) {
                throw new IllegalStateException("control backend identity unavailable");
            }
            control = new com.follarce.persistence.postgres.connection.ControlLock.ControlIdentity(
                    identityRow.getInt("pid"),
                    identityRow.getTimestamp("backend_start").toInstant(), proofKey);
        }
        com.follarce.persistence.postgres.repository.RuntimeMetadataStore metadata =
                new com.follarce.persistence.postgres.repository.RuntimeMetadataStore(
                        transactions.dataSource());
        com.follarce.persistence.postgres.repository.RuntimeMetadataStore.BootIdentity boot =
                metadata.beginBoot("cilexec-test-shared", controlKey, "test", 1,
                        com.follarce.fcl.FclContinuation.FORMAT_VERSION, control);
        metadata.markReady(boot);
        UUID bootId = boot.bootId();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        ProcessStatementExecutor executor = new ProcessStatementExecutor(transactions);
        EffectWorkerService effects = new EffectWorkerService(transactions, transactions, bootId,
                new EffectHandlerRegistry(BuiltinEffectHandlers.defaults()), 1,
                Duration.ofMillis(50), clock, fatal::set);
        SchedulerService scheduler = new SchedulerService(transactions, executor, bootId, 2,
                Duration.ofSeconds(30), Duration.ofMillis(50), fatal::set);
        scheduler.start();
        effects.start();
        try {
            Map<String, Object> emptyResult = resultOf(awaitDownload(transactions, scheduler,
                    effects, owner, "http://127.0.0.1:" + empty.getAddress().getPort()
                            + "/empty.db", "/empty.db", CilProcess.Status.TERMINATED, fatal));
            assertEquals(0L, ((Number) emptyResult.get("bytes")).longValue());
            assertEquals(206L, ((Number) emptyResult.get("status")).longValue());
            UUID emptyNode = UUID.fromString(String.valueOf(emptyResult.get("nodeId")));
            assertEquals(0L, new VfsService(transactions, clock)
                    .readFile(owner.userId(), emptyNode).content().size());

            Map<String, Object> largeResult = resultOf(awaitDownload(transactions, scheduler,
                    effects, owner, "http://127.0.0.1:" + large.getAddress().getPort()
                            + "/large.db", "/large.db", CilProcess.Status.TERMINATED, fatal));
            assertEquals(file.length, ((Number) largeResult.get("bytes")).longValue());
            assertTrue(rangeRequests.get() >= 2, "multi-chunk downloads need more than one range");
            UUID largeNode = UUID.fromString(String.valueOf(largeResult.get("nodeId")));
            byte[] storedLarge = readLogicalContent(transactions, owner, largeNode);
            assertArrayEquals(file, storedLarge);

            CilProcess unprotectedProcess = awaitDownload(transactions, scheduler, effects,
                    owner, "http://127.0.0.1:" + unprotected.getAddress().getPort()
                            + "/unprotected.db", "/unprotected.db", CilProcess.Status.FAILED,
                    fatal);
            FclContinuation failed = new FclPersistenceBridge(new FclContinuationCodec())
                    .restore(unprotectedProcess.continuation());
            assertTrue(failed.failed());
            assertFalse(failed.exceptionStack().isEmpty());
            String message = failed.exceptionStack().stream()
                    .map(FclContinuation.ExceptionFrame::message)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(message.contains("cannot resume without a validator"), message);
        } finally {
            scheduler.close();
            effects.close();
            controlConnection.close();
            empty.stop(0);
            large.stop(0);
            unprotected.stop(0);
        }
        assertNull(fatal.get());
    }

    /**
     * A terminal REPL root forks children. The children must not inherit the terminal
     * lifecycle: a child reaching the end of its bytecode (natural end) or calling
     * {@code util.exit()} must reach TERMINATED, {@code process.waitPID} must return, and
     * {@code process.kill}/{@code process.removeFinished} must be able to clean the child up — while
     * the terminal root itself keeps pausing between submissions.
     */
    static void executeForkChildLifecycle(JdbcTransactionExecutor transactions) throws Exception {
        Clock clock = Clock.systemUTC();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount owner = new AuthService(transactions, clock).create("fork-owner-" + suffix,
                "owner-password-123".toCharArray(), Set.of(Capability.PROCESS_CREATE,
                        Capability.PROCESS_CONTROL_OWN, Capability.TERMINAL_ATTACH,
                        Capability.VFS_READ, Capability.VFS_WRITE, Capability.SYSTEM_ADMIN));
        UUID sessionId = UUID.randomUUID();
        Instant now = clock.instant();
        transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED, transaction -> {
            transaction.terminal().saveSession(new TerminalSession(sessionId, owner.userId(),
                    TerminalSession.Status.OPEN, 1, now, now, Optional.empty()));
            return null;
        });

        long controlKey = 0x51A7C0DE5L;
        long proofKey = controlKey ^ 0x9e3779b97f4a7c15L;
        Connection controlConnection = transactions.dataSource().getConnection();
        try (PreparedStatement lock = controlConnection.prepareStatement(
                "SELECT pg_try_advisory_lock(?)")) {
            lock.setLong(1, controlKey);
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("control advisory lock unavailable");
                }
            }
        }
        try (PreparedStatement proof = controlConnection.prepareStatement(
                "SELECT pg_try_advisory_lock(?)")) {
            proof.setLong(1, proofKey);
            try (ResultSet result = proof.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("control proof lock unavailable");
                }
            }
        }
        com.follarce.persistence.postgres.connection.ControlLock.ControlIdentity control;
        try (PreparedStatement identity = controlConnection.prepareStatement(
                "SELECT pid, backend_start FROM pg_catalog.pg_stat_activity "
                        + "WHERE pid = pg_backend_pid()");
             ResultSet identityRow = identity.executeQuery()) {
            if (!identityRow.next()) {
                throw new IllegalStateException("control backend identity unavailable");
            }
            control = new com.follarce.persistence.postgres.connection.ControlLock.ControlIdentity(
                    identityRow.getInt("pid"),
                    identityRow.getTimestamp("backend_start").toInstant(), proofKey);
        }
        com.follarce.persistence.postgres.repository.RuntimeMetadataStore metadata =
                new com.follarce.persistence.postgres.repository.RuntimeMetadataStore(
                        transactions.dataSource());
        com.follarce.persistence.postgres.repository.RuntimeMetadataStore.BootIdentity boot =
                metadata.beginBoot("cilexec-test-shared", controlKey, "test", 1,
                        com.follarce.fcl.FclContinuation.FORMAT_VERSION, control);
        metadata.markReady(boot);
        UUID bootId = boot.bootId();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        ProcessStatementExecutor executor = new ProcessStatementExecutor(transactions);
        SchedulerService scheduler = new SchedulerService(transactions, executor, bootId, 2,
                Duration.ofSeconds(30), Duration.ofMillis(50), fatal::set);
        TimerWorkerService timers = new TimerWorkerService(new TimerService(transactions,
                transactions, clock), 16, Duration.ofMillis(25), fatal::set);
        scheduler.start();
        timers.start();
        try {
            TerminalReplService repl = new TerminalReplService(transactions, scheduler::wake);
            TerminalReplService.Submission first = repl.submit(owner.userId(), sessionId,
                    "child = process.fork()\n"
                            + "if child == 0 { util.exit(\"child done\") } else {"
                            + " waited = process.waitPID(child) }\n");
            UUID rootUid = first.process().identity().processUid();
            FclContinuation root = awaitForkPaused(transactions, scheduler, owner, rootUid,
                    "waited", fatal);
            Map<String, Object> waited = forkMap(root, "waited");
            assertEquals("TERMINATED", waited.get("status"),
                    "waitPID must report the exited fork child as TERMINATED");
            long childPid = ((Number) waited.get("pid")).longValue();
            CilProcess child = transactions.inUserTransaction(owner.userId(),
                    Isolation.READ_COMMITTED, transaction -> transaction.processes()
                            .findByPid(childPid).orElseThrow());
            assertEquals(CilProcess.Status.TERMINATED, child.status(),
                    "a fork child calling util.exit must terminate");
            FclContinuation childRuntime = new FclPersistenceBridge(new FclContinuationCodec())
                    .restore(child.continuation());
            assertFalse(childRuntime.scope().contains(
                            TerminalReplService.TERMINAL_PROCESS_SCOPE_KEY),
                    "fork child must not inherit the terminal lifecycle marker");
            assertFalse(childRuntime.scope().contains(
                            TerminalReplService.TERMINAL_SESSION_SCOPE_KEY),
                    "fork child must not inherit the terminal session marker");
            assertTrue(root.scope().contains(TerminalReplService.TERMINAL_PROCESS_SCOPE_KEY),
                    "terminal root keeps its lifecycle marker");

            TerminalReplService.Submission second = repl.submit(owner.userId(), sessionId,
                    "child2 = process.fork()\n"
                            + "if child2 == 0 { util.sleep(2500) } else {"
                            + " waited2 = process.waitPID(child2) }\n");
            FclContinuation root2 = awaitForkPaused(transactions, scheduler, owner, rootUid,
                    "waited2", fatal);
            Map<String, Object> waited2 = forkMap(root2, "waited2");
            assertEquals("TERMINATED", waited2.get("status"),
                    "waitPID must report the naturally ended fork child as TERMINATED");
            CilProcess naturalChild = transactions.inUserTransaction(owner.userId(),
                    Isolation.READ_COMMITTED, transaction -> transaction.processes()
                            .findByPid(((Number) waited2.get("pid")).longValue())
                            .orElseThrow());
            assertEquals(CilProcess.Status.TERMINATED, naturalChild.status(),
                    "a fork child reaching the end of its bytecode must terminate");

            TerminalReplService.Submission third = repl.submit(owner.userId(), sessionId,
                    "child3 = process.fork()\n"
                            + "if child3 == 0 { util.sleep(60000) } else {"
                            + " killed = process.kill(child3) }\n");
            FclContinuation root3 = awaitForkPaused(transactions, scheduler, owner, rootUid,
                    "killed", fatal);
            assertEquals(true, root3.scope().get("killed"),
                    "killing a sleeping fork child must succeed");

            TerminalReplService.Submission fourth = repl.submit(owner.userId(), sessionId,
                    "removed = process.removeFinished(child)\n");
            FclContinuation root4 = awaitForkPaused(transactions, scheduler, owner, rootUid,
                    "removed", fatal);
            assertEquals(true, root4.scope().get("removed"),
                    "a terminated fork child must be removable");
            boolean deletedRow = transactions.inUserTransaction(owner.userId(),
                            Isolation.READ_COMMITTED, transaction -> transaction.processes()
                                    .findByPid(childPid).isEmpty());
            assertTrue(deletedRow,
                    "process.removeFinished must delete the terminated fork child row");
            assertEquals(CilProcess.Status.PAUSED,
                    transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                            transaction -> transaction.processes().findByUid(rootUid)
                                    .orElseThrow().status()),
                    "terminal root must remain PAUSED after its submissions");
        } finally {
            scheduler.close();
            timers.close();
            controlConnection.close();
        }
        assertNull(fatal.get());
    }

    private static FclContinuation awaitForkPaused(JdbcTransactionExecutor transactions,
                                                   SchedulerService scheduler,
                                                   UserAccount owner, UUID processUid,
                                                   String variable,
                                                   AtomicReference<Throwable> fatal)
            throws Exception {
        FclPersistenceBridge bridge = new FclPersistenceBridge(new FclContinuationCodec());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            scheduler.wake();
            Thread.sleep(25);
            CilProcess current = transactions.inUserTransaction(owner.userId(),
                    Isolation.READ_COMMITTED, transaction -> transaction.processes()
                            .findByUid(processUid).orElseThrow());
            FclContinuation restored = bridge.restore(current.continuation());
            if (current.status() == CilProcess.Status.PAUSED
                    && restored.scope().contains(variable)) {
                return restored;
            }
        }
        CilProcess stuck = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.processes().findByUid(processUid).orElseThrow());
        throw new AssertionError("terminal root never paused with " + variable + ": uid="
                + processUid + " status=" + stuck.status()
                + (fatal.get() != null ? " FATAL=" + fatal.get() : ""));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> forkMap(FclContinuation restored, String variable) {
        return (Map<String, Object>) restored.scope().get(variable);
    }

    private static CilProcess awaitDownload(JdbcTransactionExecutor transactions,
                                            SchedulerService scheduler,
                                            EffectWorkerService effects,
                                            UserAccount owner, String url, String path,
                                            CilProcess.Status expected,
                                            AtomicReference<Throwable> fatal)
        throws Exception {
        CilProcess created = process(transactions, owner.userId(),
                "result = network.download(\"" + url + "\", \"" + path + "\")\n");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        CilProcess.Status previous = null;
        while (System.nanoTime() < deadline) {
            scheduler.wake();
            effects.wake();
            Thread.sleep(25);
            CilProcess current = transactions.inUserTransaction(owner.userId(),
                    Isolation.READ_COMMITTED, transaction -> transaction.processes()
                            .findByUid(created.identity().processUid()).orElseThrow());
            if (previous != current.status()) {
                previous = current.status();
            }
            if (current.status() == CilProcess.Status.TERMINATED
                    || current.status() == CilProcess.Status.FAILED) {
                FclContinuation inspected = new FclPersistenceBridge(new FclContinuationCodec())
                        .restore(current.continuation());
                assertEquals(expected, current.status());
                return current;
            }
        }
        CilProcess stuck = transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> transaction.processes().findByUid(created.identity().processUid())
                        .orElseThrow());
        throw new AssertionError("process never reached a terminal state: uid="
                + created.identity().processUid() + " status=" + stuck.status()
                + " epoch=" + stuck.executionEpoch() + " stateVersion=" + stuck.stateVersion()
                + " wait=" + stuck.continuation().waitState()
                + (fatal.get() != null ? " FATAL=" + fatal.get() : ""));
    }

    private static Map<String, Object> resultOf(CilProcess terminal) {
        FclContinuation restored = new FclPersistenceBridge(new FclContinuationCodec())
                .restore(terminal.continuation());
        if (!restored.scope().contains("result")) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) restored.scope().get("result");
        return result;
    }

    private static byte[] readLogicalContent(JdbcTransactionExecutor transactions,
                                             UserAccount owner, UUID nodeId) {
        return transactions.inUserTransaction(owner.userId(), Isolation.READ_COMMITTED,
                transaction -> {
                    com.follarce.domain.vfs.ObjectHash hash = transaction.vfs()
                            .findNode(nodeId).orElseThrow().currentObjectHash().orElseThrow();
                    long size = transaction.vfs().logicalObjectSize(hash);
                    java.io.ByteArrayOutputStream content =
                            new java.io.ByteArrayOutputStream((int) size);
                    long offset = 0;
                    while (offset < size) {
                        byte[] part = transaction.vfs().readObjectRange(hash, offset,
                                (int) Math.min(4 * 1024 * 1024, size - offset));
                        if (part.length == 0) {
                            throw new IllegalStateException("logical content ended early");
                        }
                        content.writeBytes(part);
                        offset += part.length;
                    }
                    return content.toByteArray();
                });
    }

    private static JdbcTransactionExecutor transactions() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getProperty("cilexec.external.jdbc"));
        source.setUser(System.getProperty("cilexec.external.user", "postgres"));
        source.setPassword(System.getProperty("cilexec.external.password"));
        return new JdbcTransactionExecutor(source);
    }
}
