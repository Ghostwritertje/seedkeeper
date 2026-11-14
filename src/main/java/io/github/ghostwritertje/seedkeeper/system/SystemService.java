package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.system.SystemStatus.TransmissionStatus;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionException;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The state of seedkeeper and of what it depends on; behind the System view, the API and the readiness check. */
@ApplicationScoped
public class SystemService {

    private final Transmission transmission;
    private final String version;

    SystemService(Transmission transmission, @ConfigProperty(name = "quarkus.application.version") String version) {
        this.transmission = transmission;
        this.version = version;
    }

    public SystemStatus status() {
        return new SystemStatus(version, transmission());
    }

    /** Asks Transmission now; never throws. */
    public TransmissionStatus transmission() {
        try {
            return new TransmissionStatus(true, transmission.session().version(), null);
        } catch (TransmissionException e) {
            return new TransmissionStatus(e.reachable(), e.version(), e.reason());
        }
    }
}
