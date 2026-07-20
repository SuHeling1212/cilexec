package com.follarce.extension.builtin;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.extension.builtin.UserFunctionProvider;
import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.FunctionContext;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

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
        assertPrivateUserDirectory("/user/alice/app/package", "alice");
        assertPrivateUserDirectory("/user/alice/app/data/package", "alice");
        assertPrivateUserDirectory("/user/alice/app/data/package/transactions", "alice");
        assertPrivateUserDirectory("/user/alice/app/data/package/packages", "alice");

        UserUtil.setCurrentUser("alice");
        FileUtil.createDirectory("/user/alice/app", "workspace");
        assertEquals("alice", FileUtil.readDirectoryMetaData("/user/alice/app/workspace").get("Owner"));
        UserUtil.setCurrentUser("local");

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

    private static void assertPrivateUserDirectory(String path, String owner) {
        Map<String, Object> metadata = FileUtil.readDirectoryMetaData(path);
        assertNotNull(metadata, "Missing directory metadata: " + path);
        assertEquals(owner, metadata.get("Owner"));
        Map<?, ?> permissions = (Map<?, ?>) metadata.get("Permission");
        assertEquals("", permissions.get(Constants.PERM_OTHERS));
    }
}
