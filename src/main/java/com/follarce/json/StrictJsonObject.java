package com.follarce.json;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Parse untrusted projection JSON without silently accepting duplicate fields. */
public final class StrictJsonObject {
    private StrictJsonObject() { }

    public static JsonObject parse(String source, String kind) {
        if (source == null) throw new IllegalArgumentException(kind + " must be a JSON object");
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IllegalArgumentException(kind + " must be a JSON object");
            }
            scan(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Invalid " + kind + " JSON");
            }
            return JsonParser.parseString(source).getAsJsonObject();
        } catch (IOException | IllegalStateException | com.google.gson.JsonParseException invalid) {
            throw new IllegalArgumentException("Invalid " + kind + " JSON", invalid);
        }
    }

    private static void scan(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) {
                        throw new IllegalArgumentException("Duplicate JSON field");
                    }
                    scan(reader);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) scan(reader);
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }
}
