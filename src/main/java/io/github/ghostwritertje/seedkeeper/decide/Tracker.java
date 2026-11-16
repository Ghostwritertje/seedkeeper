package io.github.ghostwritertje.seedkeeper.decide;

import java.time.Duration;

/** A managed tracker (or the {@code public} one) and the minimum seed time its torrents must reach. */
public record Tracker(String name, Duration minimumSeedTime) {}
