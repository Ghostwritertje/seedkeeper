package io.github.ghostwritertje.seedkeeper.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.transmission.StubTransmission;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One removal (spec §4) when the torrent list can't be read again right after it. */
class RemoverTest {

    @TempDir
    Path root;

    private Path downloads;
    private Path library;
    private Path movie;
    private Torrent torrent;
    private final StubTransmission transmission = new StubTransmission();

    @BeforeEach
    void aTorrentWithAFile() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        library = Files.createDirectory(root.resolve("library"));
        movie = downloads.resolve("Movie/movie.mkv");
        Files.createDirectories(movie.getParent());
        Files.writeString(movie, "data");
        torrent = new Torrent("a", "Movie", Torrent.Status.SEEDING, 1, 0, "", 0, true, downloads.toString(),
                List.of(), List.of(new Torrent.File("Movie/movie.mkv", 4, true)));
        transmission.add(torrent);
    }

    @Test
    void aTorrentListThatFailsOnceOrTwiceIsReadAgainAndTheFilesAreDeleted() {
        transmission.failReadsAfterRemoval(2);

        Removal removal = remove();

        assertEquals(Optional.empty(), removal.error());
        assertEquals(List.of(movie), removal.deleted().files());
        assertFalse(Files.exists(movie));
    }

    @Test
    void aTorrentListThatCannotBeReadAgainDeletesNothingAndSaysSo() {
        transmission.failReadsAfterRemoval(100);

        Removal removal = remove();

        assertEquals(List.of("a"), transmission.removed());
        assertEquals(List.of(torrent), removal.removed());
        assertEquals(Optional.of("Removed from Transmission, but the torrent list could not be read again after 4"
                + " tries (Transmission does not answer torrent_get): what the removed"
                + " torrents leave behind on disk is now an orphan"), removal.error());
        assertEquals(List.of(), removal.deleted().files());
        assertTrue(Files.exists(movie));
    }

    private Removal remove() {
        return new Remover(transmission, new Deletion(downloads, List.of(library)), Duration.ZERO)
                .remove(Reason.ALL_MET, List.of(torrent), Optional.empty());
    }
}
