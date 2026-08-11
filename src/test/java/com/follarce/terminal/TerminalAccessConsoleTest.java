package com.follarce.terminal;

import com.follarce.domain.auth.UserAccount;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAccessConsoleTest {
    private static final String PASSWORD = "alice-password";

    @Test
    void supportsLoginCreationLogoutAndPasswordErasure() {
        String source = PASSWORD + "\n" + PASSWORD + "\n"  // first-time setup
                + "login\nalice\nwrong-password-value\n"
                + "create\nalice\n" + PASSWORD + "\n" + PASSWORD + "\nn\n:logout\n"
                + "login\nalice\n" + PASSWORD + "\n:exit\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RecordingAccess access = new RecordingAccess();
        List<String> authenticated = new ArrayList<>();
        List<ShellCommand> commands = new ArrayList<>();
        TerminalInput input = TerminalInput.visible(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)));

        new TerminalAccessConsole(input,
                new PrintWriter(bytes, true, StandardCharsets.UTF_8), access, account -> {
                    authenticated.add(account.username());
                    return command -> {
                        commands.add(command);
                        return "ok";
                    };
                }, "operator").run();

        String transcript = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("invalid username or password"), transcript);
        assertTrue(transcript.contains("authenticated as alice"), transcript);
        assertTrue(transcript.contains("logged out"), transcript);
        assertEquals(List.of("alice", "alice"), authenticated);
        assertEquals(List.of(new ShellCommand.Logout()), commands,
                ":exit must close only the client transport and never reach Runtime control");
        assertEquals(1, access.registrations);
        assertEquals("operator", access.bootstrapUsername);
        assertTrue(access.receivedPasswords.stream()
                .allMatch(value -> value.length > 0 && allZero(value)));
    }

    @Test
    void rejectsAShortPasswordBeforeAskingForAdministratorPrivileges() {
        String source = "123456\n123456\n"
                + "create\nbob\n12345\n12345\n"
                + "disconnect\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RecordingAccess access = new RecordingAccess();
        TerminalInput input = TerminalInput.visible(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)));

        new TerminalAccessConsole(input,
                new PrintWriter(bytes, true, StandardCharsets.UTF_8), access,
                account -> command -> "ok", "operator").run();

        String transcript = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("new password (6+ characters)>"), transcript);
        assertTrue(transcript.contains("error: Password must contain 6 to 1024 characters"),
                transcript);
        assertTrue(!transcript.contains("Create as administrator?"), transcript);
        assertEquals(0, access.registrations);
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    private static final class RecordingAccess implements TerminalAccess {
        private final UserAccount alice = UserAccount.active(UUID.randomUUID(), "alice",
                Instant.parse("2026-07-26T00:00:00Z"));
        private final List<char[]> receivedPasswords = new ArrayList<>();
        private boolean created;
        private int registrations;
        private String bootstrapUsername;

        @Override
        public Optional<UserAccount> login(String username, char[] password) {
            receivedPasswords.add(password);
            return created && username.equals("alice") && Arrays.equals(password,
                    PASSWORD.toCharArray()) ? Optional.of(alice) : Optional.empty();
        }

        @Override
        public UserAccount register(String username, char[] password) {
            receivedPasswords.add(password);
            registrations++;
            created = true;
            return alice;
        }

        @Override
        public UserAccount register(String username, char[] password, char[] adminPassword) {
            receivedPasswords.add(password);
            receivedPasswords.add(adminPassword);
            registrations++;
            created = true;
            return alice;
        }

        @Override
        public boolean isFirstUse() {
            return !created;
        }

        @Override
        public UserAccount bootstrap(String username, char[] password) {
            receivedPasswords.add(password);
            bootstrapUsername = username;
            created = true;
            return alice;
        }
    }
}
