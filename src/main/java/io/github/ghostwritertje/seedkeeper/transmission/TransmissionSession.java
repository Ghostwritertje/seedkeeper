package io.github.ghostwritertje.seedkeeper.transmission;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Transmission's answer to {@code session_get}.
 *
 * @param version       as Transmission reports it: "4.1.0 (5e6a9e0a3d)"
 * @param incompleteDir where Transmission keeps unfinished downloads; empty when that is not enabled
 */
public record TransmissionSession(String version, Optional<Path> incompleteDir) {
}
