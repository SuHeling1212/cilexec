package com.follarce.kernel.process;

import com.follarce.bootstrap.init.FileInit;
import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.EffectPolicy;
import com.follarce.kernel.api.function.FunctionContext;
import com.follarce.kernel.api.function.FunctionProvider;
import com.follarce.kernel.function.FunctionRegistry;
import com.follarce.kernel.process.ProcessRunner;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Child JVM used to crash between a local side effect and its process receipt. */
public final class EffectCrashRecoveryProbe {
    private static final int PID = 700;
    private static final String OUTPUT = "/system/swap/effect-output.txt";

    private EffectCrashRecoveryProbe() {}

    public static void main(String[] args) {
        Path root = Path.of(args[0]);
        Path signal = Path.of(args[1]);
        boolean blockAfterEffect = Boolean.parseBoolean(args[2]);
        FileInit.init(root.toFile());
        UserUtil.setCurrentUser("local");
        if (!FileUtil.exists(OUTPUT)) {
            FileUtil.createFile(Constants.SYSTEM_SWAP_PATH, "effect-output.txt");
        }
        FunctionRegistry.registerProvider(new ProbeProvider(signal, blockAfterEffect));
        String processPath = Constants.SYSTEM_PROCESS_PATH + PID + ".proc";
        if (!FileUtil.exists(processPath)) {
            JsonUtil.writeFile(processPath, JsonUtil.toJson(process()));
        }
        ProcessRunner runner = new ProcessRunner(PID,
                JsonUtil.parseToMapStrict(FileUtil.read(processPath)));
        runner.init();
        for (int i = 0; i < 3; i++) {
            if (runner.step() == ProcessRunner.StepResult.TERMINATED) break;
        }
    }

    private static Map<String, Object> process() {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("Name", "effect-crash-probe");
        process.put("Owner", "local");
        process.put("PID", PID);
        process.put("ProcessState", ProcessState.NEW.name());
        process.put("Parent", new LinkedHashMap<>());
        process.put("Child", new LinkedHashMap<>());
        process.put("ExitedChildren", new LinkedHashMap<>());
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", List.of("result = probe.append()"));
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("Data", new LinkedHashMap<String, Object>());
        program.put("Code", code);
        process.put("Program", program);
        return process;
    }

    private static final class ProbeProvider implements FunctionProvider {
        private final Path signal;
        private final boolean block;

        private ProbeProvider(Path signal, boolean block) {
            this.signal = signal;
            this.block = block;
        }

        @Override
        public String getNamespace() {
            return "probe";
        }

        @Override
        public EffectPolicy getEffectPolicy(String functionName) {
            return "append".equals(functionName) ? EffectPolicy.LOCAL_TRANSACTIONAL : null;
        }

        @Override
        public Object call(String functionName, List<Object> args, FunctionContext context) {
            if (!"append".equals(functionName)) return null;
            try {
                FileUtil.appendOnce(OUTPUT, "X", context.getEffectId(), context.getPid(),
                        context.getProcessGeneration(), null);
                Files.writeString(signal, context.getEffectId());
                if (block) Thread.sleep(30_000L);
                return "applied";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("probe interrupted", e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
