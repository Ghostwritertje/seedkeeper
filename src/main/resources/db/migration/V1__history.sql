-- The run history (spec §8). Display only: nothing here is ever an input to a verdict.
-- Moments are milliseconds since the epoch (UTC).

CREATE TABLE run (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    started INTEGER NOT NULL,
    ended   INTEGER NOT NULL,
    status  TEXT    NOT NULL,
    error   TEXT
);

CREATE INDEX run_started ON run (started);

-- One removal of a run: the torrents that had to leave together.
CREATE TABLE removal (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id        INTEGER NOT NULL REFERENCES run (id),
    reason        TEXT    NOT NULL,
    error         TEXT,
    space_freed   INTEGER NOT NULL,
    files_deleted INTEGER NOT NULL,
    paths_refused INTEGER NOT NULL
);

CREATE INDEX removal_run ON removal (run_id);

CREATE TABLE removal_member (
    removal_id      INTEGER NOT NULL REFERENCES removal (id),
    position        INTEGER NOT NULL,
    hash            TEXT    NOT NULL,
    name            TEXT    NOT NULL,
    tracker         TEXT,
    tracker_message TEXT,
    unfinished      INTEGER NOT NULL,
    removed         INTEGER NOT NULL,
    PRIMARY KEY (removal_id, position)
);

-- The reason the group of a torrent had at the last successful run, and the start of the run that first saw it.
CREATE TABLE reason_since (
    hash   TEXT    PRIMARY KEY,
    reason TEXT    NOT NULL,
    since  INTEGER NOT NULL
);
