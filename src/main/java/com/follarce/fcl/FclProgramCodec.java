package com.follarce.fcl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Serializes program source with a SHA-256 integrity check and recompiles it on decode. */
public final class FclProgramCodec {
    /**
     * Persisted source format version. Decoding requires this version before recompiling the
     * stored source and verifying its hash; instructions are not serialized.
     */
    public static final int FORMAT_VERSION = 2;

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() { }
            .getType();

    private final FclCompiler compiler;
    private final Gson gson;

    public FclProgramCodec() {
        this(new FclCompiler());
    }

    public FclProgramCodec(FclCompiler compiler) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
    }

    public Map<String, Object> encode(FclProgram program) {
        Objects.requireNonNull(program, "program");
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("formatVersion", FORMAT_VERSION);
        encoded.put("source", program.source());
        encoded.put("sourceHash", program.sourceHash());
        return encoded;
    }

    public String toJson(FclProgram program) {
        return gson.toJson(encode(program));
    }

    public FclProgram decode(Map<String, ?> encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int version = integer(encoded.get("formatVersion"), "formatVersion");
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported program format: " + version);
        }
        String source = string(encoded.get("source"), "source");
        String expectedHash = string(encoded.get("sourceHash"), "sourceHash");
        FclProgram program = compiler.compile(source);
        if (!program.sourceHash().equals(expectedHash)) {
            throw new IllegalArgumentException("FCL program hash mismatch");
        }
        return program;
    }

    @SuppressWarnings("unchecked")
    public FclProgram fromJson(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, Object> encoded = gson.fromJson(json, MAP_TYPE);
        if (encoded == null) throw new IllegalArgumentException("Program JSON cannot be null");
        return decode(encoded);
    }

    private static int integer(Object value, String field) {
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static String string(Object value, String field) {
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(field + " must be a string");
    }
}
