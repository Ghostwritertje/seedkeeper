package io.github.ghostwritertje.seedkeeper.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/** The Log out button of every page: drops the session cookie and shows the login form. */
@Path("/logout")
public class Logout {

    private final SecurityIdentity identity;

    Logout(SecurityIdentity identity) {
        this.identity = identity;
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response logout() {
        FormAuthenticationMechanism.logout(identity);
        return Response.seeOther(URI.create("/login")).build();
    }
}
