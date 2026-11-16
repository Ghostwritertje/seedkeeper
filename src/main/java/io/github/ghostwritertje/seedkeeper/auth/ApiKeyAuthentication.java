package io.github.ghostwritertje.seedkeeper.auth;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * Lets a request under {@code /api/} in when it carries the API key ({@code SEEDKEEPER_API_KEY}), in the
 * {@code X-Api-Key} header or the {@code apikey} query parameter. Everywhere else the key means nothing.
 *
 * <p>Asked before the form login, so that an API request without a valid key gets a 401 and not a redirect to the
 * login form. A request without any key is left to the form login: someone who is logged in can open the API in the
 * browser.
 */
@ApplicationScoped
public class ApiKeyAuthentication implements HttpAuthenticationMechanism {

    static final String HEADER = "X-Api-Key";
    static final String QUERY_PARAMETER = "apikey";

    private final SeedkeeperConfig config;

    ApiKeyAuthentication(SeedkeeperConfig config) {
        this.config = config;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext request, IdentityProviderManager identityProviders) {
        if (!isApi(request)) {
            return Uni.createFrom().nullItem();
        }
        String key = request.request().getHeader(HEADER);
        if (key == null) {
            key = request.request().getParam(QUERY_PARAMETER);
        }
        if (key == null) {
            return Uni.createFrom().nullItem();
        }
        if (!Secrets.same(key, config.apiKey())) {
            return Uni.createFrom().failure(new AuthenticationFailedException());
        }
        return Uni.createFrom()
                .item(QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal("api-key"))
                        .build());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext request) {
        if (!isApi(request)) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(new ChallengeData(401, null, null));
    }

    /** None: the key is checked here, no identity provider is involved. */
    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    /** Above the form login (1000). */
    @Override
    public int getPriority() {
        return 2000;
    }

    private static boolean isApi(RoutingContext request) {
        return request.normalizedPath().startsWith("/api/");
    }
}
