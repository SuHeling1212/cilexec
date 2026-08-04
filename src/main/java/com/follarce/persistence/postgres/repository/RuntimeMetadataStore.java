package com.follarce.persistence.postgres.repository;

import com.follarce.persistence.postgres.connection.ControlLock;
import com.follarce.persistence.postgres.error.SqlStateClassifier;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/** Database-authoritative Runtime/boot lifecycle, independent of in-memory worker objects. */
public final class RuntimeMetadataStore {
    private final DataSource dataSource;

    public RuntimeMetadataStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public BootIdentity beginBoot(String instanceName, long lockKey, String runtimeVersion,
                                  int schemaVersion, int fclFormatVersion,
                                  ControlLock.ControlIdentity controlIdentity) {
        java.util.Objects.requireNonNull(controlIdentity, "controlIdentity");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID instanceId = ensureInstance(connection, instanceName, lockKey);
            markAbandonedBoots(connection, instanceId);
            UUID runtimeId = UUID.randomUUID();
            UUID bootId = UUID.randomUUID();
            Instant now = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO meta.kernel_instance(kernel_instance_id,instance_id,runtime_version,"
                            + "fcl_runtime_format_version,hostname,container_identity,status,started_at,last_seen_at) "
                            + "VALUES (?,?,?,?,?,?,'STARTING',?,?)")) {
                statement.setObject(1, runtimeId);
                statement.setObject(2, instanceId);
                statement.setString(3, runtimeVersion);
                statement.setInt(4, fclFormatVersion);
                statement.setString(5, hostname());
                statement.setString(6, containerIdentity());
                statement.setTimestamp(7, java.sql.Timestamp.from(now));
                statement.setTimestamp(8, java.sql.Timestamp.from(now));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO meta.boot(boot_id,instance_id,kernel_instance_id,status,runtime_version,"
                            + "schema_version,fcl_runtime_format_version,control_backend_pid,"
                            + "control_backend_started_at,control_proof_lock_key,started_at) "
                            + "VALUES (?,?,?,'STARTING',?,?,?,?,?,?,?)")) {
                statement.setObject(1, bootId);
                statement.setObject(2, instanceId);
                statement.setObject(3, runtimeId);
                statement.setString(4, runtimeVersion);
                statement.setString(5, Integer.toString(schemaVersion));
                statement.setInt(6, fclFormatVersion);
                statement.setInt(7, controlIdentity.backendPid());
                statement.setTimestamp(8, java.sql.Timestamp.from(
                        controlIdentity.backendStartedAt()));
                statement.setLong(9, controlIdentity.proofLockKey());
                statement.setTimestamp(10, java.sql.Timestamp.from(now));
                statement.executeUpdate();
            }
            connection.commit();
            return new BootIdentity(instanceId, runtimeId, bootId);
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("meta.beginBoot", exception);
        }
    }

    public void markRecovering(BootIdentity identity) {
        updateBoot(identity, "RECOVERING", "recovery_completed_at=NULL", null);
    }

    public void markReady(BootIdentity identity) {
        updateBoot(identity, "ACTIVE", "recovery_completed_at=clock_timestamp(),ready_at=clock_timestamp()", null);
    }

    public void markClean(BootIdentity identity, String reason) {
        updateBoot(identity, "CLEAN", "ended_at=clock_timestamp(),shutdown_reason=?", reason);
    }

    public void markFenced(BootIdentity identity, String reason) {
        updateBoot(identity, "FENCED", "ended_at=clock_timestamp(),shutdown_reason=?", reason);
    }

    private UUID ensureInstance(Connection connection, String name, long lockKey) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT instance_id,instance_name,advisory_lock_key FROM meta.instance WHERE singleton")) {
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    if (!name.equals(rows.getString("instance_name"))
                            || lockKey != rows.getLong("advisory_lock_key")) {
                        throw new IllegalStateException("Configured instance identity differs from database");
                    }
                    return rows.getObject("instance_id", UUID.class);
                }
            }
        }
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO meta.instance(instance_id,instance_name,advisory_lock_key,status) "
                        + "VALUES (?,?,?,'INITIALIZING')")) {
            insert.setObject(1, id);
            insert.setString(2, name);
            insert.setLong(3, lockKey);
            insert.executeUpdate();
        }
        return id;
    }

    private void markAbandonedBoots(Connection connection, UUID instanceId) throws SQLException {
        try (PreparedStatement boot = connection.prepareStatement(
                "UPDATE meta.boot SET status='CRASHED',ended_at=clock_timestamp(),"
                        + "shutdown_reason='runtime disappeared before clean shutdown' WHERE instance_id=? "
                        + "AND status IN ('STARTING','RECOVERING','ACTIVE')")) {
            boot.setObject(1, instanceId);
            boot.executeUpdate();
        }
        try (PreparedStatement runtime = connection.prepareStatement(
                "UPDATE meta.kernel_instance SET status='FENCED',last_seen_at=clock_timestamp() "
                        + "WHERE instance_id=? AND status IN ('STARTING','ACTIVE','DRAINING')")) {
            runtime.setObject(1, instanceId);
            runtime.executeUpdate();
        }
    }

    private void updateBoot(BootIdentity identity, String status, String additional, String reason) {
        String sql = "UPDATE meta.boot SET status=?," + additional + " WHERE boot_id=?";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status);
                int identityIndex;
                if (reason != null) {
                    statement.setString(2, reason);
                    identityIndex = 3;
                } else {
                    identityIndex = 2;
                }
                statement.setObject(identityIndex, identity.bootId());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Boot row disappeared");
                }
            }
            try (PreparedStatement runtime = connection.prepareStatement(
                    "UPDATE meta.kernel_instance SET status=?,last_seen_at=clock_timestamp(),"
                            + "stopped_at=CASE WHEN ? IN ('CLEAN','FENCED') THEN clock_timestamp() ELSE stopped_at END "
                            + "WHERE kernel_instance_id=?")) {
                String runtimeStatus = switch (status) {
                    case "ACTIVE" -> "ACTIVE";
                    case "CLEAN" -> "STOPPED";
                    case "FENCED" -> "FENCED";
                    default -> "STARTING";
                };
                runtime.setString(1, runtimeStatus);
                runtime.setString(2, status);
                runtime.setObject(3, identity.runtimeId());
                runtime.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement(
                    "UPDATE meta.instance SET status=?,updated_at=clock_timestamp() WHERE instance_id=?")) {
                String instanceStatus = switch (status) {
                    case "ACTIVE" -> "ACTIVE";
                    case "FENCED" -> "FENCED";
                    case "CLEAN" -> "STOPPED";
                    default -> "INITIALIZING";
                };
                instance.setString(1, instanceStatus);
                instance.setObject(2, identity.instanceId());
                instance.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            throw SqlStateClassifier.classify("meta.updateBoot", exception);
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "unknown";
        }
    }

    private static String containerIdentity() {
        String hostname = System.getenv("HOSTNAME");
        return hostname == null || hostname.isBlank() ? hostname() : hostname;
    }

    public record BootIdentity(UUID instanceId, UUID runtimeId, UUID bootId) {
    }
}
