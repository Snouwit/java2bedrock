package net.snouli.bedrockbridge.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Tiny download server for the Geyser extension. Serves exactly three files, GET only, and every
 * request needs {@code ?token=...}.
 */
public final class BridgeHttpServer {

    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] token;
    private final Supplier<Published> published;

    public BridgeHttpServer(String bind, int port, String token, Supplier<Published> published) throws IOException {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.published = published;
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "SnouliBedrockBridge-HTTP");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/version.json") && !path.equals("/pack.mcpack") && !path.equals("/mappings.json")) {
                send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!tokenMatches(exchange.getRequestURI().getRawQuery())) {
                send(exchange, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Published current = published.get();
            if (current == null) {
                send(exchange, 503, "text/plain", "no build yet".getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            switch (path) {
                case "/version.json" -> send(exchange, 200, "application/json", current.versionJson());
                case "/mappings.json" -> send(exchange, 200, "application/json", current.mappings());
                default -> send(exchange, 200, "application/zip", current.pack());
            }
        }
    }

    private boolean tokenMatches(String rawQuery) {
        if (rawQuery == null) {
            return false;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith("token=")) {
                byte[] given = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
                return MessageDigest.isEqual(given, token);
            }
        }
        return false;
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
