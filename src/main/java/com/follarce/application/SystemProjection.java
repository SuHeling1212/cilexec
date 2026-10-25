package com.follarce.application;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.AdminProjectionRepository;
import com.follarce.domain.port.AdminProjectionRepository.Row;
import com.follarce.domain.port.AdminProjectionRepository.TableDefinition;
import com.follarce.domain.port.TransactionContext;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclSystemHandle;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Administrator-only, read/write JSON projection at
 * {@code /.cilexec/system/db/<schema>/<table>/<key>.json}.
 *
 * <p>Rows are not stored as VFS nodes. Every open/read/write re-resolves the table and row via
 * {@link AdminProjectionRepository}; a handle grants no authority. The repository temporarily
 * switches to the runtime role for the operation; this class checks SYSTEM_ADMIN first and hides
 * the whole tree from non-administrators.
 */
public final class SystemProjection {
    static final String ROOT = "/.cilexec/system/db";
    private static final int PAGE_SIZE = 200;
    private static final Gson GSON = new Gson();

    private final TransactionContext transaction;
    private final UUID actorId;
    private final Instant now;

    public SystemProjection(TransactionContext transaction, UUID actorId, Instant now) {
        this.transaction = transaction;
        this.actorId = actorId;
        this.now = now;
    }

    public static boolean isProjectionPath(String source) {
        String path = FclPath.normalizeAbsolute(source);
        return path.equals(ROOT) || path.startsWith(ROOT + "/");
    }

    static boolean isReservedPath(String source) {
        String path = FclPath.normalizeAbsolute(source);
        return path.equals("/.cilexec") || path.startsWith("/.cilexec/")
                || path.contains("/.cilexec/") || path.endsWith("/.cilexec");
    }

    public boolean isAdministrator() {
        return transaction.auth().capabilities(actorId).contains(Capability.SYSTEM_ADMIN);
    }

    FclSystemHandle open(String source) {
        String path = FclPath.normalizeAbsolute(source);
        requireVisible(path);
        Parsed parsed = parse(path);
        switch (parsed.kind()) {
            case ROOT, SCHEMA, TABLE, PAGE -> {
                requireDirectory(parsed);
                return handle(path, "ProjectionDirectory");
            }
            case SCHEMA_FILE, NEXT_FILE, ROW -> {
                read(path);
                return handle(path, "ProjectionFile");
            }
            default -> throw notFound(path);
        }
    }

