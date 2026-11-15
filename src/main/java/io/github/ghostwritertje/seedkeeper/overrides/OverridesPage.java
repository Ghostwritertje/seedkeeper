package io.github.ghostwritertje.seedkeeper.overrides;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.InvalidOverride;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.Listed;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.NoSuchOverride;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.OverridesUnavailable;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestForm;

/**
 * The Overrides view, with its forms to add, edit and delete an override. The forms post with the CSRF token of the
 * page and need a login: changing overrides is not in the API.
 */
@Path("/overrides")
public class OverridesPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param file        the path of the overrides file
         * @param unavailable why the overrides can't be listed right now; null when they can
         * @param notice      why the form that was just posted changed nothing; null when there is nothing to tell
         */
        static native TemplateInstance overrides(List<Row> overrides, String file, String unavailable,
                String notice);
    }

    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * One override, as text ready to show.
     *
     * @param status    "Active", "Expired", "Stale" or "Expired, stale"
     * @param until     "2027-03-01" or "no end date"
     * @param created   "2026-11-20 10:12" in the configured time zone; empty when the file doesn't say
     * @param groups    the names of the members of every group it matches; empty when it is stale
     * @param hashes    separated by commas
     * @param untilDate "2027-03-01", or empty when it never ends: the value of the date field of the edit form
     */
    public record Row(String status, String note, String until, String created, List<List<String>> groups,
            String hashes, String id, String untilDate) {
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
        return page(null);
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
        return change(() -> overrides.add(hashes == null ? List.of() : Arrays.asList(hashes.split("[\\s,]+")),
                note, date(until)));
    }

    /** Changes the note and the end date of the override with this id, then sends the browser to the view. */
    @POST
    @Path("/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public Response edit(@RestForm String id, @RestForm String note, @RestForm String until) {
        return change(() -> overrides.edit(id, note, date(until)));
    }

    /** Deletes the override with this id, then sends the browser to the Overrides view. */
    @POST
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public Response delete(@RestForm String id) {
        return change(() -> overrides.delete(id));
    }

    /** Makes the change and sends the browser to the Overrides view; or shows it with why nothing changed. */
    private Response change(Runnable change) {
        try {
            change.run();
            return Response.seeOther(URI.create("/overrides")).build();
        } catch (InvalidOverride e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(page(e.getMessage())).build();
        } catch (NoSuchOverride e) {
            return Response.status(Response.Status.NOT_FOUND).entity(page(e.getMessage())).build();
        } catch (OverridesException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(page(e.getMessage())).build();
        }
    }

    /**
     * The end date of a form: empty or like 2027-03-01, as a date field sends it.
     *
     * @throws InvalidOverride when it is neither
     */
    public static Optional<LocalDate> date(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text.strip()));
        } catch (DateTimeParseException e) {
            throw new InvalidOverride("The end date is not a date like 2027-03-01: " + text.strip());
        }
    }

    private TemplateInstance page(String notice) {
        String file = config.dataDir().resolve(OverrideStore.FILE_NAME).toString();
        try {
            return Templates.overrides(overrides.overrides().stream().map(this::row).toList(), file, null, notice);
        } catch (OverridesUnavailable e) {
            return Templates.overrides(List.of(), file, e.getMessage(), notice);
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
                String.join(", ", override.hashes()), override.id(),
                override.until().map(Object::toString).orElse(""));
    }
}
