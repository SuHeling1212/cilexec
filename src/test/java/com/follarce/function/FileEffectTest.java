package com.follarce.function;

import com.follarce.init.FileInit;
import com.follarce.util.FileUtil;
import com.follarce.util.UserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileEffectTest {
    @TempDir Path root;
    private FileFunctionProvider provider;

    @BeforeEach
    void initialize() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        FileUtil.createFile("/user/local", "effect.txt");
        FileUtil.write("/user/local/effect.txt", "A");
        provider = new FileFunctionProvider();
    }

    @Test
    void appendAndCreateAreIdempotentForAStableEffectId() {
        FunctionContext append = context("append-once");
        assertEquals("", provider.call("append", List.of("/user/local/effect.txt", "B"), append));
        assertEquals("", provider.call("append", List.of("/user/local/effect.txt", "B"), append));
        assertEquals("AB", FileUtil.read("/user/local/effect.txt"));

        FunctionContext create = context("create-once");
        assertEquals("", provider.call("createFile", List.of("/user/local", "created.txt"), create));
        assertEquals("", provider.call("createFile", List.of("/user/local", "created.txt"), create));
    }

    private static FunctionContext context(String effectId) {
        return new FunctionContext(1, 0, "local", "generation-1", Map.of(),
                null, null, null).forEffect(effectId, false);
    }
}
