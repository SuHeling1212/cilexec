package com.follarce.persistence.postgres.repository;

import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.FileRevision;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcVfsRepositoryTest {
    private static final Instant SERVER_TIME = Instant.parse("2026-07-22T09:00:00Z");

    @Test
    void appendsThroughTheIdentityBoundDatabaseFunction() {
        UUID revisionId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ObjectHash objectHash = ObjectHash.sha256(new BinaryContent(
                "content".getBytes(StandardCharsets.UTF_8)));
        JdbcCapture capture = new JdbcCapture(revisionId, nodeId, ownerId, objectHash);
        JdbcVfsRepository repository = new JdbcVfsRepository(capture.connection(), new JsonCodec());

        FileRevision revision = repository.appendRevision(revisionId, nodeId, ownerId,
                objectHash, ownerId, SERVER_TIME.minusSeconds(30));

        assertEquals("SELECT * FROM vfs.append_file_revision(?,?,?,?)", capture.sql);
        assertEquals(4, capture.parameters.size());
        assertEquals(revisionId, capture.parameters.get(1));
        assertEquals(nodeId, capture.parameters.get(2));
        assertEquals(ownerId, capture.parameters.get(3));
        assertEquals(objectHash, revision.objectHash());
        assertEquals(1, revision.revisionNumber());
        assertEquals(SERVER_TIME, revision.createdAt());
    }

    @Test
    void rejectsAClaimedCreatorThatDiffersFromTheOwnerBeforeJdbc() {
        JdbcCapture capture = new JdbcCapture(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ObjectHash.sha256(new BinaryContent(new byte[]{1})));
        JdbcVfsRepository repository = new JdbcVfsRepository(capture.connection(), new JsonCodec());

        assertThrows(IllegalArgumentException.class, () -> repository.appendRevision(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ObjectHash.sha256(new BinaryContent(new byte[]{2})), UUID.randomUUID(), SERVER_TIME));
        assertEquals(null, capture.sql);
    }

    private static final class JdbcCapture {
        private final UUID revisionId;
        private final UUID nodeId;
        private final UUID ownerId;
        private final ObjectHash objectHash;
        private final Map<Integer, Object> parameters = new LinkedHashMap<>();
        private String sql;

        private JdbcCapture(UUID revisionId, UUID nodeId, UUID ownerId, ObjectHash objectHash) {
            this.revisionId = revisionId;
            this.nodeId = nodeId;
            this.ownerId = ownerId;
            this.objectHash = objectHash;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) {
                            sql = (String) arguments[0];
                            return preparedStatement();
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (method.getName().startsWith("set") && arguments != null
                                && arguments.length >= 2 && arguments[0] instanceof Integer index) {
                            parameters.put(index, arguments[1]);
                            return null;
                        }
                        if (method.getName().equals("executeQuery")) return resultSet();
                        return defaultValue(method.getReturnType());
                    });
        }

        private ResultSet resultSet() {
            boolean[] available = {true};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("next")) {
                            boolean current = available[0];
                            available[0] = false;
                            return current;
                        }
                        if (name.equals("getObject")) {
                            return switch ((String) arguments[0]) {
                                case "revision_id" -> revisionId;
                                case "node_id" -> nodeId;
                                case "owner_id", "created_by" -> ownerId;
                                default -> null;
                            };
                        }
                        if (name.equals("getLong")) return 1L;
                        if (name.equals("getBytes")) return java.util.HexFormat.of()
                                .parseHex(objectHash.value());
                        if (name.equals("getTimestamp")) return Timestamp.from(SERVER_TIME);
                        return defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
