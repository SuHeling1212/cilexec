package db.migration;

/**
 * V004 / CilExec 0.0.4: checksum-protected database-contract validation.
 *
 * <p>This migration intentionally changes no application tables or persisted user data. It
 * verifies the immutable V003 contract before Flyway records schema version 4 and establishes
 * the checksum-bearing migration pattern required for V004 and every later Java migration.
 */
public final class V004 extends ImmutableSqlJavaMigration {
    public V004() {
        super("db/contracts/V004.sql");
    }
}
