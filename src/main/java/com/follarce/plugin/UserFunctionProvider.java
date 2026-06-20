package com.follarce.plugin;

import com.follarce.init.UserInit;

/**
 * User management function provider
 * Encapsulates script calling interface for UserInit
 */
public class UserFunctionProvider implements FunctionProvider {
    
    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            // User management functions
            case "createUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return error("USERNAME_MUST_BE_STRING");
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return error("PASSWORD_MUST_BE_STRING");
                }
                if (args.length < 3 || !(args[2] instanceof Boolean)) {
                    return error("ISLOCAL_MUST_BE_BOOLEAN");
                }
                if (args.length > 3) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.createUser((String) args[0], (String) args[1], (Boolean) args[2]);
                
            case "removeUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return error("USERNAME_MUST_BE_STRING");
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return error("PASSWORD_MUST_BE_STRING");
                }
                if (args.length > 2) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.removeUser((String) args[0], (String) args[1]);
                
            case "userExists":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return error("USERNAME_MUST_BE_STRING");
                }
                if (args.length > 1) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.userExists((String) args[0]);
                
            case "validateUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return error("USERNAME_MUST_BE_STRING");
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return error("PASSWORD_MUST_BE_STRING");
                }
                if (args.length > 2) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.validateUser((String) args[0], (String) args[1]);
                
            case "switchUser":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return error("USERNAME_MUST_BE_STRING");
                }
                if (args.length < 2 || !(args[1] instanceof String)) {
                    return error("PASSWORD_MUST_BE_STRING");
                }
                if (args.length > 2) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.switchUser((String) args[0], (String) args[1]);
                
            case "getCurrentUser":
                if (args.length > 0) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.getCurrentUser();
                
            case "isLocal":
                if (args.length > 0) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.isLocal();
                
            case "getListOfUsers":
                if (args.length > 0) {
                    return error("TOO_MANY_ARGUMENTS");
                }
                return UserInit.getListOfUsers();
                
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
            new FunctionInfo("createUser", "Create user",
                new String[]{"username: string", "password: string", "local: boolean"}, "String[]", "User"),
            new FunctionInfo("removeUser", "Remove user",
                new String[]{"username: string", "password: string"}, "String[]", "User"),
            new FunctionInfo("userExists", "Check if user exists",
                new String[]{"username: string"}, "boolean", "User"),
            new FunctionInfo("validateUser", "Validate user password",
                new String[]{"username: string", "password: string"}, "boolean", "User"),
            new FunctionInfo("switchUser", "Switch user",
                new String[]{"username: string", "password: string"}, "String[]", "User"),
            new FunctionInfo("getCurrentUser", "Get current user",
                new String[]{}, "String", "User"),
            new FunctionInfo("isLocal", "Check if current user is local",
                new String[]{}, "boolean", "User"),
            new FunctionInfo("getListOfUsers", "Get list of all users",
                new String[]{}, "Map", "User")
        };
    }
    
    @Override
    public String getProviderName() {
        return "UserFunctionProvider";
    }
}
