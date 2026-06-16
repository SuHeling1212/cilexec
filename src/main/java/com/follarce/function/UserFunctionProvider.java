package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.UserUtil;

import java.util.List;

/**
 * 用户管理函数提供者。
 * 命名空间: "user"（空字符串作为备用）
 */
public class UserFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "user";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            // 只有 local 用户可以管理用户
            boolean isLocal = "local".equals(context.getCurrentUser());

            switch (functionName) {
                case "createUser":
                    if (!isLocal) {
                        return new String[]{Constants.ERROR_MARKER, "Permission denied: only local can create users"};
                    }
                    return UserUtil.createUser(getStringArg(args, 0), getStringArg(args, 1), false);

                case "removeUser":
                    if (!isLocal) {
                        return new String[]{Constants.ERROR_MARKER, "Permission denied: only local can remove users"};
                    }
                    return UserUtil.removeUser(getStringArg(args, 0), getStringArg(args, 1));

                case "switchUser":
                    return UserUtil.switchUser(getStringArg(args, 0), getStringArg(args, 1));

                case "validateUser":
                    return UserUtil.validateUser(getStringArg(args, 0), getStringArg(args, 1));

                case "getCurrentUser":
                    return UserUtil.getCurrentUser();

                case "isLocal":
                    return UserUtil.isLocal();

                case "getListOfUsers":
                    return UserUtil.getListOfUsers().toString();

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
