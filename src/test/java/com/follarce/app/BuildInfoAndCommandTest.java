package com.follarce.app;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildInfoAndCommandTest {
    @Test
    void embeddedBuildAcceptsTheLatestDatabaseMigration() {
        BuildInfo info = BuildInfo.load();

        assertEquals(1, info.minimumSchema());
        assertEquals(1, info.maximumSchema());
    }

    @Test
    void parsesFilteredBuildInformation() {
        String properties = """
                application.name=CilExec
                application.version=2.4.1
                build.revision=abc123
                fcl.runtime.format=7
                database.schema.minimum=11
                database.schema.maximum=15
                """;

        BuildInfo info = BuildInfo.load(new ByteArrayInputStream(
                properties.getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals("CilExec", info.applicationName());
        assertEquals("2.4.1", info.applicationVersion());
        assertEquals("abc123", info.revision());
        assertEquals(7, info.fclRuntimeFormat());
        assertEquals(11, info.minimumSchema());
        assertEquals(15, info.maximumSchema());
    }

    @Test
    void rejectsUnresolvedBuildPlaceholders() {
        String properties = """
                application.name=CilExec
                application.version=${project.version}
                build.revision=abc123
                fcl.runtime.format=1
                database.schema.minimum=1
                database.schema.maximum=1
                """;

        assertThrows(IllegalStateException.class, () -> BuildInfo.load(
                new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1))));
    }

    @Test
    void parsesTheSupportedCommandsAndExplicitExportPath() {
        assertEquals(ApplicationCommand.TERMINAL, ApplicationCommand.parse(new String[0]));
        assertEquals(ApplicationCommand.TERMINAL,
                ApplicationCommand.parse(new String[]{"repl"}));
        assertEquals(ApplicationCommand.RUNTIME,
                ApplicationCommand.parse(new String[]{"RUNTIME"}));
        assertEquals(ApplicationCommand.MIGRATE,
                ApplicationCommand.parse(new String[]{"migrate"}));
        assertEquals(ApplicationCommand.EXPORT,
                ApplicationCommand.parse(new String[]{"export", "snapshot.db"}));
        assertEquals(ApplicationCommand.PACKAGE_BUILD, ApplicationCommand.parse(
                new String[]{"package", "build", "hello", "hello.db"}));
        assertEquals(ApplicationCommand.HOST_MOVE, ApplicationCommand.parse(
                new String[]{"host", "move", "/tmp/source", "/documents/source", "local"}));
        assertEquals(java.nio.file.Path.of("snapshot.db"),
                ApplicationCommand.exportPath(new String[]{"export", "snapshot.db"}));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationCommand.parse(new String[]{"serve"}));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationCommand.parse(new String[]{"runtime", "migrate"}));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationCommand.parse(new String[]{"export"}));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationCommand.parse(new String[]{"export", "snapshot.sqlite"}));
        assertEquals(java.nio.file.Path.of("hello"), ApplicationCommand.packageSourcePath(
                new String[]{"package", "build", "hello", "hello.db"}));
        assertEquals(java.nio.file.Path.of("hello.db"), ApplicationCommand.packageOutputPath(
                new String[]{"package", "build", "hello", "hello.db"}));
        assertThrows(IllegalArgumentException.class, () -> ApplicationCommand.parse(
                new String[]{"package", "install", "hello", "hello.db"}));
        assertEquals(java.nio.file.Path.of("/tmp/source"), ApplicationCommand.hostSourcePath(
                new String[]{"host", "move", "/tmp/source", "/documents/source"}));
        assertEquals("/documents/source", ApplicationCommand.hostTargetPath(
                new String[]{"host", "move", "/tmp/source", "/documents/source"}));
        assertEquals("local", ApplicationCommand.hostUsername(
                new String[]{"host", "move", "/tmp/source", "/documents/source"}));
        assertThrows(IllegalArgumentException.class, () -> ApplicationCommand.parse(
                new String[]{"host", "move", "/tmp/source", "relative"}));
    }
}
