package io.github.ghostwritertje.archfixtures.cycle.b;

import io.github.ghostwritertje.archfixtures.cycle.a.Alpha;

/** Breaks rule 1: module {@code b} uses module {@code a}, which uses {@code b} back. */
public class Beta {

    public Alpha alpha() {
        return new Alpha();
    }
}
