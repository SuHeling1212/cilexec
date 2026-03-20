package com.follarce.init;

import com.follarce.util.*;
import com.follarce.process.ProcessRunner;
import java.util.*;

public class ProcessInit {
    
    private static boolean initialized = false;
    private static Thread schedulerThread;
    private static Map<Integer, ProcessRunner> runners = new HashMap<>();
    
    /**
     * Initialize the process system
     * Creates INIT process (PID 1) and starts scheduler
     */
    public static void init() {
        if (initialized) {
            return;
        }
        
        System.out.println("Initializing process system...");
        
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
            System.out.println("Started runner for existing PID: 1");
        }
        
        // Start the scheduler
        startScheduler();
        
        initialized = true;
        System.out.println("Process system initialized");
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
        System.out.println("Creating INIT process (PID 1)...");
        
        // Ensure process directory exists
        FileUtil.createDirectory("/system/", "process");
        
        // Create the process file first
        String[] createResult = FileUtil.createFile("/system/process/", "1.json");
        if (!createResult[0].equals("SUCCESS")) {
            System.err.println("Failed to create process file: " + createResult[1]);
            return;
        }
        
        // Build process JSON
        Map<String, Object> process = new HashMap<>();
        
        // Basic info
        process.put("Name", "INIT");
        process.put("Owner", "local");
        process.put("Local", true);
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
        
        // Code section - empty loop to keep process alive
        Map<String, Object> code = new HashMap<>();
        code.put("runningCodeLine", 0);
        
        List<String> codeLines = new ArrayList<>();
        codeLines.add("while true {");
        codeLines.add("}");
        code.put("Code", codeLines);
        
        program.put("Code", code);
        process.put("Program", program);
        
        // Write to file
        String processJson = JsonUtil.toJson(process);
        String[] writeResult = FileUtil.write("/system/process/1.json", processJson);
        
        if (writeResult[0].equals("SUCCESS")) {
            System.out.println("INIT process created successfully");
            
            // Start the runner immediately
            ProcessRunner runner = new ProcessRunner(1);
            runners.put(1, runner);
            new Thread(runner).start();
            System.out.println("Started runner for PID: 1");
        } else {
            System.err.println("Failed to write INIT process: " + writeResult[1]);
        }
    }
    
    /**
     * Start the process scheduler
     * Monitors for new processes and creates runners for them
     */
    private static void startScheduler() {
        System.out.println("Starting process scheduler...");
        
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
                                        System.out.println("Started runner for PID: " + pid);
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
                                entry.getValue().stop();
                                it.remove();
                                System.out.println("Removed runner for PID: " + entry.getKey());
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Scheduler error: " + e.getMessage());
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
        // Stop all runners
        for (ProcessRunner runner : runners.values()) {
            runner.stop();
        }
        runners.clear();
        
        if (schedulerThread != null) {
            schedulerThread.interrupt();
        }
        
        initialized = false;
        System.out.println("Process system shutdown");
    }
}