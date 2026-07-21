package com.follarce.extension.terminal;

import java.util.List;

/** One parsed host-shell command. */
public record ShellCommand(String name, List<String> arguments) {
    public ShellCommand {
        arguments = List.copyOf(arguments);
    }
}
