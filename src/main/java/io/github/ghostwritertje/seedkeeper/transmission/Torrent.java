package io.github.ghostwritertje.seedkeeper.transmission;

import java.util.List;

/**
 * One torrent as Transmission reports it ({@code torrent_get}).
 *
 * @param hash           the info hash
 * @param percentDone    0 to 1; 1 is finished
 * @param error          0 none, 1 tracker warning, 2 tracker error, 3 local error
 * @param secondsSeeding the seed time: time actually spent seeding
 * @param downloadDir    the directory the files are in, as Transmission sees it
 * @param trackers       the announce URLs
 */
public record Torrent(String hash, String name, Status status, double percentDone, int error, String errorString,
        long secondsSeeding, boolean isPrivate, String downloadDir, List<String> trackers, List<File> files) {

    public Torrent {
        trackers = List.copyOf(trackers);
        files = List.copyOf(files);
    }

    public boolean finished() {
        return percentDone >= 1;
    }

    /**
     * @param name   path relative to the download dir
     * @param wanted false when the user deselected the file; such a file plays no part anywhere
     */
    public record File(String name, long length, boolean wanted) {
    }

    /** Transmission's status numbers 0 to 6, in that order. */
    public enum Status {
        STOPPED, CHECK_WAIT, CHECKING, DOWNLOAD_WAIT, DOWNLOADING, SEED_WAIT, SEEDING;

        public static Status of(int number) {
            if (number < 0 || number >= values().length) {
                throw new IllegalArgumentException("Unknown Transmission status " + number);
            }
            return values()[number];
        }
    }
}
