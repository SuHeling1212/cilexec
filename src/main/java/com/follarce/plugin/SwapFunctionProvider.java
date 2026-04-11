package com.follarce.plugin;

import com.follarce.process.SwapUtil;

import java.util.List;

public class SwapFunctionProvider implements FunctionProvider {

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        if (!name.startsWith("swapPool.")) {
            return null;
        }
        switch (name) {
            case "create":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.createSwapPool((String) args[0]);

            case "remove":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.removeSwapPool((String) args[0]);

            case "add":
                if (args.length < 3 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                String[] params;
                if (args[2] instanceof List) {
                    List<?> paramList = (List<?>) args[2];
                    params = new String[paramList.size()];
                    for (int i = 0; i < paramList.size(); i++) {
                        params[i] = paramList.get(i).toString();
                    }
                } else if (args[2] instanceof Object[]) {
                    Object[] paramArray = (Object[]) args[2];
                    params = new String[paramArray.length];
                    for (int i = 0; i < paramArray.length; i++) {
                        params[i] = paramArray[i].toString();
                    }
                } else {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.swapPoolAdd((String) args[0], (String) args[1], params);

            case "get":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.swapPoolGet((String) args[0], (String) args[1]);

            case "removeVar":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.swapPoolRemove((String) args[0], (String) args[1]);

            case "lock":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.swapPoolLock((String) args[0], (String) args[1]);

            case "unlock":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.swapPoolUnlock((String) args[0], (String) args[1]);

            case "update":
                if (args.length < 3 || !(args[0] instanceof String) || !(args[1] instanceof String)
                        || !(args[2] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.swapPoolUpdate((String) args[0], (String) args[1], (String) args[2]);

            case "getAll":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return SwapUtil.swapPoolGetAll((String) args[0]);

            default:
                return null;
        }
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[] {
                new FunctionInfo("create", "Create swap pool",
                        new String[] { "name: string" }, "String[]", "Swap"),
                new FunctionInfo("remove", "Delete swap pool",
                        new String[] { "name: string" }, "String[]", "Swap"),
                new FunctionInfo("add", "Add variable to swap pool",
                        new String[] { "varSpec: string", "poolName: string", "params: array" }, "String[]", "Swap"),
                new FunctionInfo("get", "Get variable from swap pool",
                        new String[] { "varName: string", "poolName: string" }, "Object", "Swap"),
                new FunctionInfo("removeVar", "Remove variable from swap pool",
                        new String[] { "varName: string", "poolName: string" }, "String[]", "Swap"),
                new FunctionInfo("lock", "Lock variable in swap pool",
                        new String[] { "varName: string", "poolName: string" }, "String[]", "Swap"),
                new FunctionInfo("unlock", "Unlock variable in swap pool",
                        new String[] { "varName: string", "poolName: string" }, "String[]", "Swap"),
                new FunctionInfo("update", "Update variable in swap pool",
                        new String[] { "varName: string", "poolName: string", "newValue: string" }, "String[]", "Swap"),
                new FunctionInfo("getAll", "Get all variables in swap pool",
                        new String[] { "poolName: string" }, "Object", "Swap")
        };
    }

    @Override
    public String getProviderName() {
        return "SwapFunctionProvider";
    }
}
