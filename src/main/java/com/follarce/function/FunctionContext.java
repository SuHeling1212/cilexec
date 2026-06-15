package com.follarce.function;

/**
 * 函数调用上下文 —— 传递给所有插件函数。
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

    public int getPid() { return pid; }
    public int getPpid() { return ppid; }
    public String getCurrentUser() { return currentUser; }
}
