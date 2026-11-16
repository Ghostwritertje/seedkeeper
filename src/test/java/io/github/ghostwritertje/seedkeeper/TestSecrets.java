package io.github.ghostwritertje.seedkeeper;

import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission;
import java.util.LinkedHashMap;
import java.util.Map;

/** The secrets the application refuses to start without, as configuration for a test. */
public final class TestSecrets {

    public static final String USERNAME = "admin";
    public static final String PASSWORD = "ui-password";
    public static final String API_KEY = "api-key";

    private TestSecrets() {}

    public static Map<String, String> all() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("seedkeeper.transmission.username", FakeTransmission.USERNAME);
        secrets.put("seedkeeper.transmission.password", FakeTransmission.PASSWORD);
        secrets.put("seedkeeper.auth.password", PASSWORD);
        secrets.put("seedkeeper.api-key", API_KEY);
        secrets.put("quarkus.http.auth.session.encryption-key", "a-session-encryption-key-for-tests");
        return secrets;
    }

    public static Map<String, String> with(String key, String value) {
        Map<String, String> config = all();
        config.put(key, value);
        return config;
    }
}
