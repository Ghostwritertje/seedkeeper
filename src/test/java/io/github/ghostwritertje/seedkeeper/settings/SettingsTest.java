package io.github.ghostwritertje.seedkeeper.settings;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.ScrollRegions;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The Settings view, with the configuration of {@link FakeTransmissionResource}; the keys it doesn't set have their
 * defaults.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class SettingsTest {

    @Test
    void pageShowsTheScheduleTransmissionThePathsAndTheLogin() {
        Authenticated.asUser()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("Settings · seedkeeper"))
                .body(section("run") + ".dl.dt", contains("Schedule", "Time zone", "Next run"))
                // 06:00 on 1 January has no plain words: the cron expression alone.
                .body(section("run") + ".dl.dd[0]", is("0 0 6 1 1 ?"))
                .body(section("run") + ".dl.dd[1]", is("Asia/Kolkata"))
                .body(section("run") + ".dl.dd[2].time", matchesPattern("\\d{4}-01-01 06:00:00"))
                .body(section("run") + ".dl.dd[2].time.@datetime", matchesPattern("\\d{4}-01-01T06:00:00\\+05:30"))
                .body(section("run") + ".dl.dd[2].span", matchesPattern("in \\d+d( \\d+h)?"))
                .body(section("transmission") + ".dl.dt", is("URL"))
                .body(
                        section("transmission") + ".dl.dd",
                        is(FakeTransmissionResource.fake().url().toString()))
                .body(section("paths") + ".dl.dt", contains("Downloads root", "Library roots", "Data dir"))
                .body(
                        section("paths") + ".dl.dd",
                        contains(
                                FakeTransmissionResource.downloads().toString(),
                                FakeTransmissionResource.library().toString(),
                                FakeTransmissionResource.dataDir().toString()))
                .body(section("login") + ".dl.dt", is("Username"))
                .body(section("login") + ".dl.dd", is("admin"));
    }

    @Test
    void pageShowsEveryTrackerWithItsHostsAndTheMinimumThatApplies() {
        Authenticated.asUser()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                .body(section("trackers") + ".dl.dt", is("Default minimum seed time"))
                .body(section("trackers") + ".dl.dd", is("30d"))
                .body(ScrollRegions.TABLE_PARENTS, ScrollRegions.regions("Trackers"))
                .body(section("trackers") + ".div.table.thead.tr.th", contains("Tracker", "Hosts", "Minimum seed time"))
                .body(section("trackers") + ".div.table.tbody.tr.size()", is(3))
                .body(
                        section("trackers") + ".div.table.tbody.tr[0].td",
                        contains("tracker-a", "tracker-a.example", "30d (default)"))
                .body(
                        section("trackers") + ".div.table.tbody.tr[1].td",
                        contains("tracker-b", "tracker-b.example, tracker-b.example.org", "14d"))
                .body(
                        section("trackers") + ".div.table.tbody.tr[2].td",
                        contains("public", "Every torrent that is not private", "1d"));
    }

    @Test
    void pageShowsEachSecretAsSetAndNeverItsValue() {
        Authenticated.asUser()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                .body(
                        section("secrets") + ".dl.dt",
                        contains(
                                "SEEDKEEPER_TRANSMISSION_USERNAME",
                                "SEEDKEEPER_TRANSMISSION_PASSWORD",
                                "SEEDKEEPER_AUTH_PASSWORD",
                                "SEEDKEEPER_API_KEY",
                                "QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY"))
                .body(section("secrets") + ".dl.dd", contains("set", "set", "set", "set", "set"))
                .body(not(containsString(FakeTransmission.USERNAME)))
                .body(not(containsString(FakeTransmission.PASSWORD)))
                .body(not(containsString(TestSecrets.PASSWORD)))
                .body(not(containsString(TestSecrets.API_KEY)))
                .body(not(containsString(TestSecrets.all().get("quarkus.http.auth.session.encryption-key"))));
    }

    @Test
    void theNavigationLeadsToTheSettingsView() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .body("html.body.aside.nav.ul.li.a.find { it.@href == '/settings' }", is("Settings"));
    }

    private static String section(String id) {
        return "html.body.main.section.find { it.@id == '" + id + "' }";
    }
}
