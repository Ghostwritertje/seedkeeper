package io.github.ghostwritertje.seedkeeper.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The key the JSON API needs. */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class ApiKeyTest {

    @Test
    void apiWithoutKeyIs401AndDoesNotRedirectToTheLoginForm() {
        given().redirects().follow(false)
                .when().get("/api/v1/system")
                .then()
                .statusCode(401)
                .header("Location", nullValue());
    }

    @Test
    void apiEndpointThatDoesNotExistIs401WithoutKey() {
        given().redirects().follow(false)
                .when().get("/api/v1/added-later")
                .then()
                .statusCode(401);
    }

    @Test
    void apiWithWrongKeyIs401() {
        given().header("X-Api-Key", "not-the-key")
                .when().get("/api/v1/system")
                .then()
                .statusCode(401);
        given().queryParam("apikey", "not-the-key")
                .when().get("/api/v1/system")
                .then()
                .statusCode(401);
        given().header("X-Api-Key", TestSecrets.API_KEY + "x")
                .when().get("/api/v1/system")
                .then()
                .statusCode(401);
        given().header("X-Api-Key", "")
                .when().get("/api/v1/system")
                .then()
                .statusCode(401);
    }

    @Test
    void apiWorksWithTheKeyInTheHeader() {
        given().header("X-Api-Key", TestSecrets.API_KEY)
                .when().get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("transmission.reachable", is(true));
    }

    @Test
    void apiWorksWithTheKeyInTheQuery() {
        given().queryParam("apikey", TestSecrets.API_KEY)
                .when().get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("transmission.reachable", is(true));
    }

    @Test
    void wrongKeyIs401EvenWhenLoggedIn() {
        Authenticated.asUser().header("X-Api-Key", "not-the-key")
                .when().get("/api/v1/system")
                .then()
                .statusCode(401);
    }

    @Test
    void keyDoesNotOpenThePages() {
        given().redirects().follow(false)
                .header("X-Api-Key", TestSecrets.API_KEY)
                .queryParam("apikey", TestSecrets.API_KEY)
                .when().get("/system")
                .then()
                .statusCode(302);
    }
}
