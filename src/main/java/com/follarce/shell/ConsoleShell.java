package com.follarce.shell;

import com.follarce.kernel.Constants;
import com.follarce.kernel.util.JsonUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** The host-facing Java shell. It is not an FCL process and has no PID. */
public final class ConsoleShell {
    private static final String PROMPT = "cilexec> ";

    private final BufferedReader input;
    private final PrintWriter output;
    private final SystemControlService control;
    private final Runnable shutdownAction;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();

    public ConsoleShell() {
        this(new InputStreamReader(System.in), new PrintWriter(System.out, true),
                new SystemControlService(), () -> {});
    }

    public ConsoleShell(Reader input, Writer output, SystemControlService control,
                        Runnable shutdownAction) {
        this.input = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        this.output = output instanceof PrintWriter writer ? writer : new PrintWriter(output, true);
        this.control = control;
        this.shutdownAction = shutdownAction;
    }

    public void run() {
        output.println("Cilexec host shell. Type 'help' for commands.");
        try {
            boolean running = true;
            while (running) {
                output.print(PROMPT);
                output.flush();
                String line = input.readLine();
                if (line == null) break;
                running = executeLine(line);
            }
        } catch (IOException e) {
            output.println("error: shell input failed: " + e.getMessage());
        } finally {
            requestShutdown();
        }
    }

    public boolean executeLine(String line) {
        try {
            ShellCommand command = ShellCommandParser.parse(line).orElse(null);
            if (command == null) return true;
            return switch (command.name()) {
                case "help" -> showHelp(command.arguments());
                case "ps" -> showProcesses(command.arguments());
                case "inspect" -> inspect(command.arguments());
                case "run" -> runProcess(command.arguments());
                case "pause" -> pause(command.arguments());
                case "continue", "resume" -> resume(command.arguments());
                case "kill" -> kill(command.arguments());
                case "package", "pkg" -> packageCommand(command.arguments());
                case "clear" -> clear(command.arguments());
                case "exit", "quit" -> exit(command.arguments());
                default -> throw new IllegalArgumentException(
                        "Unknown command: " + command.name() + ". Type 'help'.");
            };
        } catch (RuntimeException e) {
            output.println("error: " + rootMessage(e));
            return true;
        }
    }

    private boolean showHelp(List<String> arguments) {
        requireCount(arguments, 0, "help");
        output.println("Commands:");
        output.println("  ps");
        output.println("  inspect <pid>");
        output.println("  run <vfs-script> [--user <user>] [--name <name>] [--priority <low|normal|high>]");
        output.println("  pause <pid> | continue <pid> | kill <pid>");
        output.println("  package list [--user <user>]");
        output.println("  package build <source> <output> [--user <user>]");
        output.println("  package install <source> [--binding <name>] [--repository <path>] [--user <user>]");
        output.println("  package remove|info|verify|pin|unpin <value> [--user <user>]");
        output.println("  package gc | package recover");
        output.println("  clear | exit");
        return true;
    }

    private boolean showProcesses(List<String> arguments) {
        requireCount(arguments, 0, "ps");
        List<SystemControlService.ProcessSummary> processes = control.listProcesses();
        if (processes.isEmpty()) {
            output.println("No FCL processes.");
            return true;
        }
        output.printf("%-5s %-11s %-12s %-4s %-8s %-20s %s%n",
                "PID", "STATE", "USER", "PRI", "SECONDS", "NAME", "PATH");
        for (SystemControlService.ProcessSummary process : processes) {
            output.printf("%-5d %-11s %-12s %-4d %-8d %-20s %s%n",
                    process.pid(), process.state(), process.user(), process.priority(),
                    process.runningTime(), process.name(), process.path());
        }
        return true;
    }

    private boolean inspect(List<String> arguments) {
        requireCount(arguments, 1, "inspect <pid>");
        printValue(control.inspectProcess(pid(arguments.getFirst())));
        return true;
    }

    private boolean runProcess(List<String> arguments) {
        ParsedArguments parsed = parseOptions(arguments,
                Set.of("--user", "--name", "--priority"));
        requireCount(parsed.positionals(), 1, "run <vfs-script> [options]");
        String user = parsed.option("--user", Constants.DEFAULT_USER_LOCAL);
        String name = parsed.option("--name", null);
        int priority = priority(parsed.option("--priority", "normal"));
        int pid = control.startProcess(parsed.positionals().getFirst(), user, name, priority);
        output.println("Started FCL process PID " + pid + ".");
        return true;
    }

    private boolean pause(List<String> arguments) {
        requireCount(arguments, 1, "pause <pid>");
        int pid = pid(arguments.getFirst());
        control.pauseProcess(pid);
        output.println("Pause requested for PID " + pid + ".");
        return true;
    }

    private boolean resume(List<String> arguments) {
        requireCount(arguments, 1, "continue <pid>");
        int pid = pid(arguments.getFirst());
        control.continueProcess(pid);
        output.println("Continue requested for PID " + pid + ".");
        return true;
    }

    private boolean kill(List<String> arguments) {
        requireCount(arguments, 1, "kill <pid>");
        int pid = pid(arguments.getFirst());
        control.killProcess(pid);
        output.println("Kill requested for PID " + pid + ".");
        return true;
    }

