package com.follarce.process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import com.follarce.basicUtil.FileUtil;
import com.follarce.basicUtil.JsonUtil;
import com.follarce.basicUtil.Logger;
import com.follarce.basicUtil.TimeUtil;
import com.follarce.basicUtil.UserUtil;
import com.follarce.network.SocketUtil;

public class ProcessFunc {

    private static final ThreadLocal<Integer> currentPid = ThreadLocal.withInitial(() -> 1);
    private static final ThreadLocal<String> currentUser = ThreadLocal.withInitial(() -> "local");

    public static void setCurrentPid(int pid) {
        currentPid.set(pid);
    }

    public static int getPID() {
        return currentPid.get();
    }

    public static void setCurrentUser(String user) {
        currentUser.set(user);
    }

    public static String getCurrentUser() {
        return currentUser.get();
    }

    /**
     * Fork a new process from the specified parent PID
     * 
     * @param parentPid the parent process ID
     * @return child PID on success, -1 on failure
     */
    public static int fork(int parentPid) {
        try {
            // 1. Read parent process file
            String[] readResult = FileUtil.read("/system/process/" + parentPid + ".json");
            if (!readResult[0].equals("SUCCESS")) {
                Logger.warn("Fork failed: parent process " + parentPid + " not found");
                return -1;
            }

            Object parentProcessObj = JsonUtil.readJson(readResult[1]);
            if (parentProcessObj == null || !(parentProcessObj instanceof Map)) {
                Logger.warn("Fork failed: invalid parent process JSON for PID " + parentPid);
                return -1;
            }
            Map<String, Object> parentProcess = (Map<String, Object>) parentProcessObj;

            // Get parent's current code line
            Map<String, Object> parentProgram = (Map<String, Object>) parentProcess.get("Program");
            if (parentProgram == null) {
                Logger.warn("Fork failed: parent process has no Program section");
                return -1;
            }
            Map<String, Object> parentCode = (Map<String, Object>) parentProgram.get("Code");
            if (parentCode == null) {
                Logger.warn("Fork failed: parent process has no Code section");
                return -1;
            }
            int parentRunningLine = 0;
            Object runningLineObj = parentCode.get("runningCodeLine");
            if (runningLineObj instanceof Number) {
                parentRunningLine = ((Number) runningLineObj).intValue();
            }

            // 2. Allocate new PID
            int childPid = allocatePid();
            Logger.info("Allocated PID " + childPid + " for new process");

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

            // Set child's program state - continue from next line after fork()
            Map<String, Object> program = (Map<String, Object>) childProcess.get("Program");
            Map<String, Object> code = (Map<String, Object>) program.get("Code");
            // Child continues from the line AFTER fork() call
            code.put("runningCodeLine", parentRunningLine + 1);
            
            // Clear return value register for child process
            program.put("returnValue", null);

            // 5. Write child process file
            FileUtil.createFile("/system/process/", childPid + ".json");
            FileUtil.write("/system/process/" + childPid + ".json", JsonUtil.toJsonPretty(childProcess));

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

            FileUtil.write("/system/process/" + parentPid + ".json", JsonUtil.toJsonPretty(parentProcess));

            Logger.info("Process forked: parent PID " + parentPid + " -> child PID " + childPid);

            // 7. Return child PID
            return childPid;

        } catch (Exception e) {
            Logger.error("Fork failed: " + e.getMessage(), e);
            return -1;
        }
    }
    
