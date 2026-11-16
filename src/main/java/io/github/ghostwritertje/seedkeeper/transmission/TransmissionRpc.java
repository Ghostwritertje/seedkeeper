package io.github.ghostwritertje.seedkeeper.transmission;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** Transmission's RPC endpoint as a Quarkus REST Client: one POST that carries a JSON-RPC 2.0 call. */
@Path("")
interface TransmissionRpc {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Response call(Request request);

    @RegisterForReflection
    record Request(String jsonrpc, String method, Map<String, Object> params, int id) {

        static Request of(String method, Map<String, Object> params) {
            return new Request("2.0", method, params, 1);
        }
    }

    /**
     * {@code result} is an object from Transmission 4.1 on. Older versions answer in their own format, where it is a
     * string such as "method name not recognized".
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(JsonNode result, Error error) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(int code, String message) {}
}
