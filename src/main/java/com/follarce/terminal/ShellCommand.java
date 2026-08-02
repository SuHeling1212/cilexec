package com.follarce.terminal;

import java.util.Optional;

/** Minimal host-terminal commands; system operations are exposed through FCL functions. */
public sealed interface ShellCommand permits ShellCommand.Help, ShellCommand.ChangeDirectory,
        ShellCommand.WorkingDirectory, ShellCommand.ListDirectory, ShellCommand.Logout,
        ShellCommand.Clear, ShellCommand.Exit, ShellCommand.Shutdown {

    record Help() implements ShellCommand {
    }

    record ChangeDirectory(String path) implements ShellCommand {
        public ChangeDirectory {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path is required");
            }
        }
    }

    record WorkingDirectory() implements ShellCommand {
    }

    record ListDirectory(Optional<String> path) implements ShellCommand {
        public ListDirectory {
            path = path == null ? Optional.empty() : path;
        }
    }

    record Logout() implements ShellCommand {
    }

    record Clear() implements ShellCommand {
    }

    record Exit() implements ShellCommand {
    }

    record Shutdown() implements ShellCommand {
    }
}
