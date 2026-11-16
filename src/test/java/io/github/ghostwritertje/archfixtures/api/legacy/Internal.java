package io.github.ghostwritertje.archfixtures.api.legacy;

/** Not module API, but its module isn't migrated yet, so rule 2 lets other modules use it. */
public class Internal {

    public String value() {
        return "legacy";
    }
}
