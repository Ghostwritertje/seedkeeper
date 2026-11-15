package io.github.ghostwritertje.seedkeeper.decide;

/** Why a group got its verdict, in the order of precedence of spec §3.5: the first that holds wins. */
public enum Reason {
    UNMANAGED("unmanaged", "Unmanaged", Verdict.LEFT_ALONE),
    MISSING_DATA("missing-data", "Missing data", Verdict.LEFT_ALONE),
    LOCAL_ERROR("local-error", "Local error", Verdict.LEFT_ALONE),
    VERIFYING("verifying", "Verifying", Verdict.KEEP),
    NOT_FINISHED("not-finished", "Not finished", Verdict.KEEP),
    KEEP_OVERRIDE("keep-override", "Keep override", Verdict.KEEP),
    LIBRARY_LINK("library-link", "Library link", Verdict.KEEP),
    MINIMUM_NOT_REACHED("minimum-not-reached", "Minimum not reached", Verdict.KEEP),
    ALL_MET("all-met", "All met", Verdict.REMOVE);

    private final String code;
    private final String label;
    private final Verdict verdict;

    Reason(String code, String label, Verdict verdict) {
        this.code = code;
        this.label = label;
        this.verdict = verdict;
    }

    /** The kebab-case code the JSON API uses. */
    public String code() {
        return code;
    }

    /** As the UI shows it. */
    public String label() {
        return label;
    }

    /** The verdict this reason leads to. */
    public Verdict verdict() {
        return verdict;
    }
}
