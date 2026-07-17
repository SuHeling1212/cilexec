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
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_READ, context);
                    return FileUtil.read(path);
                }

                case "write": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    Long token = getLongArg(args, 2);
                    if (context.getEffectId() != null) {
                        FileUtil.writeOnce(path, getStringArg(args, 1), context.getEffectId(),
                                context.getPid(), context.getProcessGeneration(), token);
                    } else if (token != null && context.getProcessGeneration() != null) {
                        FileUtil.write(path, getStringArg(args, 1), context.getPid(),
                                context.getProcessGeneration(), token);
                    } else {
                        FileUtil.write(path, getStringArg(args, 1));
                    }
                    return "";
                }

                case "append": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    Long token = getLongArg(args, 2);
                    if (context.getEffectId() != null) {
                        FileUtil.appendOnce(path, getStringArg(args, 1), context.getEffectId(),
                                context.getPid(), context.getProcessGeneration(), token);
                    } else if (token != null && context.getProcessGeneration() != null) {
                        FileUtil.append(path, getStringArg(args, 1), context.getPid(),
                                context.getProcessGeneration(), token);
                    } else {
                        FileUtil.append(path, getStringArg(args, 1));
                    }
                    return "";
                }

                case "createFile": {
                    String dir = context.resolvePath(getStringArg(args, 0));
                    String name = getStringArg(args, 1);
                    checkPerm(dir, Constants.PERM_WRITE, context);
                    if (context.getEffectId() != null) {
                        FileUtil.createFileOnce(dir, name, context.getEffectId(), context.getCurrentUser());
                    } else {
                        FileUtil.createFile(dir, name);
                    }
                    return "";
                }

                case "removeFile": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.removeFile(path);
                    return "";
                }

                case "createDir": {
                    String dir = context.resolvePath(getStringArg(args, 0));
                    checkPerm(dir, Constants.PERM_WRITE, context);
                    FileUtil.createDirectory(dir, getStringArg(args, 1));
                    return "";
                }

                case "removeDir": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.removeDirectory(path);
                    return "";
                }

                case "rename": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    FileUtil.rename(path, getStringArg(args, 1));
                    return "";
                }

                case "listdir": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_READ, context);
                    List<Map<String, Object>> listing = FileUtil.getListOfFileAndDirectory(path);
                    return formatListing(listing);
                }

                case "link": {
                    String dir = context.resolvePath(getStringArg(args, 0));
                    checkPerm(dir, Constants.PERM_WRITE, context);
                    FileUtil.link(dir, context.resolvePath(getStringArg(args, 1)));
                    return "";
                }

                case "lock": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    if (context.getProcessGeneration() == null) {
                        FileUtil.lock(path, context.getPid());
                        return "";
                    }
                    long lease = getLongArg(args, 1) != null
                            ? getLongArg(args, 1) : Constants.DEFAULT_FILE_LOCK_LEASE_MS;
                    FileUtil.LockHandle handle = FileUtil.acquireLock(path, context.getPid(),
                            context.getProcessGeneration(), lease);
                    return Map.of("fencingToken", handle.fencingToken(),
                            "leaseUntilEpochMs", handle.leaseUntilEpochMs());
                }

                case "renewLock": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    Long token = getLongArg(args, 1);
                    if (token == null || context.getProcessGeneration() == null) {
                        throw new IllegalArgumentException("renewLock requires a fencing token");
                    }
                    long lease = getLongArg(args, 2) != null
                            ? getLongArg(args, 2) : Constants.DEFAULT_FILE_LOCK_LEASE_MS;
                    FileUtil.LockHandle renewed = FileUtil.renewLock(path, context.getPid(),
                            context.getProcessGeneration(), token, lease);
                    return Map.of("fencingToken", renewed.fencingToken(),
                            "leaseUntilEpochMs", renewed.leaseUntilEpochMs());
                }

                case "unlock": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_WRITE, context);
                    Long token = getLongArg(args, 1);
                    if (token != null && context.getProcessGeneration() != null) {
                        FileUtil.unlock(path, context.getPid(), context.getProcessGeneration(),
                                token, context.getCurrentUser());
                    } else {
                        FileUtil.unlock(path, context.getPid(), context.getCurrentUser());
                    }
                    return "";
                }

                case "exists": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    // exists 不需要权限检查
                    return FileUtil.exists(path);
                }

                case "readMetaData": {
                    String path = context.resolvePath(getStringArg(args, 0));
                    checkPerm(path, Constants.PERM_READ, context);
                    Map<String, Object> meta;
                    if (FileUtil.isDirectory(path)) {
                        meta = FileUtil.readDirectoryMetaData(path);
                    } else {
                        meta = FileUtil.readFileMetaData(path);
                    }
                    return meta != null ? meta : "ERROR: No metadata found";
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

    private static Long getLongArg(List<Object> args, int index) {
        if (args == null || index >= args.size() || args.get(index) == null) return null;
        Object value = args.get(index);
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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
