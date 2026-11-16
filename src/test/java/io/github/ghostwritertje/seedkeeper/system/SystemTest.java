package io.github.ghostwritertje.seedkeeper.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission.Behaviour;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The System view, {@code GET /api/v1/system} and the health checks, with Transmission answering. */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class SystemTest {

    private static final String RUNS = "html.body.main.section.find { it.@id == 'runs' }";

    @BeforeEach
    void healthyTransmission() {
        FakeTransmissionResource.fake().reset();
    }

    @Test
    void apiShowsSeedkeepersVersionAndTransmissionsVersion() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("version", matchesPattern("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"))
                .body("transmission.reachable", is(true))
                .body("transmission.version", is("4.1.0 (5e6a9e0a3d)"))
                .body("transmission.reason", nullValue());
    }

    /** The schedule of {@link FakeTransmissionResource}: 06:00 on 1 January, in a time zone at UTC+05:30. */
    @Test
    void anIncompleteDirInsideTheDownloadsRootOrNotEnabledHasNoWarning() {
        FakeTransmissionResource.fake().incompleteDir("/elsewhere/incomplete", false);
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .body("transmission.incompleteDirWarning", nullValue());

        FakeTransmissionResource.fake()
                .incompleteDir(
                        FakeTransmissionResource.downloads()
                                .resolve("incomplete")
                                .toString(),
                        true);
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .body("transmission.incompleteDirWarning", nullValue());
    }

    @Test
    void anIncompleteDirOutsideTheDownloadsRootIsWarnedAbout() {
        FakeTransmissionResource.fake().incompleteDir("/elsewhere/incomplete", true);

        String warning = "Transmission's incomplete dir /elsewhere/incomplete is outside the downloads root "
                + FakeTransmissionResource.downloads() + ": seedkeeper deletes the part files of removed torrents"
                + " there too, so make sure nothing else lives in it";
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("transmission.incompleteDirWarning", is(warning));
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .body(
                        "html.body.main.section.find { it.@id == 'transmission' }.p.find { it.@id == 'incomplete-dir' }",
                        is(warning));
    }

    @Test
    void anIncompleteDirThatIsTheFilesystemRootIsWarnedAbout() {
        FakeTransmissionResource.fake().incompleteDir("/", true);

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .body(
                        "transmission.incompleteDirWarning",
                        is(
                                "Transmission's incomplete dir is /, the filesystem"
                                        + " root: seedkeeper never deletes there, so the part files of removed torrents stay behind"));
    }

    @Test
    void apiShowsTheNextRunOfTheScheduleInTheConfiguredTimeZone() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("nextRun", matchesPattern("\\d{4}-01-01T06:00:00\\+05:30"));
    }

    @Test
    void pageShowsTheNextRun() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .body(RUNS + ".dl.dt[-1]", is("Next run"))
                .body(RUNS + ".dl.dd[-1]", matchesPattern("\\d{4}-01-01 06:00:00"));
    }

    @Test
    void apiGivesTheReasonWhenTransmissionIsTooOld() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("transmission.reachable", is(true))
                .body("transmission.version", is("4.0.6 (38c164933e)"))
                .body(
                        "transmission.reason",
                        is("Transmission 4.0.6 (38c164933e) is older than 4.1,"
                                + " the oldest version seedkeeper works with"));
    }

    @Test
    void pageShowsSeedkeepersVersionAndTransmissionsVersion() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("System · seedkeeper"))
                .body(
                        "html.body.main.section.find { it.@id == 'seedkeeper' }.dl.dd[0]",
                        matchesPattern("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"))
                .body("html.body.main.section.find { it.@id == 'transmission' }.dl.dd[0]", is("Reachable"))
                .body("html.body.main.section.find { it.@id == 'transmission' }.dl.dd[1]", is("4.1.0 (5e6a9e0a3d)"));
    }

    @Test
    void pageGivesTheReasonWhenTransmissionIsTooOld() {
        FakeTransmissionResource.fake().behaviour(Behaviour.LEGACY);

        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .body("html.body.main.section.find { it.@id == 'transmission' }.dl.dd[0]", is("Reachable, not usable"))
                .body(
                        "html.body.main.section.find { it.@id == 'transmission' }.p",
                        containsString("is older than 4.1, the oldest version seedkeeper works with"
                                + " (it does not speak JSON-RPC 2.0)"));
    }

    @Test
    void pageUsesTheStylesheetAndHtmxShippedInTheApp() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .body("html.head.link.@href", is("/seedkeeper.css"))
                .body("html.head.script.@src", is("/htmx.min.js"));

        given().when().get("/seedkeeper.css").then().statusCode(200).contentType(containsString("text/css"));
        given().when().get("/htmx.min.js").then().statusCode(200).body(containsString("htmx"));
    }

    @Test
    void readinessAndLivenessAreUp() {
        given().when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", is("UP"))
                .body("checks.find { it.name == 'Transmission' }.status", is("UP"));
        given().when().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
    }
}
