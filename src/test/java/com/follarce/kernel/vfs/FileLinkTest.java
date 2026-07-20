package com.follarce.kernel.vfs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.vfs.FileUtil;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLinkTest {
    @TempDir Path root;

    @BeforeEach
    void initializeVfs() {
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void linkUsesTargetNameAndReadFollowsIt() {
        FileUtil.createDirectory("/user/local/app/data", "links");
        FileUtil.createFile("/user/local/app/data", "target.fcl");
        FileUtil.write("/user/local/app/data/target.fcl", "linked-body");

        FileUtil.link("/user/local/app/data/links", "/user/local/app/data/target.fcl");

        assertTrue(FileUtil.exists("/user/local/app/data/links/target.fcl"));
        assertEquals("linked-body", FileUtil.read("/user/local/app/data/links/target.fcl"));
    }
}
