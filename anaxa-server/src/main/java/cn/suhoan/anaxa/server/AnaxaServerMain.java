package cn.suhoan.anaxa.server;

public final class AnaxaServerMain {
    private AnaxaServerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.fromArgs(args);
        AnaxaHttpServer server = new AnaxaHttpServer(config);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
        server.start();

        System.out.printf(
                "AnaxaDB listening on http://%s:%d with data dir %s%n",
                config.host(),
                server.port(),
                config.dataDirectory().toAbsolutePath()
        );
    }
}
