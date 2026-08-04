package com.follarce.persistence.postgres.repository;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSchedulerRepositoryTest {
    @Test
    void separatesNormalAndInterruptedClaimsAtTheDatabaseFence() {
        SqlCapture capture = new SqlCapture();
        JdbcSchedulerRepository repository = new JdbcSchedulerRepository(capture.connection());

        repository.claimNext(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                Duration.ofSeconds(5));
        repository.claimInterrupted(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                Duration.ofSeconds(5));
        repository.release(UUID.randomUUID(), 1);

        String normal = capture.selects.get(0);
        String interrupted = capture.selects.get(1);
        assertTrue(normal.contains("process.interrupt_requested=false"));
        assertTrue(interrupted.contains("process.interrupt_requested=true"));
        // The claim select must stay lockless: locking the process row here created an
        // AB-BA deadlock with effect-worker wake transactions. Exclusive claiming is
        // provided by the claimProcess status CAS instead.
        assertFalse(normal.contains("FOR UPDATE"));
        assertFalse(interrupted.contains("FOR UPDATE"));
        assertTrue(capture.statements.stream().anyMatch(sql ->
                sql.contains("WITH released AS MATERIALIZED")
                        && sql.contains("cilexec_scheduler_work")
                        && sql.contains("cilexec_interrupt_work")
                        && sql.contains("interrupt_requested")));
    }

    private static final class SqlCapture {
        private final List<String> selects = new ArrayList<>();
        private final List<String> statements = new ArrayList<>();

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) {
                            String sql = (String) arguments[0];
                            statements.add(sql);
                            if (sql.startsWith("SELECT queue.process_uid")) selects.add(sql);
                            return statement(sql);
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement statement(String sql) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("executeUpdate")) return 1;
                        if (method.getName().equals("executeQuery")) {
                            return sql.startsWith("WITH released AS MATERIALIZED")
                                    ? successfulRelease() : emptyRows();
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private ResultSet emptyRows() {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("next")) return false;
                        return defaultValue(method.getReturnType());
                    });
        }

        private ResultSet successfulRelease() {
            boolean[] unread = {true};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("next")) {
                            if (!unread[0]) return false;
                            unread[0] = false;
                            return true;
                        }
                        if (method.getName().equals("getInt")) return 1;
                        return defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
