package com.follarce.extension.builtin;

import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.FunctionContext;
import java.util.List;
import java.util.Map;

/**
 * 终端控制函数提供者。
 * 命名空间: "term"
 *
 * 提供 ANSI 转义码生成、屏幕控制等功能。
 */
public class TermFunctionProvider extends BuiltinFunctionProvider {

    private static final Map<String, String> COLORS = Map.of(
        "black",   "\u001B[30m",
        "red",     "\u001B[31m",
        "green",   "\u001B[32m",
        "yellow",  "\u001B[33m",
        "blue",    "\u001B[34m",
        "magenta", "\u001B[35m",
        "cyan",    "\u001B[36m",
        "white",   "\u001B[37m"
    );

    @Override
    public String getNamespace() { return "term"; }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {

                case "color":
                    if (args.isEmpty()) return "\u001B[0m";
                    return COLORS.getOrDefault(getStringArg(args, 0), "");

                case "paint":
                    String c = COLORS.getOrDefault(getStringArg(args, 0), "");
                    String t = getStringArg(args, 1);
                    return c + (t != null ? t : "") + "\u001B[0m";

                case "bold":
                    return "\u001B[1m";

                case "dim":
                    return "\u001B[2m";

                case "reset":
                    return "\u001B[0m";

                case "clear":
                    return "\u001B[2J\u001B[H";

                case "eraseLine":
                    return "\u001B[2K";

                case "cursorUp":
                    return "\u001B[A";

                case "cursorDown":
                    return "\u001B[B";

                case "cursorForward":
                    return "\u001B[C";

                case "cursorBack":
                    return "\u001B[D";

                // 简写：直接返回对应颜色的 ANSI 码
                case "red":     return "\u001B[31m";
                case "green":   return "\u001B[32m";
                case "blue":    return "\u001B[34m";
                case "yellow":  return "\u001B[33m";
                case "cyan":    return "\u001B[36m";
                case "magenta": return "\u001B[35m";
                case "white":   return "\u001B[37m";

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) return null;
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }
}
