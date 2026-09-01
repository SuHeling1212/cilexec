package com.follarce.market.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record ServerOptions(Path repository, Path catalog, Path tokens, InetAddress bind, int port,
                     List<IpNetwork> allowedNetworks, int workers, boolean checkOnly,
                     boolean headless) {
    static ServerOptions parse(String[] arguments) {
        Path repository = Path.of("repository");
        Path catalog = Path.of("catalog.json");
        Path tokens = Path.of("tokens.json");
        InetAddress bind = address("127.0.0.1");
        int port = 8787;
        int workers = 16;
        boolean checkOnly = false;
        boolean headless = false;
        List<IpNetwork> allowed = new ArrayList<>();
        allowed.add(IpNetwork.parse("127.0.0.0/8"));
        allowed.add(IpNetwork.parse("::1/128"));
        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            if (option.equals("--check")) {
                checkOnly = true;
                continue;
            }
            if (option.equals("--headless")) {
                headless = true;
                continue;
            }
            String value = switch (option) {
                case "--repository", "--catalog", "--tokens", "--bind", "--port",
                     "--allow-cidr", "--workers" -> requireValue(arguments, ++index, option);
                case "--help", "-h" -> throw new HelpRequested();
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            };
            switch (option) {
                case "--repository" -> repository = Path.of(value);
                case "--catalog" -> catalog = Path.of(value);
                case "--tokens" -> tokens = Path.of(value);
                case "--bind" -> bind = address(value);
                case "--port" -> port = bounded(value, "port", 1, 65_535);
                case "--allow-cidr" -> allowed.add(IpNetwork.parse(value));
                case "--workers" -> workers = bounded(value, "workers", 1, 256);
                default -> throw new AssertionError(option);
            }
        }
        return new ServerOptions(repository.toAbsolutePath().normalize(),
                catalog.toAbsolutePath().normalize(), tokens.toAbsolutePath().normalize(), bind,
                port, List.copyOf(allowed), workers, checkOnly, headless);
    }

    static String usage() {
        return "Usage: java -jar cilexec-market-server.jar [options]\n"
                + "Without options this starts the interactive management console with the\n"
                + "HTTP service running in the background. Add --headless for the plain\n"
                + "foreground service used by systemd. The repository and catalog are\n"
                + "created automatically on first start.\n"
                + "  --repository PATH   package repository (default: ./repository)\n"
                + "  --catalog PATH      publication catalog (default: ./catalog.json)\n"
                + "  --tokens PATH       publish tokens file (default: ./tokens.json)\n"
                + "  --bind ADDRESS      listen address (default: 127.0.0.1)\n"
                + "  --port PORT         listen port (default: 8787)\n"
                + "  --allow-cidr CIDR   permit client network; repeatable\n"
                + "  --workers COUNT     concurrent request limit (default: 16)\n"
                + "  --headless          run the HTTP service in the foreground\n"
                + "  --check             validate repository and exit without listening\n"
                + "  --publish FILE      publish a package database and exit (one-shot)\n"
                + "  --token add NAME    create a publish token for external developers\n"
                + "  --token list        list publish token names\n"
                + "  --token remove NAME remove a publish token\n"
                + "  --setup             one-file deployment (see its own help with --help)\n"
                + "                      without further options: java -jar ... --setup --help";
    }

    private static String requireValue(String[] arguments, int index, String option) {
        if (index >= arguments.length) throw new IllegalArgumentException(
                "Missing value for " + option);
        return arguments[index];
    }

    private static int bounded(String value, String field, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be from " + minimum + " to "
                    + maximum);
        }
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException invalid) {
            throw new IllegalArgumentException("Invalid bind address: " + value, invalid);
        }
    }

    static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
