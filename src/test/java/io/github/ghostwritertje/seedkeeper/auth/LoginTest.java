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

    /**
     * The icon needs no login: a browser asks for it before the login, and the login would otherwise send it to the
     * icon instead of a page.
     */
    @Test
    void theIconNeedsNoLogin() {
        given().redirects()
                .follow(false)
                .when()
                .get("/favicon.svg")
                .then()
                .statusCode(200)
                .contentType(containsString("image/svg+xml"));
        given().redirects().follow(false).when().get("/favicon.ico").then().statusCode(404);
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

    /** A login lasts a week and survives closing the browser: the cookie is persistent, not a session cookie. */
    @Test
    void theSessionCookieLastsSevenDays() {
        io.restassured.http.Cookie session = post(TestSecrets.USERNAME, TestSecrets.PASSWORD)
                .statusCode(302)
                .extract()
                .detailedCookie(Authenticated.SESSION_COOKIE);
        org.junit.jupiter.api.Assertions.assertEquals(604_800, session.getMaxAge());
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

    /** Log out lands on {@code /login?logged-out}: the form says so, as a status rather than an error. */
    @Test
    void loginFormAfterLogOutSaysYouAreLoggedOut() {
        given().when()
                .get("/login?logged-out")
                .then()
                .statusCode(200)
                .body("html.body.main.p.find { it.@id == 'logged-out' }", is("You are logged out."))
                .body("html.body.main.p.find { it.@id == 'logged-out' }.@role", is("status"))
                .body("html.body.**.findAll { it.@role == 'alert' }.size()", is(0));
    }

    @Test
    void loginFormWithoutLogOutSaysNothingAboutLoggingOut() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body("html.body.**.findAll { it.@id == 'logged-out' }.size()", is(0));
        given().when()
                .get("/login?error")
                .then()
                .statusCode(200)
                .body("html.body.**.findAll { it.@id == 'logged-out' }.size()", is(0))
                .body("html.body.main.p", is("Wrong username or password."));
    }

    /** A failed login after a log out shows only the error. */
    @Test
    void failedLoginAfterLogOutShowsOnlyTheError() {
        given().when()
                .get("/login?logged-out&error")
                .then()
                .statusCode(200)
                .body("html.body.**.findAll { it.@id == 'logged-out' }.size()", is(0))
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
