package io.github.ghostwritertje.seedkeeper.decide;

/** What a run decides about a group. */
public enum Verdict {
    REMOVE("remove", "Remove"),
    LEFT_ALONE("left-alone", "Left alone"),
    KEEP("keep", "Keep");

    private final String code;
    private final String label;

    Verdict(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** The kebab-case code the JSON API uses. */
    public String code() {
        return code;
    }

    /** As the UI shows it. */
    public String label() {
        return label;
    }
}
