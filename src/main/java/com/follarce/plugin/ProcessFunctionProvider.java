package com.follarce.plugin;

import com.follarce.process.ProcessFunc;

import java.util.List;

/**
 * Process operation function provider
 * Encapsulates script calling interface for ProcessFunc
 */
public class ProcessFunctionProvider implements FunctionProvider {
    
    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        // Set current PID to caller's PID
        ProcessFunc.setCurrentPid(context.getPid());
        
        switch (name) {
            case "getPID":
                return context.getPid();
                
            case "getPPID":
                return context.getPpid();
                
            case "fork":
                return ProcessFunc.fork(context.getPid());
                
            case "exec":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return error("INVALID_ARGUMENTS");
                }
                String[] stringParams = null;
                if (args.length >= 2 && args[1] != null) {
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
                    }
                }
                return ProcessFunc.exec((String) args[0], stringParams);
                
            case "kill":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return error("INVALID_ARGUMENTS");
                }
                return ProcessFunc.kill(((Number) args[0]).intValue());
                
            case "Pause":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return error("INVALID_ARGUMENTS");
                }
                return ProcessFunc.Pause(((Number) args[0]).intValue());
                
            case "Continue":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return error("INVALID_ARGUMENTS");
                }
                return ProcessFunc.Continue(((Number) args[0]).intValue());
                
            case "wait":
                return ProcessFunc.waitProcess();
                
            case "waitPID":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return error("INVALID_ARGUMENTS");
                }
                return ProcessFunc.waitPID(((Number) args[0]).intValue());
                
            case "getListOfChildProcess":
                return ProcessFunc.getListOfChildProcess();
                
            case "getListOfProcess":
                return ProcessFunc.getListOfProcess();
                
            default:
                return null;
        }
    }
    
    private String[] error(String code) {
        return new String[]{"ERROR", code};
    }
    
    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo("getPID", "Get current process ID",
                new String[]{}, "int", "Process"),
            new FunctionInfo("getPPID", "Get parent process ID",
                new String[]{}, "int", "Process"),
            new FunctionInfo("fork", "Create child process",
                new String[]{}, "int", "Process"),
            new FunctionInfo("exec", "Execute program to replace current process",
                new String[]{"path: string", "params: array (optional)"}, "String[]", "Process"),
            new FunctionInfo("kill", "Terminate process",
                new String[]{"pid: int"}, "String[]", "Process"),
            new FunctionInfo("wait", "Wait for any child process to end",
                new String[]{}, "String[]", "Process"),
            new FunctionInfo("waitPID", "Wait for specified child process to end",
                new String[]{"pid: int"}, "String[]", "Process"),
            new FunctionInfo("Pause", "Pause process",
                new String[]{"pid: int"}, "String[]", "Process"),
            new FunctionInfo("Continue", "Resume paused process",
                new String[]{"pid: int"}, "String[]", "Process"),
            new FunctionInfo("getListOfChildProcess", "Get child process list",
                new String[]{}, "Map", "Process"),
            new FunctionInfo("getListOfProcess", "Get all process list (requires local permission)",
                new String[]{}, "Map", "Process")
        };
    }
    
    @Override
    public String getProviderName() {
        return "ProcessFunctionProvider";
    }
}
