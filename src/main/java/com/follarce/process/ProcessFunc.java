package com.follarce.process;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.TimeUtil;
import com.follarce.util.UserUtil;

public class ProcessFunc {

    private static AtomicInteger currentPid = new AtomicInteger(1);

    public static void setCurrentPid(int pid) {
        currentPid.set(pid);
    }

    public static int getPID() {
        return currentPid.get();
    }

    /**
     * Fork a new process
     * 
     * @return child PID for parent, 0 for child
     */
    public static int fork() {
        int parentPid = currentPid.get();

        try {
            // 1. Read parent process file
            String[] readResult = FileUtil.read("/system/process/" + parentPid + ".json");
            if (!readResult[0].equals("SUCCESS")) {
                return -1;
            }

            Map<String, Object> parentProcess = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

            // 2. Allocate new PID
            int childPid = allocatePid();

            // 3. Deep copy parent process
            String parentJson = JsonUtil.toJson(parentProcess);
            Map<String, Object> childProcess = (Map<String, Object>) JsonUtil.readJson(parentJson);

            // 4. Modify child process fields
            childProcess.put("PID", childPid);

            Map<String, Object> parentInfo = new HashMap<>();
            parentInfo.put("Name", parentProcess.get("Name"));
            parentInfo.put("PID", parentPid);
            parentInfo.put("Path", parentProcess.get("Path"));
            childProcess.put("Parent", parentInfo);

            childProcess.put("Child", new HashMap<>());
            childProcess.put("Status", true);
            childProcess.put("startTime", TimeUtil.getTime());
            childProcess.put("RunningTime", 0);

            // Reset program state (reset runningCodeLine to 0)
            Map<String, Object> program = (Map<String, Object>) childProcess.get("Program");
            Map<String, Object> code = (Map<String, Object>) program.get("Code");
            code.put("runningCodeLine", 0);

            // 5. Write child process file
            FileUtil.createFile("/system/process/", childPid + ".json");
            FileUtil.write("/system/process/" + childPid + ".json", JsonUtil.toJson(childProcess));

            // 6. Update parent's Child list
            Map<String, Object> childInfo = new HashMap<>();
            childInfo.put("Name", childProcess.get("Name"));
            childInfo.put("PID", childPid);
            childInfo.put("Path", childProcess.get("Path"));

            Map<String, Object> parentChild = (Map<String, Object>) parentProcess.get("Child");
            if (parentChild == null) {
                parentChild = new HashMap<>();
                parentProcess.put("Child", parentChild);
            }
            parentChild.put(String.valueOf(childPid), childInfo);

            FileUtil.write("/system/process/" + parentPid + ".json", JsonUtil.toJson(parentProcess));

            // 7. Return child PID (parent gets child PID, child gets 0)
            return childPid;

        } catch (Exception e) {
            return -1;
        }
    }

    private static synchronized int allocatePid() {
        String[] listResult = FileUtil.getListOfFileAndDirectory("/system/process/");
        if (!listResult[0].equals("SUCCESS")) {
            return 1;
        }

        int maxPid = 0;
        for (int i = 1; i < listResult.length; i++) {
            String name = listResult[i];
            if (name.endsWith(".json")) {
                try {
                    int pid = Integer.parseInt(name.replace(".json", ""));
                    if (pid > maxPid)
                        maxPid = pid;
                } catch (NumberFormatException e) {
                }
            }
        }
        return maxPid + 1;
    }

