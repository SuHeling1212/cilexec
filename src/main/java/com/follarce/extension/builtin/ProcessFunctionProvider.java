package com.follarce.extension.builtin;

import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.FunctionContext;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 进程控制函数提供者。
 * 命名空间: "process"（空字符串也可作为默认匹配）
 *
 * 对于 fork/exec/kill/wait 等操作，返回特殊标记字符串。
 * ProcessRunner 会检测这些标记并执行对应操作。
 */
public class ProcessFunctionProvider extends BuiltinFunctionProvider {

    @Override
    public String getNamespace() {
        return "process";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "fork":
                    return "FORK";

                case "exec":
                    return buildExecMarker(args);

                case "kill":
                    return "KILL:" + getPidArg(args, 0);

                case "wait":
                    return "WAIT";

                case "waitPID":
                    return "WAITPID:" + getPidArg(args, 0);

                case "pause":
                    return "PAUSE:" + getPidArg(args, 0);

                case "continue":
                    return "CONTINUE:" + getPidArg(args, 0);

                case "getPID":
                    return context.getPid();

                case "getPPID":
                    return context.getPpid();

                case "getListOfChildProcess":
                    return getChildProcessList(context.getPid());

                default:
                    return null;
            }
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    /**
     * 构建 EXEC 标记。
     * 格式: "EXEC:path:param1:param2:..."
     */
    private Object buildExecMarker(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return new String[]{Constants.ERROR_MARKER, "exec requires at least a path argument"};
        }
        String path = args.get(0) != null ? args.get(0).toString() : "";
        StringBuilder marker = new StringBuilder("EXEC:").append(path);
        for (int i = 1; i < args.size(); i++) {
            marker.append(":").append(args.get(i) != null ? args.get(i).toString() : "");
        }
        return marker.toString();
    }

    /**
     * 获取子进程列表。
     * 读取 /system/process/ 目录下所有 JSON 进程文件，查找 ppid 匹配当前进程的条目。
     */
    private String getChildProcessList(int pid) {
        File processDir = new File(PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH));
        if (!processDir.exists() || !processDir.isDirectory()) {
            return "(no process directory)";
        }

        File[] files = processDir.listFiles((dir, name) -> name.endsWith(".proc"));
        if (files == null || files.length == 0) {
            return "(no child processes)";
        }

        List<Map<String, Object>> children = new ArrayList<>();
        for (File f : files) {
            try {
                String content = FileUtil.read(Constants.SYSTEM_PROCESS_PATH + f.getName());
                if (content == null || content.trim().isEmpty()) continue;
                Map<String, Object> procData = JsonUtil.parseToMap(content);
                Object parentObj = procData.get("Parent");
                if (parentObj instanceof Map) {
                    Map<String, Object> parentMap = (Map<String, Object>) parentObj;
                    Object parentPidObj = parentMap.get("PID");
                    if (parentPidObj instanceof Number && ((Number) parentPidObj).intValue() == pid) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("pid", procData.get("PID"));
                        entry.put("name", procData.get("Name"));
                        entry.put("state", procData.get("ProcessState"));
                        children.add(entry);
                    }
                }
            } catch (Exception ignored) {
                // 跳过无法读取的进程文件
            }
        }

        if (children.isEmpty()) {
            return "(no child processes)";
        }

        return children.stream()
                .map(e -> "PID=" + e.get("pid") + " name=" + e.get("name") + " state=" + e.get("state"))
                .collect(Collectors.joining("\n"));
    }

    private int getPidArg(List<Object> args, int index) {
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
