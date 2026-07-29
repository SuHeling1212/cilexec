package com.follarce.persistence.postgres.migration;

import db.migration.V001__CilexecBaseline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class BaselineSql {
    private BaselineSql() {
    }

    static String load() throws IOException {
        StringBuilder sql = new StringBuilder();
        ClassLoader loader = BaselineSql.class.getClassLoader();
        for (String module : V001__CilexecBaseline.MODULES) {
            try (InputStream input = loader.getResourceAsStream(module)) {
                if (input == null) throw new IOException("Missing baseline module " + module);
                sql.append(new String(input.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sql.toString();
    }
}
