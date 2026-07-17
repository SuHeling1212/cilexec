package com.follarce.function;

import com.follarce.init.FileInit;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserTransactionTest {
    @TempDir Path root;
    private UserFunctionProvider provider;

    @BeforeEach
    void initialize() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        provider = new UserFunctionProvider();
    }

    @Test
    void createAndRemoveReplayConvergeWithTheirHomeDirectory() {
        FunctionContext create = context("create-alice");
        assertEquals("User created: alice",
                provider.call("createUser", List.of("alice", "pw"), create));
        assertEquals("User created: alice",
                provider.call("createUser", List.of("alice", "pw"), create));
        assertTrue(FileUtil.exists("/user/alice"));
        assertTrue(FileUtil.exists("/user/alice/app"));
        assertEquals("alice", FileUtil.readDirectoryMetaData("/user/alice").get("Owner"));

        FunctionContext remove = context("remove-alice");
        assertEquals("User removed: alice",
                provider.call("removeUser", List.of("alice", "pw"), remove));
        assertEquals("User removed: alice",
                provider.call("removeUser", List.of("alice", "pw"), remove));
        assertFalse(FileUtil.exists("/user/alice"));
        assertFalse(UserUtil.getListOfUsers().containsKey("alice"));

        Map<String, Object> config = JsonUtil.parseToMapStrict(FileUtil.read("/system/config/users.json"));
        assertTrue(((Map<?, ?>) config.get("AppliedEffects")).containsKey("remove-alice"));
    }

    private static FunctionContext context(String effectId) {
        return new FunctionContext(1, 0, "local", "generation-1", Map.of(),
                null, null, null).forEffect(effectId, false);
    }
}
