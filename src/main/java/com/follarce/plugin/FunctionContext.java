package com.follarce.plugin;

/**
 * Function call context
 * Contains environment information during call
 */
public class FunctionContext {
    private final int pid;
    private final int ppid;
    private final String currentUser;
    
    public FunctionContext(int pid, int ppid, String currentUser) {
        this.pid = pid;
        this.ppid = ppid;
        this.currentUser = currentUser;
    }
    
    /**
     * Get current process ID
     */
    public int getPid() {
        return pid;
    }
    
    /**
     * Get parent process ID
     */
    public int getPpid() {
        return ppid;
    }
    
    /**
     * Get current user
     */
    public String getCurrentUser() {
        return currentUser;
    }
    
    /**
     * Check if is local user
     */
    public boolean isLocal() {
        return "local".equals(currentUser);
    }
}
