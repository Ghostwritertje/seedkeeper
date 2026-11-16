package io.github.ghostwritertje.seedkeeper.transmission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for Transmission's RPC endpoint on the JDK's {@link HttpServer}. Like the real thing it answers 409 with
 * a fresh {@code X-Transmission-Session-Id} until a request carries the current one.
 */
public final class FakeTransmission implements AutoCloseable {

    public static final String USERNAME = "transmission-user";
    public static final String PASSWORD = "transmission-password";
    public static final String DEFAULT_VERSION = "4.1.0 (5e6a9e0a3d)";

    /** {@code printf 'transmission-user:transmission-password' | base64} */
    private static final String EXPECTED_AUTHORIZATION = "Basic dHJhbnNtaXNzaW9uLXVzZXI6dHJhbnNtaXNzaW9uLXBhc3N3b3Jk";

    public enum Behaviour {
        /** Transmission 4.1 or newer: speaks JSON-RPC 2.0. */
        JSON_RPC,
        /** Never accepts a session id: every request gets a 409 with a new one. */
        ALWAYS_CONFLICT,
        /** Transmission before 4.1: doesn't know the JSON-RPC 2.0 method names. */
        LEGACY,
        /** Answers every call with a JSON-RPC error. */
        RPC_ERROR,
        /** Answers with something that is not JSON. */
        GARBAGE
    }

    public record Request(String sessionId, String authorization, String body) {}

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Behaviour behaviour = Behaviour.JSON_RPC;
    private volatile String version = DEFAULT_VERSION;
    private volatile String incompleteDir = "/incomplete";
    private volatile boolean incompleteDirEnabled = false;
    private volatile String torrents = "[]";
    private final AtomicInteger sessionCounter = new AtomicInteger(1);
    private final Set<String> refusedRemovals = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Hold> nextSessionGet = new AtomicReference<>();

    public FakeTransmission() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // A thread per request, so a request that is held up doesn't hold up the others.
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-transmission");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/transmission/rpc", this::handle);
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/transmission/rpc");
    }

    public FakeTransmission behaviour(Behaviour behaviour) {
        this.behaviour = behaviour;
        return this;
    }

    public FakeTransmission version(String version) {
        this.version = version;
        return this;
    }

    /** What {@code session_get} says about the incomplete dir. */
    public FakeTransmission incompleteDir(String incompleteDir, boolean enabled) {
        this.incompleteDir = incompleteDir;
        this.incompleteDirEnabled = enabled;
        return this;
    }

    /** The JSON array {@code torrent_get} answers with, in Transmission's own field names. */
    public FakeTransmission torrents(String json) {
        this.torrents = json;
        return this;
    }

    /** A request that is kept waiting; see {@link #holdNextSessionGet()}. */
    public static final class Hold {

        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        /** Waits until the request has arrived; false when it didn't in time. */
        public boolean reached(long timeout, TimeUnit unit) throws InterruptedException {
            return reached.await(timeout, unit);
        }

        /** Lets the request be answered. */
        public void release() {
            released.countDown();
        }
    }

    /** The next {@code session_get} is not answered until the hold is released; the calls after it are. */
    public Hold holdNextSessionGet() {
        Hold hold = new Hold();
        nextSessionGet.set(hold);
        return hold;
    }

    /** {@code torrent_remove} of this torrent is answered with a JSON-RPC error; the torrent stays. */
    public FakeTransmission refuseRemovalOf(String hash) {
        refusedRemovals.add(hash);
        return this;
    }

    /** Back to a healthy Transmission 4.1 with no torrents and no recorded requests. */
    public void reset() {
        behaviour = Behaviour.JSON_RPC;
        version = DEFAULT_VERSION;
        incompleteDir = "/incomplete";
        incompleteDirEnabled = false;
        torrents = "[]";
        refusedRemovals.clear();
        nextSessionGet.set(null);
        requests.clear();
    }

    /** Transmission restarted: the session id every client holds is no longer valid. */
    public void expireSession() {
        sessionCounter.incrementAndGet();
    }

    public String sessionId() {
        return "session-" + sessionCounter.get();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sessionId = exchange.getRequestHeaders().getFirst("X-Transmission-Session-Id");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(new Request(sessionId, authorization, body));

            if (!EXPECTED_AUTHORIZATION.equals(authorization)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Transmission\"");
                respond(exchange, 401, "text/html", "<h1>401: Unauthorized</h1>");
                return;
            }
            if (behaviour == Behaviour.ALWAYS_CONFLICT) {
                expireSession();
            }
            if (!sessionId().equals(sessionId)) {
                exchange.getResponseHeaders().add("X-Transmission-Session-Id", sessionId());
                respond(exchange, 409, "text/html", "<h1>409: Conflict</h1>");
                return;
            }
            if (body.contains("\"method\":\"session_get\"")) {
                Hold hold = nextSessionGet.getAndSet(null);
                if (hold != null) {
                    hold.reached.countDown();
                    try {
                        hold.released.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            switch (behaviour) {
                case LEGACY ->
                    respond(
                            exchange,
                            200,
                            "application/json",
                            "{\"arguments\":{},\"result\":\"method name not recognized\"}");
                case RPC_ERROR ->
                    respond(
                            exchange,
                            200,
                            "application/json",
                            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"},\"id\":1}");
                case GARBAGE -> respond(exchange, 200, "text/html", "<html>not transmission</html>");
                default -> {
                    if (body.contains("\"method\":\"torrent_remove\"") && !remove(body)) {
                        respond(
                                exchange,
                                200,
                                "application/json",
                                "{\"jsonrpc\":\"2.0\",\"error\":"
                                        + "{\"code\":-32000,\"message\":\"torrent is busy\"},\"id\":1}");
                    } else {
                        respond(
                                exchange,
                                200,
                                "application/json",
                                "{\"jsonrpc\":\"2.0\",\"result\":" + result(body) + ",\"id\":1}");
                    }
                }
            }
        }
    }

    /** Drops the torrents a {@code torrent_remove} names by hash; false when one of them is refused. */
    private synchronized boolean remove(String request) throws IOException {
        ObjectMapper json = new ObjectMapper();
        Set<String> hashes = new HashSet<>();
        json.readTree(request).path("params").path("ids").forEach(id -> hashes.add(id.asText()));
        if (hashes.stream().anyMatch(refusedRemovals::contains)) {
            return false;
        }
        ArrayNode left = json.createArrayNode();
        for (JsonNode torrent : json.readTree(torrents)) {
            if (!hashes.contains(torrent.path("hash_string").asText())) {
                left.add(torrent);
            }
        }
        torrents = json.writeValueAsString(left);
        return true;
    }

    private String result(String request) {
        if (request.contains("\"method\":\"torrent_remove\"")) {
            return "{}";
        }
        if (request.contains("\"method\":\"torrent_get\"")) {
            return "{\"torrents\":" + torrents + "}";
        }
        return "{\"version\":\"" + version + "\",\"incomplete_dir\":\"" + incompleteDir
                + "\",\"incomplete_dir_enabled\":" + incompleteDirEnabled + "}";
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
