package com.follarce.market.server;

public final class MarketServerMain {
    private MarketServerMain() { }

    public static void main(String[] arguments) {
        try {
            ServerOptions options = ServerOptions.parse(arguments);
            MarketRepository repository = new MarketRepository(options.repository(),
                    options.catalog());
            if (options.checkOnly()) {
                System.out.println("CilExec market repository is valid");
                return;
            }
            MarketHttpServer server = new MarketHttpServer(options, repository);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close,
                    "cilexec-market-shutdown"));
            server.start();
            System.out.println("CilExec market: http://" + options.bind().getHostAddress() + ":"
                    + server.port() + "/market/v1/index.json");
            System.out.println("Allowed clients: " + String.join(", ",
                    options.allowedNetworks().stream().map(Object::toString).toList()));
        } catch (ServerOptions.HelpRequested help) {
            System.out.println(ServerOptions.usage());
        } catch (Exception failure) {
            System.err.println("Cannot start CilExec market: " + safe(failure.getMessage()));
            System.exit(1);
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown failure";
        return value.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "?")
                .replace('\n', ' ').replace('\r', ' ');
    }
}
