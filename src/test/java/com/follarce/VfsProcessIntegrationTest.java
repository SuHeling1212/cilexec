package com.follarce;

import com.follarce.function.FileFunctionProvider;
import com.follarce.function.FunctionContext;
import com.follarce.function.IOFunctionProvider;
import com.follarce.function.PrivilegedFunctionProvider;
import com.follarce.function.SwapFunctionProvider;
import com.follarce.function.UserFunctionProvider;
import com.follarce.init.FileInit;
import com.follarce.init.ProcessInit;
import com.follarce.process.StateManager;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VfsProcessIntegrationTest {
    @TempDir Path root;
    private final FunctionContext local = new FunctionContext(11, 1, "local");

    @BeforeEach
    void setUp() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void initializesVfsAndSupportsFileLifecyclePermissionsAndMetadata() {
        assertTrue(FileUtil.exists("/system/config/env.json"));
        FileFunctionProvider file = new FileFunctionProvider();
        assertEquals("", file.call("createFile", List.of("/user/local", "note.txt"), local));
        assertEquals("", file.call("write", List.of("/user/local/note.txt", "first"), local));
        assertEquals("", file.call("append", List.of("/user/local/note.txt", " second"), local));
        assertEquals("first second", file.call("read", List.of("/user/local/note.txt"), local));
        assertEquals("", file.call("rename", List.of("/user/local/note.txt", "renamed.txt"), local));
        assertEquals(true, file.call("exists", List.of("/user/local/renamed.txt"), local));
        assertTrue(file.call("listdir", List.of("/user/local"), local).toString().contains("renamed.txt"));
        assertTrue(file.call("readMetaData", List.of("/user/local/renamed.txt"), local) instanceof Map);
        assertEquals("", file.call("lock", List.of("/user/local/renamed.txt"), local));
        assertEquals("", file.call("unlock", List.of("/user/local/renamed.txt"), local));
        assertEquals("", file.call("removeFile", List.of("/user/local/renamed.txt"), local));
    }

    @Test
    void userAndSwapPoolProvidersPersistAccessControlledData() {
        UserFunctionProvider users = new UserFunctionProvider();
        assertEquals("User created: alice", users.call("createUser", List.of("alice", "secret"), local));
        assertEquals(true, users.call("validateUser", List.of("alice", "secret"), local));
        assertEquals("Switched to user: alice", users.call("switchUser", List.of("alice", "secret"), local));
        assertEquals("alice", users.call("getCurrentUser", List.of(), local));
        assertEquals("Switched to user: local", users.call("switchUser", List.of("local", "local"), local));

        SwapFunctionProvider swap = new SwapFunctionProvider();
        assertEquals("Swap pool created: jobs", swap.call("create", List.of("jobs"), local));
        assertEquals("Variable added: task (type=times(2))", swap.call("add", List.of("task:build", "jobs", "type:times(2)"), local));
        assertEquals("build", swap.call("get", List.of("task", "jobs"), local));
        assertEquals("build", swap.call("get", List.of("task", "jobs"), local));
        assertTrue(swap.call("get", List.of("task", "jobs"), local).toString().startsWith("ERROR:"));
        assertEquals("Swap pool removed: jobs", swap.call("remove", List.of("jobs"), local));
    }

    @Test
    void ioAndPrivilegedProvidersRespectVfsAndAuthorization() {
        IOFunctionProvider io = new IOFunctionProvider();
        assertEquals("", io.call("writeFile", List.of("/user/local/io.txt", "payload"), local));
        assertEquals("payload", io.call("readFile", List.of("/user/local/io.txt"), local));

        PrivilegedFunctionProvider system = new PrivilegedFunctionProvider();
        Object denied = system.call("forceRemove", List.of("/user/local/io.txt"), new FunctionContext(12, 1, "alice"));
        assertTrue(denied instanceof String[]);
        assertEquals("Removed: /user/local/io.txt", system.call("forceRemove", List.of("/user/local/io.txt"), local));
        assertFalse(FileUtil.exists("/user/local/io.txt"));
        assertTrue((Long) system.call("invoke", List.of("java.lang.System", "currentTimeMillis"), local) > 0);
    }

    @Test
    void processInitializationAndSnapshotRoundTripPreserveExecutionState() {
        ProcessInit.init();
        assertNotNull(ProcessInit.getInitProcessData());

        Map<String, Object> process = new LinkedHashMap<>();
        process.put("PID", 99);
        process.put("Status", true);
        process.put("Program", new LinkedHashMap<String, Object>());
        StateManager manager = new StateManager(99, System.currentTimeMillis(), process);
        StateManager.RuntimeSnapshot snapshot = new StateManager.RuntimeSnapshot(
                new LinkedHashMap<>(Map.of("counter", 5L)),
                List.of("counter = counter + 1"), 1, new ArrayList<>(), new ArrayList<>(), null, List.of());
        manager.saveToFile(snapshot);

        String raw = FileUtil.read("/system/process/99.proc");
        assertEquals(5, ((Number) ((Map<?, ?>) ((Map<?, ?>) JsonUtil.parseToMap(raw).get("Program")).get("Data")).get("counter")).intValue());
        manager.loadFromFile();
        StateManager.RuntimeSnapshot restored = manager.loadFromProcessData();
        assertEquals(1, restored.currentLine);
        assertEquals(5, ((Number) restored.data.get("counter")).intValue());
    }
}
