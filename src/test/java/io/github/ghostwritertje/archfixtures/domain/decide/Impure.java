package io.github.ghostwritertje.archfixtures.domain.decide;

import io.quarkus.runtime.LaunchMode;

/** Breaks rule 4: a domain module that depends on Quarkus. */
public class Impure {

    public boolean inProduction(LaunchMode mode) {
        return mode == LaunchMode.NORMAL;
    }
}
