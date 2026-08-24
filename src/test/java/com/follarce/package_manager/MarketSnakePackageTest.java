package com.follarce.package_manager;

import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketSnakePackageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void marketSourceBuildsAValidImmutablePackageDatabase() throws Exception {
        Path output = temporaryDirectory.resolve("snake.db");
        PackageDescriptor descriptor = new PackageBuilder().build(Path.of("dist/snake"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        SqlitePackageReader reader = new SqlitePackageReader();

        assertEquals("cilexec/snake/0.0.3", descriptor.coordinate());
        assertEquals(com.follarce.domain.packageinfo.PackageKind.APPLICATION, descriptor.kind());
        assertEquals(List.of("run"), descriptor.entrypoints().stream()
                .map(value -> value.name()).toList());
        assertEquals(List.of("main"), descriptor.modules());
        assertEquals(List.of("play"), descriptor.exports().stream()
                .map(value -> value.name()).toList());
        assertEquals(List.of("terminal.raw_input"), descriptor.capabilities());

        String module = new String(reader.readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
        assertTrue(module.contains("class SnakeGame"));
        assertTrue(module.contains("func step()"));
        assertTrue(module.contains("io.readKey(waitMs, false)"));
        assertTrue(module.contains("term.render(sn_draw"));
        assertTrue(module.contains("func play()"));
    }

    @Test
    void snakeObjectMovesTurnsAndRejectsAnImmediateReverse() throws Exception {
        String module = source();
        FclProgram program = new FclCompiler().compile(module + """

                game = new SnakeGame(12, 6)
                game.snake = [{x:5, y:2}, {x:4, y:2}, {x:3, y:2}]
                game.foodX = 0
                game.foodY = 0
                startX = game.snake[0]["x"]
                firstMoved = game.step()
                afterRightX = game.snake[0]["x"]
                turnAccepted = game.turn("DOWN")
                secondMoved = game.step()
                afterDownY = game.snake[0]["y"]
                reverseAccepted = game.turn("UP")
                """);
        FclContinuation continuation = run(program, deterministicFunctions(), 20_000);

        assertEquals(5L, continuation.scope().get("startX"));
        assertEquals(true, continuation.scope().get("firstMoved"));
        assertEquals(6L, continuation.scope().get("afterRightX"));
        assertEquals(true, continuation.scope().get("turnAccepted"));
        assertEquals(true, continuation.scope().get("secondMoved"));
        assertEquals(3L, continuation.scope().get("afterDownY"));
        assertEquals(false, continuation.scope().get("reverseAccepted"));
    }

    @Test
    void snakeWrapsAcrossEveryBoardEdge() throws Exception {
        FclProgram program = new FclCompiler().compile(source() + """

                left = new SnakeGame(12, 6)
                left.snake = [{x:0, y:2}, {x:1, y:2}, {x:2, y:2}]
                left.direction = "LEFT"
                left.nextDirection = "LEFT"
                left.foodX = 4
                left.foodY = 4
                left.step()
                leftX = left.snake[0]["x"]

                right = new SnakeGame(12, 6)
                right.snake = [{x:11, y:2}, {x:10, y:2}, {x:9, y:2}]
                right.direction = "RIGHT"
                right.nextDirection = "RIGHT"
                right.foodX = 4
                right.foodY = 4
                right.step()
                rightX = right.snake[0]["x"]

                up = new SnakeGame(12, 6)
                up.snake = [{x:5, y:0}, {x:5, y:1}, {x:5, y:2}]
                up.direction = "UP"
                up.nextDirection = "UP"
                up.foodX = 4
                up.foodY = 4
                up.step()
                upY = up.snake[0]["y"]

                down = new SnakeGame(12, 6)
                down.snake = [{x:5, y:5}, {x:5, y:4}, {x:5, y:3}]
                down.direction = "DOWN"
                down.nextDirection = "DOWN"
                down.foodX = 4
                down.foodY = 1
                down.step()
                downY = down.snake[0]["y"]
                allRunning = !left.over and !right.over and !up.over and !down.over
                """);
        FclContinuation continuation = run(program, deterministicFunctions(), 40_000);

        assertEquals(11L, continuation.scope().get("leftX"));
        assertEquals(0L, continuation.scope().get("rightX"));
        assertEquals(5L, continuation.scope().get("upY"));
        assertEquals(0L, continuation.scope().get("downY"));
        assertEquals(true, continuation.scope().get("allRunning"));
    }

    @Test
    void playUsesTimedInputAndCommittedTerminalFrames() throws Exception {
        List<Map<String, Object>> events = List.of(
                Map.of("kind", "timeout"),
                key("DOWN"),
                Map.of("kind", "timeout"),
                key("q"));
        AtomicInteger eventIndex = new AtomicInteger();
        List<String> frames = new ArrayList<>();
        List<List<Object>> readArguments = new ArrayList<>();
        FclFunctionRegistry functions = deterministicFunctions()
                .register("term", "getSize", arguments -> Map.of(
                        "width", 20L, "height", 12L), "size")
                .register("term", "render", arguments -> {
                    frames.add((String) arguments.getFirst());
                    return null;
                })
                .register("io", "readKey", arguments -> {
                    readArguments.add(List.copyOf(arguments));
                    return events.get(eventIndex.getAndIncrement());
                });
        FclProgram program = new FclCompiler().compile(source() + "\nresult = play()\n");
        FclContinuation continuation = run(program, functions, 50_000);

        assertFalse(continuation.failed());
        assertEquals(events.size(), eventIndex.get());
        assertEquals(events.size(), readArguments.size());
        assertTrue(readArguments.stream().allMatch(arguments -> Boolean.FALSE.equals(arguments.get(1))),
                "game controls must not merge multiple direction keys into a paste event");
        assertTrue(frames.size() >= 5, "enter, initial, two movements, and exit must render");
        assertTrue(frames.getFirst().contains("\u001b[?1049h"));
        assertTrue(frames.stream().anyMatch(frame -> frame.contains("Score: 0")));
        assertTrue(frames.getLast().contains("\u001b[?1049l"));
        assertEquals(0L, continuation.scope().get("result"));
    }

    private String source() throws Exception {
        Path output = temporaryDirectory.resolve("source.db");
        new PackageBuilder().build(Path.of("dist/snake"), output);
        byte[] database = java.nio.file.Files.readAllBytes(output);
        return new String(new SqlitePackageReader().readResource(database, "main.fcl"),
                StandardCharsets.UTF_8);
    }

    private static Map<String, Object> key(String key) {
        String text = key.length() == 1 ? key : "";
        return Map.of("kind", "key", "key", key, "text", text,
                "shift", false, "ctrl", false, "alt", false);
    }

    private static FclFunctionRegistry deterministicFunctions() {
        return FclBuiltins.pureRegistry()
                .register("math", "random", arguments -> {
                    if (arguments.isEmpty()) return 0.5d;
                    long lower = ((Number) arguments.get(0)).longValue();
                    long upper = ((Number) arguments.get(1)).longValue();
                    return lower + Math.min(1L, upper - lower - 1L);
                });
    }

    private static FclContinuation run(FclProgram program, FclFunctionRegistry functions,
                                       int maximumSteps) {
        FclContinuation continuation = new FclContinuation();
        FclRuntime runtime = new FclRuntime(functions);
        int steps = 0;
        while (!continuation.halted() && steps++ < maximumSteps) {
            FclStepResult step = runtime.executeOne(program, continuation);
            assertFalse(step.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(step.value()));
        }
        assertTrue(continuation.halted(), "snake program exceeded the FCL step limit");
        return continuation;
    }
}
