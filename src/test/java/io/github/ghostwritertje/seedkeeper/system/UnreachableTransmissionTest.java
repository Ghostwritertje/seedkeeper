package io.github.ghostwritertje.seedkeeper.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.matchesPattern;

import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The application with nothing listening at the Transmission URL. */
@QuarkusTest
@TestProfile(UnreachableTransmissionTest.NothingListening.class)
class UnreachableTransmissionTest {

    private static final String DOES_NOT_ANSWER =
            "Transmission at http://127\\.0\\.0\\.1:\\d+/transmission/rpc does not answer \\(.+\\)";

    public static class NothingListening implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = TestSecrets.with("seedkeeper.transmission.url",
                    "http://127.0.0.1:" + unusedPort() + "/transmission/rpc");
            config.put("seedkeeper.library-roots", "/library");
            return config;
        }
    }

    @Test
    void apiGivesTheReason() {
        given()
                .when().get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("transmission.reachable", is(false))
                .body("transmission.version", nullValue())
                .body("transmission.reason", matchesPattern(DOES_NOT_ANSWER));
    }

    @Test
    void pageGivesTheReason() {
        given()
                .when().get("/system")
                .then()
                .statusCode(200)
                .body("html.body.main.section.find { it.@id == 'transmission' }.dl.dd[0]", is("Unreachable"))
                .body("html.body.main.section.find { it.@id == 'transmission' }.dl.dd[1]", is("unknown"))
                .body("html.body.main.section.find { it.@id == 'transmission' }.p",
                        matchesPattern(DOES_NOT_ANSWER));
    }

    @Test
    void readinessIsDownAndLivenessStaysUp() {
        given().when().get("/q/health/ready").then().statusCode(503)
                .body("status", is("DOWN"))
                .body("checks.find { it.name == 'Transmission' }.status", is("DOWN"))
                .body("checks.find { it.name == 'Transmission' }.data.reason",
                        matchesPattern(DOES_NOT_ANSWER));
        given().when().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
    }

    /** A port that was free a moment ago and that nothing listens on. */
    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
