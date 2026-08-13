package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.vfs.AdminVfsService;
import com.follarce.vfs.VfsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                  "languageVersion":"fcl-1",
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
                link = file.link("/note-link.txt", "/note.txt")
                linkedContent = file.read("/note-link.txt")
                chain = file.link("/note-chain.txt", "/note-link.txt")
                chainedContent = file.read("/note-chain.txt")
                removedLink = file.removeFile("/note-link.txt")
                removedChain = file.removeFile("/note-chain.txt")
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
                deleted = file.removeFile("/managed/renamed.txt", "%s")
                users = user.getListOfUsers()
                valid = user.validateUser("%s")
                removed = user.removeUser("%s")
                processes = process.getList()
                paused = process.pause(%s)
                continued = process.continue(%s)
                killed = process.kill(%s)
                finished = process.waitPID(%s)
                killedWaiting = process.kill(%s)
                finishedWaiting = process.waitPID(%s)
                """.formatted(
                owner.userId(), owner.userId(), owner.userId(), owner.userId(),
                owner.userId(), owner.userId(), owner.userId(), removable.userId(),
                removable.userId(), ownerProcess.identity().pid(), ownerProcess.identity().pid(),
                ownerProcess.identity().pid(), ownerProcess.identity().pid(),
                waitingProcess.identity().pid(), waitingProcess.identity().pid());
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
        @SuppressWarnings("unchecked")
        Map<String, Object> removed = (Map<String, Object>) adminRuntime.scope().get("removed");
        assertEquals("DISABLED", removed.get("status"));
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
                """;
        CilProcess consumer = process(transactions, owner.userId(), consumerSource);
        var consumerProgram = program(transactions, consumer);
        FclContinuation consumerRuntime = run(transactions, consumer, consumerProgram,
                consumerSource);
        assertEquals("initial", consumerRuntime.scope().get("first"));
        assertEquals(null, consumerRuntime.scope().get("second"));

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

    private static JdbcTransactionExecutor transactions() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getProperty("cilexec.external.jdbc"));
        source.setUser(System.getProperty("cilexec.external.user", "postgres"));
        source.setPassword(System.getProperty("cilexec.external.password"));
        return new JdbcTransactionExecutor(source);
    }
}