    private boolean packageCommand(List<String> arguments) {
        if (arguments.isEmpty()) throw new IllegalArgumentException("Usage: package <operation> ...");
        String operation = arguments.getFirst().toLowerCase(Locale.ROOT);
        List<String> tail = arguments.subList(1, arguments.size());
        return switch (operation) {
            case "list" -> packageList(tail);
            case "build" -> packageBuild(tail);
            case "install" -> packageInstall(tail);
            case "remove" -> packageUnary(tail, "remove");
            case "info" -> packageUnary(tail, "info");
            case "verify" -> packageUnary(tail, "verify");
            case "pin" -> packageUnary(tail, "pin");
            case "unpin" -> packageUnary(tail, "unpin");
            case "gc" -> packageGc(tail);
            case "recover" -> packageRecover(tail);
            default -> throw new IllegalArgumentException("Unknown package operation: " + operation);
        };
    }

    private boolean packageList(List<String> arguments) {
        ParsedArguments parsed = parseOptions(arguments, Set.of("--user"));
        requireCount(parsed.positionals(), 0, "package list [--user <user>]");
        printValue(control.listPackages(parsed.option("--user", Constants.DEFAULT_USER_LOCAL)));
        return true;
    }

    private boolean packageBuild(List<String> arguments) {
        ParsedArguments parsed = parseOptions(arguments, Set.of("--user"));
        requireCount(parsed.positionals(), 2, "package build <source> <output> [--user <user>]");
        printValue(control.buildPackage(parsed.option("--user", Constants.DEFAULT_USER_LOCAL),
                parsed.positionals().get(0), parsed.positionals().get(1)));
        return true;
    }

    private boolean packageInstall(List<String> arguments) {
        ParsedArguments parsed = parseOptions(arguments,
                Set.of("--user", "--binding", "--repository"));
        requireCount(parsed.positionals(), 1, "package install <source> [options]");
        printValue(control.installPackage(
                parsed.option("--user", Constants.DEFAULT_USER_LOCAL),
                parsed.positionals().getFirst(), parsed.option("--binding", null),
                parsed.option("--repository", null)));
        return true;
    }

    private boolean packageUnary(List<String> arguments, String operation) {
        ParsedArguments parsed = parseOptions(arguments, Set.of("--user"));
        requireCount(parsed.positionals(), 1,
                "package " + operation + " <value> [--user <user>]");
        String user = parsed.option("--user", Constants.DEFAULT_USER_LOCAL);
        String value = parsed.positionals().getFirst();
        Object result = switch (operation) {
            case "remove" -> control.removePackage(user, value);
            case "info" -> control.packageInfo(user, value);
            case "verify" -> control.verifyPackage(user, value);
            case "pin" -> control.pinPackage(user, value);
            case "unpin" -> control.unpinPackage(user, value);
            default -> throw new IllegalStateException("Unsupported package operation: " + operation);
        };
        printValue(result);
        return true;
    }

    private boolean packageGc(List<String> arguments) {
        requireCount(arguments, 0, "package gc");
        printValue(control.garbageCollectPackages());
        return true;
    }

    private boolean packageRecover(List<String> arguments) {
        requireCount(arguments, 0, "package recover");
        control.recoverPackages();
        output.println("Package recovery completed.");
        return true;
    }

    private boolean clear(List<String> arguments) {
        requireCount(arguments, 0, "clear");
        output.print("\033[2J\033[H");
        output.flush();
        return true;
    }

    private boolean exit(List<String> arguments) {
        requireCount(arguments, 0, "exit");
        output.println("Shutting down Cilexec.");
        requestShutdown();
        return false;
    }

    private void printValue(Object value) {
        if (value instanceof String) {
            output.println(value);
        } else {
            output.println(JsonUtil.toJson(value));
        }
    }

    private void requestShutdown() {
        if (shutdownRequested.compareAndSet(false, true)) shutdownAction.run();
    }

    private static ParsedArguments parseOptions(List<String> arguments, Set<String> allowed) {
        List<String> positionals = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < arguments.size(); i++) {
            String value = arguments.get(i);
            if (!value.startsWith("--")) {
                positionals.add(value);
                continue;
            }
            if (!allowed.contains(value)) throw new IllegalArgumentException("Unknown option: " + value);
            if (options.containsKey(value)) throw new IllegalArgumentException("Duplicate option: " + value);
            if (++i >= arguments.size() || arguments.get(i).startsWith("--")) {
                throw new IllegalArgumentException("Missing value for option: " + value);
            }
            options.put(value, arguments.get(i));
        }
        return new ParsedArguments(positionals, options);
    }

    private static void requireCount(List<String> arguments, int count, String usage) {
        if (arguments.size() != count) throw new IllegalArgumentException("Usage: " + usage);
    }

    private static int pid(String value) {
        try {
            int pid = Integer.parseInt(value);
            if (pid <= 0) throw new NumberFormatException();
            return pid;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid PID: " + value);
        }
    }

    private static int priority(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "low", "1" -> Constants.PRIORITY_LOW;
            case "normal", "3" -> Constants.PRIORITY_NORMAL;
            case "high", "5" -> Constants.PRIORITY_HIGH;
            default -> throw new IllegalArgumentException("Invalid priority: " + value);
        };
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ParsedArguments(List<String> positionals, Map<String, String> options) {
        private ParsedArguments {
            positionals = List.copyOf(positionals);
            options = Map.copyOf(options);
        }

        String option(String name, String fallback) {
            return options.getOrDefault(name, fallback);
        }
    }
}
