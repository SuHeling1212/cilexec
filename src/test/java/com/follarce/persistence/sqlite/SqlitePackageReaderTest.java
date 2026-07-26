package com.follarce.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlitePackageReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsExplicitIndexesOnKnownPackageTables() throws Exception {
        Path database = packageDatabase(
                "CREATE INDEX idx_package_module_name ON package_module(module_name)");

        PackageDescriptor descriptor = new SqlitePackageReader()
                .inspect(Files.readAllBytes(database));

        assertEquals("std/example/1.2.3", descriptor.coordinate());
        assertEquals(java.util.List.of("main"), descriptor.modules());
        assertTrue(descriptor.packageHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void immutableConnectionAlsoEnforcesQueryOnly() throws Exception {
        Path database = packageDatabase(null);

        try (Connection connection = SqlitePackageReader.openImmutable(database);
             Statement statement = connection.createStatement()) {
            try (ResultSet mode = statement.executeQuery("PRAGMA query_only")) {
                assertTrue(mode.next());
                assertEquals(1, mode.getInt(1));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO package_module(module_name) VALUES ('forbidden')"));
        }
    }

    @Test
    void rejectsViewsAndTriggers() throws Exception {
        Path view = packageDatabase(
                "CREATE VIEW package_view AS SELECT module_name FROM package_module");
        Path trigger = packageDatabase("CREATE TRIGGER package_trigger "
                + "AFTER INSERT ON package_module BEGIN DELETE FROM package_module; END");

        assertThrows(PackageDatabaseException.class,
                () -> inspect(view));
        assertThrows(PackageDatabaseException.class,
                () -> inspect(trigger));
    }

    @Test
    void rejectsVirtualTables() throws Exception {
        Path database = packageDatabase(
                "CREATE VIRTUAL TABLE package_virtual USING fts5(value)");

        assertThrows(PackageDatabaseException.class,
                () -> inspect(database));
    }

    @Test
    void rejectsUnknownTables() throws Exception {
        Path database = packageDatabase("CREATE TABLE package_unknown(value TEXT)");

        assertThrows(PackageDatabaseException.class,
                () -> inspect(database));
    }

    private PackageDescriptor inspect(Path database) throws IOException {
        return new SqlitePackageReader().inspect(Files.readAllBytes(database));
    }

    private Path packageDatabase(String extraDdl) throws SQLException {
        Path database = temporaryDirectory.resolve(UUID.randomUUID() + ".db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE package_metadata(metadata_key TEXT NOT NULL, "
                    + "metadata_value TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_file(file_path TEXT NOT NULL, content BLOB)");
            statement.execute("CREATE TABLE package_module(module_name TEXT NOT NULL, "
                    + "module_object_path TEXT NOT NULL, module_hash TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_dependency(dependency_namespace TEXT NOT NULL, "
                    + "dependency_name TEXT NOT NULL, version_constraint TEXT NOT NULL, "
                    + "optional INTEGER NOT NULL)");
            statement.execute("CREATE TABLE package_entrypoint(entrypoint_name TEXT NOT NULL, "
                    + "module_name TEXT NOT NULL, function_name TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_export(export_name TEXT NOT NULL, "
                    + "module_name TEXT NOT NULL, symbol_name TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_capability(capability_name TEXT NOT NULL, "
                    + "required INTEGER NOT NULL, rationale TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_signature(signature BLOB)");
            statement.execute("INSERT INTO package_metadata(metadata_key,metadata_value) VALUES "
                    + "('namespace','std'),('name','example'),('version','1.2.3'),"
                    + "('language_version','1')");
            statement.execute("INSERT INTO package_module(module_name,module_object_path,module_hash) "
                    + "VALUES ('main','modules/main.fcl','"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'" + ")");
            statement.execute("INSERT INTO package_file(file_path,content) "
                    + "VALUES ('modules/main.fcl',X'')");
            statement.execute("INSERT INTO package_dependency(dependency_namespace,dependency_name,"
                    + "version_constraint,optional) VALUES ('std','base','1.0.0',0)");
            statement.execute("INSERT INTO package_capability(capability_name,required,rationale) "
                    + "VALUES ('vfs_read',1,'read package data')");
            if (extraDdl != null) statement.execute(extraDdl);
        }
        return database;
    }
}
