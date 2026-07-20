package com.follarce.shell;

import java.util.List;

/** One parsed host-shell command. */
public record ShellCommand(String name, List<String> arguments) {
    public ShellCommand {
        arguments = List.copyOf(arguments);
    }
}
