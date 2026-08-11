package com.follarce.fcl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for every deterministic function exported by {@link FclBuiltins}. */
class FclBuiltinsExhaustiveTest {
    private final FclFunctionRegistry functions = FclBuiltins.pureRegistry();

    @Test
    void exercisesEveryRegisteredPureFunctionAndAlias() {
        Set<String> expected = Set.of(
                "math.sin", "math.cos", "math.tan", "math.sqrt", "math.log",
                "math.abs", "math.round", "math.floor", "math.ceil", "math.pow",
                "math.max", "math.min", "math.pi", "math.e",
                "util.toJson", "util.fromJson", "util.typeOf", "util.isArray",
                "util.isMap", "util.isNumber", "util.isString", "util.isBool",
                "util.toString", "util.string", "util.length",
                "array.insert", "array.removeAt",
                "text.slice", "text.split", "text.join", "text.indexOf",
                "text.lastIndexOf", "text.repeat", "text.replace",
                "path.normalize", "path.resolve", "path.getFileName",
                "path.getParentPath", "path.getParent", "path.isAbsolute", "path.join",
                "term.color", "term.paint", "term.bold", "term.dim", "term.reset",
                "term.clear", "term.eraseLine", "term.inverse", "term.underline",
                "term.strikethrough", "term.alternate", "term.mouse", "term.paste",
                "term.focus", "term.bg", "term.color256", "term.bg256",
                "term.trueColor", "term.bgTrueColor", "term.hideCursor",
                "term.showCursor", "term.displayWidth", "term.truncate", "term.cursorTo",
                "term.cursorUp", "term.cursorDown", "term.cursorForward", "term.cursorBack",
                "term.red", "term.green", "term.yellow", "term.blue", "term.magenta",
                "term.cyan", "term.white");
        assertEquals(expected, functions.qualifiedNames());

        assertEquals(0.0d, call("math.sin", 0L));
        assertEquals(1.0d, call("math.cos", 0L));
        assertEquals(0.0d, call("math.tan", 0L));
        assertEquals(3.0d, call("math.sqrt", 9L));
        assertEquals(0.0d, call("math.log", 1L));
        assertEquals(7L, call("math.abs", -7L));
        assertEquals(2L, call("math.round", 1.6d));
        assertEquals(1.0d, call("math.floor", 1.9d));
        assertEquals(2.0d, call("math.ceil", 1.1d));
        assertEquals(8.0d, call("math.pow", 2L, 3L));
        assertEquals(3.0d, call("math.max", -1L, 3L));
        assertEquals(-1.0d, call("math.min", -1L, 3L));
        assertEquals(Math.PI, call("math.pi"));
        assertEquals(Math.E, call("math.e"));

        assertEquals("{\"a\":[1,true]}", call("util.toJson", Map.of("a", List.of(1L, true))));
        assertEquals(Map.of("a", List.of(1L, true)),
                call("util.fromJson", "{\"a\":[1,true]}"));
        assertEquals(1L, call("util.fromJson", "1"));
        assertEquals(1.0d, call("util.fromJson", "1.0"));
        assertEquals(1000.0d, call("util.fromJson", "1e3"));
        assertEquals(new java.math.BigInteger("12345678901234567890"),
                call("util.fromJson", "12345678901234567890"));
        assertEquals("12345678901234567890",
                call("util.toJson", call("util.fromJson", "12345678901234567890")));
        assertEquals("{\"a\":[1,true]}",
                call("util.toJson", call("util.fromJson", "{\"a\":[1,true]}")));
        assertEquals("array", call("util.typeOf", List.of()));
        assertEquals(true, call("util.isArray", List.of()));
        assertEquals(true, call("util.isMap", Map.of()));
        assertEquals(true, call("util.isNumber", 1L));
        assertEquals(true, call("util.isString", "x"));
        assertEquals(true, call("util.isBool", false));
        assertEquals("[1, x]", call("util.toString", List.of(1L, "x")));
        assertEquals("x", call("util.string", "x"));
        assertEquals(2L, call("util.length", Map.of("a", 1L, "b", 2L)));

        assertEquals(List.of("a", "b", "c"),
                call("array.insert", List.of("a", "c"), 1L, "b"));
        assertEquals(List.of("a", "c"),
                call("array.removeAt", List.of("a", "b", "c"), 1L));
        assertEquals("bc", call("text.slice", "abcd", 1L, 3L));
        assertEquals(List.of("a", "", "b", ""), call("text.split", "a,,b,", ","));
        assertEquals(List.of("中", "🙂"), call("text.split", "中🙂", ""));
        assertEquals("a/1/true", call("text.join", List.of("a", 1L, true), "/"));
        assertEquals(3L, call("text.indexOf", "ababa", "ba", 2L));
        assertEquals(3L, call("text.lastIndexOf", "ababa", "ba"));
        assertEquals("ababab", call("text.repeat", "ab", 3L));
        assertEquals("xcxc", call("text.replace", "abcabc", "ab", "x"));

        assertEquals("/a/c", call("path.normalize", "/a//b/../c"));
        assertEquals("a/c", call("path.resolve", "a/./b/../c"));
        assertEquals("..", call("path.normalize", ".."));
        assertEquals("../b", call("path.normalize", "../a/../b"));
        assertEquals("..", call("path.normalize", "a/../.."));
        assertEquals("/a", call("path.normalize", "/../a"));
        assertEquals("c.txt", call("path.getFileName", "/a/c.txt"));
        assertEquals("/a", call("path.getParentPath", "/a/c.txt"));
        assertEquals("/a", call("path.getParent", "/a/c.txt"));
        assertEquals(".", call("path.getParentPath", "c.txt"));
        assertEquals("a", call("path.getParentPath", "a/b.txt"));
        assertEquals("/", call("path.getParentPath", "/c.txt"));
        assertEquals("../a", call("path.getParentPath", "../a/b"));
        assertEquals("../a/b", call("path.getParentPath", "../a/b/c.txt"));
        assertEquals(true, call("path.isAbsolute", "\\a"));
        assertEquals("/a/c", call("path.join", "/a", "b", "..", "c"));

        assertEquals("\u001b[31mx\u001b[0m", call("term.color", "red", "x"));
        assertEquals("\u001b[34mx\u001b[0m", call("term.paint", "BLUE", "x"));
        assertEquals("\u001b[1mx\u001b[0m", call("term.bold", "x"));
        assertEquals("\u001b[2mx\u001b[0m", call("term.dim", "x"));
        assertEquals("\u001b[0m", call("term.reset"));
        assertEquals("\u001b[2J\u001b[H", call("term.clear"));
        assertEquals("\u001b[2K\r", call("term.eraseLine"));
        assertEquals("\u001b[7mx\u001b[0m", call("term.inverse", "x"));
        assertEquals("\u001b[?25l", call("term.hideCursor"));
        assertEquals("\u001b[?25h", call("term.showCursor"));
        assertEquals(5L, call("term.displayWidth", "a中文"));
        assertEquals(1L, call("term.displayWidth", "e\u0301"));
        assertEquals(1L, call("term.displayWidth", "\u001b[31mx\u001b[0m"));
        assertEquals("a中", call("term.truncate", "a中文", 3L));
        assertEquals("\u001b[2;3H", call("term.cursorTo", 2L, 3L));
        assertEquals("\u001b[2A", call("term.cursorUp", 2L));
        assertEquals("\u001b[2B", call("term.cursorDown", 2L));
        assertEquals("\u001b[2C", call("term.cursorForward", 2L));
        assertEquals("\u001b[2D", call("term.cursorBack", 2L));
        for (String color : List.of("red", "green", "yellow", "blue", "magenta", "cyan",
                "white")) {
            assertTrue(String.valueOf(call("term." + color, "x")).endsWith("x\u001b[0m"));
        }
    }

