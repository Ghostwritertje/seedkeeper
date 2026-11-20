package io.github.ghostwritertje.seedkeeper.groups;

/**
 * Paths into the table of the Groups view, for RestAssured's HTML body. Each group is a {@code <tbody>}: a header row
 * whose {@code <th>} holds the group's name and its muted lines, then one row per member when it has more than one.
 */
final class GroupsTable {

    static final String HEADINGS = "html.body.main.div.table.thead.tr.th";
    static final String GROUPS = "html.body.main.div.table.tbody";

    private GroupsTable() {}

    /**
     * The group's name, then each muted line under it: what the group is, and the tracker message, if any; without
     * the verdict that small screens show under them ({@link #verdictUnderName(int)}).
     */
    static String heading(int group) {
        return GROUPS + "[" + group + "].tr[0].th.'*'.findAll { it.name() != 'p' }.collect { it.text() }";
    }

    /**
     * The verdict under the group's name, for small screens, where the verdict and reason columns are hidden: the
     * verdict's code, the verdict, then the reason.
     */
    static String verdictUnderName(int group) {
        return GROUPS + "[" + group + "].tr[0].th.p[0]"
                + ".with { p -> [p.mark.@'data-verdict'.text(), p.mark.text(), p.localText().join('').trim()] }";
    }

    /**
     * The text of every cell of the header row after the name, up to the form that sets a keep override; a cell's
     * own text only, without the lines under it.
     */
    static String cells(int group) {
        return GROUPS + "[" + group + "].tr[0].td.findAll { it.form.size() == 0 }"
                + ".collect { it.localText().join('').trim() }";
    }

    /** The line under the reason (the third cell after the name): the secondary reasons; empty when none. */
    static String underReason(int group) {
        return GROUPS + "[" + group + "].tr[0].td[2].small.findAll { it.@'data-hint' != 'true' }"
                + ".collect { it.text() }.join('')";
    }

    /** The hint under the reason (the third cell after the name): what to do about it; empty when it has none. */
    static String hint(int group) {
        return GROUPS + "[" + group + "].tr[0].td[2].small.findAll { it.@'data-hint' == 'true' }"
                + ".collect { it.text() }.join('')";
    }

    /** The cell of the header row that shows the progress toward the minimum seed time (the first after the name). */
    static String progressCell(int group) {
        return GROUPS + "[" + group + "].tr[0].td[0]";
    }

    /** The exact durations behind the progress of the header row: the detail that opens on hover and focus. */
    static String progressDetail(int group) {
        return progressCell(group) + ".small.text()";
    }

    /** The member rows of a group: name, its lines, then the progress toward the minimum, without its detail. */
    static String member(int group, int member) {
        return GROUPS + "[" + group + "].tr.findAll { it.th.size() == 0 }[" + member + "]"
                + ".with { row -> row.td[0].'*'.collect { it.text() } + [row.td[1].localText().join('').trim()] }";
    }

    /** The number of rows of every group. */
    static final String ROWS = GROUPS + ".collect { it.tr.size() }";

    /** The name of every group, in order. */
    static final String NAMES = GROUPS + ".collect { it.tr[0].th.strong.text() }";
}
