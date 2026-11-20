package io.github.ghostwritertje.seedkeeper.settings;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Paths card of the Settings view with more than one library root: every label still lines up with its own value.
 */
@QuarkusTest
@TestProfile(SettingsLibraryRootsTest.TwoLibraryRoots.class)
class SettingsLibraryRootsTest {

    private static final String PATHS = "html.body.main.section.find { it.@id == 'paths' }.dl";

    public static class TwoLibraryRoots implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config =
                    TestSecrets.with("seedkeeper.transmission.url", "http://127.0.0.1:9/transmission/rpc");
            config.put("seedkeeper.downloads-root", "/downloads");
            config.put("seedkeeper.library-roots", "/library/movies,/library/tv");
            config.put("seedkeeper.data-dir", DATA_DIR.toString());
            return config;
        }
    }

    // A fixed path, the same in the profile's and the test's classloader; seedkeeper creates it on start.
    private static final Path DATA_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "seedkeeper-settings-library-roots");

    @Test
    void eachLabelHasOneValueAndEveryLibraryRootIsInIt() {
        Authenticated.asUser()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                // The <dl> is a two-column grid: a label, then its value, in turn.
                .body(PATHS + ".children().collect { it.name() }", contains("dt", "dd", "dt", "dd", "dt", "dd"))
                .body(PATHS + ".dt", contains("Downloads root", "Library roots", "Data dir"))
                .body(PATHS + ".dd[0]", is("/downloads"))
                .body(PATHS + ".dd[1].ul.li", contains("/library/movies", "/library/tv"))
                .body(PATHS + ".dd[2]", is(DATA_DIR.toString()));
    }
}
