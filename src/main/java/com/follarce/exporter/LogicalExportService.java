package com.follarce.exporter;

import com.follarce.app.BuildInfo;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/** Creates, verifies, hardens, and atomically publishes one logical export. */
public final class LogicalExportService {
    private static final Set<PosixFilePermission> READ_ONLY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    private final LogicalSnapshotProducer snapshot;
    private final SqliteLogicalExportVerifier verifier;
    private final Clock clock;

    public LogicalExportService(DataSource dataSource, Clock clock) {
        this(new PostgresLogicalExportSource(dataSource), new SqliteLogicalExportVerifier(), clock);
    }

    LogicalExportService(LogicalSnapshotProducer snapshot,
                         SqliteLogicalExportVerifier verifier, Clock clock) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LogicalExportReport export(Path requestedTarget, BuildInfo buildInfo) {
        Objects.requireNonNull(buildInfo, "buildInfo");
        Path target = validateTarget(requestedTarget);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(),
                    "." + target.getFileName() + "-", ".tmp");
            try (SqliteLogicalExportWriter writer = new SqliteLogicalExportWriter(temporary)) {
                snapshot.writeSnapshot(writer, buildInfo, clock.instant());
            }
            LogicalExportReport verified = verifier.verify(temporary);
            makeReadOnly(temporary);
            publishByHardLink(temporary, target);
            Files.delete(temporary);
            temporary = null;
            return new LogicalExportReport(target, verified.tableCount(), verified.rowCount(),
                    verified.manifestSha256());
        } catch (FileAlreadyExistsException exists) {
            throw new LogicalExportException(
                    "Refusing to overwrite existing export: " + target, exists);
        } catch (IOException failure) {
            throw new LogicalExportException("Cannot publish logical export: " + target, failure);
        } finally {
            cleanup(temporary);
        }
    }

    private static Path validateTarget(Path requestedTarget) {
        if (requestedTarget == null) {
            throw new IllegalArgumentException("Export output path is required");
        }
        Path target = requestedTarget.toAbsolutePath().normalize();
        if (target.getFileName() == null || !target.getFileName().toString().endsWith(".db")) {
            throw new IllegalArgumentException("Export output path must end in .db");
        }
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Export output directory does not exist: " + parent);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new LogicalExportException("Refusing to overwrite existing export: " + target);
        }
        return target;
    }

    private static void makeReadOnly(Path database) throws IOException {
        if (Files.getFileStore(database).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(database, READ_ONLY_PERMISSIONS);
        } else if (!database.toFile().setReadOnly()) {
            throw new IOException("Filesystem could not mark export read-only");
        }
    }

    /** A hard-link directory entry is atomic and fails rather than replacing an existing target. */
    private static void publishByHardLink(Path temporary, Path target) throws IOException {
        try {
            Files.createLink(target, temporary);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException(
                    "Filesystem does not support atomic non-overwriting export publication",
                    unsupported);
        }
    }

    private static void cleanup(Path temporary) {
        if (temporary == null) return;
        for (Path candidate : new Path[]{
                temporary,
                Path.of(temporary + "-journal"),
                Path.of(temporary + "-wal"),
                Path.of(temporary + "-shm")}) {
            try {
                Files.deleteIfExists(candidate);
            } catch (IOException ignored) {
                candidate.toFile().deleteOnExit();
            }
        }
    }
}
