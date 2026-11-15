package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** The Groups view. */
@Path("/groups")
public class GroupsPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param unavailable why there are no verdicts right now; null when there are
         */
        static native TemplateInstance groups(List<Group> groups, String unavailable);
    }

    /** One group, as text ready to show. */
    public record Group(String verdict, String reason, String libraryLinks, List<Member> members) {
    }

    /**
     * @param tracker  "unmanaged" when the torrent has no tracker
     * @param seedTime "40d / 30d": the seed time and the tracker's minimum; the seed time alone when unmanaged
     */
    public record Member(String name, String tracker, String seedTime, String status) {
    }

    private final GroupsService groups;

    GroupsPage(GroupsService groups) {
        this.groups = groups;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance groups() {
        try {
            return Templates.groups(groups.groups().stream().map(GroupsPage::group).toList(), null);
        } catch (GroupsUnavailable e) {
            return Templates.groups(List.of(), e.getMessage());
        }
    }

    private static Group group(GroupVerdict group) {
        String libraryLinks = group.libraryLinks() + " of " + group.files() + " files";
        String reason = group.reason() == Reason.LIBRARY_LINK
                ? group.reason().label() + " (" + libraryLinks + ")"
                : group.reason().label();
        return new Group(group.verdict().label(), reason, libraryLinks,
                group.members().stream().map(GroupsPage::member).toList());
    }

    private static Member member(GroupVerdict.Member member) {
        String seedTime = StatusText.duration(member.seedTime());
        return new Member(member.name(), member.tracker().map(Tracker::name).orElse("unmanaged"),
                member.tracker().map(tracker -> seedTime + " / " + StatusText.duration(tracker.minimumSeedTime()))
                        .orElse(seedTime),
                StatusText.label(member.status()));
    }
}
