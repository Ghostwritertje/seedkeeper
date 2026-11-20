package io.github.ghostwritertje.seedkeeper;

import static org.hamcrest.Matchers.is;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.hamcrest.Matcher;

/**
 * Where the tables of a page sit. On a small screen a wide table scrolls sideways inside a named region that takes
 * focus, so a keyboard can scroll it and a screen reader announces it: {@code <div role="region" tabindex="0"
 * aria-label="…">}. Use it as {@code .body(ScrollRegions.TABLE_PARENTS, ScrollRegions.regions("Trackers"))}.
 */
public final class ScrollRegions {

    /**
     * An HTML path to what the parent of every table of the page is, in page order and separated by {@code ;}: its
     * element name, {@code role}, {@code tabindex} and {@code aria-label}, joined by {@code |}. One string, also for a
     * single table, which a list would not be.
     */
    public static final String TABLE_PARENTS = "html.body.'**'.findAll { it.name() == 'table' }.collect { "
            + "[it.parent().name(), it.parent().@role.text(), it.parent().@tabindex.text(), "
            + "it.parent().'@aria-label'.text()].join('|') }.join(';')";

    private ScrollRegions() {}

    /**
     * Matches {@link #TABLE_PARENTS} of a page whose tables each sit, in this order, directly in a {@code div} with
     * {@code role="region"}, {@code tabindex="0"} and these names as {@code aria-label}.
     */
    public static Matcher<String> regions(String... names) {
        return is(Arrays.stream(names).map(name -> "div|region|0|" + name).collect(Collectors.joining(";")));
    }
}
