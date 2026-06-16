package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.NetworkUtil;

import java.util.List;

/**
 * 网络请求函数提供者。
 * 命名空间: "network"
 */
public class NetworkFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "network";
    }

    @Override
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "httpGet":
                case "webget":
                    return NetworkUtil.httpGet(getStringArg(args, 0));

                case "httpPost":
                case "webpost":
                    return NetworkUtil.httpPost(getStringArg(args, 0), getStringArg(args, 1));

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
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