    /**
     * Fork a new process using current PID (for backward compatibility)
     * 
     * @return child PID for parent, -1 on failure
     */
    public static int fork() {
        return fork(getPID());
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
     * @param params command line arguments (may contain special parameters starting with "-")
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

        Object processObj = JsonUtil.readJson(procResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;

        // 4. Update process info
        process.put("Path", path);
        process.put("Status", true);
        process.put("startTime", TimeUtil.getTime()); // Reset start time
        process.put("RunningTime", 0); // Reset running time

        // 5. Parse special parameters from params array
        Map<String, List<String>> specialParams = new HashMap<>();
        List<String> regularArgs = new ArrayList<>();
        String currentParam = null;
        List<String> currentValues = null;
        
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                String param = params[i];
                if (param.startsWith("-")) {
                    // Save previous parameter if any
                    if (currentParam != null) {
                        specialParams.put(currentParam.substring(1), currentValues);
                    }
                    // Start new parameter
                    currentParam = param;
                    currentValues = new ArrayList<>();
                } else {
                    // Value for current parameter or regular argument
                    if (currentParam != null) {
                        currentValues.add(param);
                    } else {
                        // Regular argument (not associated with any special parameter)
                        regularArgs.add(param);
                    }
                }
            }
            // Save last parameter if any
            if (currentParam != null) {
                specialParams.put(currentParam.substring(1), currentValues);
            }
        }
        
        // Check for -user parameter
        if (specialParams.containsKey("user")) {
            List<String> userValues = specialParams.get("user");
            if (userValues != null && !userValues.isEmpty()) {
                String username = userValues.get(0);
                process.put("user", username);
                process.put("Owner", username);
                setCurrentUser(username);
            }
        }

        // 6. Reset Program section
        Map<String, Object> program = new HashMap<>();

        // Data section (reset, but add command line arguments)
        Map<String, Object> data = new HashMap<>();
        
        // Store special parameters in data object
        for (Map.Entry<String, List<String>> entry : specialParams.entrySet()) {
            data.put(entry.getKey(), entry.getValue());
        }

        // Store command line arguments (regular args only, special params are filtered out)
        // argv[0] is the program path, argv[1..n] are the regular arguments
        List<String> argv = new ArrayList<>();
        argv.add(path);
        for (String arg : regularArgs) {
            argv.add(arg);
        }
        data.put("argv", argv);
        data.put("argc", argv.size());

        program.put("Data", data);

        // Code section (load from script)
        Map<String, Object> code = new HashMap<>();
        code.put("runningCodeLine", 0);

        // Split script content into lines
        String scriptContent = readResult[1];
        String[] lines = scriptContent.split("\n");
        List<String> codeList = new ArrayList<>(Arrays.asList(lines));
        code.put("Code", codeList);

        program.put("Code", code);
        process.put("Program", program);

