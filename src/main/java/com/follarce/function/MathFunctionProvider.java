package com.follarce.function;

import com.follarce.Constants;

import java.util.List;

/**
 * 数学函数提供者。
 * 命名空间: "math"
 */
public class MathFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "math";
    }

    @Override
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "sin":
                    return Math.sin(doubleArg(args, 0));

                case "cos":
                    return Math.cos(doubleArg(args, 0));

                case "sqrt":
                    return Math.sqrt(doubleArg(args, 0));

                case "random":
                    return Math.random();

                case "abs":
                    return Math.abs(doubleArg(args, 0));

                case "round":
                    return Math.round(doubleArg(args, 0));

                case "floor":
                    return Math.floor(doubleArg(args, 0));

                case "ceil":
                    return Math.ceil(doubleArg(args, 0));

                case "pow":
                    return Math.pow(doubleArg(args, 0), doubleArg(args, 1));

                case "max":
                    return Math.max(doubleArg(args, 0), doubleArg(args, 1));

                case "min":
                    return Math.min(doubleArg(args, 0), doubleArg(args, 1));

                case "pi":
                    return Math.PI;

                case "e":
                    return Math.E;

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    private static double doubleArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return 0.0;
        }
        Object val = args.get(index);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        if (val instanceof String) {
            try {
                return Double.parseDouble((String) val);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }
}
