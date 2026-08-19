package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * V002: package private data FILE entries may be empty.
 *
 * The frozen V001 baseline requires {@code byte_size > 0} for FILE entries, so
 * {@code packageData.write("empty.txt", "")} is rejected by the database even
 * though the language and object store support zero-length content. This forward
 * migration relaxes only the FILE arm of the constraint to {@code byte_size >= 0};
 * directories still require a NULL object hash and zero size.
 */
public final class V002__PackageDataAllowEmptyFiles extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE package.data_entry DROP CONSTRAINT data_entry_check");
            statement.execute("ALTER TABLE package.data_entry ADD CONSTRAINT data_entry_check CHECK ("
                    + "(entry_type = 'FILE' AND object_hash IS NOT NULL AND byte_size >= 0) "
                    + "OR (entry_type = 'DIRECTORY' AND object_hash IS NULL AND byte_size = 0))");
        }
    }
}
