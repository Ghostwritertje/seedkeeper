package io.github.ghostwritertje.seedkeeper.system;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** What the System view and {@code GET /api/v1/system} show. */
@RegisterForReflection
public record SystemStatus(String version, TransmissionStatus transmission) {

    /**
     * @param reachable Transmission answered
     * @param version   as Transmission reports it; null when it didn't say
     * @param reason    why seedkeeper can't work with this Transmission; null when it can
     */
    @RegisterForReflection
    public record TransmissionStatus(boolean reachable, String version, String reason) {

        public boolean usable() {
            return reason == null;
        }
    }
}
