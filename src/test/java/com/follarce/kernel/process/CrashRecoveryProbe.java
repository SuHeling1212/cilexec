package com.follarce.kernel.process;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.kernel.Constants;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Child JVM entry point used by the crash-recovery integration test. */
public final class CrashRecoveryProbe {
    private static final int PID = 300;

    private CrashRecoveryProbe() {
    }

    public static void main(String[] args) {
        Path root = Path.of(args[0]);
        String mode = args[1];
        int steps = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        String processPath = Constants.SYSTEM_PROCESS_PATH + PID + ".proc";
        if (!FileUtil.exists(processPath)) {
            JsonUtil.writeFile(processPath, JsonUtil.toJson(process()));
        }

        Map<String, Object> saved = JsonUtil.parseToMapStrict(FileUtil.read(processPath));
        ProcessRunner runner = new ProcessRunner(PID, saved);
        runner.init();
        if ("chaos".equals(mode)) {
            runner.virtualThreadRun();
            return;
        }

        for (int i = 0; i < steps; i++) {
            if (runner.step() == ProcessRunner.StepResult.TERMINATED) break;
        }
        runner.stopProcess();
    }

    private static Map<String, Object> process() {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "crash-recovery-probe");
        process.put("Owner", "local");
        process.put("PID", PID);
        process.put("Path", "/system/app/crash-recovery-probe.fcl");
        process.put("Status", true);
        process.put("ProcessState", ProcessState.NEW.name());
        process.put("Parent", new LinkedHashMap<>());
        process.put("Child", new LinkedHashMap<>());
        process.put("ExitedChildren", new LinkedHashMap<>());

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", List.of("while true", "{", "i = i + 1", "}"));
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("Data", new LinkedHashMap<>(Map.of("i", 0)));
        program.put("Code", code);
        process.put("Program", program);
        return process;
    }
}
