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

/**
 * Versioned Java-migration adapter whose SQL resource is also its Flyway checksum identity.
 *
 * <p>Future migrations should keep their database changes in one immutable resource and extend
 * this class. Flyway's {@link BaseJavaMigration} otherwise returns a {@code null} checksum, which
 * cannot detect an applied Java migration being edited later.
 */
abstract class ImmutableSqlJavaMigration extends BaseJavaMigration {
    private final String resource;

    protected ImmutableSqlJavaMigration(String resource) {
        if (resource == null || resource.isBlank() || resource.startsWith("/")) {
            throw new IllegalArgumentException("Migration resource must be a classpath-relative path");
        }
        this.resource = resource;
    }

    @Override
    public final Integer getChecksum() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(resource.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(readResource());
            return ByteBuffer.wrap(digest.digest()).getInt();
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot checksum migration resource " + resource,
                    exception);
        }
    }

    @Override
    public final void migrate(Context context) throws Exception {
        String sql = new String(readResource(), StandardCharsets.UTF_8);
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }

    private byte[] readResource() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing migration resource " + resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
            return sql.getBytes(StandardCharsets.UTF_8);
        }
    }
}
