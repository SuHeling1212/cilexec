package com.follarce.market.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketSetupTest {
    @TempDir Path temporary;

    private static final class FakeHost implements MarketSetup.Host {
        final List<String[]> commands = new ArrayList<>();
        boolean systemctlAvailable = true;
        boolean userExists;
        boolean root;
        int result;

        @Override public boolean commandExists(String command) {
            return systemctlAvailable || !command.equals("systemctl");
        }

        @Override public boolean userExists(String name) {
            return userExists;
        }

        @Override public boolean isRoot() {
            return root;
        }

        @Override public int run(String... command) {
            commands.add(command);
            return result;
        }
    }

    private Path installedJar(Path directory) throws Exception {
        Path jar = directory.resolve("cilexec-market-server.jar");
        Files.write(jar, new byte[] {1, 2, 3});
        return jar;
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Test
    void parsesSetupDefaultsAndOverrides() {
        MarketSetup.SetupArgs defaults = MarketSetup.SetupArgs.parse(new String[] {"--setup"});
        assertEquals(Path.of("/opt/cilexec-market"), defaults.installDir());
        assertEquals("cilexec-market", defaults.user());
        assertFalse(defaults.noSystemd());
        assertTrue(defaults.allowedNetworks().isEmpty());

        MarketSetup.SetupArgs custom = MarketSetup.SetupArgs.parse(new String[] {
                "--setup", "--install-dir", "/srv/market", "--user", "market",
                "--bind", "0.0.0.0", "--port", "9000", "--allow-cidr", "10.0.0.0/8",
                "--allow-cidr", "192.168.1.0/24", "--no-systemd"});
        assertEquals(Path.of("/srv/market"), custom.installDir());
        assertEquals("market", custom.user());
        assertEquals(9000, custom.port());
        assertEquals(2, custom.allowedNetworks().size());
        assertTrue(custom.noSystemd());
    }

    @Test
    void rejectsUnknownSetupOptionsAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class,
                () -> MarketSetup.SetupArgs.parse(new String[] {"--setup", "--bogus", "1"}));
        assertThrows(IllegalArgumentException.class,
                () -> MarketSetup.SetupArgs.parse(new String[] {"--setup", "--port", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> MarketSetup.SetupArgs.parse(new String[] {"--setup", "--port", "70000"}));
    }

    @Test
    void setupCreatesLayoutUserAndSystemdServiceInOneRun() throws Exception {
        Path installDir = temporary.resolve("install");
        Files.createDirectories(installDir);
        installedJar(installDir);
        Path systemdDir = temporary.resolve("systemd");
        Files.createDirectories(systemdDir);
        FakeHost host = new FakeHost();
        host.root = true;

        MarketSetup.run(new String[] {"--setup", "--install-dir", installDir.toString(),
                "--bind", "0.0.0.0", "--port", "9000", "--allow-cidr", "192.168.1.0/24"},
                host, output(), systemdDir);

        assertTrue(Files.isDirectory(installDir.resolve("repository/packages")));
        assertEquals("{}\n", Files.readString(installDir.resolve("catalog.json")));
        assertTrue(host.commands.stream().anyMatch(command -> command[0].equals("useradd")),
                "dedicated system user must be created");
        assertTrue(host.commands.stream().anyMatch(command -> command[0].equals("chown")
                && command[1].equals("-R")), "layout must be owned by the service user");
        assertEquals(3, host.commands.stream().filter(command -> command[0].equals("systemctl"))
                .count(), "daemon-reload, enable and restart");
        String unit = Files.readString(systemdDir.resolve("cilexec-market.service"));
        assertTrue(unit.contains("User=" + "cilexec-market"), unit);
        assertTrue(unit.contains("WorkingDirectory=" + installDir), unit);
        assertTrue(unit.contains("--bind 0.0.0.0 --port 9000 --allow-cidr 192.168.1.0/24"
                + " --headless"), unit);
    }

    @Test
    void setupSkipsUserCreationWhenTheUserAlreadyExists() throws Exception {
        Path installDir = temporary.resolve("install");
        Files.createDirectories(installDir);
        installedJar(installDir);
        Path systemdDir = temporary.resolve("systemd");
        Files.createDirectories(systemdDir);
        FakeHost host = new FakeHost();
        host.userExists = true;
        host.root = true;

        MarketSetup.run(new String[] {"--setup", "--install-dir", installDir.toString()},
                host, output(), systemdDir);

        assertFalse(host.commands.stream().anyMatch(command -> command[0].equals("useradd")),
                "existing user must not be recreated");
    }

    @Test
    void noSystemdSkipsTheServiceWithoutTouchingSystemctl() throws Exception {
        Path installDir = temporary.resolve("install");
        Files.createDirectories(installDir);
        installedJar(installDir);
        Path systemdDir = temporary.resolve("systemd");
        Files.createDirectories(systemdDir);
        FakeHost host = new FakeHost();

        MarketSetup.run(new String[] {"--setup", "--install-dir", installDir.toString(),
                "--no-systemd"}, host, output(), systemdDir);

        assertFalse(host.commands.stream().anyMatch(command -> command[0].equals("systemctl")));
        assertFalse(Files.exists(systemdDir.resolve("cilexec-market.service")));
        assertTrue(Files.isRegularFile(installDir.resolve("catalog.json")),
                "layout must still be created without systemd");
    }

    @Test
    void setupFailsWhenTheJarCannotBeLocatedForInstallation() throws Exception {
        Path installDir = temporary.resolve("install");
        Files.createDirectories(installDir);
        FakeHost host = new FakeHost();

        assertThrows(IllegalStateException.class,
                () -> MarketSetup.run(new String[] {"--setup", "--install-dir",
                        installDir.toString(), "--no-systemd"}, host, output(), temporary));
    }

    @Test
    void unitFileListsEveryAllowedNetworkAndHeadlessMode() throws Exception {
        MarketSetup.SetupArgs args = MarketSetup.SetupArgs.parse(new String[] {
                "--setup", "--user", "market", "--bind", "127.0.0.1", "--port", "8787",
                "--allow-cidr", "10.0.0.0/8", "--allow-cidr", "192.168.50.0/24"});
        Path install = Path.of("/opt/cilexec-market");
        Path repository = install.resolve("repository");
        Path catalog = install.resolve("catalog.json");
        Path jar = install.resolve("cilexec-market-server.jar");

        String unit = MarketSetup.unitFile(args, install, repository, catalog, jar);

        assertTrue(unit.contains("User=market"), unit);
        assertTrue(unit.contains("ExecStart=java --enable-native-access=ALL-UNNAMED -jar "
                + jar + " --repository " + repository + " --catalog " + catalog
                + " --bind 127.0.0.1 --port 8787"
                + " --allow-cidr 10.0.0.0/8 --allow-cidr 192.168.50.0/24 --headless"), unit);
        assertTrue(unit.contains("WantedBy=multi-user.target"), unit);
    }

    @Test
    void systemdSetupDemandsRoot() throws Exception {
        Path installDir = temporary.resolve("install");
        Files.createDirectories(installDir);
        installedJar(installDir);
        FakeHost host = new FakeHost();
        host.root = false;

        assertThrows(IllegalArgumentException.class,
                () -> MarketSetup.run(new String[] {"--setup", "--install-dir",
                        installDir.toString()}, host, output(), temporary));
    }

    @Test
    void interactivePromptFillsEveryValueWithDefaults() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new StringReader(
                "\n\n\n\n\nn\n"));

        MarketSetup.SetupArgs args = MarketSetup.prompt(reader, out,
                MarketSetup.SetupArgs.parse(new String[] {"--setup"}));

        assertEquals(Path.of("/opt/cilexec-market"), args.installDir());
        assertEquals("cilexec-market", args.user());
        assertEquals(8787, args.port());
        assertTrue(args.allowedNetworks().isEmpty());
        assertTrue(args.noSystemd());
        String transcript = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("Install directory [/opt/cilexec-market]: "), transcript);
        assertTrue(transcript.contains("Register a systemd service [Y]: "), transcript);
    }

    @Test
    void interactivePromptAcceptsCustomValuesAndRepeatedNetworks() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new StringReader(
                "/srv/market\nmarket\n0.0.0.0\n9000\n10.0.0.0/8\n192.168.1.0/24\n\ny\n"));

        MarketSetup.SetupArgs args = MarketSetup.prompt(reader, out,
                MarketSetup.SetupArgs.parse(new String[] {"--setup"}));

        assertEquals(Path.of("/srv/market"), args.installDir());
        assertEquals("market", args.user());
        assertEquals(9000, args.port());
        assertEquals(2, args.allowedNetworks().size());
        assertEquals("10.0.0.0/8", args.allowedNetworks().get(0).toString());
        assertEquals("192.168.1.0/24", args.allowedNetworks().get(1).toString());
        assertFalse(args.noSystemd());
    }

    @Test
    void interactivePromptKeepsCommandLineValuesWithoutAsking() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new StringReader(
                "other-user\n\n\n"));

        MarketSetup.SetupArgs args = MarketSetup.prompt(reader, out,
                MarketSetup.SetupArgs.parse(new String[] {"--setup", "--install-dir",
                        "/srv/market", "--port", "9000"}));

        assertEquals(Path.of("/srv/market"), args.installDir(),
                "given install dir must not be re-asked");
        assertEquals("other-user", args.user(), "missing user is asked");
        assertEquals(9000, args.port(), "given port must not be re-asked");
        String transcript = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(transcript.contains("Install directory"), transcript);
        assertFalse(transcript.contains("Listen port"), transcript);
    }

    @Test
    void interactivePromptReasksInvalidValues() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new StringReader(
                "not-a-port\n8787\n127.0.0.1\nnot-a-port\n8787\n\nn\n"));

        MarketSetup.SetupArgs args = MarketSetup.prompt(reader, out,
                MarketSetup.SetupArgs.parse(new String[] {"--setup"}));

        assertEquals(8787, args.port());
        assertTrue(bytes.toString(StandardCharsets.UTF_8)
                .contains("port must be from 1 to 65535"));
    }
}