    @Test
    void rejectsBoundaryAndInvalidArgumentsWithoutLeakingHostExceptions() {
        assertFailure("math.sqrt", -1L);
        assertFailure("math.log", 0L);
        assertFailure("math.abs", Long.MIN_VALUE);
        assertFailure("math.pow", 1L);
        assertFailure("math.pow", 0L, -1L);
        assertFailure("math.pow", 10L, 400L);
        assertFailure("util.fromJson", "{");
        assertEquals(0L, call("util.length", (Object) null));
        assertFailure("array.insert", List.of(), -1L, "x");
        assertFailure("array.insert", List.of(), 0.5d, "x");
        assertFailure("array.removeAt", List.of(), 0L);
        assertFailure("text.slice", "a", 1L, 0L);
        assertFailure("text.indexOf", "a", "a", -1L);
        assertFailure("text.repeat", "a", -1L);
        assertFailure("text.repeat", "a", 1_000_001L);
        assertFailure("path.join", 1L);
        assertFailure("term.color", "unknown", "x");
        assertFailure("term.truncate", "x", -1L);
        assertFailure("term.cursorTo", 0L, 1L);
        assertFailure("term.cursorBack", 0L);

        Object source = List.of(Map.of("nested", List.of(1L)));
        Object inserted = call("array.insert", source, 1L, source);
        assertInstanceOf(List.class, inserted);
        assertFalse(inserted == source, "function results must be deep copies");
    }

    private Object call(String name, Object... arguments) {
        return functions.invoke(name, java.util.Arrays.asList(arguments));
    }

    private void assertFailure(String name, Object... arguments) {
        assertThrows(FclRuntimeException.class,
                () -> functions.invoke(name, java.util.Arrays.asList(arguments)), name);
    }
}
