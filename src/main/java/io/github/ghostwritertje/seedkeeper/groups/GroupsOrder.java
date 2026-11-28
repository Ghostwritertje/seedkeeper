package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.Forecast;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The order of the Groups view: the column it is sorted by, and which way. Groups that tie keep the default order, and
 * then the snapshot order.
 *
 * @param descending true for the other way round: the last, largest or latest first
 */
record GroupsOrder(Column column, boolean descending) {

    /**
     * The order with no sort chosen, the order of the lanes: Remove largest first, then Left alone by reason, then
     * Keep by soonest forecast.
     */
    static final GroupsOrder DEFAULT = new GroupsOrder(Column.VERDICT, false);

    /** The value of {@code dir} that turns the order round. */
    static final String DESCENDING = "desc";

    /** What the Groups view can be sorted by. */
    enum Column {
        /** The name of the first member, ignoring case. */
        NAME("name"),
        /**
         * The seed time toward the minimum: of the member for a group of one, of the slowest managed member for more.
         * A group with no managed member comes last either way.
         */
        SEEDING("seeding"),
        /** The verdict, Remove first, then each in its lane's order; the other way round, all of it is reversed. */
        VERDICT("verdict"),
        /** The forecast date, then a stopped clock, then no forecast: either way, only the dates turn round. */
        FORECAST("forecast"),
        /** The bytes of the group's wanted files. */
        SIZE("size");

        private final String code;

        Column(String code) {
            this.code = code;
        }

        /** The code {@code sort} names it by. */
        String code() {
            return code;
        }

        /** The groups by this column, the usual way or turned round. */
        Comparator<GroupVerdict> order(boolean descending) {
            return switch (this) {
                case NAME -> by(GroupsOrder::name, descending);
                case SEEDING -> by(GroupsOrder::seeding, descending);
                case VERDICT -> descending ? verdict().reversed() : verdict();
                case FORECAST -> forecast(descending);
                case SIZE -> by(group -> Optional.of(group.size()), descending);
            };
        }
    }

    /**
     * The order a request names.
     *
     * @param sort the code of a {@link Column}; the default order when null, blank or unknown
     * @param dir  {@code desc} for the other way round; anything else is the usual way
     */
    static GroupsOrder parse(@Nullable String sort, @Nullable String dir) {
        String code = sort == null ? "" : sort.strip();
        return Stream.of(Column.values())
                .filter(column -> column.code().equals(code))
                .findFirst()
                .map(column -> new GroupsOrder(column, dir != null && DESCENDING.equals(dir.strip())))
                .orElse(DEFAULT);
    }

    /** The groups in this order. */
    List<GroupVerdict> sort(List<GroupVerdict> groups) {
        return groups.stream()
                .sorted(column.order(descending).thenComparing(verdict()))
                .toList();
    }

    /** The code of the direction, for {@code dir}. */
    String dir() {
        return descending ? DESCENDING : "asc";
    }

    /** By verdict, then in the order of its lane: Remove largest first, Left alone by reason, Keep by forecast. */
    private static Comparator<GroupVerdict> verdict() {
        return Comparator.comparing(GroupVerdict::verdict)
                .thenComparing((a, b) -> lane(a.verdict()).compare(a, b));
    }

    /** The order within the lane of a verdict; the groups left alone with the same reason by soonest forecast too. */
    private static Comparator<GroupVerdict> lane(Verdict verdict) {
        return switch (verdict) {
            case REMOVE -> Comparator.comparingLong(GroupVerdict::size).reversed();
            case LEFT_ALONE -> Comparator.comparing(GroupVerdict::reason).thenComparing(forecast(false));
            case KEEP -> forecast(false);
        };
    }

    private static Comparator<GroupVerdict> forecast(boolean descending) {
        return by(GroupsOrder::forecastEnd, descending).thenComparingInt(GroupsOrder::forecastRank);
    }

    /** By a key, the groups without one last whichever way. */
    private static <K extends Comparable<? super K>> Comparator<GroupVerdict> by(
            Function<GroupVerdict, Optional<K>> key, boolean descending) {
        return (a, b) -> {
            Optional<K> x = key.apply(a);
            Optional<K> y = key.apply(b);
            if (x.isEmpty() || y.isEmpty()) {
                return Boolean.compare(x.isEmpty(), y.isEmpty());
            }
            int order = x.orElseThrow().compareTo(y.orElseThrow());
            return descending ? -order : order;
        };
    }

    private static Optional<String> name(GroupVerdict group) {
        return Optional.of(group.members().getFirst().name().toLowerCase(Locale.ROOT));
    }

    /** Seed time over the minimum of the slowest managed member, as the progress the view shows; empty without one. */
    private static Optional<Double> seeding(GroupVerdict group) {
        return GroupsPage.slowestManaged(group.members())
                .map(member -> (double) member.seedTime().toSeconds()
                        / member.tracker().orElseThrow().minimumSeedTime().toSeconds());
    }

    /**
     * When the forecast ends, in the configured time zone: the forecast date, or the end of the last day of a keep
     * override, which is inclusive. Empty for a stopped clock and for no forecast.
     */
    private static Optional<LocalDateTime> forecastEnd(GroupVerdict group) {
        return group.forecast().flatMap(GroupsOrder::end);
    }

    private static Optional<LocalDateTime> end(Forecast forecast) {
        return forecast.until()
                .map(until -> until.plusDays(1).atStartOfDay())
                .or(() -> forecast.date().map(ZonedDateTime::toLocalDateTime));
    }

    /** 0 with a date, 1 for a stopped clock, 2 with no forecast. */
    private static int forecastRank(GroupVerdict group) {
        return group.forecast().map(f -> end(f).isPresent() ? 0 : 1).orElse(2);
    }
}
