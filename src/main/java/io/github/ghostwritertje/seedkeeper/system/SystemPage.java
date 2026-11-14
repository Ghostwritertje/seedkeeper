package io.github.ghostwritertje.seedkeeper.system;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The System view. */
@Path("/system")
public class SystemPage {

    @CheckedTemplate
    static class Templates {

        static native TemplateInstance system(SystemStatus status);
    }

    private final SystemService system;

    SystemPage(SystemService system) {
        this.system = system;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance system() {
        return Templates.system(system.status());
    }
}
