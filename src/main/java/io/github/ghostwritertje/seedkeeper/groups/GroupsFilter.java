package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Which groups to show: those with this verdict and this reason, a member of which has a name that contains this
 * text. The reason is the one that decided the verdict; secondary reasons don't match. An empty side matches every
 * group.
 *
 * @param name the text a member's name contains, ignoring case, stripped; empty for any name
 */
record GroupsFilter(Optional<Verdict> verdict, Optional<Reason> reason, String name) {

    /** Matches every group. */
    public static final GroupsFilter NONE = new GroupsFilter(Optional.empty(), Optional.empty(), "");

    /**
     * The filter the codes in a request name, for any name.
     *
     * @param verdict the kebab-case code of a verdict; null or blank for any
     * @param reason  the kebab-case code of a reason; null or blank for any
     * @throws UnknownFilter when a code doesn't exist; the message is fit to show to the user as it is
     */
    public static GroupsFilter parse(@Nullable String verdict, @Nullable String reason) {
        return parse(verdict, reason, null);
    }

    /**
     * The filter a request names.
     *
     * @param verdict the kebab-case code of a verdict; null or blank for any
     * @param reason  the kebab-case code of a reason; null or blank for any
     * @param name    the text a member's name contains, ignoring case; null or blank for any
     * @throws UnknownFilter when a code doesn't exist; the message is fit to show to the user as it is
     */
    public static GroupsFilter parse(@Nullable String verdict, @Nullable String reason, @Nullable String name) {
        return new GroupsFilter(
                parse("verdict", verdict, Verdict.values(), Verdict::code),
                parse("reason", reason, Reason.values(), Reason::code),
                name == null ? "" : name.strip());
    }

    public boolean matches(GroupVerdict group) {
        String text = name.toLowerCase(Locale.ROOT);
        return verdict.map(v -> v == group.verdict()).orElse(true)
                && reason.map(r -> r == group.reason()).orElse(true)
                && group.members().stream()
                        .anyMatch(
                                member -> member.name().toLowerCase(Locale.ROOT).contains(text));
    }

    /** The same filter for any reason: the one the counts of the reasons are taken under. */
    public GroupsFilter anyReason() {
        return new GroupsFilter(verdict, Optional.empty(), name);
    }

    public boolean active() {
        return verdict.isPresent() || reason.isPresent() || !name.isEmpty();
    }

    private static <T> Optional<T> parse(String what, @Nullable String code, T[] values, Function<T, String> codeOf) {
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
    static class UnknownFilter extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnknownFilter(String message) {
            super(message);
        }

        /** Never null, as the constructor takes it. */
        @Override
        public String getMessage() {
            return Objects.requireNonNull(super.getMessage());
        }
    }
}
