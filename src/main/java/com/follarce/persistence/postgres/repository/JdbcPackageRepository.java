package com.follarce.persistence.postgres.repository;

import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.port.PackageRepository;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcPackageRepository extends JdbcRepositorySupport implements PackageRepository {
    private static final String RELEASE_COLUMNS = "release.package_hash,release.namespace,release.package_name,"
            + "release.package_version,release.database_object_hash,release.database_file_hash,"
            + "release.created_at";
    private final JsonCodec json;

    public JdbcPackageRepository(Connection connection) {
        this(connection, new JsonCodec());
    }

    public JdbcPackageRepository(Connection connection, JsonCodec json) {
        super(connection);
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    @Override
    public ReleaseWriteResult registerRelease(PackageIndex packageIndex) {
        PackageRelease release = packageIndex.release();
        String sql = "SELECT package.register_release_bundle(?,?,?,?,?,?,?,"
                + "?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(release.packageHash().value()));
            statement.setString(2, release.coordinate().namespace());
            statement.setString(3, release.coordinate().name());
            statement.setString(4, release.coordinate().version());
            statement.setBytes(5, JdbcValues.hash(release.databaseObjectHash()));
            statement.setBytes(6, JdbcValues.hash(release.databaseFileHash()));
            statement.setInt(7, 1);
            statement.setString(8, json.write(moduleJson(packageIndex.modules())));
            statement.setString(9, json.write(dependencyJson(packageIndex.dependencies())));
            statement.setString(10, json.write(entrypointJson(packageIndex.entrypoints())));
            statement.setString(11, json.write(exportJson(packageIndex.exports())));
            statement.setString(12, json.write(capabilityJson(packageIndex.capabilities())));
            statement.setTimestamp(13, java.sql.Timestamp.from(release.importedAt()));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException(
                            "Package registration function returned no result");
                }
                if (rows.getBoolean(1)) return ReleaseWriteResult.REGISTERED;
            }
        } catch (SQLException exception) {
            throw failure("package.registerRelease", exception);
        }
        Optional<PackageRelease> coordinate = findRelease(release.coordinate());
        if (coordinate.isPresent() && coordinate.get().packageHash().equals(release.packageHash())) {
            return ReleaseWriteResult.ALREADY_PRESENT;
        }
        return ReleaseWriteResult.COORDINATE_CONFLICT;
    }

    @Override
    public Optional<PackageRelease> findRelease(PackageRelease.Hash packageHash) {
        return findRelease("package.findByHash", "WHERE release.package_hash=?",
                statement -> statement.setBytes(1, JdbcValues.hash(packageHash.value())));
    }

    @Override
    public Optional<PackageRelease> findRelease(PackageRelease.Coordinate coordinate) {
        return findRelease("package.findByCoordinate",
                "WHERE release.namespace=? AND release.package_name=? AND release.package_version=?",
                statement -> {
                    statement.setString(1, coordinate.namespace());
                    statement.setString(2, coordinate.name());
                    statement.setString(3, coordinate.version());
                });
    }

    @Override
    public Optional<PackageRelease> findReleaseByDatabaseFileHash(
            com.follarce.domain.vfs.ObjectHash databaseFileHash) {
        return findRelease("package.findByDatabaseFileHash",
                "WHERE release.database_file_hash=?",
                statement -> statement.setBytes(1, JdbcValues.hash(databaseFileHash)));
    }

    @Override
    public List<PackageRelease> findReleases() {
        String sql = "SELECT " + RELEASE_COLUMNS + " FROM package.release AS release "
                + "ORDER BY release.namespace,release.package_name,release.package_version";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<PackageRelease> releases = new java.util.ArrayList<>();
            while (rows.next()) releases.add(mapRelease(rows));
            return List.copyOf(releases);
        } catch (SQLException exception) {
            throw failure("package.findReleases", exception);
        }
    }

    @Override
    public void saveEnvironment(PackageEnvironment environment) {
        String sql = "INSERT INTO package.environment(environment_id,owner_id,environment_name,"
                + "parent_environment_id,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?) "
                + "ON CONFLICT (environment_id) DO UPDATE SET environment_name=EXCLUDED.environment_name,"
                + "parent_environment_id=EXCLUDED.parent_environment_id,status=EXCLUDED.status,"
                + "updated_at=EXCLUDED.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, environment.environmentId());
            statement.setObject(2, environment.ownerId());
            statement.setString(3, environment.name());
            JdbcValues.nullableUuid(statement, 4, environment.parentEnvironmentId());
            statement.setString(5, environment.status().name());
            statement.setTimestamp(6, java.sql.Timestamp.from(environment.createdAt()));
            statement.setTimestamp(7, java.sql.Timestamp.from(environment.createdAt()));
            requireOne("package.saveEnvironment", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("package.saveEnvironment", exception);
        }
    }

    @Override
    public Optional<PackageEnvironment> findEnvironment(UUID environmentId) {
        return findEnvironment("package.findEnvironment", "WHERE environment_id=?",
                statement -> statement.setObject(1, environmentId));
    }

    @Override
    public Optional<PackageEnvironment> findEnvironmentByName(String name) {
        return findEnvironment("package.findEnvironmentByName", "WHERE environment_name=?",
                statement -> statement.setString(1, name));
    }

    @Override
    public List<PackageEnvironment> findEnvironments() {
        String sql = "SELECT environment_id,owner_id,environment_name,parent_environment_id,"
                + "status,created_at FROM package.environment ORDER BY environment_name,environment_id";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<PackageEnvironment> environments = new java.util.ArrayList<>();
            while (rows.next()) environments.add(mapEnvironment(rows));
            return List.copyOf(environments);
        } catch (SQLException exception) {
            throw failure("package.findEnvironments", exception);
        }
    }

    @Override
    public void saveBinding(PackageBinding binding) {
        String sql = "INSERT INTO package.binding(environment_id,owner_id,binding_name,package_hash,bound_at,bound_by) "
                + "SELECT environment_id,owner_id,?,?,?,owner_id FROM package.environment WHERE environment_id=? "
                + "ON CONFLICT (environment_id,binding_name) DO UPDATE SET package_hash=EXCLUDED.package_hash,"
                + "bound_at=EXCLUDED.bound_at,bound_by=EXCLUDED.bound_by";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, binding.binding());
            statement.setBytes(2, JdbcValues.hash(binding.packageHash().value()));
            statement.setTimestamp(3, java.sql.Timestamp.from(binding.createdAt()));
            statement.setObject(4, binding.environmentId());
            requireOne("package.saveBinding", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("package.saveBinding", exception);
        }
    }

    @Override
    public Optional<PackageBinding> findBinding(UUID environmentId, String binding) {
        String sql = "SELECT environment_id,binding_name,package_hash,bound_at FROM package.binding "
                + "WHERE environment_id=? AND binding_name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, environmentId);
            statement.setString(2, binding);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new PackageBinding(
                        rows.getObject("environment_id", UUID.class),
                        rows.getString("binding_name"),
                        new PackageRelease.Hash(JdbcValues.hash(rows.getBytes("package_hash"))),
                        rows.getTimestamp("bound_at").toInstant()
                ));
            }
        } catch (SQLException exception) {
            throw failure("package.findBinding", exception);
        }
    }

    @Override
    public boolean deleteBinding(UUID environmentId, String binding) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM package.binding WHERE environment_id=? AND binding_name=?")) {
            statement.setObject(1, environmentId);
            statement.setString(2, binding);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw failure("package.deleteBinding", exception);
        }
    }

    @Override
    public void saveProcessBinding(ProcessPackageBinding binding) {
        String sql = "INSERT INTO process.package_binding(process_uid,owner_id,import_name,environment_id,"
                + "package_hash,resolved_at) SELECT process_uid,owner_id,?,?,?,? FROM process.process "
                + "WHERE process_uid=? ON CONFLICT (process_uid,import_name) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, binding.importName());
            statement.setObject(2, binding.environmentId());
            statement.setBytes(3, JdbcValues.hash(binding.packageHash().value()));
            statement.setTimestamp(4, java.sql.Timestamp.from(binding.resolvedAt()));
            statement.setObject(5, binding.processUid());
            int affected = statement.executeUpdate();
            if (affected == 0) {
                ProcessPackageBinding persisted = findProcessBinding(
                        binding.processUid(), binding.importName()).orElseThrow(() ->
                        new IllegalStateException("Process package binding target is missing"));
                if (!persisted.environmentId().equals(binding.environmentId())
                        || !persisted.packageHash().equals(binding.packageHash())) {
                    throw new IllegalStateException("Process package binding is immutable");
                }
            }
        } catch (SQLException exception) {
            throw failure("package.saveProcessBinding", exception);
        }
    }

    @Override
    public Optional<ProcessPackageBinding> findProcessBinding(UUID processUid, String importName) {
        String sql = "SELECT process_uid,import_name,environment_id,package_hash,resolved_at "
                + "FROM process.package_binding WHERE process_uid=? AND import_name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            statement.setString(2, importName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new ProcessPackageBinding(
                        rows.getObject("process_uid", UUID.class),
                        rows.getString("import_name"),
                        rows.getObject("environment_id", UUID.class),
                        new PackageRelease.Hash(JdbcValues.hash(rows.getBytes("package_hash"))),
                        rows.getTimestamp("resolved_at").toInstant()
                ));
            }
        } catch (SQLException exception) {
            throw failure("package.findProcessBinding", exception);
        }
    }

    @Override
    public List<ProcessPackageBinding> findProcessBindings(UUID processUid) {
        String sql = "SELECT process_uid,import_name,environment_id,package_hash,resolved_at "
                + "FROM process.package_binding WHERE process_uid=? ORDER BY import_name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            try (ResultSet rows = statement.executeQuery()) {
                List<ProcessPackageBinding> bindings = new java.util.ArrayList<>();
                while (rows.next()) bindings.add(mapProcessBinding(rows));
                return List.copyOf(bindings);
            }
        } catch (SQLException exception) {
            throw failure("package.findProcessBindings", exception);
        }
    }

    private Optional<PackageEnvironment> findEnvironment(String operation, String condition,
                                                         Binder binder) {
        String sql = "SELECT environment_id,owner_id,environment_name,parent_environment_id,"
                + "status,created_at FROM package.environment " + condition;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapEnvironment(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    private static PackageEnvironment mapEnvironment(ResultSet rows) throws SQLException {
        UUID parent = rows.getObject("parent_environment_id", UUID.class);
        return new PackageEnvironment(rows.getObject("environment_id", UUID.class),
                rows.getObject("owner_id", UUID.class), rows.getString("environment_name"),
                Optional.ofNullable(parent), PackageEnvironment.Status.valueOf(
                rows.getString("status")), rows.getTimestamp("created_at").toInstant());
    }

    private static ProcessPackageBinding mapProcessBinding(ResultSet rows) throws SQLException {
        return new ProcessPackageBinding(rows.getObject("process_uid", UUID.class),
                rows.getString("import_name"), rows.getObject("environment_id", UUID.class),
                new PackageRelease.Hash(JdbcValues.hash(rows.getBytes("package_hash"))),
                rows.getTimestamp("resolved_at").toInstant());
    }

    private Optional<PackageRelease> findRelease(String operation, String condition, Binder binder) {
        String sql = "SELECT " + RELEASE_COLUMNS + " FROM package.release AS release "
                + condition;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(mapRelease(rows));
            }
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    private static PackageRelease mapRelease(ResultSet rows) throws SQLException {
        return new PackageRelease(
                new PackageRelease.Coordinate(rows.getString("namespace"),
                        rows.getString("package_name"), rows.getString("package_version")),
                new PackageRelease.Hash(JdbcValues.hash(rows.getBytes("package_hash"))),
                JdbcValues.hash(rows.getBytes("database_object_hash")),
                JdbcValues.hash(rows.getBytes("database_file_hash")),
                rows.getTimestamp("created_at").toInstant()
        );
    }

    private static List<Map<String, Object>> moduleJson(List<PackageIndex.Module> modules) {
        return modules.stream().map(module -> fields(
                "moduleName", module.name(),
                "moduleObjectPath", module.objectPath(),
                "moduleHash", module.hash().value())).toList();
    }

    private static List<Map<String, Object>> dependencyJson(
            List<PackageIndex.Dependency> dependencies) {
        return dependencies.stream().map(dependency -> fields(
                "dependencyNamespace", dependency.namespace(),
                "dependencyName", dependency.name(),
                "versionConstraint", dependency.versionConstraint(),
                "optional", dependency.optional())).toList();
    }

    private static List<Map<String, Object>> entrypointJson(
            List<PackageIndex.Entrypoint> entrypoints) {
        return entrypoints.stream().map(entrypoint -> fields(
                "entrypointName", entrypoint.name(),
                "moduleName", entrypoint.moduleName(),
                "functionName", entrypoint.functionName())).toList();
    }

    private static List<Map<String, Object>> exportJson(List<PackageIndex.Export> exports) {
        return exports.stream().map(export -> fields(
                "exportName", export.name(),
                "moduleName", export.moduleName(),
                "symbolName", export.symbolName())).toList();
    }

    private static List<Map<String, Object>> capabilityJson(
            List<PackageIndex.CapabilityRequirement> capabilities) {
        return capabilities.stream().map(capability -> fields(
                "capabilityKey", capability.key(),
                "required", capability.required(),
                "rationale", capability.rationale())).toList();
    }

    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            fields.put((String) values[index], values[index + 1]);
        }
        return Map.copyOf(fields);
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
}
