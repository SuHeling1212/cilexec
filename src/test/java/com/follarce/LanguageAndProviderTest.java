package com.follarce;

import com.follarce.function.FunctionContext;
import com.follarce.function.MathFunctionProvider;
import com.follarce.function.PathFunctionProvider;
import com.follarce.function.ProcessFunctionProvider;
import com.follarce.function.TermFunctionProvider;
import com.follarce.function.UtilFunctionProvider;
import com.follarce.script.Lexer;
import com.follarce.script.NodeEvaluator;
import com.follarce.script.Parser;
import com.follarce.util.PathUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LanguageAndProviderTest {
    private final FunctionContext context = new FunctionContext(42, 7, "local");

    @Test
    void expressionLanguageEvaluatesPrecedenceCollectionsAndLength() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of("a", "b", "c"));
        data.put("record", Map.of("name", "cilexec"));

        assertEquals(7L, evaluate("1 + 2 * 3", data));
        assertEquals(true, evaluate("1 < 2 and 3 >= 3", data));
        assertEquals(3L, evaluate("#items", data));
        assertEquals("b", evaluate("items[1]", data));
        assertEquals("cilexec", evaluate("record[\"name\"]", data));
    }

    @Test
    void lexerRejectsTrailingTokensAndSupportsEscapedStrings() {
        assertThrows(RuntimeException.class, () -> new Parser(new Lexer("1 2").tokenize()).parse());
        assertEquals("a\nb", evaluate("\"a\\nb\"", new LinkedHashMap<>()));
    }

    @Test
    void mathUtilityPathAndProcessProvidersCoverPublicContracts() {
        MathFunctionProvider math = new MathFunctionProvider();
        assertEquals("math", math.getNamespace());
        assertEquals(8.0, math.call("pow", List.of(2, 3), context));
        assertEquals(4L, math.call("round", List.of(3.6), context));
        assertEquals(Math.PI, math.call("pi", List.of(), context));
        assertNull(math.call("unknown", List.of(), context));
        assertTrue((Double) math.call("random", List.of(), context) >= 0.0);

        UtilFunctionProvider util = new UtilFunctionProvider();
        assertEquals("util", util.getNamespace());
        List<?> parsed = (List<?>) util.call("fromJson", List.of(util.call("toJson", List.of(List.of(1, 2)), context)), context);
        assertEquals(2, parsed.size());
        assertEquals(1, ((Number) parsed.get(0)).intValue());
        assertEquals(2, ((Number) parsed.get(1)).intValue());
        assertEquals(true, util.call("isMap", List.of(Map.of()), context));
        assertEquals("EXIT", util.call("exit", List.of(), context));
        assertEquals(7, ((int[]) util.call("getTime", List.of(), context)).length);

        PathUtil.setEnvAliases(Map.of("APP", "/system/app"));
        PathFunctionProvider path = new PathFunctionProvider();
        assertEquals("/system/app/demo.fcl", path.call("resolve", List.of("$APP/./demo.fcl"), context));
        assertEquals("demo.fcl", path.call("getFileName", List.of("/system/app/demo.fcl"), context));
        assertEquals("/system/app", path.call("getParentPath", List.of("/system/app/demo.fcl"), context));

        ProcessFunctionProvider process = new ProcessFunctionProvider();
        assertEquals("FORK", process.call("fork", List.of(), context));
        assertEquals("EXEC:/system/app/test.fcl:one", process.call("exec", List.of("/system/app/test.fcl", "one"), context));
        assertEquals("KILL:9", process.call("kill", List.of("9"), context));
        assertEquals("WAITPID:8", process.call("waitPID", List.of(8), context));
        assertEquals(42, process.call("getPID", List.of(), context));
        assertEquals(7, process.call("getPPID", List.of(), context));

        TermFunctionProvider term = new TermFunctionProvider();
        assertEquals("\u001B[31m", term.call("color", List.of("red"), context));
        assertEquals("\u001B[1m", term.call("bold", List.of(), context));
        assertEquals("\u001B[32mok\u001B[0m", term.call("paint", List.of("green", "ok"), context));
    }

    private static Object evaluate(String expression, Map<String, Object> data) {
        return new NodeEvaluator(data, 1, "local")
                .evaluate(new Parser(new Lexer(expression).tokenize()).parse());
    }
}
