package db.migration;

/**
 * V005 / CilExec 0.0.5: object-runtime format fence and frozen database-contract validation.
 *
 * <p>0.0.5 moves FCL entities to the object runtime: object method calls and runtime-only
 * {@code FclSystemHandle} values replace the legacy entity functions, and the FCLB/continuation
 * format identity becomes 5. Handles are never persistent rows; a continuation is committed with
 * its existing runtime-format column, so the upgrade requires no application table, column, row,
 * function, or privilege. V005 therefore records the 0.0.5 identity as Flyway schema version 5
 * while validating the complete V004 contract. Old data are refused before any migration runs
 * (see the startup format fence); they are never upgraded in place.
 *
 * <p>The recorded V005 migration is immutable. During the unreleased 0.0.5 development
 * period, {@code V005Patch} applies the additional schema contract transactionally while
 * retaining Flyway version 5 and existing V005 data.
 */
public final class V005 extends ImmutableSqlJavaMigration {
    public V005() {
        super("db/contracts/V005.sql");
    }
}
