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
    private final String administratorUsername;
    private final PasswordPrompt passwords;

    public TerminalAccessConsole(TerminalInput input, PrintWriter output,
                                 TerminalAccess access,
                                 Function<UserAccount, TerminalControl> controls) {
        this(input, output, access, controls, "local");
    }

    public TerminalAccessConsole(TerminalInput input, PrintWriter output,
                                 TerminalAccess access,
                                 Function<UserAccount, TerminalControl> controls,
                                 String administratorUsername) {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.output = java.util.Objects.requireNonNull(output, "output");
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.controls = java.util.Objects.requireNonNull(controls, "controls");
        this.passwords = new PasswordPrompt(this.input, this.output);
        if (administratorUsername == null || administratorUsername.isBlank()) {
            throw new IllegalArgumentException("Administrator username is required");
        }
        this.administratorUsername = administratorUsername.trim();
    }

    @Override
    public void run() {
        output.print("\033[H\033[2J");
        output.flush();
        try {
            firstTimeSetup();
        } catch (IOException closed) {
            output.println("terminal closed: " + closed.getMessage());
            return;
        }
        output.println("CilExec access; choose login, create, or disconnect");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String choice = input.readLine(output, "access> ", false);
                if (choice == null) return;
                String action = choice.trim().toLowerCase(java.util.Locale.ROOT);
                if (action.equals("3") || action.equals("exit")
                        || action.equals("shutdown") || action.equals("disconnect")) return;
                Optional<UserAccount> account = switch (action) {
                    case "1", "login" -> login();
                    case "2", "create", "register" -> create();
                    default -> {
                        output.println("error: choose login, create, or disconnect");
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

    private void firstTimeSetup() throws IOException {
        if (!access.isFirstUse()) return;
        output.println("First time setup - create the administrator account");
        output.println("Username: " + administratorUsername);
        while (!Thread.currentThread().isInterrupted()) {
            PasswordPrompt.Secret password = passwords.read("Password (" + PasswordPolicy.MINIMUM_LENGTH
                    + "+ characters)> ");
            if (password == null) return;
            try (password) {
                PasswordPrompt.Secret confirmation = passwords.read("Confirm password> ");
                if (confirmation == null) return;
                try (confirmation) {
                    if (!Arrays.equals(password.value(), confirmation.value())) {
                        throw new IllegalArgumentException("Passwords do not match");
                    }
                    PasswordPolicy.require(password.value());
                    access.bootstrap(administratorUsername, password.value());
                    output.println("Administrator account created.");
                    output.println();
                    return;
                }
            } catch (IllegalArgumentException failure) {
                output.println("error: " + failure.getMessage());
            }
        }
    }

    private Optional<UserAccount> login() throws IOException {
        String username = line("username> ");
        if (username == null) return Optional.empty();
        PasswordPrompt.Secret password = passwords.read("password> ");
        if (password == null) return Optional.empty();
        try (password) {
            Optional<UserAccount> account = access.login(username, password.value());
            if (account.isEmpty()) output.println("error: invalid username or password");
            return account;
        }
    }

    private Optional<UserAccount> create() throws IOException {
        String username = line("new username> ");
        if (username == null) return Optional.empty();
        PasswordPrompt.Secret password = passwords.read("new password (" + PasswordPolicy.MINIMUM_LENGTH
                + "+ characters)> ");
        if (password == null) return Optional.empty();
        try (password) {
            PasswordPrompt.Secret confirmation = passwords.read("confirm password> ");
            if (confirmation == null) return Optional.empty();
            try (confirmation) {
                if (!Arrays.equals(password.value(), confirmation.value())) {
                    throw new IllegalArgumentException("Passwords do not match");
                }
                // Reject an invalid account password before asking whether this
                // account should receive administrator privileges.
                PasswordPolicy.require(password.value());
                String adminChoice = line("Create as administrator? (Y/N)> ");
                if (adminChoice == null) {
                    return Optional.empty();
                }
                if (adminChoice.trim().equalsIgnoreCase("y")) {
                    PasswordPrompt.Secret adminPassword = passwords.read(
                            administratorUsername + " admin password> ");
                    if (adminPassword == null) {
                        return Optional.empty();
                    }
                    try (adminPassword) {
                        return Optional.of(access.register(username, password.value(),
                                adminPassword.value()));
                    }
                }
                return Optional.of(access.register(username, password.value()));
            }
        }
    }

    private String line(String prompt) throws IOException {
        return input.readLine(output, prompt, false);
    }

}
