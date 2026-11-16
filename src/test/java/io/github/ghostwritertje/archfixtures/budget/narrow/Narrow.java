package io.github.ghostwritertje.archfixtures.budget.narrow;

import io.github.ghostwritertje.seedkeeper.ModuleApi;

/** Within rule 3: module {@code narrow} has 3 module API behaviour types, plus a record and an enum. */
final class Narrow {

    private Narrow() {}

    @ModuleApi
    public static class First {}

    @ModuleApi
    public static class Second {}

    @ModuleApi
    public interface Third {}

    @ModuleApi
    public record Data(String value) {}

    @ModuleApi
    public enum Kind {
        ONE
    }
}
