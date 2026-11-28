package io.github.ghostwritertje.seedkeeper.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/** Logging out: the one form of the layout, and so the place where the CSRF protection of forms shows. */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class LogoutTest {

    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_FIELD = "html.body.aside.form.input.find { it.@name == 'csrf-token' }.@value";

    @Test
    void logoutFormWithItsCsrfTokenLogsOut() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");
        page.then()
                .statusCode(200)
                .body("html.body.aside.form.@action", is("/logout"))
                .body("html.body.aside.form.@method", is("post"));
        String token = page.htmlPath().getString(CSRF_FIELD);
        assertFalse(token == null || token.isBlank(), "the logout form has no CSRF token");

        Response loggedOut = given().redirects()
                .follow(false)
                .cookie(session)
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .formParam("csrf-token", token)
                .when()
                .post("/logout");

        loggedOut.then().statusCode(303).header("Location", endsWith("/login?logged-out"));
        // The browser is told to drop the session cookie.
        loggedOut.then().cookie(Authenticated.SESSION_COOKIE, is(""));
    }

    @Test
    void formPostWithoutCsrfTokenIsRejected() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");

        Response rejected = given().redirects()
                .follow(false)
                .cookie(session)
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/logout");

        rejected.then().statusCode(400);
        assertFalse(rejected.cookies().containsKey(Authenticated.SESSION_COOKIE), "the session cookie was dropped");
    }

    @Test
    void formPostWithWrongCsrfTokenIsRejected() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");

        given().redirects()
                .follow(false)
                .cookie(session)
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .formParam("csrf-token", "bm90LXRoZS10b2tlbg")
                .when()
                .post("/logout")
                .then()
                .statusCode(400);
    }

    @Test
    void formPostWithoutCsrfCookieIsRejected() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");

        given().redirects()
                .follow(false)
                .cookie(session)
                .formParam("csrf-token", page.htmlPath().getString(CSRF_FIELD))
                .when()
                .post("/logout")
                .then()
                .statusCode(400);
    }
}
