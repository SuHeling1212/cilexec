package com.follarce.function;

import com.follarce.Constants;
import com.follarce.script.FunctionDef;

import java.util.*;

/**
 * 函数注册中心 —— 管理所有内建函数 Provider 和用户自定义函数。
 */
public class FunctionRegistry {

    private static final List<FunctionProvider> providers = new ArrayList<>();
    private static final Map<String, FunctionDef> userFunctions = new LinkedHashMap<>();

    private FunctionRegistry() {}

    /**
     * 注册一个函数提供者。
     */
    public static void registerProvider(FunctionProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null");
        }
        providers.add(provider);
    }

    /**
     * 注册一个用户自定义函数。
     */
    public static void registerUserFunction(String name, FunctionDef def) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Function name must not be empty");
        }
        userFunctions.put(name, def);
    }

    /**
     * 获取用户自定义函数。
     */
    public static FunctionDef getUserFunction(String name) {
        return userFunctions.get(name);
    }

    /**
     * 检查用户自定义函数是否已存在。
     */
    public static boolean hasUserFunction(String name) {
        return userFunctions.containsKey(name);
    }

    /**
     * 清除所有用户自定义函数。
     */
    public static void clearUserFunctions() {
        userFunctions.clear();
    }

    /**
     * 调用函数。
     * <ol>
     *   <li>先检查是否有命名空间（含点号），如有则解析为 namespace + funcName</li>
     *   <li>遍历所有 providers，先用全名匹配，再用短名匹配</li>
     *   <li>如果是用户函数，返回特殊标记让 ProcessRunner 处理</li>
     * </ol>
     *
     * @param name    函数名称（可含命名空间前缀，如 "file.read"）
     * @param args    参数列表
     * @param context 调用上下文
     * @return 函数执行结果
     */
    public static Object call(String name, List<Object> args, FunctionContext context) {
        if (name == null || name.isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "Function name cannot be empty"};
        }

        String namespace = null;
        String functionName = name;

        // 1. 解析命名空间
        int dotIndex = name.indexOf('.');
        if (dotIndex > 0) {
            namespace = name.substring(0, dotIndex);
            functionName = name.substring(dotIndex + 1);
        }

        // 2. 遍历所有 providers 匹配（全名 / 空命名空间精确匹配）
        for (FunctionProvider provider : providers) {
            String providerNs = provider.getNamespace();

            // 全名匹配：命名空间和函数名都匹配
            if (namespace != null && !namespace.isEmpty()) {
                if (namespace.equals(providerNs) || providerNs == null || providerNs.isEmpty()) {
                    Object result = safeCall(provider, functionName, args, context);
                    if (result != null) {
                        return result;
                    }
                }
            } else {
                // 无命名空间：先精确匹配短名，再尝试空命名空间 provider
                if (providerNs == null || providerNs.isEmpty()) {
                    Object result = safeCall(provider, functionName, args, context);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }

        // 3. 无命名空间时：优先检查用户函数（用户定义的函数应优先于短名 provider 回退）
        if (namespace == null || namespace.isEmpty()) {
            if (userFunctions.containsKey(name)) {
                return "USER:" + name;
            }
        }

        // 4. 无命名空间时：短名回退 —— 匹配非空命名空间 provider
        if (namespace == null || namespace.isEmpty()) {
            for (FunctionProvider provider : providers) {
                String providerNs = provider.getNamespace();
                if (providerNs != null && !providerNs.isEmpty()) {
                    Object result = safeCall(provider, functionName, args, context);
                    // (debug output removed)
                    if (result != null) {
                        return result;
                    }
                }
            }
        }

        // 5. 有命名空间时：检查用户函数（仅当命名空间 provider 未找到时）
        if (namespace != null && !namespace.isEmpty()) {
            if (userFunctions.containsKey(name)) {
                return "USER:" + name;
            }
        }

        return new String[]{Constants.ERROR_MARKER, "Function not found: " + name};
    }

    /**
     * 安全调用 provider，捕获异常并转换为错误结果。
     *
     * @return 如果 provider 不识别该函数名则返回 null；否则返回执行结果。
     */
    private static Object safeCall(FunctionProvider provider, String functionName,
                                    List<Object> args, FunctionContext context) {
        try {
            Object result = provider.call(functionName, args, context);
            // provider 返回 null 表示不识别此函数
            if (result == null) {
                return null;
            }
            // provider 返回错误标记
            if (result instanceof Object[] || result instanceof String[]) {
                Object[] arr = (Object[]) result;
                if (arr.length > 0 && Constants.ERROR_MARKER.equals(arr[0])) {
                    return result;
                }
            }
            return result;
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    /**
     * 检查返回结果是否为错误。
     * 约定：返回 Object[] 或 String[] 且第一个元素为 "ERROR" 即为错误。
     *
     * @param result 待检查的结果
     * @return 如果是错误结果则返回 true
     */
    public static boolean isErrorResult(Object result) {
        if (result == null) {
            return true;
        }
        if (result instanceof Object[]) {
            Object[] arr = (Object[]) result;
            return arr.length > 0 && Constants.ERROR_MARKER.equals(arr[0]);
        }
        return false;
    }
}
