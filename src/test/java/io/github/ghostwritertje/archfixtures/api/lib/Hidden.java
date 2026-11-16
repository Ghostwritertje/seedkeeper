package io.github.ghostwritertje.archfixtures.api.lib;

/** Not module API: other modules may not use it. */
public class Hidden {

    public String secret() {
        return "hidden";
    }
}
