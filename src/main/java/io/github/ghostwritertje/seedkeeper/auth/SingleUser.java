package io.github.ghostwritertje.seedkeeper.auth;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * The one user that can log in: the username from the config, the password from {@code SEEDKEEPER_AUTH_PASSWORD}.
 * Quarkus's form login asks {@link Login} when the form is posted and {@link Session} on every request that carries
 * the session cookie.
 */
@ApplicationScoped
public class SingleUser {

    private final SeedkeeperConfig config;

    SingleUser(SeedkeeperConfig config) {
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    Login login() {
        return new Login(this);
    }

    @Produces
    @ApplicationScoped
    Session session() {
        return new Session(this);
    }

    private Uni<SecurityIdentity> identityWhen(boolean authenticated) {
        if (!authenticated) {
            return Uni.createFrom().failure(new AuthenticationFailedException());
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(config.auth().username()))
                .build());
    }

    /** Checks the username and password of a posted login form. */
    public static class Login implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

        private final SingleUser user;

        Login(SingleUser user) {
            this.user = user;
        }

        @Override
        public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
            return UsernamePasswordAuthenticationRequest.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                AuthenticationRequestContext context) {
            // Both are always compared, so a wrong username takes as long as a wrong password.
            boolean username = Secrets.same(request.getUsername(), user.config.auth().username());
            boolean password = Secrets.same(new String(request.getPassword().getPassword()),
                    user.config.auth().password());
            return user.identityWhen(username & password);
        }
    }

    /** Accepts a session cookie Quarkus has decrypted, as long as it is for the configured user. */
    public static class Session implements IdentityProvider<TrustedAuthenticationRequest> {

        private final SingleUser user;

        Session(SingleUser user) {
            this.user = user;
        }

        @Override
        public Class<TrustedAuthenticationRequest> getRequestType() {
            return TrustedAuthenticationRequest.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request,
                AuthenticationRequestContext context) {
            return user.identityWhen(Secrets.same(request.getPrincipal(), user.config.auth().username()));
        }
    }
}
