package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.system.SystemStatus.TransmissionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Ready means Transmission answers. Readiness only: seedkeeper itself is alive without Transmission, and a restart
 * wouldn't bring Transmission back.
 */
@Readiness
@ApplicationScoped
public class TransmissionReadiness implements HealthCheck {

    private final SystemService system;

    TransmissionReadiness(SystemService system) {
        this.system = system;
    }

    @Override
    public HealthCheckResponse call() {
        TransmissionStatus transmission = system.transmission();
        HealthCheckResponseBuilder response =
                HealthCheckResponse.named("Transmission").status(transmission.reachable());
        if (transmission.version() != null) {
            response.withData("version", transmission.version());
        }
        if (transmission.reason() != null) {
            response.withData("reason", transmission.reason());
        }
        return response.build();
    }
}
