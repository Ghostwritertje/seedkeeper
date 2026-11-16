package io.github.ghostwritertje.seedkeeper.auth;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

/** The login form. Quarkus's form login handles its POST ({@code /j_security_check}). */
@Path("/login")
class LoginPage {

    @CheckedTemplate
    static class Templates {

        static native TemplateInstance login(boolean failed);
    }

    /** After a failed login Quarkus redirects to {@code /login?error}. */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance login(@Context UriInfo uri) {
        return Templates.login(uri.getQueryParameters().containsKey("error"));
    }
}
