package io.github.ghostwritertje.seedkeeper.system;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * The bare URL. A login that started there lands back on it (the landing page applies only to a login without a page
 * to return to), so it sends the browser on to the System view.
 */
@Path("/")
class Home {

    @GET
    public Response home() {
        return Response.seeOther(URI.create("/system")).build();
    }
}
