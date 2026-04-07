package com.follarce.plugin;

import com.follarce.basicUtil.PathUtil;
import java.util.Map;

public class PathFunctionProvider implements FunctionProvider {

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            case "resolvePath":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return PathUtil.resolvePath((String) args[0]);

            case "getPathAlias":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                Map<String, String> aliases = PathUtil.getAllPathAliases();
                String aliasTarget = aliases.get(args[0]);
                if (aliasTarget == null) {
                    return new String[] { "ERROR", "ALIAS_NOT_FOUND" };
                }
                return aliasTarget;

            case "listPathAliases":
                return PathUtil.getAllPathAliases();

            case "setPathAlias":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return PathUtil.setPathAlias((String) args[0], (String) args[1]);

            case "getSysEnv":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                String envValue = PathUtil.getEnvVar((String) args[0]);
                if (envValue == null) {
                    return new String[] { "ERROR", "ENV_NOT_FOUND" };
                }
                return envValue;

            case "listSysEnv":
                return PathUtil.getAllEnvVars();

            case "setSysEnv":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof String)) {
                    return new String[] { "ERROR", "INVALID_ARGUMENTS" };
                }
                return PathUtil.setEnvVar((String) args[0], (String) args[1]);

            default:
                return null;
        }
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[] {
            new FunctionInfo("resolvePath", "Resolve path aliases (e.g., ~ -> /user/local)",
                new String[] { "path: string" }, "string", "Path"),
            new FunctionInfo("getPathAlias", "Get the target of a path alias",
                new String[] { "alias: string" }, "string", "Path"),
            new FunctionInfo("listPathAliases", "List all path aliases",
                new String[] {}, "Map", "Path"),
            new FunctionInfo("setPathAlias", "Set a path alias",
                new String[] { "alias: string", "target: string" }, "String[]", "Path"),
            new FunctionInfo("getSysEnv", "Get system environment variable",
                new String[] { "name: string" }, "string", "Path"),
            new FunctionInfo("listSysEnv", "List all system environment variables",
                new String[] {}, "Map", "Path"),
            new FunctionInfo("setSysEnv", "Set system environment variable",
                new String[] { "name: string", "value: string" }, "String[]", "Path")
        };
    }

    @Override
    public String getProviderName() {
        return "PathFunctionProvider";
    }
}
