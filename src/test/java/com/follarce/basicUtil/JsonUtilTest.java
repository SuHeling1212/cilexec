package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonUtilTest {

    @Test
    @Order(1)
    @DisplayName("测试 readJson() 返回 Map")
    void testReadJsonReturnsMap() {
        String json = "{\"name\": \"test\", \"value\": 123}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("test", map.get("name"));
        assertEquals(123, map.get("value"));
    }

    @Test
    @Order(2)
    @DisplayName("测试 readJson() 返回 List")
    void testReadJsonReturnsList() {
        String json = "[1, 2, 3, \"four\", true]";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertEquals(5, list.size());
        assertEquals(1, list.get(0));
        assertEquals(2, list.get(1));
        assertEquals(3, list.get(2));
        assertEquals("four", list.get(3));
        assertEquals(true, list.get(4));
    }

    @Test
    @Order(3)
    @DisplayName("测试 readJson() 返回 String")
    void testReadJsonReturnsString() {
        String json = "\"hello world\"";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof String);
        assertEquals("hello world", result);
    }

    @Test
    @Order(4)
    @DisplayName("测试 readJson() 返回 Boolean")
    void testReadJsonReturnsBoolean() {
        Object resultTrue = JsonUtil.readJson("true");
        assertTrue(resultTrue instanceof Boolean);
        assertEquals(true, resultTrue);

        Object resultFalse = JsonUtil.readJson("false");
        assertTrue(resultFalse instanceof Boolean);
        assertEquals(false, resultFalse);
    }

    @Test
    @Order(5)
    @DisplayName("测试 readJson() 返回 Number (Integer)")
    void testReadJsonReturnsNumberInteger() {
        Object result = JsonUtil.readJson("42");
        assertTrue(result instanceof Integer);
        assertEquals(42, result);
    }

    @Test
    @Order(6)
    @DisplayName("测试 readJson() 返回 Number (Double)")
    void testReadJsonReturnsNumberDouble() {
        Object result = JsonUtil.readJson("3.14159");
        assertTrue(result instanceof Double);
        assertEquals(3.14159, (Double) result, 0.00001);
    }

    @Test
    @Order(7)
    @DisplayName("测试 readJson() 返回 null (重要修复: 返回 String[]{\"NULL\"})")
    void testReadJsonReturnsNullAsStringArray() {
        Object result = JsonUtil.readJson("null");
        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals(1, arr.length);
        assertEquals("NULL", arr[0]);
    }

    @Test
    @Order(8)
    @DisplayName("测试 null 值返回 String[]{\"NULL\"} 而不是 null")
    void testNullValueReturnsStringArrayNotNull() {
        Object result = JsonUtil.readJson("null");
        assertNotNull(result);
        assertTrue(result instanceof String[]);
        assertEquals("NULL", ((String[]) result)[0]);
    }

    @Test
    @Order(9)
    @DisplayName("测试 convertNumbers() - Double 转换为 Integer (整数)")
    void testConvertNumbersDoubleToInteger() {
        String json = "{\"value\": 42.0}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        Object value = map.get("value");
        assertTrue(value instanceof Integer, "42.0 should be converted to Integer");
        assertEquals(42, value);
    }

    @Test
    @Order(10)
    @DisplayName("测试 convertNumbers() - 保留 Double 小数部分")
    void testConvertNumbersKeepsDouble() {
        String json = "{\"value\": 42.5}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        Object value = map.get("value");
        assertTrue(value instanceof Double, "42.5 should remain as Double");
        assertEquals(42.5, (Double) value, 0.00001);
    }

    @Test
    @Order(11)
    @DisplayName("测试 convertNumbers() - 嵌套结构中的数字转换")
    void testConvertNumbersNested() {
        String json = "{\"outer\": {\"inner\": 100.0}, \"list\": [5.0, 3.14]}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;

        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) map.get("outer");
        assertTrue(outer.get("inner") instanceof Integer);
        assertEquals(100, outer.get("inner"));

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) map.get("list");
        assertTrue(list.get(0) instanceof Integer);
        assertEquals(5, list.get(0));
        assertTrue(list.get(1) instanceof Double);
    }

    @Test
    @Order(12)
    @DisplayName("测试 toJson() 和 toJsonPretty() 格式差异")
    void testToJsonVsToJsonPretty() {
        Map<String, Object> map = Map.of("name", "test", "age", 25);

        String compact = JsonUtil.toJson(map);
        String pretty = JsonUtil.toJsonPretty(map);

        assertFalse(compact.contains("\n"));
        assertFalse(compact.contains("  "));
        assertTrue(pretty.contains("\n"));
        assertTrue(pretty.contains("  "));
        assertEquals(compact, JsonUtil.toJson(map));
    }

    @Test
    @Order(13)
    @DisplayName("测试 toJsonPretty() 格式化输出")
    void testToJsonPrettyFormatted() {
        List<Object> list = List.of(1, 2, 3);
        String pretty = JsonUtil.toJsonPretty(list);

        assertTrue(pretty.contains("\n"));
        String[] lines = pretty.split("\n");
        assertTrue(lines.length > 1);
    }

    @Test
    @Order(14)
    @DisplayName("测试错误处理 - 无效 JSON 返回 String[]")
    void testReadJsonInvalidJsonReturnsStringArray() {
        String invalidJson = "{invalid json}";
        Object result = JsonUtil.readJson(invalidJson);

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(15)
    @DisplayName("测试错误处理 - 不完整 JSON")
    void testReadJsonIncompleteJson() {
        String incomplete = "{\"name\": \"test\"";
        Object result = JsonUtil.readJson(incomplete);

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
    }

    @Test
    @Order(16)
    @DisplayName("测试错误处理 - null 输入")
    void testReadJsonNullInput() {
        Object result = JsonUtil.readJson(null);

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(17)
    @DisplayName("测试错误处理 - 空字符串")
    void testReadJsonEmptyString() {
        Object result = JsonUtil.readJson("");

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
    }

    @Test
    @Order(18)
    @DisplayName("测试错误处理 - 括号不匹配")
    void testReadJsonMismatchedBrackets() {
        String mismatched = "[1, 2, 3}";
        Object result = JsonUtil.readJson(mismatched);

        assertTrue(result instanceof String[]);
    }

    @Test
    @Order(19)
    @DisplayName("测试 toJson() 各种类型")
    void testToJsonVariousTypes() {
        assertEquals("\"hello\"", JsonUtil.toJson("hello"));
        assertEquals("42", JsonUtil.toJson(42));
        assertEquals("3.14", JsonUtil.toJson(3.14));
        assertEquals("true", JsonUtil.toJson(true));
        assertEquals("false", JsonUtil.toJson(false));
        assertEquals("null", JsonUtil.toJson(null));
        assertEquals("[1,2,3]", JsonUtil.toJson(List.of(1, 2, 3)));
        assertEquals("{\"name\":\"test\"}", JsonUtil.toJson(Map.of("name", "test")));
    }

    @Test
    @Order(20)
    @DisplayName("测试 isValidJson() 有效 JSON")
    void testIsValidJsonValid() {
        assertTrue(JsonUtil.isValidJson("{\"name\": \"test\"}"));
        assertTrue(JsonUtil.isValidJson("[1, 2, 3]"));
        assertTrue(JsonUtil.isValidJson("\"hello\""));
        assertTrue(JsonUtil.isValidJson("123"));
        assertTrue(JsonUtil.isValidJson("true"));
        assertTrue(JsonUtil.isValidJson("false"));
        assertTrue(JsonUtil.isValidJson("null"));
    }

    @Test
    @Order(21)
    @DisplayName("测试 isValidJson() 无效 JSON")
    void testIsValidJsonInvalid() {
        assertFalse(JsonUtil.isValidJson("{invalid json}"));
        assertFalse(JsonUtil.isValidJson("[1, 2,"));
        assertFalse(JsonUtil.isValidJson(""));
        assertFalse(JsonUtil.isValidJson(null));
        assertFalse(JsonUtil.isValidJson("   "));
    }

    @Test
    @Order(22)
    @DisplayName("测试复杂嵌套结构")
    void testComplexNestedStructure() {
        String json = "{\"users\": [{\"name\": \"Alice\", \"scores\": [95, 87, 92]}], \"count\": 2}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(2, map.get("count"));

        @SuppressWarnings("unchecked")
        List<Object> users = (List<Object>) map.get("users");
        assertEquals(1, users.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> alice = (Map<String, Object>) users.get(0);
        assertEquals("Alice", alice.get("name"));

        @SuppressWarnings("unchecked")
        List<Object> scores = (List<Object>) alice.get("scores");
        assertEquals(3, scores.size());
        assertEquals(95, scores.get(0));
    }

    @Test
    @Order(23)
    @DisplayName("测试 JSON 字符串中的转义字符")
    void testReadJsonStringEscaped() {
        String json = "\"hello\\nworld\"";
        Object result = JsonUtil.readJson(json);
        assertEquals("hello\nworld", result);
    }

    @Test
    @Order(24)
    @DisplayName("测试负数解析")
    void testReadJsonNegativeNumbers() {
        assertEquals(-42, JsonUtil.readJson("-42"));
        assertEquals(-3.14, JsonUtil.readJson("-3.14"));
    }

    @Test
    @Order(25)
    @DisplayName("测试空对象和空数组")
    void testReadJsonEmptyObjectAndArray() {
        Object emptyObj = JsonUtil.readJson("{}");
        assertTrue(emptyObj instanceof Map);
        assertTrue(((Map<?, ?>) emptyObj).isEmpty());

        Object emptyArr = JsonUtil.readJson("[]");
        assertTrue(emptyArr instanceof List);
        assertTrue(((List<?>) emptyArr).isEmpty());
    }
}