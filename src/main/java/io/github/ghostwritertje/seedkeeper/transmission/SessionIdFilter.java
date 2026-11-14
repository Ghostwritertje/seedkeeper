package io.github.ghostwritertje.seedkeeper.transmission;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transmission's CSRF handshake: it answers 409 with an {@code X-Transmission-Session-Id} header, and wants that id
 * on every later request. This filter remembers the last id Transmission gave and sends it along. The one retry of
 * the refused request is done by {@link TransmissionClient}.
 */
class SessionIdFilter implements ClientRequestFilter, ClientResponseFilter {

    static final String HEADER = "X-Transmission-Session-Id";

    private final AtomicReference<String> sessionId = new AtomicReference<>();

    @Override
    public void filter(ClientRequestContext request) {
        String current = sessionId.get();
        if (current != null) {
            request.getHeaders().putSingle(HEADER, current);
        }
    }

    @Override
    public void filter(ClientRequestContext request, ClientResponseContext response) {
        String given = response.getHeaderString(HEADER);
        if (given != null && !given.isBlank()) {
            sessionId.set(given);
        }
    }
}
