package com.follarce.market.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketRepositoryTest {
    @TempDir Path temporary;

    @Test
    void failedPublicationLeavesNoOrphanPackageFileBehind() throws Exception {
        Path repository = temporary.resolve("rollback-repository");
        Path catalog = temporary.resolve("rollback-catalog.json");
        Files.writeString(catalog, "{}");
        MarketRepository market = new MarketRepository(repository, catalog);
        Path database = temporary.resolve("rollback-tool.db");
        createPackageAt(database);
        MarketRepository.StagedPackage staged = market.stage(database);

        // publish copies the package file first and only then atomically replaces the
        // catalog; forcing the catalog commit to fail must roll the copied file back so
        // no orphan file survives a failed publication.
        Files.delete(catalog);
        Files.createDirectories(catalog);
        assertThrows(IllegalArgumentException.class,
                () -> market.publish(staged, "summary", null, List.of()));
        assertFalse(Files.exists(repository.resolve("packages/demo/tool/1.0.0/tool.db")));
    }

    private static void createPackageAt(Path database) throws Exception {
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=2");
            statement.execute("CREATE TABLE package_metadata(metadata_key TEXT PRIMARY KEY,"
                    + "metadata_value TEXT NOT NULL)");
            statement.execute("CREATE TABLE package_dependency(dependency_file_hash TEXT PRIMARY KEY,"
                    + "optional INTEGER NOT NULL)");
            statement.execute("INSERT INTO package_metadata VALUES ('namespace','demo'),"
                    + "('name','tool'),('version','1.0.0'),('package_kind','application'),"
                    + "('summary','tool')");
        }
    }
}