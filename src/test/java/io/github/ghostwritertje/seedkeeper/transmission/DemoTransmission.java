package io.github.ghostwritertje.seedkeeper.transmission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A {@link FakeTransmission} with a realistic set of torrents, and their files on disk, for running the app by hand:
 * the {@code run-app} skill starts it. It covers every verdict and the cases the views have to show: a cross-seed
 * group, a library link, an unregistered torrent (one of them a cross-seed of a torrent that stays), a tracker error, a
 * torrent still downloading, a paused one, a public one, a torrent whose files are missing and orphans.
 *
 * <p>Argument: the root directory. It creates {@code downloads/}, {@code library/} and {@code data/} under it, so the
 * app runs with {@code seedkeeper.downloads-root=<root>/downloads}, {@code seedkeeper.library-roots=<root>/library}
 * and {@code seedkeeper.data-dir=<root>/data}. It prints {@code URL <rpc url>} and serves until it is killed.
 *
 * <p>The torrents' trackers are {@code tracker-a.example} and {@code tracker-b.example}/{@code
 * tracker-b.example.org}; configure them as managed trackers to get the verdicts the names suggest.
 */
public final class DemoTransmission {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACKER_A = "https://tracker-a.example/key/announce";
    private static final String TRACKER_B = "https://tracker-b.example/announce";
    private static final int SEEDING = 6;
    private static final int STOPPED = 0;
    private static final int DOWNLOADING = 4;

    private final Path downloads;
    private final ArrayNode torrents = JSON.createArrayNode();

    private DemoTransmission(Path downloads) {
        this.downloads = downloads;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: DemoTransmission <root directory>");
        }
        Path root = Path.of(args[0]);
        DemoTransmission demo = new DemoTransmission(Files.createDirectories(root.resolve("downloads")));
        demo.seed(Files.createDirectories(root.resolve("library")));
        Files.createDirectories(root.resolve("data"));

