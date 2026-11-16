package io.github.ghostwritertje.seedkeeper.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

/** The login every page needs. */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class LoginTest {

    @Test
    void pageWithoutLoginRedirectsToTheLoginForm() {
        given().redirects()
                .follow(false)
                .when()
                .get("/system")
                .then()
                .statusCode(302)
                .header("Location", endsWith("/login"));
    }

    @Test
    void pageThatDoesNotExistRedirectsToTheLoginFormToo() {
        given().redirects()
                .follow(false)
                .when()
                .get("/a-page-added-later")
                .then()
                .statusCode(302)
                .header("Location", endsWith("/login"));
    }

    @Test
    void loginFormIsShownWithoutLogin() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("Log in · seedkeeper"))
                .body("html.body.main.form.@action", is("/j_security_check"))
                .body("html.body.main.form.@method", is("post"))
                .body(
                        "html.body.main.form.label.input.collect { it.@name }",
                        is(java.util.List.of("j_username", "j_password")))
                .body(not(containsString("Wrong username or password")));
        given().when().get("/seedkeeper.css").then().statusCode(200);
    }

    @Test
    void rightUsernameAndPasswordLogIn() {
        post(TestSecrets.USERNAME, TestSecrets.PASSWORD)
                .statusCode(302)
                .header("Location", endsWith("/system"))
                .cookie(Authenticated.SESSION_COOKIE);

        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .body("html.head.title", is("System · seedkeeper"));
    }

    @Test
    void theSessionCookieIsHttpOnlyAndSameSiteStrict() {
        io.restassured.http.Cookie session = post(TestSecrets.USERNAME, TestSecrets.PASSWORD)
                .statusCode(302)
                .extract()
                .detailedCookie(Authenticated.SESSION_COOKIE);
        org.junit.jupiter.api.Assertions.assertTrue(session.isHttpOnly(), "the session cookie is not HttpOnly");
        org.junit.jupiter.api.Assertions.assertEquals("Strict", session.getSameSite());
    }

    @Test
    void wrongPasswordIsRejected() {
        ValidatableResponse rejected = post(TestSecrets.USERNAME, "not-the-password")
                .statusCode(302)
                .header("Location", endsWith("/login?error"));
        org.junit.jupiter.api.Assertions.assertNull(rejected.extract().cookie(Authenticated.SESSION_COOKIE));

        given().when()
                .get("/login?error")
                .then()
                .statusCode(200)
                .body("html.body.main.p", is("Wrong username or password."));
    }

    @Test
    void wrongUsernameIsRejected() {
        post("someone-else", TestSecrets.PASSWORD).statusCode(302).header("Location", endsWith("/login?error"));
    }

    @Test
    void madeUpSessionCookieIsNotALogin() {
        given().redirects()
                .follow(false)
                .cookie(Authenticated.SESSION_COOKIE, "bm90LWEtcmVhbC1zZXNzaW9u")
                .when()
                .get("/system")
                .then()
                .statusCode(302)
                .header("Location", endsWith("/login"));
    }

    private static ValidatableResponse post(String username, String password) {
        return given().redirects()
                .follow(false)
                .formParam("j_username", username)
                .formParam("j_password", password)
                .when()
                .post("/j_security_check")
                .then();
    }
}
