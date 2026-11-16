package io.github.ghostwritertje.archfixtures.api.fresh;

/** Not module API, in a module that has none at all: rule 2 checks it like any other, so other modules may not use it. */
public class Internal {

    public String value() {
        return "fresh";
    }
}
