package com.follarce.persistence.postgres.mapper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Optional;

/** Deterministic adapter owned by persistence mapping, never by domain objects. */
public final class JsonCodec {
    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .registerTypeAdapterFactory(new OptionalAdapterFactory())
            .create();

    public String write(Object value) {
        return gson.toJson(value);
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return gson.fromJson(json, type);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid persisted JSON for " + type.getSimpleName(), exception);
        }
    }

    public <T> T read(String json, Type type) {
        try {
            return gson.fromJson(json, type);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid persisted JSON", exception);
        }
    }

    private static final class InstantAdapter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant source, Type type, JsonSerializationContext context) {
            return context.serialize(source.toString());
        }

        @Override
        public Instant deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            try {
                return Instant.parse(json.getAsString());
            } catch (java.time.format.DateTimeParseException exception) {
                throw new JsonParseException("Invalid persisted Instant: " + json, exception);
            }
        }
    }

    private static final class OptionalAdapterFactory implements TypeAdapterFactory {
        @Override
        public <T> com.google.gson.TypeAdapter<T> create(Gson gson, TypeToken<T> token) {
            if (token.getRawType() != Optional.class || !(token.getType() instanceof ParameterizedType parameterized)) {
                return null;
            }
            Type elementType = parameterized.getActualTypeArguments()[0];
            com.google.gson.TypeAdapter<?> elementAdapter = gson.getAdapter(TypeToken.get(elementType));
            @SuppressWarnings("unchecked")
            com.google.gson.TypeAdapter<T> adapter = (com.google.gson.TypeAdapter<T>) new OptionalAdapter<>(elementAdapter);
            return adapter;
        }
    }

    private static final class OptionalAdapter<E> extends com.google.gson.TypeAdapter<Optional<E>> {
        private final com.google.gson.TypeAdapter<E> elementAdapter;

        @SuppressWarnings("unchecked")
        private OptionalAdapter(com.google.gson.TypeAdapter<?> elementAdapter) {
            this.elementAdapter = (com.google.gson.TypeAdapter<E>) elementAdapter;
        }

        @Override
        public void write(JsonWriter output, Optional<E> value) throws IOException {
            if (value == null || value.isEmpty()) {
                output.nullValue();
            } else {
                elementAdapter.write(output, value.get());
            }
        }

        @Override
        public Optional<E> read(JsonReader input) throws IOException {
            if (input.peek() == com.google.gson.stream.JsonToken.NULL) {
                input.nextNull();
                return Optional.empty();
            }
            return Optional.ofNullable(elementAdapter.read(input));
        }
    }
}
