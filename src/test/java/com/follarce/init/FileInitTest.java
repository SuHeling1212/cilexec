package com.follarce.init;

import com.follarce.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FileInitTest {
    @TempDir Path root;

    @Test
    void createsPackageAndDataDirectoriesUnderSystemApp() {
        FileInit.init(root.toFile());

        assertDirectoryWithMetadata(Constants.SYSTEM_APP_PACKAGE_PATH);
        assertDirectoryWithMetadata(Constants.SYSTEM_APP_DATA_PATH);
        assertDirectoryWithMetadata(Constants.USER_LOCAL_APP_PACKAGE_PATH);
        assertDirectoryWithMetadata(Constants.USER_LOCAL_APP_DATA_PATH);
        assertDirectoryWithMetadata(Constants.USER_LOCAL_PACKAGE_DATA_PATH);
    }

    private void assertDirectoryWithMetadata(String vfsPath) {
        Path directory = root.resolve(vfsPath.substring(1));
        assertTrue(Files.isDirectory(directory), "Missing VFS directory: " + vfsPath);
        assertTrue(Files.isRegularFile(directory.resolve(Constants.META_DIR_FILE)),
                "Missing directory metadata: " + vfsPath);
    }
}
