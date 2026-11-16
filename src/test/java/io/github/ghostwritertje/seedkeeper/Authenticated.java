package io.github.ghostwritertje.seedkeeper;

import static io.restassured.RestAssured.given;

import io.restassured.http.Cookie;
import io.restassured.specification.RequestSpecification;

/**
 * Requests that are let in, for tests of pages and API endpoints. Use these instead of REST Assured's
 * {@code given()}: {@code Authenticated.asUser().when().get("/system")}. They go through the real login and API
 * key check, so they also work against the packaged application ({@code @QuarkusIntegrationTest}). The application
 * must run with {@link TestSecrets}.
 */
public final class Authenticated {

    public static final String SESSION_COOKIE = "quarkus-credential";

    private Authenticated() {}

    /** A request with the session cookie of a fresh login, for pages. */
    public static RequestSpecification asUser() {
        return given().cookie(login(TestSecrets.USERNAME, TestSecrets.PASSWORD));
    }

    /** A request with the API key in the {@code X-Api-Key} header, for {@code /api/**}. */
    public static RequestSpecification withApiKey() {
        return given().header("X-Api-Key", TestSecrets.API_KEY);
    }

    /** Posts the login form and returns the session cookie; fails when the login is refused. */
    public static Cookie login(String username, String password) {
        Cookie session = given().redirects()
                .follow(false)
                .formParam("j_username", username)
                .formParam("j_password", password)
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(302)
                .extract()
                .detailedCookie(SESSION_COOKIE);
        if (session == null) {
            throw new AssertionError("The login of " + username + " was refused");
        }
        return new Cookie.Builder(SESSION_COOKIE, session.getValue()).build();
    }
}
