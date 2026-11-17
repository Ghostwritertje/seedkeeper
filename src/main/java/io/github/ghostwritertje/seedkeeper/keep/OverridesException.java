package io.github.ghostwritertje.seedkeeper.keep;

import io.github.ghostwritertje.seedkeeper.ModuleApi;

/**
 * A keep override can't be read or changed. The message is the one reason, fit to show to the user as it is; the
 * {@link Problem} says what kind of reason it is. While the file can't be read there are no verdicts: an unreadable
 * file must never count as "no overrides".
 */
@ModuleApi
public class OverridesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What kind of reason an {@link OverridesException} gives. */
    public enum Problem {
        /** The overrides file, or the data dir it is in, can't be read or written. */
        FILE,
        /** What was asked for is not a keep override. */
        INVALID,
        /** The override to change is not in the file. */
        NO_SUCH_OVERRIDE
    }

    private final Problem problem;

    OverridesException(String reason, Throwable cause) {
        super(reason, cause);
        this.problem = Problem.FILE;
    }

    OverridesException(Problem problem, String reason) {
        super(reason);
        this.problem = problem;
    }

    /** What kind of reason this is. */
    public Problem problem() {
        return problem;
    }
}
