package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.JsonUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Scanner;

/**
 * 通用工具函数提供者。
 * 命名空间: "util"（空字符串作为备用）
 */
public class UtilFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "util";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "print":
                    if (args != null && !args.isEmpty()) {
                        System.out.println(args.get(0));
                    } else {
                        System.out.println();
                    }
                    return "";

                case "println":
                    if (args != null && !args.isEmpty()) {
                        System.out.println(args.get(0));
                    } else {
                        System.out.println();
                    }
                    return "";

                case "input":
                    if (args != null && !args.isEmpty()) {
                        System.out.print(args.get(0));
                    }
                    return readInput();

                case "toJson":
                    if (args == null || args.isEmpty()) {
                        return JsonUtil.toJson(null);
                    }
                    return JsonUtil.toJson(args.get(0));

                case "fromJson":
                    return JsonUtil.parseJson(getStringArg(args, 0));

                case "typeOf":
                    if (args == null || args.isEmpty()) {
                        return "null";
                    }
                    return args.get(0).getClass().getName();

                case "isArray":
                    return args != null && !args.isEmpty() && args.get(0) instanceof List;

                case "isMap":
                    return args != null && !args.isEmpty() && args.get(0) instanceof java.util.Map;

                case "isNumber":
                    return args != null && !args.isEmpty() && args.get(0) instanceof Number;

                case "isString":
                    return args != null && !args.isEmpty() && args.get(0) instanceof String;

                case "isBool":
                    return args != null && !args.isEmpty() && args.get(0) instanceof Boolean;

                case "toString":
                    if (args == null || args.isEmpty()) {
                        return "null";
                    }
                    return String.valueOf(args.get(0));

                case "exit":
                    return "EXIT";

                case "sleep":
                    if (args != null && !args.isEmpty()) {
                        long ms = ((Number) args.get(0)).longValue();
                        Thread.sleep(ms);
                    }
                    return "";

                case "getTime": {
                    LocalDateTime now = LocalDateTime.now();
                    int year = now.getYear();
                    int month = now.getMonthValue();
                    int day = now.getDayOfMonth();
                    int hour = now.getHour();
                    int minute = now.getMinute();
                    int second = now.getSecond();
                    int nano = now.getNano();
                    int millis = nano / 1_000_000;
                    return new int[] { year, month, day, hour, minute, second, millis };
                }

                default:
                    return null;
            }
        } catch (Exception e) {
            if ("input".equals(functionName)) {
                throw new UnknownEffectOutcomeException("Input outcome is unknown: " + e.getMessage(), e);
            }
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    private static String readInput() {
        try {
            Scanner scanner = new Scanner(System.in);
            if (scanner.hasNextLine()) {
                return scanner.nextLine();
            }
            return "";
        } catch (Exception e) {
            throw new RuntimeException("Input error: " + e.getMessage());
        }
    }

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return null;
        }
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }
}
