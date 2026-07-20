package com.follarce.extension.builtin;

import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.FunctionContext;
import com.follarce.kernel.api.function.UnknownEffectOutcomeException;
import com.follarce.kernel.security.UserUtil;

import java.util.List;

/**
 * 用户管理函数提供者。
 * 命名空间: "user"（空字符串作为备用）
 */
public class UserFunctionProvider extends BuiltinFunctionProvider {

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
                    return UserUtil.createUser(getStringArg(args, 0), getStringArg(args, 1), false,
                            context.getEffectId());

                case "removeUser":
                    if (!isLocal) {
                        return new String[]{Constants.ERROR_MARKER, "Permission denied: only local can remove users"};
                    }
                    return UserUtil.removeUser(getStringArg(args, 0), getStringArg(args, 1),
                            context.getEffectId());

                case "switchUser":
                    String username = getStringArg(args, 0);
                    if (!UserUtil.validateUser(username, getStringArg(args, 1))) {
                        return new String[]{Constants.ERROR_MARKER, "Invalid credentials for user: " + username};
                    }
                    context.setEffectiveUser(username);
                    return "Switched to user: " + username;

                case "validateUser":
                    return UserUtil.validateUser(getStringArg(args, 0), getStringArg(args, 1));

                case "getCurrentUser":
                    return context.getCurrentUser();

                case "isLocal":
                    return Constants.DEFAULT_USER_LOCAL.equals(context.getCurrentUser());

                case "getListOfUsers":
                    return UserUtil.getListOfUsers().keySet().toString();

                default:
                    return null;
            }
        } catch (Exception e) {
            if ("createUser".equals(functionName) || "removeUser".equals(functionName)) {
                throw new UnknownEffectOutcomeException(
                        "User transaction outcome is unknown: " + e.getMessage(), e);
            }
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
