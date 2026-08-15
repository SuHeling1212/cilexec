package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Frozen Flyway version 1 entry point for the ordered, modular CilExec 1.0 baseline. */
public final class V001__CilexecBaseline extends BaseJavaMigration {
    public static final List<String> MODULES = List.of(
            "db/baseline/foundation.sql",
            "db/baseline/execution.sql",
            "db/baseline/vfs_package.sql",
            "db/baseline/effect_terminal_audit.sql",
            "db/baseline/contracts.sql",
            "db/baseline/administrator_storage.sql",
            "db/baseline/atomic_administration.sql",
            "db/baseline/environment_permissions.sql",
            "db/baseline/password_vfs_runtime.sql",
            "db/baseline/terminal_runtime.sql",
            "db/baseline/production_hardening.sql",
            "db/baseline/package_lifecycle.sql");

    @Override
    public Integer getChecksum() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String module : MODULES) {
                digest.update(module.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(readModule(module));
            }
            return ByteBuffer.wrap(digest.digest()).getInt();
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot checksum the CilExec baseline", exception);
        }
    }

    @Override
    public void migrate(Context context) throws Exception {
        for (String module : MODULES) execute(context, module);
    }

    private void execute(Context context, String module) throws IOException, SQLException {
        String sql = new String(readModule(module), StandardCharsets.UTF_8);
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }

    private static byte[] readModule(String module) throws IOException {
        ClassLoader loader = V001__CilexecBaseline.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(module)) {
            if (input == null) throw new IOException("Missing baseline module " + module);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
            return sql.getBytes(StandardCharsets.UTF_8);
        }
    }
}
