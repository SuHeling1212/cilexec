package com.follarce.terminal;

import java.util.List;

/** Converts a durable terminal operation log into an executable FCL-oriented script. */
final class TerminalOperationScript {
    private TerminalOperationScript() {
    }

    static String render(List<String> operations) {
        java.util.Objects.requireNonNull(operations, "operations");
        StringBuilder script = new StringBuilder();
        script.append("// CilExec terminal operation export\n")
                .append("// Terminal commands are retained as comments.\n\n");
        for (String operation : operations) {
            java.util.Objects.requireNonNull(operation, "operation");
            if (operation.stripLeading().startsWith(":")) {
                script.append("// terminal: ")
                        .append(operation.replace("\n", "\n// "))
                        .append("\n\n");
            } else {
                script.append(operation).append("\n\n");
            }
        }
        return script.toString();
    }
}
