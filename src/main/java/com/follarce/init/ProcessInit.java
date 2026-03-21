package com.follarce.init;

import com.follarce.basicUtil.*;
import com.follarce.process.ProcessRunner;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProcessInit {

    private static boolean initialized = false;
    private static Thread schedulerThread;
    private static Map<Integer, ProcessRunner> runners = new ConcurrentHashMap<>();
    
    /**
     * Initialize the process system
     * Creates INIT process (PID 1) and starts scheduler
     */
    public static void init() {
        if (initialized) {
            return;
        }
        
        Logger.info("Initializing process system");
        
        // Ensure process directory exists
        ensureProcessDirectory();
        
        // Create INIT process if not exists
        if (!processExists(1)) {
            createInitProcess();
        } else {
            // If exists, start runner for existing process
            ProcessRunner runner = new ProcessRunner(1);
            runners.put(1, runner);
            new Thread(runner).start();
            Logger.debug("Started runner for existing PID: 1");
        }
        
        // Start the scheduler
        startScheduler();
        
        initialized = true;
        Logger.info("Process system initialized");
    }
    
    /**
     * Ensure /system/process directory exists
     */
    private static void ensureProcessDirectory() {
        String[] listResult = FileUtil.getListOfFileAndDirectory("/system/process/");
        if (!listResult[0].equals("SUCCESS")) {
            FileUtil.createDirectory("/system/", "process");
        }
    }
    
    /**
     * Check if a process with given PID exists
     */
    private static boolean processExists(int pid) {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        return readResult[0].equals("SUCCESS");
    }
    
    /**
     * Create the INIT process (PID 1)
     */
    private static void createInitProcess() {
        Logger.info("Creating INIT process (PID 1)");
        
        // Ensure process directory exists
        FileUtil.createDirectory("/system/", "process");
        
        // Create the process file first
        String[] createResult = FileUtil.createFile("/system/process/", "1.json");
        if (!createResult[0].equals("SUCCESS")) {
            Logger.error("Failed to create process file: " + createResult[1]);
            return;
        }
        
        // Get current user
        String currentUser = UserInit.getCurrentUser();
        if (currentUser == null) {
            currentUser = "local";
        }
        boolean isLocal = UserInit.isLocal();
        
        // Build process JSON
        Map<String, Object> process = new HashMap<>();
        
        // Basic info
        process.put("Name", "INIT");
        process.put("Owner", currentUser);
        process.put("Local", isLocal);
        process.put("PID", 1);
        process.put("Path", "");
        process.put("Status", true);
        
        // Start time (current time)
        int[] now = TimeUtil.getTime();
        process.put("startTime", new int[]{now[0], now[1], now[2], now[3], now[4], now[5], now[6]});
        process.put("RunningTime", 0);
        
        // Parent (none)
        process.put("Parent", new HashMap<>());
        
        // Children (none)
        process.put("Child", new HashMap<>());
        
        // Program section
        Map<String, Object> program = new HashMap<>();
        
        // Data section
        program.put("Data", new HashMap<>());
        
        // Code section - read from INIT.fcl config file
        Map<String, Object> code = new HashMap<>();
        code.put("runningCodeLine", 0);

        List<String> codeLines = new ArrayList<>();
        String[] initConfigResult = FileUtil.read("/system/config/INIT.fcl");
        if (initConfigResult[0].equals("SUCCESS")) {
            String[] lines = initConfigResult[1].split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                // Skip comments and empty lines
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    codeLines.add(trimmed);
                }
            }
        }
        // Fallback to default if config is empty or failed to read
        if (codeLines.isEmpty()) {
            codeLines.add("while true {");
            codeLines.add("}");
        }
        code.put("Code", codeLines);
        
        program.put("Code", code);
        process.put("Program", program);
        
        // Write to file
        String processJson = JsonUtil.toJson(process);
        String[] writeResult = FileUtil.write("/system/process/1.json", processJson);
        
        if (writeResult[0].equals("SUCCESS")) {
            Logger.info("INIT process created successfully");
            
            // Start the runner immediately
            ProcessRunner runner = new ProcessRunner(1);
            runners.put(1, runner);
            new Thread(runner).start();
            Logger.debug("Started runner for PID: 1");
        } else {
            Logger.error("Failed to write INIT process: " + writeResult[1]);
        }
    }
    
    /**
     * Start the process scheduler
     * Monitors for new processes and creates runners for them
     */
    private static void startScheduler() {
        Logger.info("Starting process scheduler");
        
        schedulerThread = new Thread(() -> {
            while (true) {
                try {
                    // Get all process files
                    String[] listResult = FileUtil.getListOfFileAndDirectory("/system/process/");
                    
                    if (listResult[0].equals("SUCCESS")) {
                        // Collect all PIDs
                        Set<Integer> currentPids = new HashSet<>();
                        for (int i = 1; i < listResult.length; i++) {
                            String name = listResult[i];
                            if (name.endsWith(".json")) {
                                try {
                                    int pid = Integer.parseInt(name.replace(".json", ""));
                                    currentPids.add(pid);
                                    
                                    // Create runner if not exists
                                    if (!runners.containsKey(pid)) {
                                        ProcessRunner runner = new ProcessRunner(pid);
                                        runners.put(pid, runner);
                                        new Thread(runner).start();
                                        Logger.debug("Started runner for PID: " + pid);
                                    }
                                } catch (NumberFormatException e) {
                                    // Ignore non-numeric filenames
                                }
                            }
                        }
                        
                        // Remove runners for terminated processes
                        Iterator<Map.Entry<Integer, ProcessRunner>> it = runners.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<Integer, ProcessRunner> entry = it.next();
                            if (!currentPids.contains(entry.getKey())) {
                                entry.getValue().shutdown(); // Use shutdown to avoid modifying deleted process file
                                it.remove();
                                Logger.debug("Removed runner for PID: " + entry.getKey());
                            }
                        }
                    }
                } catch (Exception e) {
                    Logger.error("Scheduler error: " + e.getMessage());
                }
                
                // Sleep before next scan
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        
        schedulerThread.setDaemon(true);
        schedulerThread.start();
    }
    
    /**
     * Get runner for a specific PID
     */
    public static ProcessRunner getRunner(int pid) {
        return runners.get(pid);
    }
    
    /**
     * Shutdown the process system
     */
    public static void shutdown() {
        // Stop all runners without modifying process files
        for (ProcessRunner runner : runners.values()) {
            runner.shutdown();
        }
        runners.clear();
        
        if (schedulerThread != null) {
            schedulerThread.interrupt();
        }
        
        initialized = false;
        Logger.info("Process system shutdown");
    }
}