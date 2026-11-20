package io.github.ghostwritertje.seedkeeper.overrides;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.keep.KeepOverride;
import io.github.ghostwritertje.seedkeeper.keep.OverrideChanges;
import io.github.ghostwritertje.seedkeeper.keep.OverrideStore;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.Listed;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.OverridesUnavailable;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import org.jboss.resteasy.reactive.RestForm;
import org.jspecify.annotations.Nullable;

/**
 * The Overrides view, with its forms to add, edit and delete an override. The forms post with the CSRF token of the
 * page and need a login: changing overrides is not in the API.
 */
@Path("/overrides")
class OverridesPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param file        the path of the overrides file
         * @param unavailable   why the overrides file can't be read right now; null when it can
         * @param groupsUnknown why the groups the overrides match can't be known right now; null when they can
         * @param notice        why the form that was just posted changed nothing; null when there is nothing to tell
         * @param done          what the form that was just posted changed, "Keep override added."; null when nothing
         */
        static native TemplateInstance overrides(
                List<Row> overrides,
                String file,
                @Nullable String unavailable,
                @Nullable String groupsUnknown,
                @Nullable String notice,
                @Nullable String done);
    }

    /**
     * A change the forms of the view make, as the value of {@code done} in the address the browser is sent to after it.
     */
    enum Done {
        ADDED("added", "Keep override added."),
        SAVED("saved", "Keep override saved."),
        DELETED("deleted", "Keep override deleted.");

        private final String value;
        private final String text;

        Done(String value, String text) {
            this.value = value;
            this.text = text;
        }

        /** The text to show for this value of {@code done}; null for no value or one that is not a change. */
        static @Nullable String text(@Nullable String value) {
            for (Done done : values()) {
                if (done.value.equals(value)) {
                    return done.text;
                }
            }
            return null;
        }
    }

    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * One override, as text ready to show.
     *
     * @param status    "Active", "Expired", "Stale", "Expired, stale", or "Unknown" when the groups are unknown
     * @param until     "2027-03-01" or "no end date"
     * @param created   "2026-11-20 10:12" in the configured time zone; empty when the file doesn't say
     * @param groups    the names of the members of every group it matches; empty when it is stale or unknown
     * @param known     false when the groups can't be known right now
     * @param hashes    separated by commas
     * @param untilDate "2027-03-01", or empty when it never ends: the value of the date field of the edit form
     */
    record Row(
            String status,
            String note,
            String until,
            String created,
            List<List<String>> groups,
            boolean known,
            String hashes,
            String id,
            String untilDate) {}

    private final OverridesService overrides;
    private final OverrideChanges changes;
    private final SeedkeeperConfig config;

    OverridesPage(OverridesService overrides, OverrideChanges changes, SeedkeeperConfig config) {
        this.overrides = overrides;
        this.changes = changes;
        this.config = config;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance overrides(@QueryParam("done") @Nullable String done) {
        return page(null, Done.text(done));
    }

    /**
     * Adds an override, then sends the browser to the Overrides view.
     *
     * @param hashes info hashes, separated by commas or white space
     * @param until  "2027-03-01", or empty for no end date
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public Response add(@RestForm String hashes, @RestForm String note, @RestForm String until) {
        return change(
                () -> changes.add(
                        hashes == null ? List.of() : Arrays.asList(hashes.split("[\\s,]+")),
                        note,
                        OverrideChanges.date(until)),
                Done.ADDED);
    }

    /** Changes the note and the end date of the override with this id, then sends the browser to the view. */
    @POST
    @Path("/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public Response edit(@RestForm String id, @RestForm String note, @RestForm String until) {
        return change(() -> changes.edit(id, note, OverrideChanges.date(until)), Done.SAVED);
    }

    /** Deletes the override with this id, then sends the browser to the Overrides view. */
    @POST
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public Response delete(@RestForm String id) {
        return change(() -> changes.delete(id), Done.DELETED);
    }

    /**
     * Makes the change and sends the browser to the Overrides view, which then says it is done; or shows the view with
     * why nothing changed.
     */
    private Response change(Runnable change, Done done) {
        try {
            change.run();
            return Response.seeOther(URI.create("/overrides?done=" + done.value))
                    .build();
        } catch (OverridesException e) {
            Response.Status status = switch (e.problem()) {
                case INVALID -> Response.Status.BAD_REQUEST;
                case NO_SUCH_OVERRIDE -> Response.Status.NOT_FOUND;
                case FILE -> Response.Status.SERVICE_UNAVAILABLE;
            };
            return Response.status(status).entity(page(e.getMessage(), null)).build();
        }
    }

    private TemplateInstance page(@Nullable String notice, @Nullable String done) {
        String file = config.dataDir().resolve(OverrideStore.FILE_NAME).toString();
        List<Listed> listed;
        try {
            listed = overrides.overrides();
        } catch (OverridesUnavailable e) {
            return Templates.overrides(List.of(), file, e.getMessage(), null, notice, done);
        }
        String groupsUnknown = listed.stream()
                .flatMap(override -> override.groupsUnknown().stream())
                .findFirst()
                .orElse(null);
        return Templates.overrides(listed.stream().map(this::row).toList(), file, null, groupsUnknown, notice, done);
    }

    private Row row(Listed listed) {
        KeepOverride override = listed.override();
        String status = listed.active()
                ? "Active"
                : listed.expired() && listed.stale()
                        ? "Expired, stale"
                        : listed.expired() ? "Expired" : listed.groupsKnown() ? "Stale" : "Unknown";
        return new Row(
                status,
                override.note(),
                override.until().map(Object::toString).orElse("no end date"),
                override.created()
                        .map(created -> CREATED.format(created.atZoneSameInstant(config.timeZone())))
                        .orElse(""),
                listed.groups().stream()
                        .map(group ->
                                group.stream().map(GroupVerdict.Member::name).toList())
                        .toList(),
                listed.groupsKnown(),
                String.join(", ", override.hashes()),
                override.id(),
                override.until().map(Object::toString).orElse(""));
    }
}
