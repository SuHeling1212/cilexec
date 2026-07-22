package com.follarce.persistence.postgres.repository;

import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.FileRevision;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsMount;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import com.google.gson.reflect.TypeToken;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class JdbcVfsRepository extends JdbcRepositorySupport implements VfsRepository {
    private final JsonCodec json;

    public JdbcVfsRepository(Connection connection, JsonCodec json) {
        super(connection);
        this.json = json;
    }

    @Override
    public void saveObject(StoredObject object) {
        String sql = "SELECT object_store.put_object(?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(object.objectHash()));
            statement.setString(2, object.mediaType());
            statement.setBytes(3, object.content().bytes());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !object.objectHash().equals(JdbcValues.hash(rows.getBytes(1)))) {
                    throw new IllegalStateException("Object store did not confirm the supplied hash");
                }
            }
        } catch (SQLException exception) {
            throw failure("object_store.save", exception);
        }
    }

    @Override
    public Optional<StoredObject> findObject(ObjectHash objectHash) {
        String sql = "SELECT object_hash,byte_size,media_type,content,created_at "
                + "FROM object_store.read_object(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(objectHash));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new StoredObject(
                        JdbcValues.hash(rows.getBytes("object_hash")),
                        rows.getLong("byte_size"),
                        rows.getString("media_type"),
                        new BinaryContent(rows.getBytes("content")),
                        rows.getTimestamp("created_at").toInstant()
                ));
            }
        } catch (SQLException exception) {
            throw failure("object_store.find", exception);
        }
    }

    @Override
    public Optional<VfsNode> findNode(UUID nodeId) {
        return find("vfs.findNode", "SELECT * FROM vfs.node WHERE node_id=?",
                statement -> statement.setObject(1, nodeId));
    }

    @Override
    public Optional<VfsNode> findChild(UUID ownerId, Optional<UUID> parentNodeId, String name) {
        return find("vfs.findChild", "SELECT * FROM vfs.node WHERE owner_id=? "
                        + "AND parent_node_id IS NOT DISTINCT FROM ? AND node_name=?",
                statement -> {
                    statement.setObject(1, ownerId);
                    JdbcValues.nullableUuid(statement, 2, parentNodeId);
                    statement.setString(3, name);
                });
    }

    @Override
    public void insertNode(VfsNode node) {
        String sql = "INSERT INTO vfs.node(node_id,owner_id,parent_node_id,node_name,node_type,"
                + "current_object_hash,capability_json,revision_enabled,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, node.nodeId());
            statement.setObject(2, node.ownerId());
            JdbcValues.nullableUuid(statement, 3, node.parentNodeId());
            statement.setString(4, node.name());
            statement.setString(5, node.type().name());
            if (node.currentObjectHash().isPresent()) {
                statement.setBytes(6, JdbcValues.hash(node.currentObjectHash().get()));
            } else {
                statement.setNull(6, java.sql.Types.BINARY);
            }
            statement.setObject(7, JdbcValues.json(json.write(node.capabilities())));
            statement.setBoolean(8, node.revisionEnabled());
            statement.setTimestamp(9, java.sql.Timestamp.from(node.createdAt()));
            statement.setTimestamp(10, java.sql.Timestamp.from(node.updatedAt()));
            requireOne("vfs.insertNode", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("vfs.insertNode", exception);
        }
    }

    @Override
    public boolean replaceContent(UUID nodeId, Optional<ObjectHash> expectedObjectHash,
                                  ObjectHash replacementObjectHash, Instant updatedAt) {
        String sql = "UPDATE vfs.node SET current_object_hash=?,state_version=state_version+1,updated_at=? "
                + "WHERE node_id=? AND node_type IN ('FILE','SYMLINK') "
                + "AND current_object_hash IS NOT DISTINCT FROM ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(replacementObjectHash));
            statement.setTimestamp(2, java.sql.Timestamp.from(updatedAt));
            statement.setObject(3, nodeId);
            if (expectedObjectHash.isPresent()) statement.setBytes(4, JdbcValues.hash(expectedObjectHash.get()));
            else statement.setNull(4, java.sql.Types.BINARY);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("vfs.replaceContent", exception);
        }
    }

    @Override
    public FileRevision appendRevision(UUID revisionId, UUID nodeId, UUID ownerId,
                                       ObjectHash objectHash, UUID createdBy,
                                       Instant createdAt) {
        if (!ownerId.equals(createdBy)) {
            throw new IllegalArgumentException("File revision creator must be its owner");
        }
        String sql = "SELECT * FROM vfs.append_file_revision(?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, nodeId);
            statement.setObject(3, ownerId);
            statement.setBytes(4, JdbcValues.hash(objectHash));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw com.follarce.persistence.postgres.error.SqlStateClassifier
                            .optimisticConflict("vfs.appendRevision");
                }
                return mapRevision(rows);
            }
        } catch (SQLException exception) {
            throw failure("vfs.appendRevision", exception);
        }
    }

    @Override
    public Optional<FileRevision> findRevision(UUID nodeId, long revisionNumber) {
        String sql = "SELECT * FROM vfs.file_revision WHERE node_id=? AND revision_number=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, nodeId);
            statement.setLong(2, revisionNumber);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapRevision(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("vfs.findRevision", exception);
        }
    }

    @Override
    public List<FileRevision> findRevisions(UUID nodeId) {
        String sql = "SELECT * FROM vfs.file_revision WHERE node_id=? "
                + "ORDER BY revision_number";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, nodeId);
            try (ResultSet rows = statement.executeQuery()) {
                List<FileRevision> revisions = new ArrayList<>();
                while (rows.next()) revisions.add(mapRevision(rows));
                return List.copyOf(revisions);
            }
        } catch (SQLException exception) {
            throw failure("vfs.findRevisions", exception);
        }
    }

    @Override
    public void insertMount(VfsMount mount) {
        String sql = "INSERT INTO vfs.mount(mount_id,node_id,owner_id,host_source_key,"
                + "container_path,required_capability,read_only,status,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, mount.mountId());
            statement.setObject(2, mount.nodeId());
            statement.setObject(3, mount.ownerId());
            statement.setString(4, mount.hostSourceKey());
            statement.setString(5, mount.containerPath());
            statement.setString(6, mount.requiredCapability());
            statement.setBoolean(7, mount.readOnly());
            statement.setString(8, mount.status().name());
            statement.setTimestamp(9, java.sql.Timestamp.from(mount.createdAt()));
            requireOne("vfs.insertMount", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("vfs.insertMount", exception);
        }
    }

    @Override
    public Optional<VfsMount> findMount(UUID mountId) {
        String sql = "SELECT * FROM vfs.mount WHERE mount_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, mountId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapMount(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("vfs.findMount", exception);
        }
    }

    @Override
    public List<VfsMount> findMounts(UUID ownerId) {
        String sql = "SELECT * FROM vfs.mount WHERE owner_id=? ORDER BY created_at,mount_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                List<VfsMount> mounts = new ArrayList<>();
                while (rows.next()) mounts.add(mapMount(rows));
                return List.copyOf(mounts);
            }
        } catch (SQLException exception) {
            throw failure("vfs.findMounts", exception);
        }
    }

    @Override
    public boolean disableMount(UUID mountId, UUID ownerId) {
        String sql = "UPDATE vfs.mount SET status='DISABLED' "
                + "WHERE mount_id=? AND owner_id=? AND status='ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, mountId);
            statement.setObject(2, ownerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("vfs.disableMount", exception);
        }
    }

    private Optional<VfsNode> find(String operation, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    private VfsNode map(ResultSet rows) throws SQLException {
        byte[] hash = rows.getBytes("current_object_hash");
        Set<String> capabilities = json.read(rows.getString("capability_json"),
                new TypeToken<LinkedHashSet<String>>() { }.getType());
        return new VfsNode(
                rows.getObject("node_id", UUID.class),
                JdbcValues.optionalUuid(rows, "parent_node_id"),
                rows.getObject("owner_id", UUID.class),
                rows.getString("node_name"),
                VfsNode.Type.valueOf(rows.getString("node_type")),
                hash == null ? Optional.empty() : Optional.of(JdbcValues.hash(hash)),
                capabilities,
                rows.getBoolean("revision_enabled"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant()
        );
    }

    private static FileRevision mapRevision(ResultSet rows) throws SQLException {
        return new FileRevision(
                rows.getObject("revision_id", UUID.class),
                rows.getObject("node_id", UUID.class),
                rows.getObject("owner_id", UUID.class),
                rows.getLong("revision_number"),
                JdbcValues.hash(rows.getBytes("object_hash")),
                rows.getObject("created_by", UUID.class),
                rows.getTimestamp("created_at").toInstant()
        );
    }

    private static VfsMount mapMount(ResultSet rows) throws SQLException {
        return new VfsMount(
                rows.getObject("mount_id", UUID.class),
                rows.getObject("node_id", UUID.class),
                rows.getObject("owner_id", UUID.class),
                rows.getString("host_source_key"),
                rows.getString("container_path"),
                rows.getString("required_capability"),
                rows.getBoolean("read_only"),
                VfsMount.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant()
        );
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
}
