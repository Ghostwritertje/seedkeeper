package io.github.ghostwritertje.seedkeeper.transmission;

import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** Starts a {@link FakeTransmission} and points the application at it, with every secret set. */
public class FakeTransmissionResource implements QuarkusTestResourceLifecycleManager {

    private static volatile FakeTransmission fake;

    /** The fake the application under test talks to. */
    public static FakeTransmission fake() {
        return fake;
    }

    @Override
    public Map<String, String> start() {
        fake = new FakeTransmission();
        return TestSecrets.with("seedkeeper.transmission.url", fake.url().toString());
    }

    @Override
    public void stop() {
        if (fake != null) {
            fake.close();
        }
    }
}
