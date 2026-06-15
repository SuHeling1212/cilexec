package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.util.List;
import java.util.Map;

/**
 * 路径与环境变量函数提供者。
 * 命名空间: "path"
 */
public class PathFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "path";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "resolve":
                    return PathUtil.resolvePath(getStringArg(args, 0));

                case "normalize":
                    return PathUtil.normalizePath(getStringArg(args, 0));

                case "getFileName":
                    return PathUtil.getFileName(getStringArg(args, 0));

                case "getParentPath":
                    return PathUtil.getParentPath(getStringArg(args, 0));

                case "getEnvVar":
                    return getEnvVar(getStringArg(args, 0));

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    /**
     * 从 env.json 读取环境变量。
     */
    private static Object getEnvVar(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Environment variable name cannot be empty"};
        }

        String envPath = Constants.SYSTEM_CONFIG_PATH + Constants.CONFIG_ENV_JSON;
        if (!FileUtil.exists(envPath)) {
            return new String[]{Constants.ERROR_MARKER, "Environment config not found"};
        }

        String content = FileUtil.read(envPath);
        if (content == null || content.trim().isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Environment config is empty"};
        }

        Object parsed = JsonUtil.parseJson(content);
        if (parsed instanceof Map) {
            Map<String, Object> env = (Map<String, Object>) parsed;
            Object value = env.get(name);
            if (value == null) {
                return new String[]{Constants.ERROR_MARKER, "Environment variable not found: " + name};
            }
            return value.toString();
        }

        return new String[]{Constants.ERROR_MARKER, "Invalid environment config format"};
    }

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return null;
        }
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }
}
