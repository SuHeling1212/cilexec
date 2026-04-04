package com.follarce.plugin;

import com.follarce.basicUtil.EnvVarUtil;

public class EnvVarFunctionProvider implements FunctionProvider {

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            case "setEnv":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return EnvVarUtil.setEnv((String) args[0], (String) args[1]);

            case "getEnv":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return EnvVarUtil.getEnv((String) args[0]);

            case "listEnv":
                return EnvVarUtil.listEnv();

            case "deleteEnv":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return EnvVarUtil.deleteEnv((String) args[0]);

            default:
                return null;
        }
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[] {
            new FunctionInfo("setEnv", "Set environment variable",
                new String[] { "name: string", "value: string" }, "String[]", "EnvVar"),
            new FunctionInfo("getEnv", "Get environment variable",
                new String[] { "name: string" }, "String[]", "EnvVar"),
            new FunctionInfo("listEnv", "List all environment variables",
                new String[] {}, "String[]", "EnvVar"),
            new FunctionInfo("deleteEnv", "Delete environment variable",
                new String[] { "name: string" }, "String[]", "EnvVar")
        };
    }

    @Override
    public String getProviderName() {
        return "EnvVarFunctionProvider";
    }
}
