package com.follarce.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connects the launching TTY to the Runtime's local terminal protocol. */
final class LocalTerminalClient {
    private static final byte[] RESET = ("\u001b[?1049l\u001b[?1002l\u001b[?1006l"
            + "\u001b[?2004l\u001b[?1004l\u001b[?25h")
            .getBytes(StandardCharsets.US_ASCII);

    private LocalTerminalClient() {
    }

    static void attach(int port) {
        // Windows Console supplies line input itself and has no stty command. The
        // protocol remains the same; terminal sizing uses the server's default.
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("windows");
        String saved = windows ? null : terminalCommand("-g").trim();
        if (!windows && saved.isEmpty()) {
            throw new IllegalStateException("Cannot read terminal mode");
        }
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            var remoteInput = socket.getInputStream();
            var remoteOutput = socket.getOutputStream();
            remoteOutput.write("\0M INTERACTIVE\n".getBytes(StandardCharsets.US_ASCII));
            remoteOutput.flush();
            if (!windows) terminalCommand("raw", "-echo");
            try {
                System.out.write(RESET);
                System.out.flush();
                sendSize(remoteOutput);
                AtomicBoolean connected = new AtomicBoolean(true);
                Thread output = Thread.ofVirtual().name("cilexec-local-terminal-output")
                        .start(() -> {
                            try {
                                copyOutput(remoteInput);
                            } catch (IOException ignored) {
                                // Closing the terminal socket ends this attachment only.
                            } finally {
                                connected.set(false);
                            }
                        });
                if (windows) {
                    Thread.ofVirtual().name("cilexec-windows-terminal-input").start(() -> {
                        try {
                            java.io.Console console = System.console();
                            java.io.BufferedReader fallback = console == null
                                    ? new java.io.BufferedReader(new java.io.InputStreamReader(
                                    System.in, StandardCharsets.UTF_8)) : null;
                            while (connected.get()) {
                                String line = console == null ? fallback.readLine()
                                        : console.readLine();
                                if (line == null) {
                                    socket.shutdownOutput();
                                    break;
                                }
                                remoteOutput.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                                remoteOutput.flush();
                            }
                        } catch (IOException ignored) {
                            // A remote disconnect ends this attachment only.
                        }
                    });
                    output.join();
                } else {
                    byte[] input = new byte[4096];
                    long nextSizeCheck = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                    try {
                        while (connected.get()) {
                            if (System.in.available() > 0) {
                                int count = System.in.read(input, 0,
                                        Math.min(input.length, System.in.available()));
                                if (count < 0) {
                                    socket.shutdownOutput();
                                    break;
                                }
                                for (int index = 0; index < count; index++) {
                                    if (input[index] == 3) remoteOutput.write(new byte[]{0, 'I', '\n'});
                                    else remoteOutput.write(input[index]);
                                }
                                remoteOutput.flush();
                            } else {
                                Thread.sleep(25);
                            }
                            if (System.nanoTime() >= nextSizeCheck) {
                                sendSize(remoteOutput);
                                nextSizeCheck = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                            }
                        }
                    } catch (IOException ignored) {
                        // A remote disconnect leaves the Runtime running for other terminals.
                    }
                    output.join(Duration.ofSeconds(1));
                }
            } finally {
                System.out.write(RESET);
                System.out.flush();
                if (!windows) terminalCommand(saved);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot attach local terminal on port " + port,
                    failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Local terminal was interrupted", interrupted);
        }
    }

    private static void sendSize(java.io.OutputStream remote) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return;
        }
        String[] values = terminalCommand("size").trim().split("\\s+");
        if (values.length != 2) return;
        try {
            int rows = Integer.parseInt(values[0]);
            int columns = Integer.parseInt(values[1]);
            if (rows < 1 || columns < 1) return;
            remote.write(("\0S " + rows + " " + columns + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            remote.flush();
        } catch (NumberFormatException ignored) {
            // A broken or unsupported size report leaves the server's 80x24 default.
        }
    }

    private static void copyOutput(InputStream remote) throws IOException {
        byte[] bytes = new byte[8192];
        boolean previousWasReturn = false;
        int count;
        while ((count = remote.read(bytes)) >= 0) {
            for (int index = 0; index < count; index++) {
                byte value = bytes[index];
                if (value == '\n' && !previousWasReturn) System.out.write('\r');
                System.out.write(value);
                previousWasReturn = value == '\r';
            }
            System.out.flush();
        }
    }

    private static String terminalCommand(String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = "stty";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Cannot configure terminal: " + error.trim());
            }
            return output;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot run stty for the local terminal", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Terminal setup was interrupted", interrupted);
        }
    }
}
