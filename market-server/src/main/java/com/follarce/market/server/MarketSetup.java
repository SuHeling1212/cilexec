package com.follarce.market.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * One-file market deployment. The JAR is the only file needed to configure a
 * CilExec market on a fresh Linux host:
 *
 * <pre>
 * java -jar cilexec-market-server.jar --setup [options]
 * </pre>
 *
 * It creates the repository layout and empty catalog, creates the dedicated
 * system user, copies itself into the install directory, and registers a
 * systemd service (unless {@code --no-systemd}) that starts the server at
 * boot. Management stays on the same JAR through the interactive console.
 */
final class MarketSetup {
    private static final String SERVICE_NAME = "cilexec-market.service";

    private MarketSetup() { }

    /** OS primitives, injectable for tests. */
    interface Host {
        boolean commandExists(String command);
        boolean userExists(String name);
        boolean isRoot();
        int run(String... command) throws Exception;
    }

    static final Host SYSTEM_HOST = new Host() {
        @Override public boolean commandExists(String command) {
            try {
                return new ProcessBuilder("sh", "-c", "command -v " + command)
                        .redirectErrorStream(true).start().waitFor() == 0;
            } catch (Exception failure) {
                return false;
            }
        }

        @Override public boolean userExists(String name) {
            try {
                return run("id", name) == 0;
            } catch (Exception failure) {
                return false;
            }
        }

        @Override public boolean isRoot() {
            String name = System.getProperty("user.name", "");
            return name.equals("root") || name.equals("0");
        }

        @Override public int run(String... command) throws Exception {
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        }
    };

    static void run(String[] arguments) throws Exception {
        run(arguments, new BufferedReader(new InputStreamReader(System.in,
                StandardCharsets.UTF_8)), SYSTEM_HOST, System.out, Path.of("/etc/systemd/system"));
    }

    /** Non-interactive entry point used by tests: missing values fall back to defaults. */
    static void run(String[] arguments, Host host, PrintStream output, Path systemdDirectory)
            throws Exception {
        run(arguments, new BufferedReader(new StringReader("")), host, output, systemdDirectory);
    }

