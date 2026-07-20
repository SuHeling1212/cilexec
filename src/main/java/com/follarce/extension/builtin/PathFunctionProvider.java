package com.follarce.extension.builtin;

import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.FunctionContext;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.util.List;
import java.util.Map;

/**
 * 路径与环境变量函数提供者。
 * 命名空间: "path"
 */
public class PathFunctionProvider extends BuiltinFunctionProvider {

    @Override
    public String getNamespace() {
        return "path";
    }

    @Override
    public Object call(
            String functionName,
            List<Object> args,
            FunctionContext context
    ) {
        try {
            switch (functionName) {
                case "resolve":
                    return context.resolvePath(
                            getStringArg(args, 0)
                    );

                case "normalize":
                    return PathUtil.normalizePath(
                            getStringArg(args, 0)
                    );

                case "getFileName":
                    return PathUtil.getFileName(
                            context.resolvePath(
                                    getStringArg(args, 0)
                            )
                    );

                case "getParentPath":
                    return PathUtil.getParentPath(
                            context.resolvePath(
                                    getStringArg(args, 0)
                            )
                    );

                case "getEnvVar": {
                    String envName = getStringArg(args, 0);

                    if ("PACKAGE_DATA".equals(envName)) {
                        String packageDataPath = context.getPackageDataPath();
                        if (packageDataPath == null || packageDataPath.isBlank()) {
                            return new String[]{
                                    Constants.ERROR_MARKER,
                                    "PACKAGE_DATA is only available while package code is running"
                            };
                        }
                        return packageDataPath;
                    }

                    if ("HOME".equals(envName)) {
                        return Constants.USER_HOME_PREFIX
                                + context.getCurrentUser();
                    }

                    return getEnvVar(envName);
                }

                case "getAlias":
                    return context.getPathAliases().get(
                            getStringArg(args, 0)
                    );

                case "listAliases":
                    return context.getPathAliases();

                case "setAlias": {
                    String name = getStringArg(args, 0);
                    String value = context.resolvePath(
                            getStringArg(args, 1)
                    );

                    validateAliasName(name);
                    context.setPathAlias(name, value);

                    return value;
                }

                case "removeAlias":
                    context.removePathAlias(
                            getStringArg(args, 0)
                    );
                    return true;

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{
                    Constants.ERROR_MARKER,
                    getErrorMessage(e)
            };
        }
    }

    /**
     * 从 env.json 读取环境变量。
     */
    private static Object getEnvVar(String name) {
        if (name == null || name.isBlank()) {
            return new String[]{
                    Constants.ERROR_MARKER,
                    "Environment variable name cannot be empty"
            };
        }

        String envPath = Constants.SYSTEM_CONFIG_PATH
                + Constants.CONFIG_ENV_JSON;

        if (!FileUtil.exists(envPath)) {
            return new String[]{
                    Constants.ERROR_MARKER,
                    "Environment config not found"
            };
        }

        String content = FileUtil.read(envPath);

        if (content == null || content.isBlank()) {
            return new String[]{
                    Constants.ERROR_MARKER,
                    "Environment config is empty"
            };
        }

        Object parsed = JsonUtil.parseJson(content);

        /*
         * Map<?, ?> 不要求进行未经检查的泛型强制转换，
         * 因此不再需要 @SuppressWarnings("unchecked")。
         */
        if (!(parsed instanceof Map<?, ?> env)) {
            return new String[]{
                    Constants.ERROR_MARKER,
                    "Invalid environment config format"
            };
        }

        if (!env.containsKey(name)) {
            return new String[]{
                    Constants.ERROR_MARKER,
                    "Environment variable not found: " + name
            };
        }

        Object value = env.get(name);

        if (value == null) {
            return new String[]{
                    Constants.ERROR_MARKER,
                    "Environment variable is null: " + name
            };
        }

        return value.toString();
    }

    /**
     * 获取字符串参数。
     */
    private static String getStringArg(
            List<Object> args,
            int index
    ) {
        if (args == null || index < 0 || index >= args.size()) {
            return null;
        }

        Object value = args.get(index);
        return value != null ? value.toString() : null;
    }

    /**
     * 验证路径别名名称。
     */
    private static void validateAliasName(String name) {
        if (name == null
                || !name.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Invalid alias name: " + name
            );
        }
    }

    /**
     * 避免异常消息本身为 null。
     */
    private static String getErrorMessage(Exception exception) {
        String message = exception.getMessage();

        return message != null
                ? message
                : exception.getClass().getSimpleName();
    }
}
