package com.follarce.persistence.postgres.repository;

import com.follarce.domain.packageinfo.PackageDataEntry;
import com.follarce.domain.packageinfo.PackageDataUsage;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageInstallation;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.PackageUninstallResult;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.persistence.postgres.mapper.JdbcValues;
import com.follarce.persistence.postgres.mapper.JsonCodec;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
            statement.setInt(7, com.follarce.persistence.sqlite.SqlitePackageReader.FORMAT_VERSION);
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
    public void saveProcessBinding(ProcessPackageBinding binding) {
        // Last-wins: re-importing the same qualifier re-pins it to the newest hash.
        String sql = "INSERT INTO process.package_binding(process_uid,owner_id,import_name,"
                + "package_hash,resolved_at) SELECT process_uid,owner_id,?,?,? FROM process.process "
                + "WHERE process_uid=? ON CONFLICT (process_uid,import_name) DO UPDATE SET "
                + "package_hash=EXCLUDED.package_hash,resolved_at=EXCLUDED.resolved_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, binding.importName());
            statement.setBytes(2, JdbcValues.hash(binding.packageHash().value()));
            statement.setTimestamp(3, java.sql.Timestamp.from(binding.resolvedAt()));
            statement.setObject(4, binding.processUid());
            requireOne("package.saveProcessBinding", statement.executeUpdate());
        } catch (SQLException exception) {
            throw failure("package.saveProcessBinding", exception);
        }
    }

    @Override
    public Optional<ProcessPackageBinding> findProcessBinding(UUID processUid, String importName) {
        String sql = "SELECT process_uid,import_name,package_hash,resolved_at "
                + "FROM process.package_binding WHERE process_uid=? AND import_name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, processUid);
            statement.setString(2, importName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(mapProcessBinding(rows));
            }
        } catch (SQLException exception) {
            throw failure("package.findProcessBinding", exception);
        }
    }

    @Override
    public List<ProcessPackageBinding> findProcessBindings(UUID processUid) {
        String sql = "SELECT process_uid,import_name,package_hash,resolved_at "
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

    private static ProcessPackageBinding mapProcessBinding(ResultSet rows) throws SQLException {
        return new ProcessPackageBinding(rows.getObject("process_uid", UUID.class),
                rows.getString("import_name"),
                new PackageRelease.Hash(JdbcValues.hash(rows.getBytes("package_hash"))),
                rows.getTimestamp("resolved_at").toInstant());
    }

    // ------------------------------------------------------------------
    // Per-user installation ledger
    // ------------------------------------------------------------------

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final Type LIST_TYPE = new TypeToken<List<Map<String, Object>>>() { }.getType();

    @Override
    public boolean publishInstallation(UUID installationId, UUID ownerId,
                                       ObjectHash rootFileHash, String source,
                                       List<PackageInstallation.Member> members,
                                       Instant at) {
        List<Map<String, Object>> memberJson = members.stream().map(member -> fields(
                "packageHash", member.packageHash().value(),
                "dependencyDepth", member.dependencyDepth(),
                "optional", member.optional())).toList();
        String sql = "SELECT package.publish_installation(?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, installationId);
            statement.setBytes(2, JdbcValues.hash(rootFileHash));
            statement.setString(3, source);
            statement.setObject(4, JdbcValues.json(json.write(memberJson)));
            statement.setTimestamp(5, java.sql.Timestamp.from(at));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Installation publication returned no result");
                }
                Map<String, Object> result = json.read(rows.getString(1), MAP_TYPE);
                return Boolean.TRUE.equals(result.get("created"));
            }
        } catch (SQLException exception) {
            throw failure("package.publishInstallation", exception);
        }
    }

    @Override
    public List<PackageInstallation> findInstallations(UUID ownerId) {
        String sql = "SELECT package.list_user_installations()";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) return List.of();
            List<Map<String, Object>> installations = json.read(rows.getString(1), LIST_TYPE);
            List<PackageInstallation> result = new ArrayList<>(installations.size());
            for (Map<String, Object> installation : installations) {
                String coordinate = text(installation.get("rootCoordinate"),
                        "installation root coordinate");
                String[] parts = coordinate.split("/", 3);
                List<Map<String, Object>> members = json.read(
                        json.write(installation.get("members")), LIST_TYPE);
                List<PackageInstallation.Member> mapped = new ArrayList<>(members.size());
                for (Map<String, Object> member : members) {
                    String memberCoordinate = text(member.get("coordinate"),
                            "installation member coordinate");
                    String[] memberParts = memberCoordinate.split("/", 3);
                    mapped.add(new PackageInstallation.Member(
                            new PackageRelease.Coordinate(memberParts[0], memberParts[1],
                                    memberParts[2]),
                            new ObjectHash(text(member.get("packageHash"),
                                    "installation member hash")),
                            new ObjectHash(text(member.get("databaseFileSha256"),
                                    "installation member file hash")),
                            integer(member.get("dependencyDepth"), "dependency depth"),
                            Boolean.TRUE.equals(member.get("optional"))));
                }
                result.add(new PackageInstallation(text(installation.get("installationId"),
                        "installation id"), ownerId,
                        new PackageRelease.Coordinate(parts[0], parts[1], parts[2]),
                        new ObjectHash(text(installation.get("rootFileSha256"),
                                "installation root file hash")),
                        text(installation.get("source"), "installation source"),
                        Instant.parse(text(installation.get("installedAt"),
                                "installation timestamp")), List.copyOf(mapped)));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("package.findInstallations", exception);
        }
    }

    @Override
    public Optional<PackageRelease> findInstalledReleaseByDatabaseFileHash(
            UUID ownerId, ObjectHash databaseFileHash) {
        Optional<PackageRelease> release = findReleaseByDatabaseFileHash(databaseFileHash);
        if (release.isEmpty()) return Optional.empty();
        String sql = "SELECT package.installed_release(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) return Optional.empty();
                return release;
            }
        } catch (SQLException exception) {
            throw failure("package.findInstalledRelease", exception);
        }
    }

    @Override
    public List<PackageRelease> findInstalledReleases(UUID ownerId) {
        List<PackageInstallation> installations = findInstallations(ownerId);
        Map<String, PackageRelease> releases = new LinkedHashMap<>();
        for (PackageInstallation installation : installations) {
            for (PackageInstallation.Member member : installation.members()) {
                findRelease(new PackageRelease.Hash(member.packageHash())).ifPresent(release ->
                        releases.put(release.packageHash().value().value(), release));
            }
        }
        return List.copyOf(releases.values());
    }

    @Override
    public PackageUninstallResult uninstall(UUID ownerId, ObjectHash databaseFileHash,
                                            boolean force, UUID callerProcessUid) {
        String sql = "SELECT package.uninstall_package(?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setBoolean(2, force);
            statement.setObject(3, callerProcessUid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package uninstall returned no result");
                }
                Map<String, Object> summary = json.read(rows.getString(1), MAP_TYPE);
                return new PackageUninstallResult(
                        Boolean.TRUE.equals(summary.get("removed")),
                        integer(summary.get("packagesRemoved"), "packagesRemoved"),
                        integer(summary.get("dependenciesRemoved"), "dependenciesRemoved"),
                        integer(summary.get("processesRemoved"), "processesRemoved"),
                        integer(summary.get("bindingsRemoved"), "bindingsRemoved"),
                        integer(summary.get("cacheFilesRemoved"), "cacheFilesRemoved"),
                        integer(summary.get("dataNodesRemoved"), "dataNodesRemoved"),
                        integer(summary.get("releasesPurged"), "releasesPurged"),
                        integer(summary.get("objectsPurged"), "objectsPurged"));
            }
        } catch (SQLException exception) {
            throw failure("package.uninstall", exception);
        }
    }

    // ------------------------------------------------------------------
    // Per-user per-package private data
    // ------------------------------------------------------------------

    @Override
    public PackageDataUsage findDataUsage(UUID ownerId, ObjectHash databaseFileHash) {
        String sql = "SELECT package.data_usage(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package data usage returned no result");
                }
                Map<String, Object> usage = json.read(rows.getString(1), MAP_TYPE);
                return new PackageDataUsage(text(usage.get("spaceId"), "space id"), ownerId,
                        new ObjectHash(text(usage.get("packageHash"),
                                "package hash")),
                        new ObjectHash(text(usage.get("databaseFileSha256"),
                                "database file hash")),
                        number(usage.get("logicalBytes"), "logical bytes"),
                        number(usage.get("quota"), "quota"),
                        number(usage.get("files"), "files"),
                        Instant.parse(text(usage.get("updatedAt"), "usage updated at")));
            }
        } catch (SQLException exception) {
            throw failure("package.findDataUsage", exception);
        }
    }

    @Override
    public byte[] readDataEntry(UUID ownerId, ObjectHash databaseFileHash, String path) {
        String sql = "SELECT package.data_read(?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setString(2, path);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return rows.getBytes(1);
            }
        } catch (SQLException exception) {
            throw failure("package.readDataEntry", exception);
        }
    }

    @Override
    public PackageDataEntry writeDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                           String path, byte[] content, String mediaType,
                                           long expectedVersion) {
        String sql = "SELECT package.data_write(?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setString(2, path);
            statement.setBytes(3, content);
            statement.setString(4, mediaType);
            statement.setLong(5, expectedVersion);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package data write returned no result");
                }
                Map<String, Object> result = json.read(rows.getString(1), MAP_TYPE);
                return new PackageDataEntry(path, "FILE",
                        Optional.of(ObjectHash.sha256(
                                new com.follarce.domain.vfs.BinaryContent(content))),
                        content.length,
                        number(result.get("version"), "data entry version"),
                        Optional.of(Instant.now()));
            }
        } catch (SQLException exception) {
            throw failure("package.writeDataEntry", exception);
        }
    }

    @Override
    public PackageDataEntry appendDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                            String path, byte[] content, long expectedVersion) {
        String sql = "SELECT package.data_append(?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setString(2, path);
            statement.setBytes(3, content);
            statement.setLong(4, expectedVersion);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package data append returned no result");
                }
                Map<String, Object> result = json.read(rows.getString(1), MAP_TYPE);
                return new PackageDataEntry(path, "FILE", Optional.empty(),
                        number(result.get("bytes"), "appended bytes"),
                        number(result.get("version"), "data entry version"),
                        Optional.of(Instant.now()));
            }
        } catch (SQLException exception) {
            throw failure("package.appendDataEntry", exception);
        }
    }

    @Override
    public List<PackageDataEntry> listDataEntries(UUID ownerId, ObjectHash databaseFileHash,
                                                  String path) {
        String sql = "SELECT package.data_list(?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setString(2, path);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return List.of();
                List<Map<String, Object>> entries = json.read(rows.getString(1), LIST_TYPE);
                List<PackageDataEntry> result = new ArrayList<>(entries.size());
                for (Map<String, Object> entry : entries) {
                    result.add(new PackageDataEntry(text(entry.get("name"), "data entry name"),
                            text(entry.get("type"), "data entry type"), Optional.empty(),
                            number(entry.get("size"), "data entry size"),
                            number(entry.get("version"), "data entry version"),
                            Optional.empty()));
                }
                return List.copyOf(result);
            }
        } catch (SQLException exception) {
            throw failure("package.listDataEntries", exception);
        }
    }

    @Override
    public boolean removeDataEntry(UUID ownerId, ObjectHash databaseFileHash, String path) {
        String sql = "SELECT package.data_remove(?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setString(2, path);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package data remove returned no result");
                }
                Map<String, Object> result = json.read(rows.getString(1), MAP_TYPE);
                return Boolean.TRUE.equals(result.get("removed"));
            }
        } catch (SQLException exception) {
            throw failure("package.removeDataEntry", exception);
        }
    }

    @Override
    public PackageDataEntry renameDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                            String from, String to) {
        String sql = "SELECT package.data_rename(?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setString(2, from);
            statement.setString(3, to);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package data rename returned no result");
                }
                return new PackageDataEntry(to, "FILE", Optional.empty(), 0, 0,
                        Optional.of(Instant.now()));
            }
        } catch (SQLException exception) {
            throw failure("package.renameDataEntry", exception);
        }
    }

    @Override
    public void mkdirDataEntry(UUID ownerId, ObjectHash databaseFileHash, String path) {
        String sql = "SELECT package.data_mkdir(?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            statement.setString(2, path);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package data mkdir returned no result");
                }
            }
        } catch (SQLException exception) {
            throw failure("package.mkdirDataEntry", exception);
        }
    }

    @Override
    public long clearDataEntries(UUID ownerId, ObjectHash databaseFileHash) {
        String sql = "SELECT package.data_clear(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, JdbcValues.hash(databaseFileHash));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package data clear returned no result");
                }
                Map<String, Object> result = json.read(rows.getString(1), MAP_TYPE);
                return number(result.get("entriesRemoved"), "entriesRemoved");
            }
        } catch (SQLException exception) {
            throw failure("package.clearDataEntries", exception);
        }
    }

    @Override
    public long findDataQuota(UUID ownerId, ObjectHash databaseFileHash) {
        return findDataUsage(ownerId, databaseFileHash).quota();
    }

    @Override
    public void setDataQuota(UUID administratorId, UUID ownerId, ObjectHash databaseFileHash,
                             long quotaBytes) {
        String sql = "SELECT package.set_data_quota(?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, ownerId);
            statement.setBytes(2, JdbcValues.hash(databaseFileHash));
            statement.setLong(3, quotaBytes);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package quota override returned no result");
                }
            }
        } catch (SQLException exception) {
            throw failure("package.setDataQuota", exception);
        }
    }

    @Override
    public void clearDataQuota(UUID administratorId, UUID ownerId, ObjectHash databaseFileHash) {
        String sql = "SELECT package.clear_data_quota(?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, ownerId);
            statement.setBytes(2, JdbcValues.hash(databaseFileHash));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Package quota override returned no result");
                }
            }
        } catch (SQLException exception) {
            throw failure("package.clearDataQuota", exception);
        }
    }

    @Override
    public void registerManagedNode(UUID ownerId, UUID nodeId, ObjectHash databaseFileHash,
                                    String purpose) {
        String sql = "SELECT package.register_managed_node(?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, nodeId);
            statement.setBytes(2, JdbcValues.hash(databaseFileHash));
            statement.setString(3, purpose);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Managed node registration returned no result");
                }
            }
        } catch (SQLException exception) {
            throw failure("package.registerManagedNode", exception);
        }
    }

    @Override
    public Map<String, Object> recoverReport(UUID administratorId) {
        String sql = "SELECT package.recover_report()";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("Package recovery report returned no result");
            }
            return json.read(rows.getString(1), MAP_TYPE);
        } catch (SQLException exception) {
            throw failure("package.recoverReport", exception);
        }
    }

    private static String text(Object value, String description) {
        if (!(value instanceof String string)) {
            throw new IllegalStateException(description + " is not text: " + value);
        }
        return string;
    }

    private static int integer(Object value, String description) {
        return (int) number(value, description);
    }

    private static long number(Object value, String description) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(description + " is not numeric: " + value);
        }
        return number.longValue();
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
                "dependencyFileHash", dependency.databaseFileHash().value(),
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
