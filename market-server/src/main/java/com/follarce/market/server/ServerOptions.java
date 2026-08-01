package com.follarce.market.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record ServerOptions(Path repository, Path catalog, InetAddress bind, int port,
                     List<IpNetwork> allowedNetworks, int workers) {
    static ServerOptions parse(String[] arguments) {
        Path repository = Path.of("repository");
        Path catalog = Path.of("catalog.json");
        InetAddress bind = address("127.0.0.1");
        int port = 8787;
        int workers = 16;
        List<IpNetwork> allowed = new ArrayList<>();
        allowed.add(IpNetwork.parse("127.0.0.0/8"));
        allowed.add(IpNetwork.parse("::1/128"));
        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            String value = switch (option) {
                case "--repository", "--catalog", "--bind", "--port", "--allow-cidr",
                     "--workers" -> requireValue(arguments, ++index, option);
                case "--help", "-h" -> throw new HelpRequested();
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            };
            switch (option) {
                case "--repository" -> repository = Path.of(value);
                case "--catalog" -> catalog = Path.of(value);
                case "--bind" -> bind = address(value);
                case "--port" -> port = bounded(value, "port", 1, 65_535);
                case "--allow-cidr" -> allowed.add(IpNetwork.parse(value));
                case "--workers" -> workers = bounded(value, "workers", 1, 256);
                default -> throw new AssertionError(option);
            }
        }
        return new ServerOptions(repository.toAbsolutePath().normalize(),
                catalog.toAbsolutePath().normalize(), bind, port, List.copyOf(allowed), workers);
    }

    static String usage() {
        return "Usage: java -jar cilexec-market-server.jar [options]\n"
                + "  --repository PATH   package repository (default: ./repository)\n"
                + "  --catalog PATH      publication catalog (default: ./catalog.json)\n"
                + "  --bind ADDRESS      listen address (default: 127.0.0.1)\n"
                + "  --port PORT         listen port (default: 8787)\n"
                + "  --allow-cidr CIDR   permit client network; repeatable\n"
                + "  --workers COUNT     concurrent request limit (default: 16)";
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

    static final class HelpRequested extends RuntimeException { }
}
