package com.follarce.terminal;

import com.follarce.domain.auth.UserAccount;
import com.follarce.auth.PasswordPolicy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/** Login and self-registration gate in front of the composite FCL terminal. */
public final class TerminalAccessConsole implements Runnable {
    private final TerminalInput input;
    private final PrintWriter output;
    private final TerminalAccess access;
    private final Function<UserAccount, TerminalControl> controls;

    public TerminalAccessConsole(TerminalInput input, PrintWriter output,
                                 TerminalAccess access,
                                 Function<UserAccount, TerminalControl> controls) {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.output = java.util.Objects.requireNonNull(output, "output");
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.controls = java.util.Objects.requireNonNull(controls, "controls");
    }

    @Override
    public void run() {
        output.println("CilExec access; choose login, create, or shutdown");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String choice = input.readLine(output, "access> ", false);
                if (choice == null) return;
                String action = choice.trim().toLowerCase(java.util.Locale.ROOT);
                if (action.equals("3") || action.equals("exit")
                        || action.equals("shutdown")) return;
                Optional<UserAccount> account = switch (action) {
                    case "1", "login" -> login();
                    case "2", "create", "register" -> create();
                    default -> {
                        output.println("error: choose login, create, or shutdown");
                        yield Optional.empty();
                    }
                };
                if (account.isEmpty()) continue;
                UserAccount authenticated = account.orElseThrow();
                output.println("authenticated as " + authenticated.username());
                TerminalConsole.Outcome outcome = new TerminalConsole(input, output,
                        controls.apply(authenticated)).runSession();
                if (outcome != TerminalConsole.Outcome.LOGOUT) return;
                output.println("logged out");
            } catch (IllegalArgumentException | IllegalStateException failure) {
                output.println("error: " + failure.getMessage());
            } catch (IOException closed) {
                output.println("terminal closed: " + closed.getMessage());
                return;
            }
        }
    }

    private Optional<UserAccount> login() throws IOException {
        String username = line("username> ");
        if (username == null) return Optional.empty();
        char[] password = password("password> ");
        if (password == null) return Optional.empty();
        try {
            Optional<UserAccount> account = access.login(username, password);
            if (account.isEmpty()) output.println("error: invalid username or password");
            return account;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private Optional<UserAccount> create() throws IOException {
        String username = line("new username> ");
        if (username == null) return Optional.empty();
        char[] password = password("new password (" + PasswordPolicy.MINIMUM_LENGTH
                + "+ characters)> ");
        if (password == null) return Optional.empty();
        char[] confirmation = password("confirm password> ");
        if (confirmation == null) {
            Arrays.fill(password, '\0');
            return Optional.empty();
        }
        try {
            if (!Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Passwords do not match");
            }
            return Optional.of(access.register(username, password));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private String line(String prompt) throws IOException {
        return input.readLine(output, prompt, false);
    }

    private char[] password(String prompt) throws IOException {
        output.print(prompt);
        output.flush();
        return input.readPassword();
    }
}
