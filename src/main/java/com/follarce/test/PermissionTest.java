package com.follarce.test;

import com.follarce.basicUtil.FileUtil;
import com.follarce.basicUtil.UserUtil;
import com.follarce.init.FileInit;

/**
 * Simple test for permission system
 */
public class PermissionTest {

    public static void main(String[] args) {
        System.out.println("=== Permission System Test ===\n");

        // Initialize file system
        FileInit.init();

        // Test 1: Local user access
        System.out.println("Test 1: Local user access");
        testLocalUserAccess();

        // Test 2: Non-local user permission check
        System.out.println("\nTest 2: Non-local user permission check");
        testNonLocalUserPermission();

        // Test 3: File metadata
        System.out.println("\nTest 3: File metadata structure");
        testFileMetadata();

        System.out.println("\n=== All Tests Completed ===");
    }

    private static void testLocalUserAccess() {
        UserUtil.setCurrentUser("local");
        System.out.println("  Current user: " + UserUtil.getCurrentUser());
        System.out.println("  Is local: " + UserUtil.isLocal());

        // Create file
        String[] createResult = FileUtil.createFile("/user/local/app/", "perm_test.txt");
        System.out.println("  Create file: " + createResult[0]);

        if (createResult[0].equals("SUCCESS")) {
            // Write to file
            String[] writeResult = FileUtil.write("/user/local/app/perm_test.txt", "test content");
            System.out.println("  Write file: " + writeResult[0]);

            // Read from file
            String[] readResult = FileUtil.read("/user/local/app/perm_test.txt");
            System.out.println("  Read file: " + readResult[0]);

            // Cleanup
            FileUtil.removeFile("/user/local/app/perm_test.txt");
        }
    }

    private static void testNonLocalUserPermission() {
        // Create file as local user
        UserUtil.setCurrentUser("local");
        FileUtil.createFile("/user/local/app/", "nonlocal_test.txt");

        // Switch to non-local user
        UserUtil.setCurrentUser("testuser");
        System.out.println("  Current user: " + UserUtil.getCurrentUser());
        System.out.println("  Is local: " + UserUtil.isLocal());

        // Check permission
        boolean canRead = UserUtil.checkFilePermission("/user/local/app/nonlocal_test.txt", "read");
        System.out.println("  Can read: " + canRead);

        boolean canWrite = UserUtil.checkFilePermission("/user/local/app/nonlocal_test.txt", "write");
        System.out.println("  Can write: " + canWrite);

        // Cleanup
        UserUtil.setCurrentUser("local");
        FileUtil.removeFile("/user/local/app/nonlocal_test.txt");
    }

    private static void testFileMetadata() {
        UserUtil.setCurrentUser("local");

        // Create and write file
        FileUtil.createFile("/user/local/app/", "meta_test.txt");
        FileUtil.write("/user/local/app/meta_test.txt", "test content");

        // Read metadata
        String[] metaResult = FileUtil.readFileMetaData("/user/local/app/meta_test.txt");
        System.out.println("  Read metadata: " + metaResult[0]);

        if (metaResult[0].equals("SUCCESS")) {
            System.out.println("  Metadata content: " + metaResult[1]);

            // Check required fields
            boolean hasOwner = metaResult[1].contains("Owner");
            boolean hasPermission = metaResult[1].contains("Permission");
            System.out.println("  Has Owner field: " + hasOwner);
            System.out.println("  Has Permission field: " + hasPermission);
        }

        // Cleanup
        FileUtil.removeFile("/user/local/app/meta_test.txt");
    }
}
