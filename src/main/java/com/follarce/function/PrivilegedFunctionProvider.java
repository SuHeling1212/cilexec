package com.follarce.function;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.PathUtil;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 特权函数提供者 —— 仅供 local 用户使用。
 * 命名空间: "system"
 * <p>
 * 提供：直接 Java 反射调用、强制文件操作、系统重置等。
 */
public class PrivilegedFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "system";
    }

    @Override
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        // 权限检查：只有 local 用户可以使用特权函数
        if (!"local".equals(context.getCurrentUser())) {
            return new String[]{Constants.ERROR_MARKER, "Privileged function 'system." + functionName
                    + "' requires local user (current: " + context.getCurrentUser() + ")"};
        }

        try {
            switch (functionName) {

                // ────────────────────────────────────────
                // system.invoke("类名", "方法名", arg1, arg2, ...)
                // 通过 Java 反射调用任意静态方法
                // 示例: system.invoke("com.follarce.util.FileUtil", "removeFile", "/system/process/2.json")
                // ────────────────────────────────────────
                case "invoke": {
                    if (args == null || args.size() < 2) {
                        return "ERROR: system.invoke requires className, methodName, and optional args";
                    }
                    String className = args.get(0).toString();
                    String methodName = args.get(1).toString();

                    // 提取实际参数（从 index 2 开始）
                    Object[] methodArgs = new Object[args.size() - 2];
                    Class<?>[] paramTypes = new Class<?>[args.size() - 2];
                    for (int i = 2; i < args.size(); i++) {
                        Object val = args.get(i);
                        methodArgs[i - 2] = val;
                        if (val instanceof String) paramTypes[i - 2] = String.class;
                        else if (val instanceof Integer) paramTypes[i - 2] = Integer.class;
                        else if (val instanceof Double) paramTypes[i - 2] = Double.class;
                        else if (val instanceof Boolean) paramTypes[i - 2] = Boolean.class;
                        else if (val instanceof List) paramTypes[i - 2] = List.class;
                        else paramTypes[i - 2] = val.getClass();
                    }

                    Class<?> clazz = Class.forName(className);
                    Method method = clazz.getMethod(methodName, paramTypes);
                    Object result = method.invoke(null, methodArgs);
                    Logger.info("system.invoke: " + className + "." + methodName + "() = " + result);
                    return result != null ? result : "OK";
                }

                // ────────────────────────────────────────
                // system.forceRemove(path)
                // 强制删除文件（跳过锁检查和权限检查）
                // ────────────────────────────────────────
                case "forceRemove": {
                    String path = getStringArg(args, 0);
                    if (path == null) return "ERROR: path required";
                    String realPath = PathUtil.toRealPath(path);
                    File file = new File(realPath);
                    if (deleteRecursive(file)) {
                        Logger.info("system.forceRemove: " + path);
                        return "Removed: " + path;
                    }
                    return "ERROR: Failed to remove: " + path;
                }

                // ────────────────────────────────────────
                // system.kill(pid)
                // 强制终止进程（直接删除进程文件，跳过权限检查）
                // ────────────────────────────────────────
                case "kill": {
                    int pid = getIntArg(args, 0);
                    String processPath = PathUtil.findProcessFilePathByPid(pid);
                    if (processPath == null) return "ERROR: Process not found: " + pid;
                    String realPath = PathUtil.toRealPath(processPath);
                    File file = new File(realPath);
                    if (file.delete()) {
                        Logger.info("system.kill: PID " + pid);
                        return "Killed PID " + pid;
                    }
                    return "ERROR: Process not found: " + pid;
                }

                // ────────────────────────────────────────
                // system.reset()
                // 重置整个文件系统（删除 VFS 根目录下所有内容）
                // ────────────────────────────────────────
                case "reset": {
                    File vfsRoot = PathUtil.getVfsRoot();
                    if (vfsRoot == null) return "ERROR: VFS root not set";
                    if (deleteRecursive(vfsRoot)) {
                        Logger.info("system.reset: VFS root deleted");
                        return "VFS root deleted. Restart to reinitialize.";
                    }
                    return "ERROR: Reset failed";
                }

                // ────────────────────────────────────────
                // system.exec(cmd)
                // 在宿主机上执行 Shell 命令（仅限 local 用户）
                // ────────────────────────────────────────
                case "exec": {
                    String cmd = getStringArg(args, 0);
                    if (cmd == null) return "ERROR: command required";
                    Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
                    int exitCode = process.waitFor();
                    String output = new String(process.getInputStream().readAllBytes());
                    Logger.info("system.exec: exit=" + exitCode + " cmd=" + cmd);
                    return "Exit: " + exitCode + "\n" + output;
                }

                // ────────────────────────────────────────
                // system.ls(path)
                // 直接在宿主机上列出目录内容
                // ────────────────────────────────────────
                case "ls": {
                    String path = getStringArg(args, 0);
                    if (path == null) path = ".";
                    String realPath = PathUtil.toRealPath(path);
                    File dir = new File(realPath);
                    File[] files = dir.listFiles();
                    if (files == null) return "(empty or not a directory)";
                    StringBuilder sb = new StringBuilder();
                    for (File f : files) {
                        sb.append(f.isDirectory() ? "[DIR] " : "[FILE] ")
                                .append(f.getName()).append(" (").append(f.length()).append("B)\n");
                    }
                    return sb.toString().trim();
                }

                default:
                    return null; // 不识别此函数
            }
        } catch (Exception e) {
            Logger.error("system." + functionName + " error: " + e.getMessage());
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    private static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) return null;
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }

    private static int getIntArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) return 0;
        Object val = args.get(index);
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }
}
