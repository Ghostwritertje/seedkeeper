package io.github.ghostwritertje.seedkeeper.overrides;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.Listed;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.OverridesUnavailable;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The Overrides view, read-only. */
@Path("/overrides")
public class OverridesPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param file        the path of the overrides file
         * @param unavailable why the overrides can't be listed right now; null when they can
         */
        static native TemplateInstance overrides(List<Row> overrides, String file, String unavailable);
    }

    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * One override, as text ready to show.
     *
     * @param status  "Active", "Expired", "Stale" or "Expired, stale"
     * @param until   "2027-03-01" or "no end date"
     * @param created "2026-11-20 10:12" in the configured time zone; empty when the file doesn't say
     * @param groups  the names of the members of every group it matches; empty when it is stale
     * @param hashes  separated by commas
     */
    public record Row(String status, String note, String until, String created, List<List<String>> groups,
            String hashes, String id) {
    }

    private final OverridesService overrides;
    private final SeedkeeperConfig config;

    OverridesPage(OverridesService overrides, SeedkeeperConfig config) {
        this.overrides = overrides;
        this.config = config;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance overrides() {
        String file = config.dataDir().resolve(OverrideStore.FILE_NAME).toString();
        try {
            return Templates.overrides(overrides.overrides().stream().map(this::row).toList(), file, null);
        } catch (OverridesUnavailable e) {
            return Templates.overrides(List.of(), file, e.getMessage());
        }
    }

    private Row row(Listed listed) {
        KeepOverride override = listed.override();
        String status = listed.active() ? "Active"
                : listed.expired() && listed.stale() ? "Expired, stale"
                : listed.expired() ? "Expired" : "Stale";
        return new Row(status, override.note(),
                override.until().map(Object::toString).orElse("no end date"),
                override.created().map(created -> CREATED.format(created.atZoneSameInstant(config.timeZone())))
                        .orElse(""),
                listed.groups().stream().map(group -> group.stream().map(GroupVerdict.Member::name).toList())
                        .toList(),
                String.join(", ", override.hashes()), override.id());
    }
}
