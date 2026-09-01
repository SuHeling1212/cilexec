package com.follarce.fcl;

import com.follarce.version.ReleaseVersion;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned codec for immutable executable FCL programs.
 *
 * <p>V003 introduced {@code FCLB}: a canonical binary instruction artifact. Source remains a
 * separate immutable VFS object for inspection and editing, but recovery executes this artifact
 * without parsing or recompiling source. V004 retains the same binary structure with a new format
 * identity. V002's JSON source envelope and V003 FCLB remain readable so an upgraded runtime can
 * finish already-persisted work safely.
 */
public final class FclProgramCodec {
    public static final int LEGACY_FORMAT_VERSION = 2;
    public static final int FIRST_BINARY_FORMAT_VERSION = 3;
    public static final int FORMAT_VERSION = ReleaseVersion.schemaNumber(ReleaseVersion.current());

    private static final int MAGIC = 0x46434c42; // FCLB
    private static final int MAX_COLLECTION_SIZE = 1_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EXPRESSION_DEPTH = 4_096;

    private static final byte ASSIGNMENT = 1;
    private static final byte LINK = 2;
    private static final byte EVALUATION = 3;
    private static final byte CONDITIONAL = 4;
    private static final byte LOOP = 5;
    private static final byte BREAK = 6;
    private static final byte CONTINUE = 7;
    private static final byte UPDATE = 8;
    private static final byte RETURN = 9;
    private static final byte FUNCTION_DECLARATION = 10;
    private static final byte IMPORT = 11;
    private static final byte INCLUDE = 12;
    private static final byte TRY_START = 13;
    private static final byte CATCH_ENTER = 14;
    private static final byte CATCH_END = 15;
    private static final byte JUMP = 16;

    private static final byte LITERAL = 1;
    private static final byte VARIABLE = 2;
    private static final byte ARRAY_LITERAL = 3;
    private static final byte MAP_LITERAL = 4;
    private static final byte UNARY = 5;
    private static final byte BINARY = 6;
    private static final byte INDEX = 7;
    private static final byte CALL = 8;
    private static final byte MEMBER = 9;
    private static final byte EXPRESSION_UPDATE = 10;
    private static final byte DESTROY_TARGET = 11;
    private static final byte NEW_OBJECT = 12;
    private static final byte SUPER_CONSTRUCTOR = 13;

    private static final byte NULL = 0;
    private static final byte STRING = 1;
    private static final byte LONG = 2;
    private static final byte DOUBLE = 3;
    private static final byte BOOLEAN = 4;
    private static final byte CHARACTER = 5;
    private static final byte BIG_INTEGER = 6;

    private static final Type LEGACY_MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() { }
            .getType();

    private final FclCompiler legacyCompiler;
    private final Gson gson;

    public FclProgramCodec() {
        this(new FclCompiler());
    }

    public FclProgramCodec(FclCompiler legacyCompiler) {
        this.legacyCompiler = Objects.requireNonNull(legacyCompiler, "legacyCompiler");
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
    }

    public static boolean supportsFormat(int version) {
        return version == LEGACY_FORMAT_VERSION
                || version >= FIRST_BINARY_FORMAT_VERSION && version <= FORMAT_VERSION;
    }

