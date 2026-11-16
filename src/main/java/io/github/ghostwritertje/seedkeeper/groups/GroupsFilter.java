package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Which groups to show: those with this verdict and this reason. The reason is the one that decided the verdict;
 * secondary reasons don't match. An empty side matches every group.
 */
public record GroupsFilter(Optional<Verdict> verdict, Optional<Reason> reason) {

    /** Matches every group. */
    public static final GroupsFilter NONE = new GroupsFilter(Optional.empty(), Optional.empty());

    /**
     * @param verdict the kebab-case code of a verdict; null or blank for any
     * @param reason  the kebab-case code of a reason; null or blank for any
     * @throws UnknownFilter when a code doesn't exist; the message is fit to show to the user as it is
     */
    public static GroupsFilter parse(String verdict, String reason) {
        return new GroupsFilter(
                parse("verdict", verdict, Verdict.values(), Verdict::code),
                parse("reason", reason, Reason.values(), Reason::code));
    }

    public boolean matches(GroupVerdict group) {
        return verdict.map(v -> v == group.verdict()).orElse(true)
                && reason.map(r -> r == group.reason()).orElse(true);
    }

    public boolean active() {
        return verdict.isPresent() || reason.isPresent();
    }

    private static <T> Optional<T> parse(String what, String code, T[] values, Function<T, String> codeOf) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Optional<T> found = Stream.of(values)
                .filter(value -> codeOf.apply(value).equals(code.trim()))
                .findFirst();
        if (found.isEmpty()) {
            List<String> codes = Stream.of(values).map(codeOf).toList();
            throw new UnknownFilter("Unknown " + what + " '" + code + "': use "
                    + String.join(", ", codes.subList(0, codes.size() - 1)) + " or " + codes.getLast());
        }
        return found;
    }

    /** A filter names a verdict or a reason that doesn't exist. */
    public static class UnknownFilter extends RuntimeException {

        UnknownFilter(String message) {
            super(message);
        }
    }
}
