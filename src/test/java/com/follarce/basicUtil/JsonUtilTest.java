package com.follarce.basicUtil;

import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonUtilTest {

    @Test
    @Order(1)
    @DisplayName("测试 JSON 对象解析 - 简单对象")
    void testReadJsonObjectSimple() {
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
    @DisplayName("测试 JSON 对象解析 - 嵌套对象")
    void testReadJsonObjectNested() {
        String json = "{\"user\": {\"name\": \"John\", \"age\": 30}, \"active\": true}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) map.get("user");
        assertEquals("John", user.get("name"));
        assertEquals(30, user.get("age"));
        assertEquals(true, map.get("active"));
    }

    @Test
    @Order(3)
    @DisplayName("测试 JSON 对象解析 - 空对象")
    void testReadJsonObjectEmpty() {
        String json = "{}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertTrue(map.isEmpty());
    }

    @Test
    @Order(4)
    @DisplayName("测试 JSON 数组解析 - 简单数组")
    void testReadJsonArraySimple() {
        String json = "[1, 2, 3, 4, 5]";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertEquals(5, list.size());
        assertEquals(1, list.get(0));
        assertEquals(5, list.get(4));
    }

    @Test
    @Order(5)
    @DisplayName("测试 JSON 数组解析 - 字符串数组")
    void testReadJsonArrayStrings() {
        String json = "[\"apple\", \"banana\", \"cherry\"]";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertEquals("apple", list.get(0));
        assertEquals("banana", list.get(1));
        assertEquals("cherry", list.get(2));
    }

    @Test
    @Order(6)
    @DisplayName("测试 JSON 数组解析 - 嵌套数组")
    void testReadJsonArrayNested() {
        String json = "[[1, 2], [3, 4], [5, 6]]";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());

        @SuppressWarnings("unchecked")
        List<Object> inner = (List<Object>) list.get(0);
        assertEquals(2, inner.size());
        assertEquals(1, inner.get(0));
    }

    @Test
    @Order(7)
    @DisplayName("测试 JSON 数组解析 - 空数组")
    void testReadJsonArrayEmpty() {
        String json = "[]";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertTrue(list.isEmpty());
    }

    @Test
    @Order(8)
    @DisplayName("测试 JSON 数组解析 - 对象数组")
    void testReadJsonArrayOfObjects() {
        String json = "[{\"id\": 1}, {\"id\": 2}]";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertEquals(2, list.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> obj1 = (Map<String, Object>) list.get(0);
        assertEquals(1, obj1.get("id"));
    }

    @Test
    @Order(9)
    @DisplayName("测试无效 JSON 处理 - 格式错误")
    void testReadJsonInvalidFormat() {
        String invalidJson = "{invalid json}";
        Object result = JsonUtil.readJson(invalidJson);

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(10)
    @DisplayName("测试无效 JSON 处理 - 不完整 JSON")
    void testReadJsonIncomplete() {
        String incompleteJson = "{\"name\": \"test\"";
        Object result = JsonUtil.readJson(incompleteJson);

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(11)
    @DisplayName("测试无效 JSON 处理 - 括号不匹配")
    void testReadJsonMismatchedBrackets() {
        String mismatchedJson = "[1, 2, 3}";
        Object result = JsonUtil.readJson(mismatchedJson);

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(12)
    @DisplayName("测试 null 检查 - null 输入")
    void testReadJsonNull() {
        Object result = JsonUtil.readJson(null);

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(13)
    @DisplayName("测试 null 检查 - 空字符串")
    void testReadJsonEmpty() {
        Object result = JsonUtil.readJson("");

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(14)
    @DisplayName("测试 null 检查 - 空白字符串")
    void testReadJsonWhitespace() {
        Object result = JsonUtil.readJson("   ");

        assertTrue(result instanceof String[]);
        String[] arr = (String[]) result;
        assertEquals("ERROR", arr[0]);
        assertEquals("INCORRECT_FORMAT", arr[1]);
    }

    @Test
    @Order(15)
    @DisplayName("测试 JSON null 值解析")
    void testReadJsonNullValue() {
        String json = "null";
        Object result = JsonUtil.readJson(json);

        assertNull(result);
    }

    @Test
    @Order(16)
    @DisplayName("测试 JSON 布尔值解析")
    void testReadJsonBoolean() {
        Object resultTrue = JsonUtil.readJson("true");
        assertEquals(true, resultTrue);

        Object resultFalse = JsonUtil.readJson("false");
        assertEquals(false, resultFalse);
    }

    @Test
    @Order(17)
    @DisplayName("测试 JSON 数字解析 - 整数")
    void testReadJsonNumberInteger() {
        String json = "42";
        Object result = JsonUtil.readJson(json);

        assertEquals(42, result);
        assertTrue(result instanceof Integer);
    }

    @Test
    @Order(18)
    @DisplayName("测试 JSON 数字解析 - 浮点数")
    void testReadJsonNumberDouble() {
        String json = "3.14159";
        Object result = JsonUtil.readJson(json);

        assertEquals(3.14159, (Double) result, 0.00001);
        assertTrue(result instanceof Double);
    }

    @Test
    @Order(19)
    @DisplayName("测试 JSON 数字解析 - 负数")
    void testReadJsonNumberNegative() {
        Object result = JsonUtil.readJson("-42");
        assertEquals(-42, result);

        Object resultDouble = JsonUtil.readJson("-3.14");
        assertEquals(-3.14, (Double) resultDouble, 0.001);
    }

    @Test
    @Order(20)
    @DisplayName("测试 JSON 字符串解析")
    void testReadJsonString() {
        String json = "\"hello world\"";
        Object result = JsonUtil.readJson(json);

        assertEquals("hello world", result);
        assertTrue(result instanceof String);
    }

    @Test
    @Order(21)
    @DisplayName("测试 JSON 字符串解析 - 转义字符")
    void testReadJsonStringEscaped() {
        String json = "\"hello\\nworld\"";
        Object result = JsonUtil.readJson(json);

        assertEquals("hello\nworld", result);
    }

    @Test
    @Order(22)
    @DisplayName("测试 JSON 字符串解析 - Unicode")
    void testReadJsonStringUnicode() {
        String json = "\"你好世界\"";
        Object result = JsonUtil.readJson(json);

        assertEquals("你好世界", result);
    }

    @Test
    @Order(23)
    @DisplayName("测试 isValidJson - 有效 JSON")
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
    @Order(24)
    @DisplayName("测试 isValidJson - 无效 JSON")
    void testIsValidJsonInvalid() {
        assertFalse(JsonUtil.isValidJson("{invalid json}"));
        assertFalse(JsonUtil.isValidJson("[1, 2,"));
        assertFalse(JsonUtil.isValidJson(""));
        assertFalse(JsonUtil.isValidJson(null));
        assertFalse(JsonUtil.isValidJson("   "));
    }

    @Test
    @Order(25)
    @DisplayName("测试 toJson - Map 对象")
    void testToJsonMap() {
        Map<String, Object> map = Map.of("name", "test", "value", 123);
        String json = JsonUtil.toJson(map);

        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"test\""));
        assertTrue(json.contains("\"value\""));
        assertTrue(json.contains("123"));
    }

    @Test
    @Order(26)
    @DisplayName("测试 toJson - List 对象")
    void testToJsonList() {
        List<Object> list = List.of(1, 2, 3);
        String json = JsonUtil.toJson(list);

        assertEquals("[1,2,3]", json);
    }

    @Test
    @Order(27)
    @DisplayName("测试 toJson - 字符串")
    void testToJsonString() {
        String json = JsonUtil.toJson("hello");
        assertEquals("\"hello\"", json);
    }

    @Test
    @Order(28)
    @DisplayName("测试 toJson - 数字")
    void testToJsonNumber() {
        assertEquals("42", JsonUtil.toJson(42));
        assertEquals("3.14", JsonUtil.toJson(3.14));
    }

    @Test
    @Order(29)
    @DisplayName("测试 toJson - 布尔值")
    void testToJsonBoolean() {
        assertEquals("true", JsonUtil.toJson(true));
        assertEquals("false", JsonUtil.toJson(false));
    }

    @Test
    @Order(30)
    @DisplayName("测试 toJson - null")
    void testToJsonNull() {
        assertEquals("null", JsonUtil.toJson(null));
    }

    @Test
    @Order(31)
    @DisplayName("测试数字转换 - Double 转 Integer")
    void testNumberConversion() {
        String json = "{\"value\": 42.0}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(42, map.get("value"));
        assertTrue(map.get("value") instanceof Integer);
    }

    @Test
    @Order(32)
    @DisplayName("测试数字转换 - 保留小数")
    void testNumberConversionDecimal() {
        String json = "{\"value\": 42.5}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(42.5, map.get("value"));
        assertTrue(map.get("value") instanceof Double);
    }

    @Test
    @Order(33)
    @DisplayName("测试复杂嵌套结构")
    void testComplexNestedStructure() {
        String json = "{\"users\": [{\"name\": \"Alice\", \"scores\": [95, 87, 92]}, {\"name\": \"Bob\", \"scores\": [88, 91, 85]}], \"count\": 2}";
        Object result = JsonUtil.readJson(json);

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;

        assertEquals(2, map.get("count"));

        @SuppressWarnings("unchecked")
        List<Object> users = (List<Object>) map.get("users");
        assertEquals(2, users.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> alice = (Map<String, Object>) users.get(0);
        assertEquals("Alice", alice.get("name"));

        @SuppressWarnings("unchecked")
        List<Object> scores = (List<Object>) alice.get("scores");
        assertEquals(3, scores.size());
        assertEquals(95, scores.get(0));
    }
}
