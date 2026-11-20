package io.github.ghostwritertje.seedkeeper.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The bare URL: a login that started there lands on it again, so it shows the System view. */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class HomeTest {

    @Test
    void bareUrlWithLoginRedirectsToTheSystemView() {
        Authenticated.asUser()
                .redirects()
                .follow(false)
                .when()
                .get("/")
                .then()
                .statusCode(303)
                .header("Location", endsWith("/system"));
    }

    @Test
    void bareUrlWithoutLoginRedirectsToTheLoginForm() {
        given().redirects().follow(false).when().get("/").then().statusCode(302).header("Location", endsWith("/login"));
    }
}
