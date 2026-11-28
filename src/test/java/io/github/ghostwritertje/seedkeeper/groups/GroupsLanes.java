package io.github.ghostwritertje.seedkeeper.groups;

/**
 * Paths into the lanes of the Groups view, for RestAssured's HTML body. Each lane is a {@code <section>} with the code
 * of its verdict as {@code data-verdict}; each group in it is an {@code <article>}, a card: its name in an
 * {@code <h3>}, then a {@code <p>} per line.
 */
final class GroupsLanes {

    /** Every lane, from left to right. */
    static final String LANES = "html.body.main.div.find { it.@id == 'lanes' }.section";

    /** The heading of every lane, from left to right: "5 Keep". */
    static final String HEADINGS = LANES + ".collect { it.h2.text() }";

    /** Every card of the view, in every lane. */
    static final String CARDS = LANES + ".article";

    private GroupsLanes() {}

    /** The lane of the verdict with this code. */
    static String lane(String verdict) {
        return LANES + ".find { it.@'data-verdict' == '" + verdict + "' }";
    }

    /** The names of the groups of a lane, in order. */
    static String names(String verdict) {
        return lane(verdict) + ".article.collect { it.h3.text() }";
    }

    /** The card of a group of a lane. */
    static String card(String verdict, int group) {
        return lane(verdict) + ".article[" + group + "]";
    }

    /** What the group is and its size: "tracker-a · Seeding · 1 of 2 files linked · 8 B". */
    static String summary(String verdict, int group) {
        return card(verdict, group) + ".p[0].text()";
    }

    /** The reason of the group, without the secondary reasons. */
    static String reason(String verdict, int group) {
        return card(verdict, group) + ".p[1].strong.text()";
    }

    /** The secondary reasons after the reason: "also: Minimum not reached"; empty when none. */
    static String secondaryReasons(String verdict, int group) {
        return card(verdict, group) + ".p[1].small.text()";
    }

    /** What to do about the reason; empty when it has none. */
    static String hint(String verdict, int group) {
        return card(verdict, group) + ".p.findAll { it.@'data-hint' == 'true' }.collect { it.text() }.join('')";
    }

    /** The tracker message of a group of one; empty when the tracker reports nothing wrong. */
    static String trackerMessage(String verdict, int group) {
        return card(verdict, group) + ".p.findAll { it.@'data-tone' == 'danger' }.collect { it.text() }.join('')";
    }

    /** The line of the progress toward the minimum seed time, shown while below it. */
    static String progressLine(String verdict, int group) {
        return card(verdict, group) + ".p.find { it.@'data-tone' == 'warn' }";
    }

    /** The progress and the forecast: "18% of minimum · removable 2026-12-02 10:15", without the exact durations. */
    static String progress(String verdict, int group) {
        return progressLine(verdict, group) + ".with { p -> p.text() - p.small.text() }";
    }

    /** The exact durations under the progress: "2d 12h of 14d · 11d 12h to go". */
    static String progressDetail(String verdict, int group) {
        return progressLine(verdict, group) + ".small.text()";
    }

    /** The members that cause the reason, each "causes "Unmanaged" Cross.Seed.Elsewhere". */
    static String causes(String verdict, int group) {
        return card(verdict, group) + ".p.findAll { it.@'data-cause' == 'true' }.collect { it.text() }";
    }

    /** A member of a group of more than one: its name, the reason it causes, if any, then each line under it. */
    static String member(String verdict, int group, int member) {
        return card(verdict, group) + ".details.ul.li[" + member + "].'*'.collect { it.text() }";
    }
}
