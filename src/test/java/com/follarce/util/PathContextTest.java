package com.follarce.util;

import com.follarce.Constants;
import com.follarce.init.FileInit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathContextTest {
    @TempDir Path root;

    @AfterEach
    void clearAliases() {
        PathUtil.setEnvAliases(Map.of());
    }

    @Test
    void processAliasesOverrideGlobalAliasesAndGlobalStateIsDefensivelyCopied() {
        Map<String, String> globals = new LinkedHashMap<>();
        globals.put("work", "/global/work");
        globals.put("shared", "/global/shared");
        PathUtil.setEnvAliases(globals);

        globals.put("work", "/mutated/source");
        Map<String, String> returned = PathUtil.getEnvAliases();
        returned.put("work", "/mutated/return");

        Map<String, String> processAliases = Map.of("work", "/process/work");
        assertEquals("/process/work/file.txt",
                PathUtil.resolvePath("@work/file.txt", "alice", processAliases));
        assertEquals("/global/shared/file.txt",
                PathUtil.resolvePath("@shared/file.txt", "alice", processAliases));
        assertEquals("/global/work/file.txt", PathUtil.resolvePath("$work/file.txt"));
    }

    @Test
    void homeAndSystemTokensUseTheEffectiveContext() {
        assertEquals("/user/alice", PathUtil.resolvePath("~", "alice", Map.of()));
        assertEquals("/user/alice/docs",
                PathUtil.resolvePath("$HOME/docs", "alice", Map.of()));
        assertEquals("/system/app", PathUtil.resolvePath("$SYSTEM/app", "alice", Map.of()));
        assertEquals("/user/local/docs", PathUtil.resolvePath("$HOME/docs"));
    }

    @Test
    void unknownTokensCyclesAndExcessiveExpansionAreRejected() {
        PathUtil.setEnvAliases(Map.of(
                "first", "@second",
                "second", "@first"));

        assertThrows(IllegalArgumentException.class,
                () -> PathUtil.resolvePath("@missing/file", "alice", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PathUtil.resolvePath("$MISSING/file", "alice", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PathUtil.resolvePath("@first/file", "alice", Map.of()));

        Map<String, String> deepAliases = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            deepAliases.put("level" + i, i == 32 ? "/done" : "@level" + (i + 1));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PathUtil.resolvePath("@level0", "alice", deepAliases));
    }

    @Test
    void fileInitReloadsAliasesAndDoesNotLeakThemAcrossVfsRoots() {
        Path firstRoot = root.resolve("first");
        FileInit.init(firstRoot.toFile());

        Map<String, Object> env = JsonUtil.parseToMap(FileUtil.read(
                Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_ENV_JSON));
        env.put("aliases", Map.of("project", "/user/local/project"));
        FileUtil.write(Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_ENV_JSON,
                JsonUtil.toMetaJson(env));

        assertThrows(IllegalArgumentException.class, () -> PathUtil.resolvePath("@project"));
        FileInit.init(firstRoot.toFile());
        assertEquals("/user/local/project", PathUtil.resolvePath("@project"));

        FileInit.init(root.resolve("second").toFile());
        assertThrows(IllegalArgumentException.class, () -> PathUtil.resolvePath("@project"));
        assertEquals("/system/swap", PathUtil.resolvePath("@temp"));
    }
}
