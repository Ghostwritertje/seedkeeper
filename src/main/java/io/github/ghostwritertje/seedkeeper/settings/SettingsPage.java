package io.github.ghostwritertje.seedkeeper.settings;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules.ManagedTracker;
import io.github.ghostwritertje.seedkeeper.groups.StatusText;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The Settings view: the configuration seedkeeper runs with, read-only. */
@Path("/settings")
public class SettingsPage {

    @CheckedTemplate
    static class Templates {

        static native TemplateInstance settings(Settings settings);
    }

    /** The configuration, as text ready to show. It holds no secret value. */
    public record Settings(
            String schedule,
            String timeZone,
            String transmissionUrl,
            String downloadsRoot,
            List<String> libraryRoots,
            String dataDir,
            String defaultMinimumSeedTime,
            List<Tracker> trackers,
            boolean hasPublicTracker,
            String username,
            List<Secret> secrets) {}

    /**
     * One tracker rule, as text ready to show.
     *
     * @param hosts           the announce hosts, comma-separated; for the public tracker, which torrents it gets
     * @param minimumSeedTime the minimum that applies, marked "(default)" when the tracker sets none of its own
     */
    public record Tracker(String name, String hosts, String minimumSeedTime) {}

    /**
     * One secret, and whether it is set.
     *
     * @param variable the environment variable the secret comes from
     * @param state    "set" or "missing"; the value itself is never kept here
     */
    public record Secret(String variable, String state) {

        static Secret of(String variable, Optional<String> value) {
            return new Secret(variable, value.filter(v -> !v.isBlank()).isPresent() ? "set" : "missing");
        }
    }

    private final SeedkeeperConfig config;
    private final Optional<String> sessionEncryptionKey;

    SettingsPage(
            SeedkeeperConfig config,
            @ConfigProperty(name = "quarkus.http.auth.session.encryption-key") Optional<String> sessionEncryptionKey) {
        this.config = config;
        this.sessionEncryptionKey = sessionEncryptionKey;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settings() {
        return Templates.settings(new Settings(
                config.schedule(),
                config.timeZone().getId(),
                io.github.ghostwritertje.seedkeeper.transmission.TransmissionClient.withoutUserInfo(
                                config.transmission().url())
                        .toString(),
                config.downloadsRoot().toString(),
                config.libraryRoots().stream().map(Object::toString).toList(),
                config.dataDir().toString(),
                StatusText.duration(config.defaultMinimumSeedTime()),
                trackers(),
                config.publicTracker().isPresent(),
                config.auth().username(),
                secrets()));
    }

    private List<Secret> secrets() {
        return List.of(
                Secret.of(
                        "SEEDKEEPER_TRANSMISSION_USERNAME",
                        Optional.ofNullable(config.transmission().username())),
                Secret.of(
                        "SEEDKEEPER_TRANSMISSION_PASSWORD",
                        Optional.ofNullable(config.transmission().password())),
                Secret.of(
                        "SEEDKEEPER_AUTH_PASSWORD",
                        Optional.ofNullable(config.auth().password())),
                Secret.of("SEEDKEEPER_API_KEY", Optional.ofNullable(config.apiKey())),
                Secret.of("QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY", sessionEncryptionKey));
    }

    /** The managed trackers by name, then the public tracker when the config has one. */
    private List<Tracker> trackers() {
        TrackerRules rules = TrackerRules.of(config);
        String defaultMinimum = StatusText.duration(rules.defaultMinimumSeedTime()) + " (default)";
        List<Tracker> trackers = new ArrayList<>();
        rules.managed().stream()
                .sorted(Comparator.comparing(ManagedTracker::name))
                .map(tracker -> new Tracker(
                        tracker.name(),
                        String.join(", ", tracker.hosts()),
                        tracker.minimumSeedTime().map(StatusText::duration).orElse(defaultMinimum)))
                .forEach(trackers::add);
        rules.publicMinimumSeedTime()
                .map(minimum -> new Tracker(
                        TrackerRules.PUBLIC, "Every torrent that is not private", StatusText.duration(minimum)))
                .ifPresent(trackers::add);
        return trackers;
    }
}