    /** Encodes an executable artifact using the current FCLB format identity. */
    public byte[] toBytes(FclProgram program) {
        Objects.requireNonNull(program, "program");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                writeString(output, program.sourceHash());
                writeInstructions(output, program.instructions());
                writeFunctions(output, program.functions());
                writeClasses(output, program.classes());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode in-memory FCL program", impossible);
        }
    }

    /** Decodes exactly the indicated persisted program format. */
    public FclProgram fromBytes(byte[] encoded, int formatVersion, String source) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(source, "source");
        if (formatVersion >= FIRST_BINARY_FORMAT_VERSION && formatVersion <= FORMAT_VERSION) {
            return decodeBinary(encoded, formatVersion, source);
        }
        if (formatVersion == LEGACY_FORMAT_VERSION) {
            FclProgram legacy = decodeLegacyJson(new String(encoded, StandardCharsets.UTF_8));
            if (!legacy.source().equals(source)) {
                throw new IllegalArgumentException("Legacy FCL program does not match source object");
            }
            return legacy;
        }
        throw new IllegalArgumentException("Unsupported FCL program format: " + formatVersion);
    }

    /**
     * Reads the V002 source envelope. This is deliberately read-only compatibility support:
     * newly persisted programs always use {@link #toBytes(FclProgram)}.
     */
    public FclProgram decodeLegacyJson(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, Object> encoded = gson.fromJson(json, LEGACY_MAP_TYPE);
        if (encoded == null) throw new IllegalArgumentException("Program JSON cannot be null");
        int version = integer(encoded.get("formatVersion"), "formatVersion");
        if (version != LEGACY_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported legacy FCL program format: " + version);
        }
        String source = string(encoded.get("source"), "source");
        String expectedHash = string(encoded.get("sourceHash"), "sourceHash");
        FclProgram program = legacyCompiler.compile(source);
        if (!program.sourceHash().equals(expectedHash)) {
            throw new IllegalArgumentException("FCL program source hash mismatch");
        }
        return program;
    }

    /** Test/support helper for constructing an authentic V002 artifact. */
    public String toLegacyJson(FclProgram program) {
        Objects.requireNonNull(program, "program");
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("formatVersion", LEGACY_FORMAT_VERSION);
        encoded.put("source", program.source());
        encoded.put("sourceHash", program.sourceHash());
        return gson.toJson(encoded);
    }

    private FclProgram decodeBinary(byte[] encoded, int expectedFormat, String source) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IllegalArgumentException("Invalid FCLB magic");
            int format = input.readInt();
            if (format != expectedFormat || !supportsFormat(format)
                    || format < FIRST_BINARY_FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported FCLB format: " + format);
            }
            String expectedHash = readString(input);
            List<FclInstruction> instructions = readInstructions(input);
            Map<String, FclProgram.Function> functions = readFunctions(input);
            Map<String, FclProgram.ClassDefinition> classes = readClasses(input);
            if (input.read() != -1) throw new IllegalArgumentException("Unexpected trailing FCLB bytes");
            FclProgram program = new FclProgram(instructions, functions, classes, source);
            if (!program.sourceHash().equals(expectedHash)) {
                throw new IllegalArgumentException("FCLB source hash mismatch");
            }
            return program;
        } catch (EOFException failure) {
            throw new IllegalArgumentException("Truncated FCLB artifact", failure);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid FCLB artifact", failure);
        }
    }

    private static void writeInstructions(DataOutputStream output, List<FclInstruction> values)
            throws IOException {
        writeCount(output, values.size(), "instruction");
        for (FclInstruction instruction : values) writeInstruction(output, instruction);
    }

    private static List<FclInstruction> readInstructions(DataInputStream input) throws IOException {
        int count = readCount(input, "instruction");
        List<FclInstruction> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(readInstruction(input));
        return values;
    }

    private static void writeInstruction(DataOutputStream output, FclInstruction value)
            throws IOException {
        if (value instanceof FclInstruction.Assignment instruction) {
            output.writeByte(ASSIGNMENT); output.writeInt(instruction.line());
            writeString(output, instruction.variable()); writeExpressions(output, instruction.indices());
            writeExpression(output, instruction.value(), 0); return;
        }
        if (value instanceof FclInstruction.Link instruction) {
            output.writeByte(LINK); output.writeInt(instruction.line());
            writeString(output, instruction.target()); writeString(output, instruction.source()); return;
        }
        if (value instanceof FclInstruction.Evaluation instruction) {
            output.writeByte(EVALUATION); output.writeInt(instruction.line());
            writeExpression(output, instruction.expression(), 0); return;
        }
        if (value instanceof FclInstruction.Conditional instruction) {
            output.writeByte(CONDITIONAL); output.writeInt(instruction.line());
            writeExpression(output, instruction.condition(), 0); output.writeInt(instruction.falseTarget());
            output.writeInt(instruction.endTarget()); return;
        }
        if (value instanceof FclInstruction.Loop instruction) {
            output.writeByte(LOOP); output.writeInt(instruction.line());
            writeExpression(output, instruction.condition(), 0); output.writeInt(instruction.bodyTarget());
            output.writeInt(instruction.endTarget()); return;
        }
        if (value instanceof FclInstruction.Break instruction) {
            output.writeByte(BREAK); output.writeInt(instruction.line()); return;
        }
        if (value instanceof FclInstruction.Continue instruction) {
            output.writeByte(CONTINUE); output.writeInt(instruction.line()); return;
        }
        if (value instanceof FclInstruction.Update instruction) {
            output.writeByte(UPDATE); output.writeInt(instruction.line());
            writeString(output, instruction.variable()); writeExpressions(output, instruction.indices());
            output.writeInt(instruction.delta()); return;
        }
        if (value instanceof FclInstruction.Return instruction) {
            output.writeByte(RETURN); output.writeInt(instruction.line());
            writeExpression(output, instruction.value(), 0); output.writeBoolean(instruction.implicit()); return;
        }
        if (value instanceof FclInstruction.FunctionDeclaration instruction) {
            output.writeByte(FUNCTION_DECLARATION); output.writeInt(instruction.line());
            writeString(output, instruction.name()); writeStrings(output, instruction.parameters());
            output.writeInt(instruction.bodyTarget()); output.writeInt(instruction.endTarget());
            output.writeBoolean(instruction.publicBinding()); return;
        }
        if (value instanceof FclInstruction.Import instruction) {
            output.writeByte(IMPORT); output.writeInt(instruction.line());
            writeString(output, instruction.target()); writeNullableString(output, instruction.alias());
            output.writeBoolean(instruction.wildcard()); return;
        }
        if (value instanceof FclInstruction.Include instruction) {
            output.writeByte(INCLUDE); output.writeInt(instruction.line());
            writeString(output, instruction.target()); return;
        }
        if (value instanceof FclInstruction.TryStart instruction) {
            output.writeByte(TRY_START); output.writeInt(instruction.line());
            output.writeInt(instruction.catchTarget()); output.writeInt(instruction.catchEndTarget());
            writeString(output, instruction.catchVariable()); return;
        }
        if (value instanceof FclInstruction.CatchEnter instruction) {
            output.writeByte(CATCH_ENTER); output.writeInt(instruction.line()); return;
        }
        if (value instanceof FclInstruction.CatchEnd instruction) {
            output.writeByte(CATCH_END); output.writeInt(instruction.line()); return;
        }
        if (value instanceof FclInstruction.Jump instruction) {
            output.writeByte(JUMP); output.writeInt(instruction.line()); output.writeInt(instruction.target()); return;
        }
        throw new IllegalArgumentException("Unsupported FCL instruction: " + value.getClass().getName());
    }

    private static FclInstruction readInstruction(DataInputStream input) throws IOException {
        byte opcode = input.readByte();
        int line = input.readInt();
        return switch (opcode) {
            case ASSIGNMENT -> new FclInstruction.Assignment(line, readString(input), readExpressions(input),
                    readExpression(input, 0));
            case LINK -> new FclInstruction.Link(line, readString(input), readString(input));
            case EVALUATION -> new FclInstruction.Evaluation(line, readExpression(input, 0));
            case CONDITIONAL -> new FclInstruction.Conditional(line, readExpression(input, 0),
                    input.readInt(), input.readInt());
            case LOOP -> new FclInstruction.Loop(line, readExpression(input, 0), input.readInt(), input.readInt());
            case BREAK -> new FclInstruction.Break(line);
            case CONTINUE -> new FclInstruction.Continue(line);
            case UPDATE -> new FclInstruction.Update(line, readString(input), readExpressions(input), input.readInt());
            case RETURN -> new FclInstruction.Return(line, readExpression(input, 0), input.readBoolean());
            case FUNCTION_DECLARATION -> new FclInstruction.FunctionDeclaration(line, readString(input),
                    readStrings(input), input.readInt(), input.readInt(), input.readBoolean());
            case IMPORT -> new FclInstruction.Import(line, readString(input), readNullableString(input),
                    input.readBoolean());
            case INCLUDE -> new FclInstruction.Include(line, readString(input));
            case TRY_START -> new FclInstruction.TryStart(line, input.readInt(), input.readInt(), readString(input));
            case CATCH_ENTER -> new FclInstruction.CatchEnter(line);
            case CATCH_END -> new FclInstruction.CatchEnd(line);
            case JUMP -> new FclInstruction.Jump(line, input.readInt());
            default -> throw new IllegalArgumentException("Unknown FCLB instruction opcode: " + opcode);
        };
    }

    private static void writeFunctions(DataOutputStream output, Map<String, FclProgram.Function> values)
            throws IOException {
        writeCount(output, values.size(), "function");
        for (Map.Entry<String, FclProgram.Function> entry : values.entrySet()) {
            FclProgram.Function function = entry.getValue();
            if (function.moduleBindings() != null) {
                throw new IllegalArgumentException("Executable FCLB cannot persist linked module bindings");
            }
            writeString(output, entry.getKey()); writeString(output, function.name());
            writeStrings(output, function.parameters()); output.writeInt(function.entryPoint());
            output.writeInt(function.endPoint()); writeNullableString(output, function.packageIdentity());
            output.writeBoolean(function.publicBinding());
        }
    }

    private static Map<String, FclProgram.Function> readFunctions(DataInputStream input) throws IOException {
        int count = readCount(input, "function");
        Map<String, FclProgram.Function> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String key = readString(input); String name = readString(input); List<String> parameters = readStrings(input);
            FclProgram.Function function = new FclProgram.Function(name, parameters, input.readInt(), input.readInt(),
                    readNullableString(input), input.readBoolean(), null);
            if (values.putIfAbsent(key, function) != null) {
                throw new IllegalArgumentException("Duplicate FCLB function key: " + key);
            }
        }
        return values;
    }

    private static void writeClasses(DataOutputStream output, Map<String, FclProgram.ClassDefinition> values)
            throws IOException {
        writeCount(output, values.size(), "class");
        for (Map.Entry<String, FclProgram.ClassDefinition> entry : values.entrySet()) {
            FclProgram.ClassDefinition definition = entry.getValue();
            writeString(output, entry.getKey()); writeString(output, definition.name());
            output.writeByte(definition.access().ordinal()); writeNullableString(output, definition.parent());
            writeCount(output, definition.fields().size(), "field");
            for (Map.Entry<String, FclProgram.Field> fieldEntry : definition.fields().entrySet()) {
                FclProgram.Field field = fieldEntry.getValue();
                writeString(output, fieldEntry.getKey()); writeString(output, field.name());
                output.writeByte(field.access().ordinal()); writeExpression(output, field.defaultValue(), 0);
            }
            writeCount(output, definition.methods().size(), "method");
            for (Map.Entry<String, FclProgram.Method> methodEntry : definition.methods().entrySet()) {
                FclProgram.Method method = methodEntry.getValue();
                writeString(output, methodEntry.getKey()); writeString(output, method.name());
                output.writeInt(method.arity()); output.writeByte(method.access().ordinal());
                writeString(output, method.functionKey()); output.writeBoolean(method.constructor());
            }
        }
    }

    private static Map<String, FclProgram.ClassDefinition> readClasses(DataInputStream input) throws IOException {
        int count = readCount(input, "class");
        Map<String, FclProgram.ClassDefinition> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String key = readString(input); String name = readString(input);
            FclProgram.Access access = access(input.readByte()); String parent = readNullableString(input);
            int fieldCount = readCount(input, "field");
            Map<String, FclProgram.Field> fields = new LinkedHashMap<>();
            for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                String fieldKey = readString(input); String fieldName = readString(input);
                FclProgram.Field field = new FclProgram.Field(fieldName, access(input.readByte()),
                        readExpression(input, 0));
                if (fields.putIfAbsent(fieldKey, field) != null) {
                    throw new IllegalArgumentException("Duplicate FCLB field key: " + fieldKey);
                }
            }
            int methodCount = readCount(input, "method");
            Map<String, FclProgram.Method> methods = new LinkedHashMap<>();
            for (int methodIndex = 0; methodIndex < methodCount; methodIndex++) {
                String methodKey = readString(input); String methodName = readString(input);
                FclProgram.Method method = new FclProgram.Method(methodName, input.readInt(), access(input.readByte()),
                        readString(input), input.readBoolean());
                if (methods.putIfAbsent(methodKey, method) != null) {
                    throw new IllegalArgumentException("Duplicate FCLB method key: " + methodKey);
                }
            }
            FclProgram.ClassDefinition definition = new FclProgram.ClassDefinition(name, access, parent, fields, methods);
            if (values.putIfAbsent(key, definition) != null) {
                throw new IllegalArgumentException("Duplicate FCLB class key: " + key);
            }
        }
        return values;
    }

    private static void writeExpressions(DataOutputStream output, List<FclExpression> values) throws IOException {
        writeCount(output, values.size(), "expression");
        for (FclExpression value : values) writeExpression(output, value, 0);
    }

    private static List<FclExpression> readExpressions(DataInputStream input) throws IOException {
        int count = readCount(input, "expression");
        List<FclExpression> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(readExpression(input, 0));
        return values;
    }

    private static void writeExpression(DataOutputStream output, FclExpression value, int depth)
            throws IOException {
        requireExpressionDepth(depth);
        if (value instanceof FclExpression.Literal expression) {
            output.writeByte(LITERAL); output.writeLong(expression.id()); writeLiteral(output, expression.value()); return;
        }
        if (value instanceof FclExpression.Variable expression) {
            output.writeByte(VARIABLE); output.writeLong(expression.id()); writeString(output, expression.name()); return;
        }
        if (value instanceof FclExpression.ArrayLiteral expression) {
            output.writeByte(ARRAY_LITERAL); output.writeLong(expression.id());
            writeExpressions(output, expression.elements()); return;
        }
        if (value instanceof FclExpression.MapLiteral expression) {
            output.writeByte(MAP_LITERAL); output.writeLong(expression.id());
            writeCount(output, expression.entries().size(), "map entry");
            for (FclExpression.MapEntry entry : expression.entries()) {
                writeExpression(output, entry.key(), depth + 1); writeExpression(output, entry.value(), depth + 1);
            }
            return;
        }
        if (value instanceof FclExpression.Unary expression) {
            output.writeByte(UNARY); output.writeLong(expression.id()); writeString(output, expression.operator());
            writeExpression(output, expression.operand(), depth + 1); return;
        }
        if (value instanceof FclExpression.Binary expression) {
            output.writeByte(BINARY); output.writeLong(expression.id()); writeString(output, expression.operator());
            writeExpression(output, expression.left(), depth + 1); writeExpression(output, expression.right(), depth + 1); return;
        }
        if (value instanceof FclExpression.Index expression) {
            output.writeByte(INDEX); output.writeLong(expression.id());
            writeExpression(output, expression.target(), depth + 1); writeExpression(output, expression.index(), depth + 1); return;
        }
        if (value instanceof FclExpression.Call expression) {
            output.writeByte(CALL); output.writeLong(expression.id()); writeString(output, expression.name());
            writeExpressions(output, expression.arguments()); return;
        }
        if (value instanceof FclExpression.Member expression) {
            output.writeByte(MEMBER); output.writeLong(expression.id());
            writeExpression(output, expression.target(), depth + 1); writeString(output, expression.name()); return;
        }
        if (value instanceof FclExpression.Update expression) {
            output.writeByte(EXPRESSION_UPDATE); output.writeLong(expression.id()); writeString(output, expression.variable());
            writeExpressions(output, expression.indices()); output.writeInt(expression.delta()); return;
        }
        if (value instanceof FclExpression.DestroyTarget expression) {
            output.writeByte(DESTROY_TARGET); output.writeLong(expression.id());
            writeString(output, expression.functionName()); writeString(output, expression.rootName());
            writeExpressions(output, expression.indices()); return;
        }
        if (value instanceof FclExpression.NewObject expression) {
            output.writeByte(NEW_OBJECT); output.writeLong(expression.id()); writeString(output, expression.className());
            writeExpressions(output, expression.arguments()); return;
        }
        if (value instanceof FclExpression.SuperConstructor expression) {
            output.writeByte(SUPER_CONSTRUCTOR); output.writeLong(expression.id());
            writeExpressions(output, expression.arguments()); return;
        }
        throw new IllegalArgumentException("Unsupported FCL expression: " + value.getClass().getName());
    }

    private static FclExpression readExpression(DataInputStream input, int depth) throws IOException {
        requireExpressionDepth(depth);
        byte opcode = input.readByte(); long id = input.readLong();
        return switch (opcode) {
            case LITERAL -> new FclExpression.Literal(id, readLiteral(input));
            case VARIABLE -> new FclExpression.Variable(id, readString(input));
            case ARRAY_LITERAL -> new FclExpression.ArrayLiteral(id, readExpressions(input));
            case MAP_LITERAL -> {
                int count = readCount(input, "map entry"); List<FclExpression.MapEntry> entries = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    entries.add(new FclExpression.MapEntry(readExpression(input, depth + 1),
                            readExpression(input, depth + 1)));
                }
                yield new FclExpression.MapLiteral(id, entries);
            }
            case UNARY -> new FclExpression.Unary(id, readString(input), readExpression(input, depth + 1));
            case BINARY -> new FclExpression.Binary(id, readString(input), readExpression(input, depth + 1),
                    readExpression(input, depth + 1));
            case INDEX -> new FclExpression.Index(id, readExpression(input, depth + 1),
                    readExpression(input, depth + 1));
            case CALL -> new FclExpression.Call(id, readString(input), readExpressions(input));
            case MEMBER -> new FclExpression.Member(id, readExpression(input, depth + 1), readString(input));
            case EXPRESSION_UPDATE -> new FclExpression.Update(id, readString(input), readExpressions(input), input.readInt());
            case DESTROY_TARGET -> new FclExpression.DestroyTarget(id, readString(input), readString(input),
                    readExpressions(input));
            case NEW_OBJECT -> new FclExpression.NewObject(id, readString(input), readExpressions(input));
            case SUPER_CONSTRUCTOR -> new FclExpression.SuperConstructor(id, readExpressions(input));
            default -> throw new IllegalArgumentException("Unknown FCLB expression opcode: " + opcode);
        };
    }

    private static void writeLiteral(DataOutputStream output, Object value) throws IOException {
        if (value == null) { output.writeByte(NULL); return; }
        if (value instanceof String text) { output.writeByte(STRING); writeString(output, text); return; }
        if (value instanceof Long number) { output.writeByte(LONG); output.writeLong(number); return; }
        if (value instanceof Double number) { output.writeByte(DOUBLE); output.writeDouble(number); return; }
        if (value instanceof Boolean flag) { output.writeByte(BOOLEAN); output.writeBoolean(flag); return; }
        if (value instanceof Character character) { output.writeByte(CHARACTER); output.writeChar(character); return; }
        if (value instanceof BigInteger number) { output.writeByte(BIG_INTEGER); writeString(output, number.toString()); return; }
        throw new IllegalArgumentException("Unsupported FCL literal type: " + value.getClass().getName());
    }

    private static Object readLiteral(DataInputStream input) throws IOException {
        return switch (input.readByte()) {
            case NULL -> null;
            case STRING -> readString(input);
            case LONG -> input.readLong();
            case DOUBLE -> input.readDouble();
            case BOOLEAN -> input.readBoolean();
            case CHARACTER -> input.readChar();
            case BIG_INTEGER -> new BigInteger(readString(input));
            default -> throw new IllegalArgumentException("Unknown FCLB literal type");
        };
    }

    private static void writeStrings(DataOutputStream output, List<String> values) throws IOException {
        writeCount(output, values.size(), "string");
        for (String value : values) writeString(output, value);
    }

    private static List<String> readStrings(DataInputStream input) throws IOException {
        int count = readCount(input, "string"); List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(readString(input));
        return values;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("FCLB string is too large");
        output.writeInt(bytes.length); output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IllegalArgumentException("Invalid FCLB string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) writeString(output, value);
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return input.readBoolean() ? readString(input) : null;
    }

    private static void writeCount(DataOutputStream output, int count, String name) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_SIZE) throw new IllegalArgumentException("Invalid " + name + " count");
        output.writeInt(count);
    }

    private static int readCount(DataInputStream input, String name) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) throw new IllegalArgumentException("Invalid FCLB " + name + " count");
        return count;
    }

    private static void requireExpressionDepth(int depth) {
        if (depth > MAX_EXPRESSION_DEPTH) throw new IllegalArgumentException("FCLB expression nesting is too deep");
    }

    private static FclProgram.Access access(byte ordinal) {
        FclProgram.Access[] values = FclProgram.Access.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Invalid FCLB access value");
        return values[ordinal];
    }

    private static int integer(Object value, String field) {
        if (value instanceof Number number && number.doubleValue() == number.intValue()) return number.intValue();
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static String string(Object value, String field) {
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(field + " must be a string");
    }
}