    public boolean exists(String source) {
        if (!isAdministrator()) return false;
        String path = FclPath.normalizeAbsolute(source);
        if (!isProjectionPath(path)) return false;
        try {
            Parsed parsed = parse(path);
            switch (parsed.kind()) {
                case ROOT -> {
                    return true;
                }
                case SCHEMA -> {
                    return repository().schemas(actorId).contains(parsed.schema());
                }
                case TABLE, SCHEMA_FILE, NEXT_FILE -> {
                    table(parsed);
                    return true;
                }
                case PAGE -> {
                    table(parsed);
                    decodeCursor(parsed.cursor());
                    return true;
                }
                case ROW -> {
                    table(parsed);
                    return repository().readRow(actorId, parsed.schema(), parsed.table(),
                            decodeRowKey(parsed.keyFile())).isPresent();
                }
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException missing) {
            return false;
        }
    }

    public List<Map<String, Object>> list(String source) {
        String path = FclPath.normalizeAbsolute(source);
        requireVisible(path);
        Parsed parsed = parse(path);
        return switch (parsed.kind()) {
            case ROOT -> repository().schemas(actorId).stream()
                    .map(schema -> entry(schema, "DIRECTORY", 0))
                    .toList();
            case SCHEMA -> {
                requireSchema(parsed.schema());
                yield repository().tables(actorId, parsed.schema()).stream()
                        .map(table -> entry(table, "DIRECTORY", 0))
                        .toList();
            }
            case TABLE -> tableEntries(parsed, Optional.empty());
            case PAGE -> tableEntries(parsed, Optional.of(decodeCursor(parsed.cursor())));
            default -> throw new FclRuntimeException("NotDirectory",
                    "Not a projection directory: " + path);
        };
    }

    String read(String source) {
        String path = FclPath.normalizeAbsolute(source);
        requireVisible(path);
        Parsed parsed = parse(path);
        return switch (parsed.kind()) {
            case SCHEMA_FILE -> GSON.toJson(schemaFile(table(parsed)));
            case NEXT_FILE -> nextFile(parsed);
            case ROW -> repository().readRow(actorId, parsed.schema(), parsed.table(),
                            decodeRowKey(parsed.keyFile()))
                    .orElseThrow(() -> notFound(path)).rowJson();
            default -> throw new FclRuntimeException("NotFile",
                    "Not a projection file: " + path);
        };
    }

    String readChunk(String source, long offset, int maximum) {
        byte[] bytes = read(source).getBytes(StandardCharsets.UTF_8);
        if (offset < 0 || maximum < 0 || offset > bytes.length) {
            throw new FclRuntimeException("InvalidOffset", "Projection read offset is invalid");
        }
        int length = Math.min(maximum, bytes.length - (int) offset);
        return new String(bytes, (int) offset, length, StandardCharsets.UTF_8);
    }

    long size(String source) {
        return read(source).getBytes(StandardCharsets.UTF_8).length;
    }

    String write(String source, String content) {
        String path = FclPath.normalizeAbsolute(source);
        requireVisible(path);
        Parsed parsed = parse(path);
        if (parsed.kind() != Kind.ROW) {
            throw new FclRuntimeException("ReadOnlyProjection",
                    "Only row files are writable: " + path);
        }
        table(parsed);
        String key = decodeRowKey(parsed.keyFile());
        String merged = mergeKey(content, key);
        repository().writeRow(actorId, parsed.schema(), parsed.table(), merged);
        return path;
    }

    String create(String source, String content) {
        return write(source, content);
    }

    boolean remove(String source) {
        String path = FclPath.normalizeAbsolute(source);
        requireVisible(path);
        Parsed parsed = parse(path);
        if (parsed.kind() != Kind.ROW) {
            throw new FclRuntimeException("ReadOnlyProjection",
                    "Only row files can be removed: " + path);
        }
        table(parsed);
        return repository().deleteRow(actorId, parsed.schema(), parsed.table(),
                decodeRowKey(parsed.keyFile()));
    }

    Map<String, Object> metadata(String source) {
        String path = FclPath.normalizeAbsolute(source);
        requireVisible(path);
        Parsed parsed = parse(path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name(path));
        result.put("path", path);
        result.put("projection", "database");
        switch (parsed.kind()) {
            case ROOT, SCHEMA, TABLE, PAGE -> {
                result.put("type", "DIRECTORY");
                result.put("size", 0L);
            }
            case SCHEMA_FILE, NEXT_FILE, ROW -> {
                result.put("type", "FILE");
                result.put("size", size(path));
                result.put("schema", parsed.schema());
                result.put("table", parsed.table());
                if (parsed.kind() == Kind.ROW) {
                    result.put("key", decodeRowKey(parsed.keyFile()));
                }
            }
            default -> throw notFound(path);
        }
        return Map.copyOf(result);
    }

    /** Directory view including the two shell-only aliases above {@code system/db}. */
    public List<Map<String, Object>> listShell(String source) {
        String path = FclPath.normalizeAbsolute(source);
        requireVisible(path);
        if (path.equals("/.cilexec")) {
            return List.of(entry("system", "DIRECTORY", 0));
        }
        if (path.equals("/.cilexec/system")) {
            return List.of(entry("db", "DIRECTORY", 0));
        }
        return list(path);
    }

    /** Whether a shell path can become the working directory. */
    public boolean isShellDirectory(String source) {
        if (!isAdministrator()) return false;
        String path = FclPath.normalizeAbsolute(source);
        if (path.equals("/.cilexec") || path.equals("/.cilexec/system")) return true;
        if (!isProjectionPath(path)) return false;
        try {
            listShell(path);
            return true;
        } catch (RuntimeException notDirectory) {
            return false;
        }
    }

    private List<Map<String, Object>> tableEntries(Parsed parsed, Optional<String> after) {
        TableDefinition definition = table(parsed);
        List<Row> rows = repository().listRows(actorId, parsed.schema(), parsed.table(),
                after, PAGE_SIZE);
        List<Map<String, Object>> entries = new ArrayList<>();
        if (after.isEmpty()) {
            entries.add(entry("_schema.json", "FILE", GSON.toJson(schemaFile(definition))
                    .getBytes(StandardCharsets.UTF_8).length));
        }
        for (Row row : rows) {
            entries.add(entry(rowName(row.keyJson()), "FILE", row.rowJson()
                    .getBytes(StandardCharsets.UTF_8).length));
        }
        if (rows.size() == PAGE_SIZE) {
            String cursor = encodeCursor(rows.getLast().keyJson());
            entries.add(entry("_next.json", "FILE", nextFile(parsed, cursor)
                    .getBytes(StandardCharsets.UTF_8).length));
        }
        return List.copyOf(entries);
    }

    private String nextFile(Parsed parsed) {
        TableDefinition definition = table(parsed);
        Optional<String> after = parsed.keyFile().isEmpty()
                ? Optional.empty() : Optional.of(decodeCursor(parsed.keyFile()));
        List<Row> rows = repository().listRows(actorId, definition.schema(), definition.table(),
                after, PAGE_SIZE);
        if (rows.size() < PAGE_SIZE) {
            return "{\"next\":null}";
        }
        return nextFile(parsed, encodeCursor(rows.getLast().keyJson()));
    }

    private String nextFile(Parsed parsed, String cursor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("next", ROOT + "/" + parsed.schema() + "/" + parsed.table()
                + "/page/" + cursor);
        result.put("after", cursor);
        return GSON.toJson(result);
    }

    private TableDefinition table(Parsed parsed) {
        requireSchema(parsed.schema());
        try {
            return repository().describe(actorId, parsed.schema(), parsed.table());
        } catch (RuntimeException missing) {
            throw notFound(ROOT + "/" + parsed.schema() + "/" + parsed.table());
        }
    }

    private void requireSchema(String schema) {
        if (!repository().schemas(actorId).contains(schema)) {
            throw notFound(ROOT + "/" + schema);
        }
    }

    private void requireDirectory(Parsed parsed) {
        switch (parsed.kind()) {
            case ROOT -> {
            }
            case SCHEMA -> requireSchema(parsed.schema());
            case TABLE -> table(parsed);
            case PAGE -> {
                table(parsed);
                decodeCursor(parsed.cursor());
            }
            default -> throw notFound(ROOT);
        }
    }

    private void requireVisible(String path) {
        if (!isAdministrator()) {
            throw notFound(path);
        }
    }

    private AdminProjectionRepository repository() {
        return transaction.adminProjection();
    }

    private Map<String, Object> schemaFile(TableDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", definition.schema());
        result.put("table", definition.table());
        result.put("primaryKey", definition.primaryKey());
        result.put("columns", definition.columns());
        result.put("generated", definition.generated());
        result.put("identity", definition.identity());
        result.put("writable", true);
        return Map.copyOf(result);
    }

    private String mergeKey(String content, String keyJson) {
        JsonElement element = JsonParser.parseString(content);
        if (!element.isJsonObject()) {
            throw new FclRuntimeException("InvalidRow", "Row content must be a JSON object");
        }
        JsonObject row = element.getAsJsonObject();
        JsonObject key = JsonParser.parseString(keyJson).getAsJsonObject();
        for (String column : key.keySet()) {
            JsonElement supplied = row.get(column);
            if (supplied != null && !supplied.equals(key.get(column))) {
                throw new FclRuntimeException("PrimaryKeyChange",
                        "Primary-key columns cannot be changed through a row file");
            }
            row.add(column, key.get(column));
        }
        return row.toString();
    }

    private FclSystemHandle handle(String path, String kind) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("projection", "database");
        return new FclSystemHandle(kind, path, Map.copyOf(attributes));
    }

