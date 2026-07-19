import com.follarce.Constants;
import com.follarce.init.FileInit;
import com.follarce.init.ProcessInit;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;
import com.follarce.util.UserUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Host-side launcher only; test behavior and assertions live in FCL scripts. */
public final class FclTestRuntime {
    private FclTestRuntime() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: FclTestRuntime <seed|assert> <runtime> [argument]");
        }
        Path runtime = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "seed" -> seed(runtime, requiredArg(args, 2, "FCL script"));
            case "assert" -> inspect(runtime, requiredArg(args, 2, "result VFS path"));
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void seed(Path runtime, String scriptArgument) throws Exception {
        Files.createDirectories(runtime);
        Logger.init(runtime.resolve("seed.log").toString());
        try {
            FileInit.init(runtime.resolve("cilexec_root").toFile());
            UserUtil.setCurrentUser(Constants.DEFAULT_USER_LOCAL);
            String script = Files.readString(Path.of(scriptArgument), StandardCharsets.UTF_8);
            FileUtil.write(Constants.SYSTEM_CONFIG_PATH + Constants.INIT_FCL, script);
            String processPath = Constants.SYSTEM_PROCESS_PATH + Constants.PID_INIT + ".proc";
            if (FileUtil.exists(processPath)) FileUtil.removeFile(processPath);
            ProcessInit.createInitProcess();
        } finally {
            UserUtil.clearCurrentUser();
            Logger.close();
        }
    }

    private static void inspect(Path runtime, String resultPath) {
        PathUtil.setVfsRoot(runtime.resolve("cilexec_root").toFile());
        Map<String, Object> process = JsonUtil.parseToMapStrict(
                FileUtil.read(Constants.SYSTEM_PROCESS_PATH + Constants.PID_INIT + ".proc"));
        require("TERMINATED".equals(process.get("ProcessState")),
                "INIT did not terminate normally: " + process.get("ProcessState"));
        require("NORMAL".equals(process.get("ExitReason")),
                "Unexpected INIT exit reason: " + process.get("ExitReason"));

        Map<String, Object> result = JsonUtil.parseToMapStrict(FileUtil.read(resultPath));
        require(Boolean.TRUE.equals(result.get("passed")), "FCL assertions failed: " + result);
        System.out.println("FCL_CASE_PASS:" + result.getOrDefault("case", resultPath)
                + ":" + JsonUtil.toJson(result));
    }

    private static String requiredArg(String[] args, int index, String label) {
        if (args.length <= index || args[index].isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return args[index];
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
