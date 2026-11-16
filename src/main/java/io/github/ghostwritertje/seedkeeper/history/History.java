package io.github.ghostwritertje.seedkeeper.history;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.decide.TrackerError;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The run history (spec §8): the runs, what each removed, and since when each group has its reason. Kept in SQLite,
 * with plain JDBC, on a database that {@link HistoryKeeper} has migrated.
 *
 * <p>For display only. Nothing read from here is ever an input to a verdict, and the history may be lost: deleting
 * the database loses nothing else.
 */
@ApplicationScoped
public class History {

    /**
     * A run as it was recorded.
     *
     * @param id       counts up: a later run has a higher id
     * @param status   {@code succeeded} or {@code failed}
     * @param error    the one reason of a failed run
     * @param removals what the run carried out, in the order it did
     */
    public record RecordedRun(
            long id,
            Instant started,
            Instant ended,
            String status,
            Optional<String> error,
            List<RecordedRemoval> removals) {

        public RecordedRun {
            removals = List.copyOf(removals);
        }

        /** The removals carried out in full. */
        public int groupsRemoved() {
            return (int) removals.stream()
                    .filter(removal -> removal.error().isEmpty())
                    .count();
        }

        /** The torrents removed from Transmission, also those of a removal that then went wrong. */
        public int torrentsRemoved() {
            return (int) removals.stream()
                    .flatMap(removal -> removal.members().stream())
                    .filter(RecordedMember::removed)
                    .count();
        }

        public int filesDeleted() {
            return removals.stream().mapToInt(RecordedRemoval::filesDeleted).sum();
        }

        public long spaceFreed() {
            return removals.stream().mapToLong(RecordedRemoval::spaceFreed).sum();
        }
    }

    /**
     * One removal of a run: the torrents that had to leave together.
     *
     * @param started      when its run started
     * @param reason       the kebab-case code of the reason they had to leave
     * @param error        what went wrong; then no file was deleted
     * @param spaceFreed   in bytes
     * @param filesDeleted how many files and symlinks were deleted
     * @param pathsRefused how many paths were due but not deleted
     */
    public record RecordedRemoval(
            long runId,
            Instant started,
            String reason,
            Optional<String> error,
            long spaceFreed,
            int filesDeleted,
            int pathsRefused,
            List<RecordedMember> members) {

        public RecordedRemoval {
            members = List.copyOf(members);
        }
    }

    /**
     * @param tracker        the tracker the torrent belonged to; empty when it was unmanaged
     * @param trackerMessage what the tracker said about the torrent (Transmission's error string of a tracker
     *                       warning or error); empty when it said nothing
     * @param unfinished     true when the download was not complete
     * @param removed        true when Transmission removed it; false when the removal went wrong before or at it
     */
    public record RecordedMember(
            String hash,
            String name,
            Optional<String> tracker,
            Optional<String> trackerMessage,
            boolean unfinished,
            boolean removed) {}

    /** Since when each group has its reason, as the last successful run left it. */
    public static final class ReasonsSince {

        /** No group has a date. */
        public static final ReasonsSince NONE = new ReasonsSince(Map.of());

        private record Entry(String reason, Instant since) {}

        private final Map<String, Entry> byHash;

        private ReasonsSince(Map<String, Entry> byHash) {
            this.byHash = byHash;
        }

        /**
         * The start of the first of the unbroken row of successful runs that gave this group this reason, up to the
         * last run. Empty when the last successful run didn't see the group with this reason: it is new, or its
         * reason has changed since.
         *
         * <p>A group is followed through its members: one that gains or loses a member keeps its date as long as
         * its reason stays the same.
         */
        public Optional<Instant> of(GroupVerdict group) {
            return group.members().stream()
                    .map(member -> byHash.get(member.hash()))
                    .filter(entry -> entry != null
                            && entry.reason().equals(group.reason().code()))
                    .map(Entry::since)
                    .min(Instant::compareTo);
        }
    }