    /**
     * Execute a new program in the current process
     * 
     * @param path   program file path
     * @param params command line arguments
     * @return ["SUCCESS", content] on success, ["ERROR", code] on failure
     */
    public static String[] exec(String path, String[] params) {
        int currentPid = getPID();

        // Check permission to execute
        if (!UserUtil.checkFilePermission(path, "execute")) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        // 1. Check if file exists
        String[] readResult = FileUtil.read(path);
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "FILE_DOES_NOT_EXIST" };
        }

        // 2. Check if it's a file (not directory)
        String[] fileInfo = FileUtil.readFileMetaData(path);
        if (!fileInfo[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "IS_NOT_FILE" };
        }

        // 3. Read current process file
        String[] procResult = FileUtil.read("/system/process/" + currentPid + ".json");
        if (!procResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(procResult[1]);

        // 4. Update process info
        process.put("Path", path);
        process.put("Status", true);
        process.put("startTime", TimeUtil.getTime()); // Reset start time
        process.put("RunningTime", 0); // Reset running time

        // 5. Reset Program section
        Map<String, Object> program = new HashMap<>();

        // Data section (reset)
        program.put("Data", new HashMap<>());

        // Code section (load from script)
        Map<String, Object> code = new HashMap<>();
        code.put("runningCodeLine", 0);

        // Split script content into lines
        String scriptContent = readResult[1];
        String[] lines = scriptContent.split("\n");
        code.put("Code", Arrays.asList(lines));

        program.put("Code", code);
        process.put("Program", program);

        // 6. Save back
        FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(process));

        return new String[] { "SUCCESS", scriptContent };
    }

    /**
     * Get list of child processes
     * 
     * @return Map of child name to PID, or error array on failure
     */
    public static Object getListOfChildProcess() {
        int currentPid = getPID();

        // Read current process
        String[] readResult = FileUtil.read("/system/process/" + currentPid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> children = (Map<String, Object>) process.get("Child");

        if (children == null || children.isEmpty()) {
            return new HashMap<>();
        }

        // Build result map: child name -> PID
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : children.entrySet()) {
            Map<String, Object> child = (Map<String, Object>) entry.getValue();
            String name = (String) child.get("Name");
            int pid = ((Number) child.get("PID")).intValue();
            result.put(name, pid);
        }

        return result;
    }

    /**
     * Kill a process
     */
    public static String[] kill(int pid) {
        // Check permission
        if (!UserUtil.checkProcessPermission(pid)) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_DOES_NOT_EXIST" };
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        // 1. FIRST: Adopt orphaned children to INIT (PID 1)
        Map<String, Object> children = (Map<String, Object>) process.get("Child");
        if (children != null && !children.isEmpty()) {
            // Read INIT process
            String[] initResult = FileUtil.read("/system/process/1.json");
            if (initResult[0].equals("SUCCESS")) {
                Map<String, Object> initProcess = (Map<String, Object>) JsonUtil.readJson(initResult[1]);
                Map<String, Object> initChildren = (Map<String, Object>) initProcess.get("Child");
                if (initChildren == null) {
                    initChildren = new HashMap<>();
                    initProcess.put("Child", initChildren);
                }

                for (Map.Entry<String, Object> entry : children.entrySet()) {
                    Map<String, Object> child = (Map<String, Object>) entry.getValue();
                    int childPid = ((Number) child.get("PID")).intValue();

                    // Update child's parent to INIT
                    String[] childResult = FileUtil.read("/system/process/" + childPid + ".json");
                    if (childResult[0].equals("SUCCESS")) {
                        Map<String, Object> childProcess = (Map<String, Object>) JsonUtil.readJson(childResult[1]);

                        Map<String, Object> parentInfo = new HashMap<>();
                        parentInfo.put("Name", "INIT");
                        parentInfo.put("PID", 1);
                        parentInfo.put("Path", "");
                        childProcess.put("Parent", parentInfo);

                        FileUtil.write("/system/process/" + childPid + ".json", JsonUtil.toJson(childProcess));
                    }

                    // Add to INIT's child list
                    initChildren.put(String.valueOf(childPid), child);
                }

                FileUtil.write("/system/process/1.json", JsonUtil.toJson(initProcess));
            }
        }

        // 2. SECOND: Remove from parent's child list
        Map<String, Object> parent = (Map<String, Object>) process.get("Parent");
        if (parent != null && parent.containsKey("PID")) {
            int parentPid = ((Number) parent.get("PID")).intValue();
            String[] parentResult = FileUtil.read("/system/process/" + parentPid + ".json");
            if (parentResult[0].equals("SUCCESS")) {
                Map<String, Object> parentProcess = (Map<String, Object>) JsonUtil.readJson(parentResult[1]);
                Map<String, Object> parentChildren = (Map<String, Object>) parentProcess.get("Child");
                if (parentChildren != null) {
                    parentChildren.remove(String.valueOf(pid));
                }
                FileUtil.write("/system/process/" + parentPid + ".json", JsonUtil.toJson(parentProcess));
            }
        }

        // 3. LAST: Delete the process file
        FileUtil.removeFile("/system/process/" + pid + ".json");

        return new String[] { "SUCCESS", null };
    }

    /**
     * Wait for any child process to terminate
     * 
     * @return ["SUCCESS", null] on success, ["ERROR", code] on failure
     */
    public static String[] waitProcess() {
        int currentPid = getPID();

        // Read current process
        String[] readResult = FileUtil.read("/system/process/" + currentPid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> children = (Map<String, Object>) process.get("Child");

        // Check if there are any children
        if (children == null || children.isEmpty()) {
            return new String[] { "ERROR", "CHILD_PROCESS_DOES_NOT_EXIST" };
        }

        // Wait for any child to terminate
        while (true) {
            for (Object childInfo : children.values()) {
                Map<String, Object> child = (Map<String, Object>) childInfo;
                int childPid = ((Number) child.get("PID")).intValue();

                // Check child status
                String[] childResult = FileUtil.read("/system/process/" + childPid + ".json");
                if (childResult[0].equals("SUCCESS")) {
                    Map<String, Object> childProcess = (Map<String, Object>) JsonUtil.readJson(childResult[1]);
                    Boolean status = (Boolean) childProcess.get("Status");

                    if (status == null || !status) {
                        // Child terminated, remove from parent's child list
                        children.remove(String.valueOf(childPid));

                        // Update parent file
                        FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(process));

                        return new String[] { "SUCCESS", null };
                    }
                } else {
                    // Child process file doesn't exist, remove from list
                    children.remove(String.valueOf(childPid));
                    FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(process));
                    return new String[] { "SUCCESS", null };
                }
            }

            // Sleep before checking again
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return new String[] { "ERROR", "INTERRUPTED" };
            }

            // Reload children list (in case it changed)
            readResult = FileUtil.read("/system/process/" + currentPid + ".json");
            if (!readResult[0].equals("SUCCESS")) {
                return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
            }
            process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            children = (Map<String, Object>) process.get("Child");

            if (children == null || children.isEmpty()) {
                return new String[] { "ERROR", "CHILD_PROCESS_DOES_NOT_EXIST" };
            }
        }
    }

    /**
     * Get list of all processes (requires local permission)
     * 
     * @return Map of process name to PID, or error array on failure
     */
    public static Object getListOfProcess() {
        int currentPid = getPID();

        // Check local permission
        String[] currentResult = FileUtil.read("/system/process/" + currentPid + ".json");
        if (!currentResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Map<String, Object> currentProcess = (Map<String, Object>) JsonUtil.readJson(currentResult[1]);
        Boolean isLocal = (Boolean) currentProcess.get("Local");
        if (isLocal == null || !isLocal) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        // Get all process files
        String[] listResult = FileUtil.getListOfFileAndDirectory("/system/process/");
        if (!listResult[0].equals("SUCCESS")) {
            return new HashMap<>();
        }

        Map<String, Integer> result = new HashMap<>();
        for (int i = 1; i < listResult.length; i++) {
            String name = listResult[i];
            if (name.endsWith(".json")) {
                try {
                    int pid = Integer.parseInt(name.replace(".json", ""));
                    String[] procResult = FileUtil.read("/system/process/" + pid + ".json");
                    if (procResult[0].equals("SUCCESS")) {
                        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(procResult[1]);
                        String procName = (String) process.get("Name");
                        result.put(procName, pid);
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }

        return result;
    }

    /**
     * Wait for a specific child process to terminate
     * 
     * @param pid child process ID to wait for
     * @return ["SUCCESS", null] on success, ["ERROR", code] on failure
     */
    public static String[] waitPID(int pid) {
        int currentPid = getPID();

        // Check if the process exists
        String[] targetResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!targetResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_DOES_NOT_EXIST" };
        }

        // Read current process to check if it's a child
        String[] currentResult = FileUtil.read("/system/process/" + currentPid + ".json");
        if (!currentResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Map<String, Object> currentProcess = (Map<String, Object>) JsonUtil.readJson(currentResult[1]);
        Map<String, Object> children = (Map<String, Object>) currentProcess.get("Child");

        // Check if pid is a child process
        if (children == null || !children.containsKey(String.valueOf(pid))) {
            return new String[] { "ERROR", "PID_DOES_NOT_CHILD_PROCESS" };
        }

        // Wait for the child to terminate
        while (true) {
            String[] childResult = FileUtil.read("/system/process/" + pid + ".json");
            if (!childResult[0].equals("SUCCESS")) {
                // Process file deleted, consider it terminated
                children.remove(String.valueOf(pid));
                FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(currentProcess));
                return new String[] { "SUCCESS", null };
            }

            Map<String, Object> childProcess = (Map<String, Object>) JsonUtil.readJson(childResult[1]);
            Boolean status = (Boolean) childProcess.get("Status");

            if (status == null || !status) {
                // Child terminated, remove from parent's child list
                children.remove(String.valueOf(pid));
                FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(currentProcess));
                return new String[] { "SUCCESS", null };
            }

            // Sleep before checking again
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return new String[] { "ERROR", "INTERRUPTED" };
            }
        }
    }

    /**
     * Pause a process
     * 
     * @param pid process ID to pause
     * @return ["SUCCESS", null] on success, ["ERROR", code] on failure
     */
    public static String[] Pause(int pid) {
        // Check permission
        if (!UserUtil.checkProcessPermission(pid)) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        // Check if process exists
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_DOES_NOT_EXIST" };
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        // Check if already paused (Status is Boolean false when paused)
        Object statusObj = process.get("Status");
        if (Boolean.FALSE.equals(statusObj)) {
            return new String[] { "ERROR", "PROCESS_IS_PAUSED" };
        }

        // Pause the process - use Boolean false for paused state
        process.put("Status", false);
        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));

        return new String[] { "SUCCESS", null };
    }

    /**
     * Get parent process ID
     * 
     * @return parent PID, or 0 if no parent
     */
    public static int getPPID() {
        int currentPid = getPID();

        String[] readResult = FileUtil.read("/system/process/" + currentPid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return 0;
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        Map<String, Object> parent = (Map<String, Object>) process.get("Parent");

        if (parent == null || !parent.containsKey("PID")) {
            return 0;
        }

        return ((Number) parent.get("PID")).intValue();
    }

    /**
     * Continue a paused process
     * 
     * @param pid process ID to continue
     * @return ["SUCCESS", null] on success, ["ERROR", code] on failure
     */
    public static String[] Continue(int pid) {
        // Check permission
        if (!UserUtil.checkProcessPermission(pid)) {
            return new String[] { "ERROR", "INSUFFICIENT_PERMISSION" };
        }

        // Check if process exists
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_DOES_NOT_EXIST" };
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        // Check if already running
        Object statusObj = process.get("Status");
        if (Boolean.TRUE.equals(statusObj) || "Running".equals(statusObj) || statusObj == null) {
            return new String[] { "ERROR", "PROCESS_IS_RUNNING" };
        }

        // Continue the process - use Boolean true for running state
        process.put("Status", true);
        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));

        return new String[] { "SUCCESS", null };
    }

    /**
     * Dispatch function calls from script engine
     */
    public static Object call(String name, Object[] args) {
        switch (name) {
            // Process info
            case "getPID":
                return getPID();
            case "getPPID":
                return getPPID();

            // Process creation
            case "fork":
                return fork();
            case "exec":
                if (args.length != 2 || !(args[0] instanceof String) || !(args[1] instanceof String[])) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return exec((String) args[0], (String[]) args[1]);

            // Process control
            case "kill":
                if (args.length != 1 || !(args[0] instanceof Number)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return kill(((Number) args[0]).intValue());
            case "Pause":
                if (args.length != 1 || !(args[0] instanceof Number)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return Pause(((Number) args[0]).intValue());
            case "Continue":
                if (args.length != 1 || !(args[0] instanceof Number)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return Continue(((Number) args[0]).intValue());

            // Process waiting
            case "wait":
                return waitProcess();
            case "waitPID":
                if (args.length != 1 || !(args[0] instanceof Number)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return waitPID(((Number) args[0]).intValue());

            // Process listing
            case "getListOfChildProcess":
                return getListOfChildProcess();
            case "getListOfProcess":
                return getListOfProcess();

            default:
                return null;
        }
    }
}