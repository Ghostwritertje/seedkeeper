package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.Forecast;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
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

    /** The order with no sort chosen: Remove, then Left alone, then Keep, each by soonest forecast. */
    static final GroupsOrder DEFAULT = new GroupsOrder(Column.VERDICT, false);

    /** The value of {@code dir} that turns the order round. */
    static final String DESCENDING = "desc";

    /** What the Groups view can be sorted by. */
    enum Column {
        /** The name of the first member, ignoring case. */
        NAME("name", descending -> by(GroupsOrder::name, descending)),
        /**
         * The seed time toward the minimum: of the member for a group of one, of the slowest managed member for more.
         * A group with no managed member comes last either way.
         */
        SEEDING("seeding", descending -> by(GroupsOrder::seeding, descending)),
        /** The verdict, Remove first, then each by soonest forecast; the other way round, all of it is reversed. */
        VERDICT("verdict", descending -> descending ? verdict().reversed() : verdict()),
        /** The forecast date, then a stopped clock, then no forecast: either way, only the dates turn round. */
        FORECAST("forecast", GroupsOrder::forecast),
        /** The bytes of the group's wanted files. */
        SIZE("size", descending -> by(group -> Optional.of(group.size()), descending));

        private final String code;
        private final Function<Boolean, Comparator<GroupVerdict>> order;

        Column(String code, Function<Boolean, Comparator<GroupVerdict>> order) {
            this.code = code;
            this.order = order;
        }

        /** The code {@code sort} names it by. */
        String code() {
            return code;
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
                .sorted(column.order.apply(descending).thenComparing(verdict()))
                .toList();
    }

    /** The order a click on the header of a column gives: by it the usual way, or this one turned round. */
    GroupsOrder by(Column clicked) {
        return clicked == column ? new GroupsOrder(column, !descending) : new GroupsOrder(clicked, false);
    }

    /** The code of the direction, for {@code dir}. */
    String dir() {
        return descending ? DESCENDING : "asc";
    }

    private static Comparator<GroupVerdict> verdict() {
        return Comparator.comparing(GroupVerdict::verdict).thenComparing(forecast(false));
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
