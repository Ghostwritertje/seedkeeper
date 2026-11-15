package io.github.ghostwritertje.seedkeeper;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class HealthTest {

    @Test
    void healthIsUp() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