        // Serves until the process is killed.
        FakeTransmission fake = new FakeTransmission();
        fake.torrents(JSON.writeValueAsString(demo.torrents));
        System.out.println("URL " + fake.url());
        System.out.flush();
        Thread.currentThread().join();
    }

    private void seed(Path library) throws IOException {
        // Seeded long enough, but a library link keeps it.
        Path episode = file("The.Expanse.S01.1080p.BluRay.x264/e01.mkv");
        file("The.Expanse.S01.1080p.BluRay.x264/e02.mkv");
        Path link = library.resolve("tv/The Expanse/S01E01.mkv");
        Files.createDirectories(link.getParent());
        Files.createLink(link, episode);
        torrent(
                "a1b2c3d4e5f60718293a4b5c6d7e8f9012345601",
                "The.Expanse.S01.1080p.BluRay.x264",
                SEEDING,
                1,
                40,
                true,
                TRACKER_A,
                0,
                "",
                "The.Expanse.S01.1080p.BluRay.x264/e01.mkv",
                "The.Expanse.S01.1080p.BluRay.x264/e02.mkv");

        // A cross-seed group: the second torrent's file is a symlink to the first's.
        Path original = file("Arrival.2016.2160p.UHD.BluRay.REMUX/movie.mkv");
        Path cross = downloads.resolve("Arrival.2016.2160p.UHD.BluRay.REMUX-CROSS/movie.mkv");
        Files.createDirectories(cross.getParent());
        Files.createSymbolicLink(cross, original);
        torrent(
                "b1b2c3d4e5f60718293a4b5c6d7e8f9012345602",
                "Arrival.2016.2160p.UHD.BluRay.REMUX",
                SEEDING,
                1,
                65,
                true,
                TRACKER_A,
                0,
                "",
                "Arrival.2016.2160p.UHD.BluRay.REMUX/movie.mkv");
        torrent(
                "b1b2c3d4e5f60718293a4b5c6d7e8f9012345603",
                "Arrival.2016.2160p.UHD.BluRay.REMUX-CROSS",
                SEEDING,
                1,
                20,
                true,
                TRACKER_B,
                0,
                "",
                "Arrival.2016.2160p.UHD.BluRay.REMUX-CROSS/movie.mkv");

        // Short of tracker-b's minimum seed time: kept, with a forecast.
        file("Dune.Part.Two.2024.1080p.WEB-DL/movie.mkv");
        torrent(
                "c1b2c3d4e5f60718293a4b5c6d7e8f9012345604",
                "Dune.Part.Two.2024.1080p.WEB-DL",
                SEEDING,
                1,
                3,
                true,
                "https://tracker-b.example.org/announce",
                0,
                "",
                "Dune.Part.Two.2024.1080p.WEB-DL/movie.mkv");
        // An unregistered cross-seed of it: removed on its own, but its file stays, as the torrent above still uses it.
        torrent(
                "c1b2c3d4e5f60718293a4b5c6d7e8f9012345612",
                "Dune.Part.Two.2024.1080p.WEB-DL.Unregistered",
                SEEDING,
                1,
                3,
                true,
                TRACKER_A,
                2,
                "Unregistered torrent",
                "Dune.Part.Two.2024.1080p.WEB-DL/movie.mkv");

        // Unregistered by its tracker.
        file("Blade.Runner.1982.Final.Cut.720p/movie.mkv");
        torrent(
                "d1b2c3d4e5f60718293a4b5c6d7e8f9012345605",
                "Blade.Runner.1982.Final.Cut.720p",
                SEEDING,
                1,
                120,
                true,
                TRACKER_A,
                2,
                "Unregistered torrent",
                "Blade.Runner.1982.Final.Cut.720p/movie.mkv");

        // Still downloading.
        file("Severance.S02E03.2160p.WEB.H265/e03.mkv");
        torrent(
                "e1b2c3d4e5f60718293a4b5c6d7e8f9012345606",
                "Severance.S02E03.2160p.WEB.H265",
                DOWNLOADING,
                0.42,
                0,
                true,
                TRACKER_A,
                0,
                "",
                "Severance.S02E03.2160p.WEB.H265/e03.mkv");

        // Public, and of an unmanaged tracker.
        file("ubuntu-24.04.1-desktop-amd64.iso");
        torrent(
                "f1b2c3d4e5f60718293a4b5c6d7e8f9012345607",
                "ubuntu-24.04.1-desktop-amd64.iso",
                SEEDING,
                1,
                5,
                false,
                "udp://open.example:1337/announce",
                0,
                "",
                "ubuntu-24.04.1-desktop-amd64.iso");
        file("Some.Indie.Album.FLAC/01.flac");
        torrent(
                "0ab2c3d4e5f60718293a4b5c6d7e8f9012345608",
                "Some.Indie.Album.FLAC",
                SEEDING,
                1,
                400,
                true,
                "https://elsewhere.example/announce",
                0,
                "",
                "Some.Indie.Album.FLAC/01.flac");
        // A managed cross-seed of it: their group is left alone, and the unmanaged member is the one that causes it.
        torrent(
                "0bb2c3d4e5f60718293a4b5c6d7e8f9012345613",
                "Some.Indie.Album.FLAC.WEB",
                SEEDING,
                1,
                45,
                true,
                TRACKER_A,
                0,
                "",
                "Some.Indie.Album.FLAC/01.flac");

        // Paused.
        file("Paused.Documentary.2019.1080p/movie.mkv");
        torrent(
                "1ab2c3d4e5f60718293a4b5c6d7e8f9012345609",
                "Paused.Documentary.2019.1080p",
                STOPPED,
                1,
                9,
                true,
                TRACKER_B,
                0,
                "",
                "Paused.Documentary.2019.1080p/movie.mkv");

        // A tracker error that isn't unregistered: only shown.
        file("Old.Sitcom.S03.DVDRip/e01.avi");
        torrent(
                "2ab2c3d4e5f60718293a4b5c6d7e8f9012345610",
                "Old.Sitcom.S03.DVDRip",
                SEEDING,
                1,
                90,
                true,
                TRACKER_B,
                3,
                "Passkey does not exist!",
                "Old.Sitcom.S03.DVDRip/e01.avi");

        // Its files are gone from disk.
        torrent(
                "3ab2c3d4e5f60718293a4b5c6d7e8f9012345611",
                "Missing.Movie.2020.1080p",
                SEEDING,
                1,
                50,
                true,
                TRACKER_A,
                0,
                "",
                "Missing.Movie.2020.1080p/movie.mkv");

        // Orphans: files no torrent references, OS and NAS metadata, and dangling symlinks, a directory full of them.
        file("Leftover.Movie.2018/movie.mkv");
        file("random-notes.txt");
        file("Leftover.Movie.2018/.DS_Store");
        file("Leftover.Movie.2018/@eaDir/movie.mkv@SynoEAStream");
        file("Thumbs.db");
        Files.createSymbolicLink(downloads.resolve("dangling.mkv"), downloads.resolve("nowhere.mkv"));
        Path crossSeed = Files.createDirectories(downloads.resolve("cross-seed"));
        for (int number = 1; number <= 12; number++) {
            String name = "Gone.Show.S01E%02d.1080p.mkv".formatted(number);
            Files.createSymbolicLink(crossSeed.resolve(name), Path.of("../Gone.Show.S01/" + name));
        }
    }

    /** A file of a few KB to a few MB, its size fixed by its name. */
    private Path file(String relative) throws IOException {
        Path path = downloads.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[4096 * (1 + Math.floorMod(relative.hashCode(), 500))]);
        return path;
    }

    private void torrent(
            String hash,
            String name,
            int status,
            double percentDone,
            long daysSeeding,
            boolean isPrivate,
            String announce,
            int error,
            String errorString,
            String... files)
            throws IOException {
        ObjectNode torrent = torrents.addObject()
                .put("hash_string", hash)
                .put("name", name)
                .put("status", status)
                .put("percent_done", percentDone)
                .put("error", error)
                .put("error_string", errorString)
                .put("seconds_seeding", Duration.ofDays(daysSeeding).toSeconds())
                .put("is_private", isPrivate)
                .put("download_dir", downloads.toString());
        torrent.putArray("trackers").addObject().put("announce", announce);
        ArrayNode list = torrent.putArray("files");
        ArrayNode stats = torrent.putArray("file_stats");
        for (String file : files) {
            Path path = downloads.resolve(file);
            list.addObject().put("name", file).put("length", Files.exists(path) ? Files.size(path) : 4096);
            stats.addObject().put("wanted", true);
        }
    }
}
