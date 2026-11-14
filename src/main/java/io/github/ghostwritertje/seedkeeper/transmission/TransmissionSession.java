package io.github.ghostwritertje.seedkeeper.transmission;

/** Transmission's answer to {@code session_get}. {@code version} is as Transmission reports it: "4.1.0 (5e6a9e0a3d)". */
public record TransmissionSession(String version) {
}
