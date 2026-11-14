package io.github.ghostwritertje.seedkeeper.startup;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

/** Shared by the tests that start the whole application with a configuration it should refuse. */
final class Startup {

    private Startup() {
    }

    /** Puts the application (classes and templates) in the archive a QuarkusUnitTest starts. */
    static void application(JavaArchive jar) {
        jar.addPackages(true, SeedkeeperConfig.class.getPackage())
                .addAsResource("templates/layout.html")
                .addAsResource("templates/LoginPage/login.html")
                .addAsResource("templates/SystemPage/system.html");
    }

    /** The messages of a failure and all its causes, one per line. */
    static String messages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