    static void run(String[] arguments, BufferedReader reader, Host host, PrintStream output,
                     Path systemdDirectory) throws Exception {
        SetupArgs args = prompt(reader, output, SetupArgs.parse(arguments));
        if (host.commandExists("systemctl") && !args.noSystemd() && !host.isRoot()) {
            throw new IllegalArgumentException("--setup with systemd requires root; "
                    + "re-run with sudo or add --no-systemd");
        }
        Path installDir = args.installDir().toAbsolutePath().normalize();
        Path repository = installDir.resolve("repository");
        Path catalog = installDir.resolve("catalog.json");

        output.println("=== CilExec Market setup ===");
        output.println("Install dir: " + installDir);
        output.println("User:        " + args.user());
        output.println("Listen:      " + args.bind().getHostAddress() + ":" + args.port());

        // 1. Layout: directory tree plus an empty catalog, so the JAR can start alone.
        Files.createDirectories(repository.resolve("packages"));
        if (!Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(catalog, "{}\n", StandardCharsets.UTF_8);
            output.println("Created empty catalog: " + catalog);
        }

        // 2. Dedicated system user. Creation is best-effort: when useradd/adduser is
        // missing the service can still run under the current (root) user.
        if (!host.userExists(args.user())) {
            createSystemUser(host, args.user(), installDir, output);
        }

        // 3. Copy this JAR into the install directory (no-op when already there).
        Path sourceJar = runningJar();
        if (sourceJar != null && !sourceJar.toAbsolutePath().normalize()
                .equals(installDir.resolve(sourceJar.getFileName()))) {
            Files.copy(sourceJar, installDir.resolve(sourceJar.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Path installedJar = installDir.resolve("cilexec-market-server.jar");
        if (!Files.exists(installedJar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Cannot locate cilexec-market-server.jar "
                    + "for installation; run --setup from the distribution directory");
        }

        // 4. Own the layout by the service user.
        int chown = host.run("chown", "-R", args.user() + ":" + args.user(),
                installDir.toString());
        if (chown != 0) {
            output.println("Warning: chown failed (non-root --setup?); the service user "
                    + "may not be able to write the repository");
        }

        // 5. systemd service.
        if (args.noSystemd()) {
            output.println("Skipping systemd service (--no-systemd)");
        } else if (!host.commandExists("systemctl")) {
            output.println("Warning: systemctl not found; skipping the systemd service");
        } else {
            String unit = unitFile(args, installDir, repository, catalog, installedJar);
            Path unitPath = systemdDirectory.resolve(SERVICE_NAME);
            Files.writeString(unitPath, unit, StandardCharsets.UTF_8);
            output.println("Wrote " + unitPath);
            check(host, "systemctl", "daemon-reload");
            check(host, "systemctl", "enable", SERVICE_NAME);
            check(host, "systemctl", "restart", SERVICE_NAME);
        }

        output.println();
        output.println("=== Setup complete ===");
        output.println("Index:      http://" + args.bind().getHostAddress() + ":"
                + args.port() + "/market/v1/index.json");
        output.println("Packages:   " + repository + "/packages/<namespace>/<name>/<version>/"
                + "<name>.db");
        output.println();
        output.println("Manage packages with the interactive console (as the same user):");
        output.println("  java --enable-native-access=ALL-UNNAMED -jar " + installedJar);
        output.println();
        output.println("Publish a package from the console:");
        output.println("  publish /path/to/package.db");
        output.println("  list");
        output.println("  unpublish namespace/name/version");
        output.println("  exit");
    }

    static String unitFile(SetupArgs args, Path installDir, Path repository, Path catalog,
                           Path installedJar) {
        StringBuilder exec = new StringBuilder("java --enable-native-access=ALL-UNNAMED -jar ")
                .append(installedJar)
                .append(" --repository ").append(repository)
                .append(" --catalog ").append(catalog)
                .append(" --bind ").append(args.bind().getHostAddress())
                .append(" --port ").append(args.port());
        for (IpNetwork network : args.allowedNetworks()) {
            exec.append(" --allow-cidr ").append(network);
        }
        exec.append(" --headless");
        return """
                [Unit]
                Description=CilExec Market repository
                After=network-online.target
                Wants=network-online.target

                [Service]
                Type=simple
                User=%s
                Group=%s
                WorkingDirectory=%s
                ExecStart=%s
                Restart=on-failure
                RestartSec=2
                NoNewPrivileges=true

                [Install]
                WantedBy=multi-user.target
                """.formatted(args.user(), args.user(), installDir, exec);
    }

    /**
     * Interactive configuration: every value that was not given on the command line
     * is asked for, with the default shown in brackets. Invalid answers are re-asked;
     * end of input falls back to the default so scripts can pipe answers.
     */
    static SetupArgs prompt(BufferedReader reader, PrintStream output, SetupArgs args)
            throws IOException {
        if (!args.specified().contains("--install-dir")) {
            String installDir = ask(reader, output, "Install directory",
                    "/opt/cilexec-market", value -> value);
            args = args.withInstallDir(Path.of(installDir));
        }
        if (!args.specified().contains("--user")) {
            args = args.withUser(ask(reader, output, "Service user", "cilexec-market",
                    value -> value));
        }
        if (!args.specified().contains("--bind")) {
            args = args.withBind(ask(reader, output, "Listen address", "127.0.0.1",
                    value -> addressOrFail(value)));
        }
        if (!args.specified().contains("--port")) {
            args = args.withPort(ask(reader, output, "Listen port", "8787",
                    value -> boundedPort(value)));
        }
        if (!args.specified().contains("--allow-cidr")) {
            List<IpNetwork> networks = new ArrayList<>();
            while (true) {
                String answer = ask(reader, output,
                        "Allow client network (CIDR; blank to finish)", "", value -> value);
                if (answer.isBlank()) break;
                networks.add(IpNetwork.parse(answer));
            }
            args = args.withAllowedNetworks(List.copyOf(networks));
        }
        if (!args.specified().contains("--no-systemd")) {
            String answer = ask(reader, output, "Register a systemd service", "Y",
                    value -> value);
            args = args.withNoSystemd(!answer.toLowerCase(Locale.ROOT).startsWith("y"));
        }
        return args;
    }

    private static <T> T ask(BufferedReader reader, PrintStream output, String label,
                             String defaultValue, Function<String, T> validate)
            throws IOException {
        while (true) {
            output.print(label + " [" + defaultValue + "]: ");
            output.flush();
            String answer = reader.readLine();
            if (answer == null || answer.strip().isEmpty()) {
                return validate.apply(defaultValue);
            }
            try {
                return validate.apply(answer.strip());
            } catch (IllegalArgumentException invalid) {
                output.println("Invalid value: " + invalid.getMessage());
            }
        }
    }

    private static InetAddress addressOrFail(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException invalid) {
            throw new IllegalArgumentException("Invalid address: " + value);
        }
    }

    private static Integer boundedPort(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 65_535) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("port must be from 1 to 65535");
        }
    }

