package com.follarce.process;

import com.follarce.Constants;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProcessEffectCrashTest {
    @TempDir Path root;

    @Test
    @Timeout(30)
    void localEffectIsAppliedOnceAcrossForcedJvmTermination() throws Exception {
        Path signal = root.resolve("effect-applied.signal");
        Process first = runProbe(signal, true);
        try {
            awaitFile(signal, Duration.ofSeconds(8));
        } finally {
            first.destroyForcibly();
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
        }

        PathUtil.setVfsRoot(root.toFile());
        assertEquals("X", FileUtil.read("/system/swap/effect-output.txt"));
        Map<String, Object> interrupted = process();
        assertEquals("PREPARED", nested(interrupted,
                "Execution", "ActiveAttempt", "Effects", 0, "State"));

        Files.deleteIfExists(signal);
        Process resumed = runProbe(signal, false);
        assertTrue(resumed.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, resumed.exitValue());

        PathUtil.setVfsRoot(root.toFile());
        assertEquals("X", FileUtil.read("/system/swap/effect-output.txt"));
        Map<String, Object> recovered = process();
        assertNull(nested(recovered, "Execution", "ActiveAttempt"));
        assertEquals("applied", nested(recovered, "Program", "Data", "result"));
    }

    private Process runProbe(Path signal, boolean block) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                EffectCrashRecoveryProbe.class.getName(), root.toString(), signal.toString(),
                Boolean.toString(block));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("effect-probe.log").toFile()));
        return builder.start();
    }

    private void awaitFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) return;
            Thread.sleep(10L);
        }
        fail("effect probe did not reach the crash point");
    }

    private Map<String, Object> process() {
        return JsonUtil.parseToMapStrict(FileUtil.read(Constants.SYSTEM_PROCESS_PATH + "700.proc"));
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Object root, Object... path) {
        Object current = root;
        for (Object part : path) {
            if (part instanceof Integer index && current instanceof List) {
                current = ((List<Object>) current).get(index);
            } else if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part.toString());
            } else {
                return null;
            }
        }
        return current;
    }
}
