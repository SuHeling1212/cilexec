package com.follarce.plugin;

import com.follarce.basicUtil.JsonUtil;
import com.follarce.basicUtil.TimeUtil;

import java.util.List;
import java.util.Map;

/**
 * Utility function provider
 * Provides time, JSON, type conversion and other utility functions
 */
public class UtilFunctionProvider implements FunctionProvider {
    
    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            // Time functions
            case "now":
                return TimeUtil.getTime();
                
            // JSON functions
            case "parseJson":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return error("INVALID_ARGUMENTS");
                }
                return JsonUtil.readJson((String) args[0]);
                
            case "toJson":
                if (args.length < 1) {
                    return error("INVALID_ARGUMENTS");
                }
                return JsonUtil.toJson(args[0]);
                
            // Type conversion functions
            case "int":
                if (args.length < 1) return 0;
                if (args[0] instanceof String) {
                    try {
                        return Integer.parseInt((String) args[0]);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
                if (args[0] instanceof Number) {
                    return ((Number) args[0]).intValue();
                }
                return 0;
                
            case "str":
                if (args.length < 1) return "";
                return args[0].toString();
                
            case "len":
                if (args.length < 1) return 0;
                Object obj = args[0];
                if (obj instanceof List) {
                    return ((List<?>) obj).size();
                }
                if (obj instanceof Map) {
                    return ((Map<?, ?>) obj).size();
                }
                if (obj instanceof String) {
                    return ((String) obj).length();
                }
                if (obj instanceof Object[]) {
                    return ((Object[]) obj).length;
                }
                return 1;

            // Sleep function
            case "sleep":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                long millis = 0;
                if (args[0] instanceof Number) {
                    millis = ((Number) args[0]).longValue();
                } else if (args[0] instanceof String) {
                    try {
                        millis = Long.parseLong((String) args[0]);
                    } catch (NumberFormatException e) {
                        return error("INVALID_ARGUMENTS");
                    }
                }
                try {
                    Thread.sleep(millis);
                    return new String[]{"SUCCESS"};
                } catch (InterruptedException e) {
                    return error("INTERRUPTED");
                }

            default:
                return null;
        }
    }
    
    private String[] error(String code) {
        return new String[]{"ERROR", code};
    }
    
    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo("now", "Get current time",
                new String[]{}, "int[]", "Util"),
            new FunctionInfo("parseJson", "Parse JSON string",
                new String[]{"json: string"}, "Object", "Util"),
            new FunctionInfo("toJson", "Convert to JSON string",
                new String[]{"obj: any"}, "String", "Util"),
            new FunctionInfo("int", "Convert to integer",
                new String[]{"value: any"}, "int", "Util"),
            new FunctionInfo("str", "Convert to string",
                new String[]{"value: any"}, "String", "Util"),
            new FunctionInfo("len", "Get length",
                new String[]{"collection: array/map/string"}, "int", "Util"),
            new FunctionInfo("sleep", "Sleep for milliseconds",
                new String[]{"millis: int"}, "String[]", "Util")
        };
    }
    
    @Override
    public String getProviderName() {
        return "UtilFunctionProvider";
    }
}