        // 6. Save back
        Logger.info("Exec: Saving process file with " + codeList.size() + " code lines");
        String[] writeResult = FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(process));
        Logger.info("Exec: Write result: " + writeResult[0]);

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

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;
        Map<String, Object> children = (Map<String, Object>) process.get("Child");

        if (children == null || children.isEmpty()) {
            return new HashMap<>();
        }

        // Build result map: child name -> PID
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : children.entrySet()) {
            Object childValue = entry.getValue();
            if (!(childValue instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> child = (Map<String, Object>) childValue;
            String name = (String) child.get("Name");
            Object pidObj = child.get("PID");
            if (!(pidObj instanceof Number)) continue;
            int pid = ((Number) pidObj).intValue();
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

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;

        // 1. FIRST: Adopt orphaned children to INIT (PID 1)
        Map<String, Object> children = (Map<String, Object>) process.get("Child");
        if (children != null && !children.isEmpty()) {
            // Read INIT process
            String[] initResult = FileUtil.read("/system/process/1.json");
            if (initResult[0].equals("SUCCESS")) {
                Object initProcessObj = JsonUtil.readJson(initResult[1]);
                if (initProcessObj == null || !(initProcessObj instanceof Map)) {
                    Logger.warn("Failed to parse INIT process JSON");
                } else {
                    Map<String, Object> initProcess = (Map<String, Object>) initProcessObj;
                    Map<String, Object> initChildren = (Map<String, Object>) initProcess.get("Child");
                if (initChildren == null) {
                    initChildren = new HashMap<>();
                    initProcess.put("Child", initChildren);
                }

                for (Map.Entry<String, Object> entry : children.entrySet()) {
                    Object childValue = entry.getValue();
                    if (!(childValue instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> child = (Map<String, Object>) childValue;
                    Object pidObj = child.get("PID");
                    if (!(pidObj instanceof Number)) continue;
                    int childPid = ((Number) pidObj).intValue();

                    // Update child's parent to INIT
                    String[] childResult = FileUtil.read("/system/process/" + childPid + ".json");
                    if (childResult[0].equals("SUCCESS")) {
                        Object childProcessObj = JsonUtil.readJson(childResult[1]);
                        if (childProcessObj != null && childProcessObj instanceof Map) {
                            Map<String, Object> childProcess = (Map<String, Object>) childProcessObj;

                            Map<String, Object> parentInfo = new HashMap<>();
                        parentInfo.put("Name", "INIT");
                        parentInfo.put("PID", 1);
                        parentInfo.put("Path", "");
                            childProcess.put("Parent", parentInfo);

                            FileUtil.write("/system/process/" + childPid + ".json", JsonUtil.toJson(childProcess));
                        }
                    }

                    // Add to INIT's child list
                    initChildren.put(String.valueOf(childPid), child);
                }

                FileUtil.write("/system/process/1.json", JsonUtil.toJson(initProcess));
                }
            }
        }

        // 2. SECOND: Remove from parent's child list
        Map<String, Object> parent = (Map<String, Object>) process.get("Parent");
        if (parent != null && parent.containsKey("PID")) {
            Object parentPidObj = parent.get("PID");
            if (parentPidObj instanceof Number) {
                int parentPid = ((Number) parentPidObj).intValue();
                String[] parentResult = FileUtil.read("/system/process/" + parentPid + ".json");
                if (parentResult[0].equals("SUCCESS")) {
                    Object parentProcessObj = JsonUtil.readJson(parentResult[1]);
                    if (parentProcessObj != null && parentProcessObj instanceof Map) {
                        Map<String, Object> parentProcess = (Map<String, Object>) parentProcessObj;
                        Map<String, Object> parentChildren = (Map<String, Object>) parentProcess.get("Child");
                        if (parentChildren != null) {
                            parentChildren.remove(String.valueOf(pid));
                        }
                        FileUtil.write("/system/process/" + parentPid + ".json", JsonUtil.toJson(parentProcess));
                    }
                }
            }
        }

        // 3. Clean up sockets owned by this process
        SocketUtil.onProcessExit(pid);

        // 4. LAST: Delete the process file
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

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;
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
                    Object childProcessObj = JsonUtil.readJson(childResult[1]);
                    if (childProcessObj == null || !(childProcessObj instanceof Map)) {
                        children.remove(String.valueOf(childPid));
                        FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(process));
                        return new String[] { "SUCCESS", null };
                    }
                    Map<String, Object> childProcess = (Map<String, Object>) childProcessObj;
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
            processObj = JsonUtil.readJson(readResult[1]);
            if (processObj == null || !(processObj instanceof Map)) {
                return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
            }
            process = (Map<String, Object>) processObj;
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

        Object currentProcessObj = JsonUtil.readJson(currentResult[1]);
        if (currentProcessObj == null || !(currentProcessObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> currentProcess = (Map<String, Object>) currentProcessObj;
        Boolean isLocal = (Boolean) currentProcess.get("isLocal");
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
                        Object procObj = JsonUtil.readJson(procResult[1]);
                        if (procObj != null && procObj instanceof Map) {
                            Map<String, Object> process = (Map<String, Object>) procObj;
                            String procName = (String) process.get("Name");
                            if (procName != null) {
                                result.put(procName, pid);
                            }
                        }
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

        Object currentProcessObj = JsonUtil.readJson(currentResult[1]);
        if (currentProcessObj == null || !(currentProcessObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> currentProcess = (Map<String, Object>) currentProcessObj;
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

            Object childProcessObj = JsonUtil.readJson(childResult[1]);
            if (childProcessObj == null || !(childProcessObj instanceof Map)) {
                children.remove(String.valueOf(pid));
                FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(currentProcess));
                return new String[] { "SUCCESS", null };
            }
            Map<String, Object> childProcess = (Map<String, Object>) childProcessObj;
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

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;

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

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return 0;
        }
        Map<String, Object> process = (Map<String, Object>) processObj;
        Map<String, Object> parent = (Map<String, Object>) process.get("Parent");

        if (parent == null || !parent.containsKey("PID")) {
            return 0;
        }

        Object parentPidObj = parent.get("PID");
        if (!(parentPidObj instanceof Number)) {
            return 0;
        }
        return ((Number) parentPidObj).intValue();
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

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;

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
     * Dispatch function calls from script engine (with explicit caller PID)
     */
    public static Object call(String name, Object[] args, int callerPid) {
        // Set current PID for this call
        setCurrentPid(callerPid);
        
        switch (name) {
            // Process info
            case "getPID":
                return callerPid;
            case "getPPID":
                return getPPIDInternal(callerPid);

            // Process creation
            case "fork":
                return fork(callerPid);
            case "exec":
                if (args.length != 2 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                // Convert List or Object[] to String[]
                String[] stringParams;
                if (args[1] instanceof List) {
                    List<?> paramList = (List<?>) args[1];
                    stringParams = new String[paramList.size()];
                    for (int i = 0; i < paramList.size(); i++) {
                        stringParams[i] = paramList.get(i).toString();
                    }
                } else if (args[1] instanceof Object[]) {
                    Object[] paramArray = (Object[]) args[1];
                    stringParams = new String[paramArray.length];
                    for (int i = 0; i < paramArray.length; i++) {
                        stringParams[i] = paramArray[i].toString();
                    }
                } else {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return exec((String) args[0], stringParams);

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
                return waitProcessInternal(callerPid);
            case "waitPID":
                if (args.length != 1 || !(args[0] instanceof Number)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return waitPIDInternal(callerPid, ((Number) args[0]).intValue());

            // Process listing
            case "getListOfChildProcess":
                return getListOfChildProcessInternal(callerPid);
            case "getListOfProcess":
                return getListOfProcess();

            default:
                return null;
        }
    }
    
    /**
     * Dispatch function calls from script engine (backward compatibility)
     */
    public static Object call(String name, Object[] args) {
        return call(name, args, getPID());
    }
    
    private static int getPPIDInternal(int pid) {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return 0;
        }

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return 0;
        }
        Map<String, Object> process = (Map<String, Object>) processObj;
        Map<String, Object> parent = (Map<String, Object>) process.get("Parent");

        if (parent == null || !parent.containsKey("PID")) {
            return 0;
        }

        Object parentPidObj = parent.get("PID");
        if (!(parentPidObj instanceof Number)) {
            return 0;
        }
        return ((Number) parentPidObj).intValue();
    }
    
    private static String[] waitProcessInternal(int pid) {
        // Read current process
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;
        Map<String, Object> children = (Map<String, Object>) process.get("Child");

        // Check if there are any children
        if (children == null || children.isEmpty()) {
            return new String[] { "ERROR", "CHILD_PROCESS_DOES_NOT_EXIST" };
        }

        // Wait for any child to terminate
        while (true) {
            for (Object childInfo : children.values()) {
                Map<String, Object> child = (Map<String, Object>) childInfo;
                Object childPidObj = child.get("PID");
                if (!(childPidObj instanceof Number)) continue;
                int childPid = ((Number) childPidObj).intValue();

                // Check child status
                String[] childResult = FileUtil.read("/system/process/" + childPid + ".json");
                if (childResult[0].equals("SUCCESS")) {
                    Object childProcessObj = JsonUtil.readJson(childResult[1]);
                    if (childProcessObj == null || !(childProcessObj instanceof Map)) {
                        children.remove(String.valueOf(childPid));
                        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));
                        return new String[] { "SUCCESS", null };
                    }
                    Map<String, Object> childProcess = (Map<String, Object>) childProcessObj;
                    Boolean status = (Boolean) childProcess.get("Status");

                    if (status == null || !status) {
                        // Child terminated, remove from parent's child list
                        children.remove(String.valueOf(childPid));

                        // Update parent file
                        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));

                        return new String[] { "SUCCESS", null };
                    }
                } else {
                    // Child process file doesn't exist, remove from list
                    children.remove(String.valueOf(childPid));
                    FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));
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
            readResult = FileUtil.read("/system/process/" + pid + ".json");
            if (!readResult[0].equals("SUCCESS")) {
                return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
            }
            processObj = JsonUtil.readJson(readResult[1]);
            if (processObj == null || !(processObj instanceof Map)) {
                return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
            }
            process = (Map<String, Object>) processObj;
            children = (Map<String, Object>) process.get("Child");

            if (children == null || children.isEmpty()) {
                return new String[] { "ERROR", "CHILD_PROCESS_DOES_NOT_EXIST" };
            }
        }
    }
    
    private static String[] waitPIDInternal(int currentPid, int targetPid) {
        // Check if the process exists
        String[] targetResult = FileUtil.read("/system/process/" + targetPid + ".json");
        if (!targetResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_DOES_NOT_EXIST" };
        }

        // Read current process to check if it's a child
        String[] currentResult = FileUtil.read("/system/process/" + currentPid + ".json");
        if (!currentResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Object currentProcessObj = JsonUtil.readJson(currentResult[1]);
        if (currentProcessObj == null || !(currentProcessObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> currentProcess = (Map<String, Object>) currentProcessObj;
        Map<String, Object> children = (Map<String, Object>) currentProcess.get("Child");

        // Check if pid is a child process
        if (children == null || !children.containsKey(String.valueOf(targetPid))) {
            return new String[] { "ERROR", "PID_DOES_NOT_CHILD_PROCESS" };
        }

        // Wait for the child to terminate
        while (true) {
            String[] childResult = FileUtil.read("/system/process/" + targetPid + ".json");
            if (!childResult[0].equals("SUCCESS")) {
                // Process file deleted, consider it terminated
                children.remove(String.valueOf(targetPid));
                FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(currentProcess));
                return new String[] { "SUCCESS", null };
            }

            Object childProcessObj = JsonUtil.readJson(childResult[1]);
            if (childProcessObj == null || !(childProcessObj instanceof Map)) {
                children.remove(String.valueOf(targetPid));
                FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(currentProcess));
                return new String[] { "SUCCESS", null };
            }
            Map<String, Object> childProcess = (Map<String, Object>) childProcessObj;
            Boolean status = (Boolean) childProcess.get("Status");

            if (status == null || !status) {
                // Child terminated, remove from parent's child list
                children.remove(String.valueOf(targetPid));
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
    
    private static Object getListOfChildProcessInternal(int pid) {
        // Read current process
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (processObj == null || !(processObj instanceof Map)) {
            return new String[] { "ERROR", "INVALID_PROCESS_JSON" };
        }
        Map<String, Object> process = (Map<String, Object>) processObj;
        Map<String, Object> children = (Map<String, Object>) process.get("Child");

        if (children == null || children.isEmpty()) {
            return new HashMap<>();
        }

        // Build result map: child name -> PID
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : children.entrySet()) {
            Object childValue = entry.getValue();
            if (!(childValue instanceof Map)) continue;
            Map<String, Object> child = (Map<String, Object>) childValue;
            String name = (String) child.get("Name");
            Object childPidObj = child.get("PID");
            if (!(childPidObj instanceof Number)) continue;
            int childPid = ((Number) childPidObj).intValue();
            if (name != null) {
                result.put(name, childPid);
            }
        }

        return result;
    }
}