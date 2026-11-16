package io.github.ghostwritertje.archfixtures.cycle.a;

import io.github.ghostwritertje.archfixtures.cycle.b.Beta;

/** Breaks rule 1: module {@code a} uses module {@code b}, which uses {@code a} back. */
public class Alpha {

    public Beta beta() {
        return new Beta();
    }
}