    /** The history can't be read or written. The message is fit to show to the user as it is. */
    public static class HistoryException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        HistoryException(String what, Throwable cause) {
            super(what + ": " + cause.getMessage(), cause);
        }
    }

    private final DataSource database;

    /** @param database a database with the tables of {@code db/migration} */
    public History(DataSource database) {
        this.database = database;
    }

    /**
     * Writes one run, and deletes the runs that started more than a year before it. A successful run also sets
     * since when each group has its reason; a failed run has no verdicts and leaves that as it is.
     */
    public void record(RunOutcome outcome) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long run = insertRun(connection, outcome);
                Map<String, GroupVerdict.Member> members = new HashMap<>();
                for (GroupVerdict verdict : outcome.verdicts()) {
                    verdict.members().forEach(member -> members.putIfAbsent(member.hash(), member));
                }
                for (Removal removal : outcome.removals()) {
                    insertRemoval(connection, run, removal, members);
                }
                if (outcome.status() == Status.SUCCEEDED) {
                    replaceReasonsSince(connection, outcome);
                }
                deleteRunsBefore(connection, outcome.started().minusYears(1).toInstant());
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new HistoryException("The run could not be written to the history", e);
        }
    }

    /** The runs that started at or after {@code since} (all of them when empty), the newest first. */
    public List<RecordedRun> runs(Optional<Instant> since) {
        long from = since.map(Instant::toEpochMilli).orElse(Long.MIN_VALUE);
        try (Connection connection = database.getConnection()) {
            Map<Long, List<RecordedMember>> members = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT m.removal_id, m.hash, m.name, m.tracker, m.tracker_message, m.unfinished, m.removed
                    FROM removal_member m
                    JOIN removal r ON r.id = m.removal_id
                    JOIN run ON run.id = r.run_id
                    WHERE run.started >= ?
                    ORDER BY m.removal_id, m.position
                    """)) {
                select.setLong(1, from);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        members.computeIfAbsent(rows.getLong(1), id -> new ArrayList<>())
                                .add(new RecordedMember(
                                        rows.getString(2),
                                        rows.getString(3),
                                        Optional.ofNullable(rows.getString(4)),
                                        Optional.ofNullable(rows.getString(5)),
                                        rows.getBoolean(6),
                                        rows.getBoolean(7)));
                    }
                }
            }
            Map<Long, List<RecordedRemoval>> removals = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT r.id, r.run_id, run.started, r.reason, r.error, r.space_freed, r.files_deleted,
                           r.paths_refused
                    FROM removal r
                    JOIN run ON run.id = r.run_id
                    WHERE run.started >= ?
                    ORDER BY r.id
                    """)) {
                select.setLong(1, from);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        removals.computeIfAbsent(rows.getLong(2), id -> new ArrayList<>())
                                .add(new RecordedRemoval(
                                        rows.getLong(2),
                                        Instant.ofEpochMilli(rows.getLong(3)),
                                        rows.getString(4),
                                        Optional.ofNullable(rows.getString(5)),
                                        rows.getLong(6),
                                        rows.getInt(7),
                                        rows.getInt(8),
                                        members.getOrDefault(rows.getLong(1), List.of())));
                    }
                }
            }
            List<RecordedRun> runs = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, started, ended, status, error FROM run WHERE started >= ? ORDER BY started DESC, id DESC
                    """)) {
                select.setLong(1, from);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        runs.add(new RecordedRun(
                                rows.getLong(1),
                                Instant.ofEpochMilli(rows.getLong(2)),
                                Instant.ofEpochMilli(rows.getLong(3)),
                                rows.getString(4),
                                Optional.ofNullable(rows.getString(5)),
                                removals.getOrDefault(rows.getLong(1), List.of())));
                    }
                }
            }
            return runs;
        } catch (SQLException e) {
            throw new HistoryException("The history could not be read", e);
        }
    }

    /** The run that started last; empty when there is none. */
    public Optional<RecordedRun> lastRun() {
        return newest("SELECT started FROM run ORDER BY started DESC, id DESC LIMIT 1")
                .flatMap(started -> runs(Optional.of(started)).stream().findFirst());
    }

    /** When the last run that succeeded ended; empty when none has. */
    public Optional<Instant> lastSuccessfulRunEnded() {
        return newest("SELECT ended FROM run WHERE status = 'succeeded' ORDER BY ended DESC LIMIT 1");
    }

    /** The moment of the one row the query gives, as epoch milliseconds; empty when it gives none. */
    private Optional<Instant> newest(String sql) {
        try (Connection connection = database.getConnection();
                Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery(sql)) {
            return rows.next() ? Optional.of(Instant.ofEpochMilli(rows.getLong(1))) : Optional.empty();
        } catch (SQLException e) {
            throw new HistoryException("The history could not be read", e);
        }
    }

    /** The removals of the runs that started at or after {@code since} (all when empty), the newest run first. */
    public List<RecordedRemoval> removals(Optional<Instant> since) {
        return runs(since).stream().flatMap(run -> run.removals().stream()).toList();
    }

    /** Since when each group has its reason, as the last successful run left it. */
    public ReasonsSince reasonsSince() {
        try (Connection connection = database.getConnection()) {
            return new ReasonsSince(reasonsSince(connection));
        } catch (SQLException e) {
            throw new HistoryException("The history could not be read", e);
        }
    }

    private static long insertRun(Connection connection, RunOutcome outcome) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO run (started, ended, status, error) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, outcome.started().toInstant().toEpochMilli());
            insert.setLong(2, outcome.ended().toInstant().toEpochMilli());
            insert.setString(3, outcome.status().code());
            setNullable(insert, 4, outcome.error());
            insert.executeUpdate();
            return generatedKey(insert);
        }
    }

    private static void insertRemoval(
            Connection connection, long run, Removal removal, Map<String, GroupVerdict.Member> members)
            throws SQLException {
        long id;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO removal (run_id, reason, error, space_freed, files_deleted, paths_refused)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, run);
            insert.setString(2, removal.reason().code());
            setNullable(insert, 3, removal.error());
            insert.setLong(4, removal.deleted().spaceFreed());
            insert.setInt(5, removal.deleted().files().size());
            insert.setInt(6, removal.deleted().refused().size());
            insert.executeUpdate();
            id = generatedKey(insert);
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO removal_member
                    (removal_id, position, hash, name, tracker, tracker_message, unfinished, removed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int position = 0;
            for (Torrent torrent : removal.torrents()) {
                insert.setLong(1, id);
                insert.setInt(2, position++);
                insert.setString(3, torrent.hash());
                insert.setString(4, torrent.name());
                setNullable(
                        insert,
                        5,
                        Optional.ofNullable(members.get(torrent.hash()))
                                .flatMap(GroupVerdict.Member::tracker)
                                .map(Tracker::name));
                setNullable(insert, 6, trackerMessage(torrent));
                insert.setBoolean(7, !torrent.finished());
                insert.setBoolean(
                        8,
                        removal.removed().stream()
                                .anyMatch(removed -> removed.hash().equals(torrent.hash())));
                insert.executeUpdate();
            }
        }
    }

    /** What the tracker said: Transmission's error string of a tracker warning (1) or tracker error (2). */
    private static Optional<String> trackerMessage(Torrent torrent) {
        return TrackerError.of(torrent.error(), torrent.errorString())
                .map(TrackerError::message)
                .filter(message -> !message.isBlank());
    }

    /**
     * A group keeps the date it had when the run before gave it the same reason; otherwise it has its reason since
     * this run. Torrents this run didn't see are forgotten.
     */
    private static void replaceReasonsSince(Connection connection, RunOutcome outcome) throws SQLException {
        ReasonsSince before = new ReasonsSince(reasonsSince(connection));
        Instant now = outcome.started().toInstant();
        try (Statement delete = connection.createStatement()) {
            delete.executeUpdate("DELETE FROM reason_since");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR REPLACE INTO reason_since (hash, reason, since) VALUES (?, ?, ?)")) {
            for (GroupVerdict verdict : outcome.verdicts()) {
                long since = before.of(verdict).orElse(now).toEpochMilli();
                for (GroupVerdict.Member member : verdict.members()) {
                    insert.setString(1, member.hash());
                    insert.setString(2, verdict.reason().code());
                    insert.setLong(3, since);
                    insert.executeUpdate();
                }
            }
        }
    }

    private static Map<String, ReasonsSince.Entry> reasonsSince(Connection connection) throws SQLException {
        Map<String, ReasonsSince.Entry> byHash = new LinkedHashMap<>();
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery("SELECT hash, reason, since FROM reason_since")) {
            while (rows.next()) {
                byHash.put(
                        rows.getString(1),
                        new ReasonsSince.Entry(rows.getString(2), Instant.ofEpochMilli(rows.getLong(3))));
            }
        }
        return byHash;
    }

    private static void deleteRunsBefore(Connection connection, Instant moment) throws SQLException {
        String old = "SELECT id FROM run WHERE started < ?";
        for (String sql : List.of(
                "DELETE FROM removal_member WHERE removal_id IN (SELECT id FROM removal WHERE run_id IN (" + old + "))",
                "DELETE FROM removal WHERE run_id IN (" + old + ")",
                "DELETE FROM run WHERE started < ?")) {
            try (PreparedStatement delete = connection.prepareStatement(sql)) {
                delete.setLong(1, moment.toEpochMilli());
                delete.executeUpdate();
            }
        }
    }

    private static long generatedKey(PreparedStatement insert) throws SQLException {
        try (ResultSet keys = insert.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("The database gave no id for the row it inserted");
            }
            return keys.getLong(1);
        }
    }

    private static void setNullable(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }
}
