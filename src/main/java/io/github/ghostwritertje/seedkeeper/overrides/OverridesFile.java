package io.github.ghostwritertje.seedkeeper.overrides;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/** The text of {@code overrides.yaml} (spec §7): from YAML to overrides and back. No I/O. */
final class OverridesFile {

    private OverridesFile() {
    }

    /**
     * @throws Unreadable with what is wrong, when the text is not an overrides file
     */
    static List<KeepOverride> parse(String yaml) {
        Object document;
        try {
            document = textOnly().load(yaml);
        } catch (YAMLException e) {
            throw new Unreadable("it is not valid YAML: " + e.getMessage().lines().findFirst().orElse(""));
        }
        if (document == null) {
            return List.of();
        }
        if (!(document instanceof Map<?, ?> root) || !root.containsKey("overrides")) {
            throw new Unreadable("it has no 'overrides' list");
        }
        Object list = root.get("overrides");
        if (blank(list)) {
            return List.of();
        }
        if (!(list instanceof List<?> items)) {
            throw new Unreadable("'overrides' is not a list");
        }
        List<KeepOverride> overrides = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            KeepOverride override = override(items.get(i), "override " + (i + 1));
            if (!ids.add(override.id())) {
                throw new Unreadable("override " + (i + 1) + " has the same 'id' as an earlier one: "
                        + override.id());
            }
            overrides.add(override);
        }
        return List.copyOf(overrides);
    }

    private static KeepOverride override(Object item, String which) {
        if (!(item instanceof Map<?, ?> fields)) {
            throw new Unreadable(which + " is not a set of keys (id, hashes, note, until, created)");
        }
        if (blank(fields.get("id")) || !(fields.get("id") instanceof String id)) {
            throw new Unreadable(which + " has no 'id'");
        }
        String named = which + " (" + id.trim() + ")";
        Object hashList = fields.get("hashes");
        if (!blank(hashList) && !(hashList instanceof List<?>)) {
            throw new Unreadable(named + ": 'hashes' is not a list");
        }
        List<String> hashes = blank(hashList) ? List.of() : ((List<?>) hashList).stream()
                .filter(hash -> hash instanceof String text && !text.isBlank())
                .map(hash -> ((String) hash).trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (hashes.isEmpty()) {
            throw new Unreadable(named + " has no 'hashes'");
        }
        Object note = fields.get("note");
        if (note != null && !(note instanceof String)) {
            throw new Unreadable(named + ": 'note' is not text");
        }
        return new KeepOverride(id.trim(), hashes, note == null ? "" : (String) note,
                date(fields.get("until"), named), dateTime(fields.get("created"), named));
    }

    private static Optional<LocalDate> date(Object value, String named) {
        if (blank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.toString().trim()));
        } catch (DateTimeParseException e) {
            throw new Unreadable(named + ": 'until' is not a date like 2027-03-01: " + value);
        }
    }

    private static Optional<OffsetDateTime> dateTime(Object value, String named) {
        if (blank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value.toString().trim()));
        } catch (DateTimeParseException e) {
            throw new Unreadable(named + ": 'created' is not a date and time like 2026-11-20T10:12:00+01:00: "
                    + value);
        }
    }

    /** A key that is absent, or present without a value. */
    private static boolean blank(Object value) {
        return value == null || value instanceof String text && (text.isBlank() || text.equals("~")
                || text.equals("null"));
    }

    /**
     * A YAML reader for which every scalar is text. An info hash can be all digits, or digits around one {@code e},
     * and must not become a number; the dates are parsed here, with their offset.
     */
    private static Yaml textOnly() {
        LoaderOptions loading = new LoaderOptions();
        DumperOptions dumping = new DumperOptions();
        return new Yaml(new SafeConstructor(loading), new Representer(dumping), dumping, loading, new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                // None: without them every scalar resolves to a string.
            }
        });
    }

    /** The whole file. Every text is double-quoted, so no id, hash or note can be read as anything else. */
    static String format(List<KeepOverride> overrides) {
        StringBuilder yaml = new StringBuilder(
                "# Keep overrides. seedkeeper rewrites this file as a whole: comments added by hand are lost.\n");
        if (overrides.isEmpty()) {
            return yaml.append("overrides: []\n").toString();
        }
        yaml.append("overrides:\n");
        for (KeepOverride override : overrides) {
            yaml.append("  - id: ").append(quoted(override.id())).append('\n');
            yaml.append("    hashes: [")
                    .append(override.hashes().stream().map(OverridesFile::quoted).collect(Collectors.joining(", ")))
                    .append("]\n");
            yaml.append("    note: ").append(quoted(override.note())).append('\n');
            override.until().ifPresent(until -> yaml.append("    until: ").append(until).append('\n'));
            override.created().ifPresent(created -> yaml.append("    created: ")
                    .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(created)).append('\n'));
        }
        return yaml.toString();
    }

    /** A YAML double-quoted scalar. */
    private static String quoted(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        text.codePoints().forEach(c -> {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20 || c >= 0x7f && c < 0xa0 || c == 0x2028 || c == 0x2029 || c == 0xfeff) {
                        quoted.append(String.format("\\u%04x", c));
                    } else {
                        quoted.appendCodePoint(c);
                    }
                }
            }
        });
        return quoted.append('"').toString();
    }

    /** The text is not an overrides file. The message says what is wrong. */
    static final class Unreadable extends RuntimeException {

        Unreadable(String problem) {
            super(problem);
        }
    }
}
