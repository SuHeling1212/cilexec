package com.follarce.application;

import com.follarce.auth.AuthService;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "cilexec.external.jdbc", matches = ".+")
class FclSystemFunctionsExternalIT {
    @Test
    void executesVfsSwapProcessAndAdministratorFunctionsFromFcl() {
        execute(transactions());
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
        UserAccount removable = auth.create("fcl-removable-" + suffix,
                "removable-password-123".toCharArray(), Set.of(Capability.VFS_READ));
        VfsService vfs = new VfsService(transactions, clock);
        VfsNode ownerRoot = vfs.createDirectory(owner.userId(), Optional.empty(), "/", Set.of());
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
                swapPool.create("shared")
                swapPool.add("message:ready", "shared", "type:sync")
                received = swapPool.get("shared", "message")
                pid = process.getPID()
                ownProcesses = process.getList()
                functions = system.ls()
                builtPackage = package.build("/pkg/package.json", "/hello.db")
                installedPackage = package.install("/hello.db", "hello")
                packageCheck = package.verify("demo/hello/1.0.0")
                packageEnvironments = package.environments()
                launchedPackage = package.run("hello")
                """;
        var ownerProgram = new ProgramService(transactions).create(owner.userId(), source);
        CilProcess ownerProcess = new ProcessService(transactions).create(owner.userId(),
                ownerProgram, Optional.empty());
        FclContinuation ownerRuntime = run(transactions, ownerProcess, ownerProgram, source);
        assertEquals("hello", ownerRuntime.scope().get("content"));
        assertEquals("ready", ownerRuntime.scope().get("received"));
        assertEquals(ownerProcess.identity().pid(), ownerRuntime.scope().get("pid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ownProcesses =
                (List<Map<String, Object>>) ownerRuntime.scope().get("ownProcesses");
        assertTrue(ownProcesses.stream().allMatch(item ->
                owner.userId().toString().equals(item.get("ownerId"))));
        @SuppressWarnings("unchecked")
        List<Object> names = (List<Object>) ownerRuntime.scope().get("functions");
        assertTrue(names.contains("file.adminRead"));
        assertTrue(names.contains("network.httpGet"));
        assertTrue(names.contains("package.install"));
        assertTrue(names.contains("socket.connect"));
        @SuppressWarnings("unchecked")
        Map<String, Object> installedPackage =
                (Map<String, Object>) ownerRuntime.scope().get("installedPackage");
        assertEquals("hello", installedPackage.get("binding"));
        @SuppressWarnings("unchecked")
        Map<String, Object> packageCheck =
                (Map<String, Object>) ownerRuntime.scope().get("packageCheck");
        assertEquals(true, packageCheck.get("valid"));
        @SuppressWarnings("unchecked")
        Map<String, Object> launchedPackage =
                (Map<String, Object>) ownerRuntime.scope().get("launchedPackage");
        long launchedPid = ((Number) launchedPackage.get("pid")).longValue();
        assertTrue(launchedPid > 0);
        CilProcess launchedChild = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED, transaction -> transaction.processes()
                        .findByPid(launchedPid).orElseThrow());
        assertEquals(CilProcess.Status.READY, launchedChild.status());
        assertTrue(launchedChild.continuation().packageBindings().containsKey("hello"));
        boolean bindingPersisted = transactions.inUserTransaction(owner.userId(),
                Isolation.READ_COMMITTED,
                transaction -> transaction.packages().findProcessBinding(
                        launchedChild.identity().processUid(), "hello").isPresent());
        assertTrue(bindingPersisted);

        String createdUsername = "fcl-created-" + suffix;
        String adminSource = """
                data = file.adminRead("%s", "%s")
                written = file.adminWrite("%s", "%s", "changed")
                managed = file.adminCreateDir("%s", "%s", "managed")
                createdFile = file.adminCreateFile("%s", managed, "new.txt", "body", false)
                renamed = file.adminRename("%s", createdFile, "renamed.txt")
                nodes = file.adminList("%s")
                deleted = file.adminDelete("%s", renamed)
                users = user.getListOfUsers()
                valid = user.validateUser("%s")
                created = user.createUser("%s", "created-password-123")
                removed = user.removeUser("%s")
                processes = process.getList()
                paused = process.pause(%s)
                continued = process.continue(%s)
                killed = process.kill(%s)
                finished = process.waitPID(%s)
                """.formatted(
                owner.userId(), privateFile.nodeId(), owner.userId(), privateFile.nodeId(),
                owner.userId(), ownerRoot.nodeId(), owner.userId(), owner.userId(),
                owner.userId(), owner.userId(), removable.userId(), createdUsername,
                removable.userId(), ownerProcess.identity().pid(), ownerProcess.identity().pid(),
                ownerProcess.identity().pid(), ownerProcess.identity().pid());
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
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes =
                (List<Map<String, Object>>) adminRuntime.scope().get("processes");
        assertTrue(processes.stream().anyMatch(item ->
                owner.userId().toString().equals(item.get("ownerId"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> finished = (Map<String, Object>) adminRuntime.scope().get("finished");
        assertEquals("TERMINATED", finished.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) adminRuntime.scope().get("created");
        @SuppressWarnings("unchecked")
        Map<String, Object> removed = (Map<String, Object>) adminRuntime.scope().get("removed");
        assertEquals(createdUsername, created.get("username"));
        assertEquals("ACTIVE", created.get("status"));
        assertEquals("DISABLED", removed.get("status"));
        AdminVfsService administratorVfs = new AdminVfsService(transactions, clock);
        assertEquals("changed", new String(administratorVfs
                .readFile(administrator.userId(), owner.userId(), privateFile.nodeId())
                .content().bytes(), StandardCharsets.UTF_8));
        assertTrue(administratorVfs.listNodes(administrator.userId(), owner.userId()).stream()
                .anyMatch(node -> node.name().equals("managed")));
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
                                    Clock.systemUTC().instant()))
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