    private static void createSystemUser(Host host, String user, Path home, PrintStream output)
            throws Exception {
        if (host.commandExists("useradd")) {
            int result = host.run("useradd", "--system", "--home-dir", home.toString(),
                    "--shell", "/usr/sbin/nologin", "--comment", "CilExec Market", user);
            if (result == 0) {
                output.println("Created system user: " + user);
                return;
            }
            output.println("Warning: useradd failed for " + user + " (exit " + result
                    + "); the service will run under the current user");
            return;
        }
        if (host.commandExists("adduser")) {
            int result = host.run("adduser", "--system", "--home", home.toString(),
                    "--shell", "/usr/sbin/nologin", "--gecos", "CilExec Market", user);
            if (result == 0) {
                output.println("Created system user: " + user);
                return;
            }
            output.println("Warning: adduser failed for " + user + " (exit " + result
                    + "); the service will run under the current user");
            return;
        }
        output.println("Warning: cannot create system user " + user
                + " (no useradd/adduser); the service will run under the current user");
    }

    private static void check(Host host, String... command) throws Exception {
        int result = host.run(command);
        if (result != 0) {
            throw new IllegalStateException(String.join(" ", command)
                    + " failed (exit " + result + ")");
        }
    }

    /** Location of the running JAR, or null when launched from classes (tests/IDE). */
    static Path runningJar() {
        try {
            var location = MarketSetup.class.getProtectionDomain().getCodeSource()
                    .getLocation();
            if (location == null) return null;
            Path path = Path.of(location.toURI());
            return path.getFileName().toString().endsWith(".jar") ? path : null;
        } catch (Exception failure) {
            return null;
        }
    }

    static String usage() {
        return """
                Usage: java -jar cilexec-market-server.jar --setup [options]
                One-file deployment: configures the whole market interactively (layout,
                system user, this JAR, and a systemd service). Values not given on the
                command line are asked for one by one. Requires root when systemd is used.
                  --install-dir PATH   installation directory (default: /opt/cilexec-market)
                  --user NAME          dedicated system user (default: cilexec-market)
                  --bind ADDRESS       listen address (default: 127.0.0.1)
                  --port PORT          listen port (default: 8787)
                  --allow-cidr CIDR    permit client network; repeatable
                  --no-systemd         do not create or enable the systemd service""";
    }

    record SetupArgs(Path installDir, String user, InetAddress bind, int port,
                     List<IpNetwork> allowedNetworks, boolean noSystemd,
                     Set<String> specified) {
        SetupArgs withInstallDir(Path value) {
            return new SetupArgs(value, user, bind, port, allowedNetworks, noSystemd, specified);
        }

        SetupArgs withUser(String value) {
            return new SetupArgs(installDir, value, bind, port, allowedNetworks, noSystemd,
                    specified);
        }

        SetupArgs withBind(InetAddress value) {
            return new SetupArgs(installDir, user, value, port, allowedNetworks, noSystemd,
                    specified);
        }

        SetupArgs withPort(int value) {
            return new SetupArgs(installDir, user, bind, value, allowedNetworks, noSystemd,
                    specified);
        }

        SetupArgs withAllowedNetworks(List<IpNetwork> value) {
            return new SetupArgs(installDir, user, bind, port, value, noSystemd, specified);
        }

        SetupArgs withNoSystemd(boolean value) {
            return new SetupArgs(installDir, user, bind, port, allowedNetworks, value, specified);
        }

        static SetupArgs parse(String[] arguments) {
            Path installDir = Path.of("/opt/cilexec-market");
            String user = "cilexec-market";
            InetAddress bind = address("127.0.0.1");
            int port = 8787;
            boolean noSystemd = false;
            List<IpNetwork> allowed = new ArrayList<>();
            Set<String> specified = new LinkedHashSet<>();
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if (option.equals("--setup")) continue;
                if (option.equals("--no-systemd")) {
                    noSystemd = true;
                    specified.add("--no-systemd");
                    continue;
                }
                if (option.equals("--help") || option.equals("-h")) {
                    System.out.println(usage());
                    System.exit(0);
                }
                String value = switch (option) {
                    case "--install-dir", "--user", "--bind", "--port", "--allow-cidr"
                            -> requireValue(arguments, ++index, option);
                    default -> throw new IllegalArgumentException("Unknown setup option: "
                            + option);
                };
                specified.add(option);
                switch (option) {
                    case "--install-dir" -> installDir = Path.of(value);
                    case "--user" -> user = value;
                    case "--bind" -> bind = address(value);
                    case "--port" -> port = bounded(value);
                    case "--allow-cidr" -> allowed.add(IpNetwork.parse(value));
                    default -> throw new AssertionError(option);
                }
            }
            return new SetupArgs(installDir, user, bind, port, List.copyOf(allowed), noSystemd,
                    Set.copyOf(specified));
        }

        private static String requireValue(String[] arguments, int index, String option) {
            if (index >= arguments.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return arguments[index];
        }

        private static int bounded(String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1 || parsed > 65_535) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("port must be from 1 to 65535");
            }
        }

        private static InetAddress address(String value) {
            try {
                return InetAddress.getByName(value);
            } catch (UnknownHostException invalid) {
                throw new IllegalArgumentException("Invalid bind address: " + value, invalid);
            }
        }
    }
}
