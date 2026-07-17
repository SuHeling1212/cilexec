package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.SocketUtil;

import java.util.List;

/**
 * Socket 通信函数提供者。
 * 命名空间: "socket"
 */
public class SocketFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "socket";
    }

    @Override
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "connect":
                    return SocketUtil.socketConnect(getStringArg(args, 0), intArg(args, 1));

                case "send":
                    return SocketUtil.socketSend(intArg(args, 0), getStringArg(args, 1));

                case "receive":
                    return SocketUtil.socketReceive(intArg(args, 0));

                case "close":
                    return SocketUtil.socketClose(intArg(args, 0));

                case "bind":
                    return SocketUtil.socketBind(intArg(args, 0));

                case "accept":
                    return SocketUtil.socketAccept(intArg(args, 0));

                default:
                    return null;
            }
        } catch (Exception e) {
            throw new UnknownEffectOutcomeException(
                    "Socket operation outcome is unknown: " + e.getMessage(), e);
        }
    }

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return null;
        }
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }

    private static int intArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return 0;
        }
        Object val = args.get(index);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
