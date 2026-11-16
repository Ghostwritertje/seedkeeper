package io.github.ghostwritertje.archfixtures.budget.wide;

import io.github.ghostwritertje.seedkeeper.ModuleApi;

/**
 * Breaks rule 3: module {@code wide} has 4 module API behaviour types. Its module API record and enum don't count.
 */
final class Wide {

    private Wide() {}

    @ModuleApi
    public static class First {}

    @ModuleApi
    public static class Second {}

    @ModuleApi
    public interface Third {}

    @ModuleApi
    public static class Fourth {}

    @ModuleApi
    public record Data(String value) {}

    @ModuleApi
    public enum Kind {
        ONE
    }
}
