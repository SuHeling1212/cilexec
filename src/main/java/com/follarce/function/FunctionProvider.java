package com.follarce.function;

import java.util.List;

/**
 * 函数提供者接口 —— 所有插件函数通过此接口注册。
 */
public interface FunctionProvider {

    /**
     * 命名空间名称。
     * 如 "file", "process", "swapPool", "user", "util", "math", "network", "socket", "path", "io"。
     */
    String getNamespace();

    /**
     * 调用函数。
     * @param functionName 函数名（不含命名空间）
     * @param args 参数列表
     * @param context 调用上下文
     * @return 返回结果，失败返回 String[] 以 "ERROR" 开头
     */
    Object call(String functionName, List<Object> args, FunctionContext context);
}
