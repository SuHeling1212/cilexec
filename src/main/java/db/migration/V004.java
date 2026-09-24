package db.migration;

/**
 * V004 / CilExec 0.0.4: checksum-protected database-contract completion and validation.
 *
 * <p>This still-unreleased migration completes a tenant-role privilege required by
 * {@code process.exec}, changes no application tables or persisted user data, and verifies the
 * immutable V003 contract before Flyway records schema version 4. Once 0.0.4 is released, its
 * resource checksum becomes immutable migration history.
 */
public final class V004 extends ImmutableSqlJavaMigration {
    public V004() {
        super("db/contracts/V004.sql");
    }
}
