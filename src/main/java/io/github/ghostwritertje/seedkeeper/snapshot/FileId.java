package io.github.ghostwritertje.seedkeeper.snapshot;

/** What a file is on disk, whatever its path: its device and inode. Two paths with the same id are links of one file. */
public record FileId(long dev, long ino) {
}
