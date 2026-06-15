package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.FileUtil;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件操作函数提供者。
 * 命名空间: "file"
 * <p>
 * 每个操作前都会检查当前用户对目标路径的权限。
 * local 用户自动绕过所有权限检查。
 */
public class FileFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "file";
    }

    /**
     * 检查权限，无权则抛出异常。
     */
    private static void checkPerm(String path, String operation, FunctionContext ctx) {
        FileUtil.PermissionResult pr = FileUtil.validatePermission(path, operation, ctx.getCurrentUser());
        if (!pr.granted) {
            throw new RuntimeException(pr.message);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {

                case "read": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_READ, context);
                    return FileUtil.read(path);
                }

                case "write": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.write(path, getStringArg(args, 1));
                    return "";
                }

                case "append": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.append(path, getStringArg(args, 1));
                    return "";
                }

                case "createFile": {
                    String dir = getStringArg(args, 0);
                    String name = getStringArg(args, 1);
                    checkPerm(dir, Constants.PERM_WRITE, context);
                    FileUtil.createFile(dir, name);
                    return "";
                }

                case "removeFile": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.removeFile(path);
                    return "";
                }

                case "createDir": {
                    String dir = getStringArg(args, 0);
                    checkPerm(dir, Constants.PERM_WRITE, context);
                    FileUtil.createDirectory(dir, getStringArg(args, 1));
                    return "";
                }

                case "removeDir": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.removeDirectory(path);
                    return "";
                }

                case "rename": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.Rename(path, getStringArg(args, 1));
                    return "";
                }

                case "listdir": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_READ, context);
                    List<Map<String, Object>> listing = FileUtil.getListOfFileAndDirectory(path);
                    return formatListing(listing);
                }

                case "link": {
                    String dir = getStringArg(args, 0);
                    checkPerm(dir, Constants.PERM_WRITE, context);
                    FileUtil.Link(dir, getStringArg(args, 1));
                    return "";
                }

                case "lock": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.lock(path, context.getPid());
                    return "";
                }

                case "unlock": {
                    String path = getStringArg(args, 0);
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.unlock(path, context.getPid(), context.getCurrentUser());
                    return "";
                }

                case "exists": {
                    String path = getStringArg(args, 0);
                    // exists 不需要权限检查
                    return FileUtil.exists(path);
                }

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) return "";
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }

    private static String formatListing(List<Map<String, Object>> listing) {
        if (listing == null || listing.isEmpty()) return "(empty)";
        return listing.stream()
                .map(entry -> {
                    String name = (String) entry.get("name");
                    boolean isDir = Boolean.TRUE.equals(entry.get("isDirectory"));
                    long size = entry.containsKey("size") ? ((Number) entry.get("size")).longValue() : 0;
                    return (isDir ? "[DIR]  " : "[FILE] ") + name + " (" + size + " bytes)";
                })
                .collect(Collectors.joining("\n"));
    }
}
