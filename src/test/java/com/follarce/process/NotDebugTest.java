package com.follarce.process;

import com.follarce.basicUtil.*;
import com.follarce.init.FileInit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class NotDebugTest {

    @TempDir
    Path tempDir;

    @Test
    public void debugNotOperator() throws Exception {
        // Setup test environment
        Path testRoot = tempDir.resolve("test_vfs_debug");
        Files.createDirectories(testRoot);

        java.lang.reflect.Field vfsRootField = FileUtil.class.getDeclaredField("VFS_ROOT");
        vfsRootField.setAccessible(true);
        String originalVfsRoot = (String) vfsRootField.get(null);
        vfsRootField.set(null, testRoot.toString());
        
        FileInit.init();

        // Create process
        int pid = createTestProcess("result = not true");
        
        // Get tokenize method for debugging
        ProcessRunner runner = new ProcessRunner(pid);
        
        // Use reflection to call tokenize
        java.lang.reflect.Method tokenizeMethod = ProcessRunner.class.getDeclaredMethod("tokenize", String.class);
        tokenizeMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<Object> tokens = (List<Object>) tokenizeMethod.invoke(runner, "not true");
        
        System.out.println("=== Tokens for 'not true' ===");
        for (Object token : tokens) {
            java.lang.reflect.Field typeField = token.getClass().getDeclaredField("type");
            java.lang.reflect.Field valueField = token.getClass().getDeclaredField("value");
            typeField.setAccessible(true);
            valueField.setAccessible(true);
            System.out.println("Type: " + typeField.get(token) + ", Value: " + valueField.get(token));
        }
        
        // Restore original VFS root
        vfsRootField.set(null, originalVfsRoot);
    }
    
    private int createTestProcess(String scriptContent) throws Exception {
        int pid = allocateTestPid();
        
        FileUtil.createDirectory("/", "system");
        FileUtil.createDirectory("/system/", "process");
        
        Map<String, Object> process = new HashMap<>();
        process.put("Name", "TestProcess");
        process.put("Owner", "local");
        process.put("Local", true);
        process.put("PID", pid);
        process.put("Path", "/test/script.fcl");
        process.put("Status", true);
        
        int[] now = TimeUtil.getTime();
        process.put("startTime", new int[]{now[0], now[1], now[2], now[3], now[4], now[5], now[6]});
        process.put("RunningTime", 0);
        process.put("Parent", new HashMap<>());
        process.put("Child", new HashMap<>());
        
        Map<String, Object> program = new HashMap<>();
        program.put("Data", new HashMap<>());
        
        Map<String, Object> code = new HashMap<>();
        code.put("runningCodeLine", 0);
        
        List<String> codeLines = Arrays.asList(scriptContent.split("\n"));
        code.put("Code", codeLines);
        
        program.put("Code", code);
        process.put("Program", program);
        
        String[] createResult = FileUtil.createFile("/system/process/", pid + ".json");
        if (!"SUCCESS".equals(createResult[0])) {
            throw new RuntimeException("Failed to create process file: " + Arrays.toString(createResult));
        }
        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));
        
        return pid;
    }
    
    private int allocateTestPid() {
        String[] listResult = FileUtil.getListOfFileAndDirectory("/system/process/");
        if (!listResult[0].equals("SUCCESS")) {
            return 100;
        }
        
        int maxPid = 99;
        for (int i = 1; i < listResult.length; i++) {
            String name = listResult[i];
            if (name.endsWith(".json")) {
                try {
                    int pid = Integer.parseInt(name.replace(".json", ""));
                    if (pid > maxPid) maxPid = pid;
                } catch (NumberFormatException e) {
                }
            }
        }
        return maxPid + 1;
    }
}
