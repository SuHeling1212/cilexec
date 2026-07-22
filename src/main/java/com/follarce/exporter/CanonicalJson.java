package com.follarce.exporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.TreeMap;

/** Canonical compact JSON with recursively lexicographically ordered object keys. */
final class CanonicalJson {
    private static final Gson WRITER = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private CanonicalJson() {
    }

    static String normalizeObject(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new LogicalExportException("Export rows must be JSON objects");
            }
            return WRITER.toJson(sort(parsed));
        } catch (JsonParseException | IllegalStateException failure) {
            throw new LogicalExportException("Export row is not valid JSON", failure);
        }
    }

    private static JsonElement sort(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            Map<String, JsonElement> ordered = new TreeMap<>();
            value.getAsJsonObject().entrySet().forEach(entry ->
                    ordered.put(entry.getKey(), sort(entry.getValue())));
            ordered.forEach(result::add);
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(sort(element)));
            return result;
        }
        return value.deepCopy();
    }
}
