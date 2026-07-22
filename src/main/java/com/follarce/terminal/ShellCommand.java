package com.follarce.terminal;

import java.util.Optional;
import java.util.UUID;

/** Host control-plane commands; this shell is never represented as an FCL process. */
public sealed interface ShellCommand permits ShellCommand.Help, ShellCommand.Processes,
        ShellCommand.Inspect, ShellCommand.Run, ShellCommand.Pause, ShellCommand.Resume,
        ShellCommand.Kill, ShellCommand.Attach, ShellCommand.Submit, ShellCommand.ResolveEffect,
        ShellCommand.Shutdown, ShellCommand.Exit {

    record Help() implements ShellCommand {
    }

    record Processes() implements ShellCommand {
    }

    record Inspect(long pid) implements ShellCommand {
    }

    record Run(String vfsPath, Optional<String> user, Optional<String> name) implements ShellCommand {
        public Run {
            if (vfsPath == null || vfsPath.isBlank()) {
                throw new IllegalArgumentException("vfsPath is required");
            }
            user = user == null ? Optional.empty() : user;
            name = name == null ? Optional.empty() : name;
        }
    }

    record Pause(long pid) implements ShellCommand {
    }

    record Resume(long pid) implements ShellCommand {
    }

    record Kill(long pid) implements ShellCommand {
    }

    record Attach(long pid) implements ShellCommand {
    }

    record Submit(UUID sessionId, String input) implements ShellCommand {
        public Submit {
            if (input == null) {
                throw new IllegalArgumentException("input is required");
            }
        }
    }

    record ResolveEffect(UUID effectId, Decision decision) implements ShellCommand {
        public enum Decision { COMPLETED, FAILED, RETRY }
    }

    record Shutdown() implements ShellCommand {
    }

    record Exit() implements ShellCommand {
    }
}
