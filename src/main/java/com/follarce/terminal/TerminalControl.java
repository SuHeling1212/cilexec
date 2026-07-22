package com.follarce.terminal;

/** Application boundary used by host terminal transports. */
@FunctionalInterface
public interface TerminalControl {
    String execute(ShellCommand command);
}
