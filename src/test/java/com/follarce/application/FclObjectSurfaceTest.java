package com.follarce.application;

import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.0.5 acceptance surface: the removed 0.0.4 entity API never appears in the live registry,
 * the new object factories do, and {@code system.list()} reflects the same visible set.
 */
class FclObjectSurfaceTest {
    @Test
    void removedEntityApiIsNotCallableAndNewFactoriesAreVisible() {
        FclFunctionRegistry registry = registry();
        Set<String> visible = registry.qualifiedNames();

        List<String> removed = List.of(
                "file.read", "file.readChunk", "file.size", "file.exists", "file.list",
                "file.readMetaData", "file.write", "file.append", "file.createFile",
                "file.createDir", "file.remove", "file.clear", "file.rename", "file.lock",
                "file.renewLock", "file.unlock", "io.readFile", "io.writeFile",
                "process.kill", "process.pause", "process.continue", "process.waitPID",
                "process.removeFinished", "system.kill",
                "swapPool.get", "swapPool.add", "swapPool.update", "swapPool.list",
                "swapPool.clear", "swapPool.lock", "swapPool.renewLock", "swapPool.unlock",
                "swapPool.signal", "swapPool.waitFor", "swapPool.remove", "swapPool.exists",
                "ipc.sendDirect", "ipc.sendChannel", "ipc.publishTopic", "ipc.broadcast",
                "ipc.subscribeChannel", "ipc.subscribeTopic", "ipc.removeChannel",
                "ipc.removeTopic", "ipc.consume",
                "package.info", "package.verify", "package.run", "package.resource",
                "package.pin", "package.uninstall", "package.dataInfo", "package.dataList",
                "package.dataRead", "package.dataExport", "package.dataImport",
                "package.clearData", "package.dataQuota", "package.setDataQuota",
                "package.removeDataQuota",
                "packageData.root", "packageData.exists", "packageData.read",
                "packageData.readChunk", "packageData.write", "packageData.seedResource",
                "packageData.append", "packageData.mkdir", "packageData.list",
                "packageData.remove", "packageData.clear", "packageData.rename",
                "packageData.size", "packageData.usage",
                "user.validateUser", "user.disable", "user.remove",
                "program.remove", "terminal.remove");
        for (String name : removed) {
            assertFalse(visible.contains(name), "removed API still visible: " + name);
        }

        List<String> factories = List.of(
                "file.open", "file.create", "file.mkdir", "file.path", "file.link",
                "process.find", "swapPool.open", "swapPool.create",
                "ipc.channel", "ipc.createChannel", "ipc.topic", "ipc.createTopic",
                "ipc.delivery", "package.find", "package.install", "package.quotaFor",
                "packageData.current", "user.find", "user.create",
                "program.find", "terminal.find", "system.list", "system.extensions");
        for (String name : factories) {
            assertTrue(visible.contains(name), "factory missing: " + name);
        }

        // system.list() returns exactly the visible qualified names.
        Object listed = registry.resolve("system.list").function().invoke(List.of(), null);
        assertTrue(listed instanceof List<?>);
        assertEquals(visible, Set.copyOf(((List<?>) listed).stream()
                .map(String::valueOf).toList()));
    }

    @Test
    void removedNameInvocationFailsWithUndefinedFunction() {
        FclFunctionRegistry registry = registry();
        assertTrue(registry.qualifiedNames().contains("file.open"));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.follarce.fcl.FclRuntimeException.class,
                () -> registry.invoke("file.read", List.of("/x.txt")));
    }

    private static FclFunctionRegistry registry() {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        var program = new ProgramService(persistence).create(ownerId, "value = 1\n");
        var process = new ProcessService(persistence).create(ownerId, program, Optional.empty());
        return FclRuntimeFunctions.create(persistence, process, program,
                new FclContinuation(), Instant.now());
    }
}
