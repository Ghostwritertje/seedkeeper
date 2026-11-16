package io.github.ghostwritertje.seedkeeper.system;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** {@code GET /api/v1/system}: the same data as the System view. */
@Path("/api/v1/system")
public class SystemApi {

    private final SystemService system;

    SystemApi(SystemService system) {
        this.system = system;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public SystemStatus system() {
        return system.status();
    }
}
