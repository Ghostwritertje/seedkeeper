package io.github.ghostwritertje.seedkeeper.transmission;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link Transmission} over JSON-RPC 2.0, which Transmission speaks from 4.1 on. */
@ApplicationScoped
public class TransmissionClient implements Transmission {

    private static final Pattern MAJOR_MINOR = Pattern.compile("(\\d+)\\.(\\d+)");
    private static final String TOO_OLD = "older than 4.1, the oldest version seedkeeper works with";
    private static final List<String> TORRENT_FIELDS = List.of("hash_string", "name", "status", "percent_done",
            "error", "error_string", "seconds_seeding", "is_private", "download_dir", "trackers", "files",
            "file_stats");

    private final URI url;
    private final TransmissionRpc rpc;

    @Inject
    TransmissionClient(SeedkeeperConfig config) {
        this(config.transmission().url(), config.transmission().username(), config.transmission().password());
    }

    public TransmissionClient(URI url, String username, String password) {
        this.url = url;
        this.rpc = QuarkusRestClientBuilder.newBuilder()
                .baseUri(url)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .register(new BasicAuthFilter(username, password))
                .register(new SessionIdFilter())
                .build(TransmissionRpc.class);
    }

    @Override
    public TransmissionSession session() {
        JsonNode result = call("session_get",
                Map.of("fields", List.of("version", "incomplete_dir", "incomplete_dir_enabled")));
        String version = result.path("version").asText("");
        if (!isSupported(version)) {
            throw TransmissionException.unsupported("Transmission " + version + " is " + TOO_OLD, version);
        }
        Optional<Path> incompleteDir = result.path("incomplete_dir_enabled").asBoolean(false)
                ? Optional.of(Path.of(result.path("incomplete_dir").asText()))
                : Optional.empty();
        return new TransmissionSession(version, incompleteDir);
    }

    @Override
    public List<Torrent> torrents() {
        JsonNode torrents = call("torrent_get", Map.of("fields", TORRENT_FIELDS)).path("torrents");
        if (!torrents.isArray()) {
            throw notJsonRpc(null);
        }
        List<Torrent> result = new ArrayList<>();
        for (JsonNode torrent : torrents) {
            result.add(torrent(torrent));
        }
        return result;
    }

    @Override
    public void remove(String hash) {
        call("torrent_remove", Map.of("ids", List.of(hash), "delete_local_data", false));
    }

    private Torrent torrent(JsonNode torrent) {
        List<String> trackers = new ArrayList<>();
        for (JsonNode tracker : torrent.path("trackers")) {
            trackers.add(tracker.path("announce").asText());
        }
        List<Torrent.File> files = new ArrayList<>();
        JsonNode fileStats = torrent.path("file_stats");
        int index = 0;
        for (JsonNode file : torrent.path("files")) {
            // A file Transmission says nothing about counts as wanted: the careful side, it can only keep a torrent.
            boolean wanted = fileStats.path(index++).path("wanted").asBoolean(true);
            files.add(new Torrent.File(file.path("name").asText(), file.path("length").asLong(), wanted));
        }
        Torrent.Status status;
        try {
            status = Torrent.Status.of(torrent.path("status").asInt(-1));
        } catch (IllegalArgumentException e) {
            throw notJsonRpc(e);
        }
        return new Torrent(torrent.path("hash_string").asText(), torrent.path("name").asText(), status,
                torrent.path("percent_done").asDouble(), torrent.path("error").asInt(),
                torrent.path("error_string").asText(), torrent.path("seconds_seeding").asLong(),
                torrent.path("is_private").asBoolean(), torrent.path("download_dir").asText(), trackers, files);
    }

    /** True for 4.1 and newer. The version looks like "4.1.0 (5e6a9e0a3d)"; one that can't be read is not supported. */
    private static boolean isSupported(String version) {
        Matcher matcher = MAJOR_MINOR.matcher(version);
        if (!matcher.lookingAt()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major > 4 || (major == 4 && minor >= 1);
    }

    /** One JSON-RPC call; returns its {@code result} object. */
    private JsonNode call(String method, Map<String, Object> params) {
        TransmissionRpc.Response response = post(TransmissionRpc.Request.of(method, params));
        if (response == null) {
            throw notJsonRpc(null);
        }
        if (response.error() != null) {
            throw TransmissionException.unreachable(
                    here() + " refused " + method + ": " + response.error().message(), null);
        }
        JsonNode result = response.result();
        if (result != null && result.isTextual()) {
            // The pre-4.1 format: {"result": "method name not recognized", "arguments": {}}
            throw TransmissionException.unsupported(here() + " is " + TOO_OLD + " (it does not speak JSON-RPC 2.0)",
                    null);
        }
        if (result == null || !result.isObject()) {
            throw notJsonRpc(null);
        }
        return result;
    }

    private TransmissionRpc.Response post(TransmissionRpc.Request request) {
        try {
            try {
                return rpc.call(request);
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() != 409) {
                    throw e;
                }
                // SessionIdFilter has picked up the session id from the 409; one retry with it.
                return rpc.call(request);
            }
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            throw TransmissionException.unreachable(here() + switch (status) {
                case 401 -> " rejected the username or password (HTTP 401)";
                case 409 -> " keeps refusing its own session id (HTTP 409 twice in a row)";
                default -> " answered HTTP " + status;
            }, e);
        } catch (RuntimeException e) {
            Throwable root = rootCause(e);
            if (root instanceof IOException || root instanceof TimeoutException) {
                String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                throw TransmissionException.unreachable(here() + " does not answer (" + detail + ")", e);
            }
            throw notJsonRpc(e);
        }
    }

    private TransmissionException notJsonRpc(Throwable cause) {
        return TransmissionException.unreachable(here() + " gave an answer that is not JSON-RPC; is the URL right?",
                cause);
    }

    private String here() {
        return "Transmission at " + url;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
