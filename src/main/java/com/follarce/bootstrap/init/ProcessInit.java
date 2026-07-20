package com.follarce.bootstrap.init;

import com.follarce.kernel.Constants;
import com.follarce.kernel.log.Logger;
import com.follarce.kernel.process.CodeLoader;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;

import java.util.*;

/**
 * 进程系统初始化 —— 创建 INIT 进程、启动调度器。
 */
public final class ProcessInit {

    private ProcessInit() {}

    private static final String INIT_PROCESS_FILE = Constants.PID_INIT + ".proc";

    /**
     * 初始化进程系统。
     * 检查 PID 1 (INIT) 是否存在，不存在则创建并启动。
     */
    public static void init() {
        Logger.info("ProcessInit: Initializing process system");

        // 检查 PID 1 是否存在
        String initProcessPath = Constants.SYSTEM_PROCESS_PATH + INIT_PROCESS_FILE;
        boolean initExists = FileUtil.exists(initProcessPath);

        if (!initExists) {
            Logger.info("INIT process not found, creating...");
            createInitProcess();
        } else {
            Logger.info("INIT process already exists");
        }
    }

    /**
     * 创建 PID 1 (INIT) 进程文件。
     */
    public static void createInitProcess() {
        // 读取 INIT.fcl 代码
        String initFclPath = Constants.SYSTEM_CONFIG_PATH + Constants.INIT_FCL;
        String initCode;

        if (FileUtil.exists(initFclPath)) {
            initCode = FileUtil.read(initFclPath);
        } else {
            Logger.warn("INIT.fcl not found, using default idle loop");
            initCode = "while true {}";
        }

        // 分割代码为行，剔除注释，确保 .proc 写入干净代码
        List<String> rawLines = new ArrayList<>();
        if (initCode != null && !initCode.trim().isEmpty()) {
            for (String line : initCode.split("\n")) {
                rawLines.add(line);
            }
        }
        if (rawLines.isEmpty()) {
            rawLines.add("while true {}");
        }
        List<String> codeLines = CodeLoader.stripComments(rawLines);

        // 读取用户配置
        String currentUser = UserUtil.getCurrentUserFromFile();
        if (currentUser == null) currentUser = Constants.DEFAULT_USER_LOCAL;

        // 构建进程 JSON
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("Name", "INIT");
        processData.put("Owner", currentUser);
        processData.put("EffectiveUser", currentUser);
        processData.put("ProcessGeneration", com.follarce.kernel.process.ProcessIdentity.newGeneration());
        processData.put("PathAliases", new LinkedHashMap<String, String>());
        processData.put("PID", Constants.PID_INIT);
        processData.put("Path", initFclPath);
        processData.put("ProcessState", ProcessState.NEW.name());
        processData.put("BlockReason", null);
        processData.put("ExitReason", null);
        processData.put("StateMessage", null);
        processData.put("startTime", FileUtil.getCurrentTimeArray());
        processData.put("RunningTime", 0);
        processData.put("Priority", Constants.PRIORITY_LOW);
        processData.put("Parent", new LinkedHashMap<>());
        processData.put("Child", new LinkedHashMap<>());
        processData.put("ExitedChildren", new LinkedHashMap<>());
        processData.put("ReapedChildren", new LinkedHashMap<>());
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("NextAttemptOrdinal", 0L);
        processData.put("Execution", execution);

        // Program 部分
        Map<String, Object> program = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("__current_script", initFclPath);
        program.put("Data", data);

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", codeLines);
        code.put("runningCodeLine", 0);

        List<Map<String, Object>> blockStack = new ArrayList<>();
        code.put("BlockStack", blockStack);

        program.put("Code", code);

        processData.put("Program", program);

        // 写入进程文件
        String json = JsonUtil.toMetaJson(processData);
        JsonUtil.writeFile(Constants.SYSTEM_PROCESS_PATH + INIT_PROCESS_FILE, json);

        Logger.info("Created INIT process (PID=1)");
    }

    /**
     * 获取初始进程的启动参数。
     */
    public static Map<String, Object> getInitProcessData() {
        String initProcessPath = Constants.SYSTEM_PROCESS_PATH + INIT_PROCESS_FILE;
        if (!FileUtil.exists(initProcessPath)) return null;

        String content = FileUtil.read(initProcessPath);
        return JsonUtil.parseToMap(content);
    }
}
