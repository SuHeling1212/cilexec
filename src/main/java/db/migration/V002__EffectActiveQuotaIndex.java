package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/** Indexes the per-owner active-effect quota check used by every external effect insert. */
public final class V002__EffectActiveQuotaIndex extends BaseJavaMigration {
    public static final String SQL = "CREATE INDEX ix_effect_owner_active "
            + "ON effect.effect(owner_id) WHERE status IN "
            + "('PREPARED','CLAIMED','EXECUTING','UNKNOWN')";

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(SQL);
        }
    }
}
