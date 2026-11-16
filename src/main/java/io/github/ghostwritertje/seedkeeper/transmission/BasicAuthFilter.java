package io.github.ghostwritertje.seedkeeper.transmission;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Sends Transmission's RPC username and password with every request. */
class BasicAuthFilter implements ClientRequestFilter {

    private final String authorization;

    BasicAuthFilter(String username, String password) {
        byte[] credentials = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(credentials);
    }

    @Override
    public void filter(ClientRequestContext request) {
        request.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, authorization);
    }
}
