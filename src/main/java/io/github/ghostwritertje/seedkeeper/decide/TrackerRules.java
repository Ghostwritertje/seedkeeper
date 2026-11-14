package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The trackers of the config and what they require; maps a torrent to its tracker (spec §3.2).
 *
 * @param publicMinimumSeedTime the minimum of the {@code public} entry; empty when the config has none
 */
public record TrackerRules(List<ManagedTracker> managed, Duration defaultMinimumSeedTime,
        Optional<Duration> publicMinimumSeedTime) {

    /** The name the public tracker goes by. */
    public static final String PUBLIC = "public";

    /** A tracker of the config: its announce hosts and, when it has one of its own, its minimum seed time. */
    public record ManagedTracker(String name, List<String> hosts, Optional<Duration> minimumSeedTime) {
    }

    public static TrackerRules of(SeedkeeperConfig config) {
        List<ManagedTracker> managed = config.trackers().entrySet().stream()
                .map(entry -> new ManagedTracker(entry.getKey(), entry.getValue().hosts(),
                        entry.getValue().minimumSeedTime()))
                .toList();
        return new TrackerRules(managed, config.defaultMinimumSeedTime(),
                config.publicTracker().map(SeedkeeperConfig.PublicTracker::minimumSeedTime));
    }

    /**
     * The tracker a torrent belongs to; empty when it is unmanaged.
     *
     * @param announceUrls the torrent's announce URLs
     */
    public Optional<Tracker> attribute(List<String> announceUrls, boolean isPrivate) {
        Set<ManagedTracker> matched = new HashSet<>();
        boolean allHostsManaged = !announceUrls.isEmpty();
        for (String url : announceUrls) {
            String host = host(url);
            List<ManagedTracker> owners = managed.stream()
                    .filter(tracker -> tracker.hosts().stream().anyMatch(h -> h.trim().equalsIgnoreCase(host)))
                    .toList();
            matched.addAll(owners);
            allHostsManaged &= !owners.isEmpty();
        }
        if (allHostsManaged && matched.size() == 1) {
            ManagedTracker tracker = matched.iterator().next();
            return Optional.of(new Tracker(tracker.name(),
                    tracker.minimumSeedTime().orElse(defaultMinimumSeedTime)));
        }
        if (!isPrivate && publicMinimumSeedTime.isPresent()) {
            return Optional.of(new Tracker(PUBLIC, publicMinimumSeedTime.get()));
        }
        return Optional.empty();
    }

    /** The host of an announce URL, lower-cased; empty text when the URL can't be read. */
    private static String host(String announceUrl) {
        try {
            String host = URI.create(announceUrl.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