    private String decodeRowKey(String keyFile) {
        if (!keyFile.endsWith(".json") || keyFile.length() <= 5) {
            throw new FclRuntimeException("InvalidPath", "Invalid projected row name");
        }
        String encoded = keyFile.substring(0, keyFile.length() - 5);
        try {
            String json = new String(Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            if (!JsonParser.parseString(json).isJsonObject()) {
                throw new FclRuntimeException("InvalidPath", "Invalid projected row key");
            }
            return json;
        } catch (RuntimeException invalid) {
            throw new FclRuntimeException("InvalidPath", "Invalid projected row key");
        }
    }

    private String rowName(String keyJson) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keyJson.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > 200) {
            throw new FclRuntimeException("UnsupportedKey",
                    "Projected primary key is too long for a file name");
        }
        return encoded + ".json";
    }

    private String encodeCursor(String keyJson) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keyJson.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String cursor) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            if (!JsonParser.parseString(json).isJsonObject()) {
                throw new IllegalArgumentException("Not a key object");
            }
            return json;
        } catch (RuntimeException invalid) {
            throw new FclRuntimeException("InvalidCursor", "Invalid projection cursor");
        }
    }

    private Map<String, Object> entry(String name, String type, long size) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("type", type);
        entry.put("size", size);
        entry.put("projection", true);
        return Map.copyOf(entry);
    }

    private static String name(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static FclRuntimeException notFound(String path) {
        return new FclRuntimeException("PathNotFound", "No such path: " + path);
    }

    private Parsed parse(String path) {
        if (!path.equals(ROOT) && !path.startsWith(ROOT + "/")) {
            throw notFound(path);
        }
        if (path.equals(ROOT)) return new Parsed(Kind.ROOT, "", "", "");
        String rest = path.substring(ROOT.length() + 1);
        String[] parts = rest.split("/", -1);
        if (parts.length == 1) {
            return new Parsed(Kind.SCHEMA, parts[0], "", "");
        }
        if (parts.length == 2) {
            return new Parsed(Kind.TABLE, parts[0], parts[1], "");
        }
        if (parts.length == 3) {
            if (parts[2].equals("_schema.json")) {
                return new Parsed(Kind.SCHEMA_FILE, parts[0], parts[1], "");
            }
            if (parts[2].equals("_next.json")) {
                return new Parsed(Kind.NEXT_FILE, parts[0], parts[1], "");
            }
            if (parts[2].endsWith(".json")) {
                return new Parsed(Kind.ROW, parts[0], parts[1], parts[2]);
            }
            throw notFound(path);
        }
        if (parts.length == 4 && parts[2].equals("page")) {
            return new Parsed(Kind.PAGE, parts[0], parts[1], parts[3]);
        }
        if (parts.length == 5 && parts[2].equals("page") && parts[4].equals("_next.json")) {
            return new Parsed(Kind.NEXT_FILE, parts[0], parts[1], parts[3]);
        }
        throw notFound(path);
    }

    private enum Kind {
        ROOT, SCHEMA, TABLE, PAGE, SCHEMA_FILE, NEXT_FILE, ROW
    }

    private record Parsed(Kind kind, String schema, String table, String keyFile) {
        String cursor() {
            return keyFile;
        }
    }
}
