# seedkeeper — spec

seedkeeper removes torrents from **Transmission** once they may leave: no file has a link into the media library any
more and every tracker's hit-and-run rule is met. It treats cross-seeded torrents as one group and never deletes data
another torrent still uses. It is a small web app in the style of Sonarr/Radarr: a scheduled run, a UI that shows what
it will do and why, and a read-only JSON API.

> This file is written to become `SPEC.md` in the public repo `Ghostwritertje/seedkeeper`, copied verbatim as its
> first commit. Until then it lives in the homelab repo. It is generic: nothing here knows about one particular
> homelab. How the homelab deploys and reports on it is in [homelab.md](homelab.md).

## 1. Language

These terms are used with exactly this meaning in the code, the UI and this spec.

| Term | Meaning |
|---|---|
| **Library link** | A downloaded file that shares its inode with a file under a library root. A link between two downloads (cross-seed symlink or hardlink) is not a library link, so the link count alone says nothing. |
| **Seed time** | Time actually spent seeding since the download finished: Transmission's `secondsSeeding`. Paused or stopped time doesn't count, like a tracker's hit-and-run counter. |
| **Minimum seed time** | The seed time a tracker requires before a torrent may leave. Set per tracker; default 30 days. |
| **Managed tracker** | A tracker in the config. Only its torrents may be removed by rule. |
| **Cross-seed group** | All torrents whose wanted files resolve (following symlinks) to the same inodes, directly or through another member (transitive). Removed as a whole. |
| **Keep override** | A manual instruction never to remove a group, until an optional end date (inclusive), with a note. It can only prevent a removal. |
| **Stale override** | A keep override none of whose info hashes matches a torrent any more. No effect; never deleted automatically. |
| **Expired override** | A keep override past its end date. No effect; never deleted automatically. |
| **Verdict** | What a run decides about a group: **Remove**, **Left alone** or **Keep**, always with a reason (§3). |
| **Missing data** | A finished torrent with a wanted file that resolves to nothing on disk. Reported, never removed by rule. |
| **Orphan** | A file or symlink under the downloads root that no torrent references. Shown, never deleted. |
| **Space freed** | For each file a run deleted whose link count was 1 (its last link), its size, counted once per inode. A deleted symlink or a file with other links frees nothing. |

## 2. Use cases

- **UC1** Keep a torrent from a managed tracker while any of its files has a library link.
- **UC2** Remove a torrent once none of its files has a library link and its seed time has reached its tracker's
  minimum.
- **UC3** A cross-seed group is removed as a whole, and only once **every** member has reached its own tracker's
  minimum. Removing one member frees no space, and a symlinked member breaks when the torrent holding the data leaves.
- **UC4** A torrent the tracker has permanently deleted (**unregistered**) is removed straight away, whatever its seed
  time or library link. This is the one case where one member leaves its group. Temporary tracker errors and local
  errors never cause a removal; they are only shown.
- Files are deleted only when no remaining torrent resolves to them (§4). Nothing under a library root is ever written.

Not in scope: removing more aggressively when the disk is full (the rules never tighten), deleting orphans, any
download client other than Transmission, a dry-run mode (the forecast in the UI plays that role), sending
notifications.

## 3. The rules

### 3.1 Inputs (one snapshot per run)

1. **Transmission** (JSON-RPC 2.0, Transmission ≥ 4.1; older → the run is refused and System says why):
   `session_get` (version, `incomplete_dir`, `incomplete_dir_enabled`) and `torrent_get` with: hash, name, status,
   percent done, error, error string, `seconds_seeding`, `is_private`, download dir, trackers (announce URLs), files
   (name, length) and file stats (wanted).
2. **Filesystem**: for every wanted file of every torrent, the `(dev, ino)` it resolves to, following symlinks.
   Candidate paths, in order: `downloadDir/name`, `downloadDir/name.part`, and if the incomplete dir is enabled
   `incompleteDir/name` and `incompleteDir/name.part`. The first that exists wins.
3. **Library index**: the set of `(dev, ino)` of every regular file under the configured library roots (walk without
   following symlinks).
4. **Config** (§6) and the **overrides file** (§7), as read at the start of the run.
5. **now**, in the configured time zone.

Nothing else. SQLite history is never an input: two runs on the same snapshot give the same verdicts.

### 3.2 Tracker attribution

Each tracker in the config lists its announce hosts. A torrent's announce hosts are mapped to trackers:

- all hosts map to exactly one managed tracker → that tracker;
- the torrent is not private (`is_private = false`) and the config has a `public` entry → the public tracker
  (minimum 1 day, so a fresh download is imported by Sonarr/Radarr first);
- anything else (no match, or hosts of two different trackers) → **unmanaged**.

### 3.3 Groups

Union-find over torrents: two torrents are in one group when any of their resolved wanted files share a `(dev, ino)`.
A torrent with no resolvable file is a group of its own. Files with `wanted = false` play no part anywhere.

### 3.4 Unregistered

A member is **unregistered** when `error = 2` (tracker error) and the error string, lower-cased:

1. contains `passkey` → **not** unregistered (`Passkey does not exist!` is an account problem);
2. otherwise matches one of the built-in patterns → unregistered.

The built-in list starts with the patterns from Cleanuparr's Transmission classifier and tqm (for example
`torrent not registered with this tracker`, `unregistered torrent`, `torrent not found`, `torrent does not exist`,
`infohash not found`, `torrent has been deleted`, `trumped`, `nuked`, `dupe`). It lives in code with a table test per
pattern; adding one is a pull request. `error = 2` that doesn't match is a **tracker error**: shown, no effect.

### 3.5 Verdict and precedence

`Decide(snapshot, config, overrides, now) → List<GroupVerdict>` is a pure function. For each group the first matching
rule wins. Every other reason that also holds is listed as a secondary reason.

1. **Remove (one member)**: a member is unregistered and not *Verifying* (status check-wait or checking). Only that
   member is removed (UC4). The rest of the group is then judged by rules 2–4 **in the same run**, without it.
2. **Left alone**: any member is *unmanaged*; any finished member has *Missing data (n of m files)*; any member has a
   *Local error* (`error = 3`).
3. **Keep**, with reason:
   - *Verifying*: a member is in check-wait or checking;
   - *Not finished*: a member has percent done < 1 (downloading, download-wait, or stopped before finishing). An
     unfinished member is never *Missing data*: its files may not exist yet;
   - *Keep override*: an override matches the group and has not expired;
   - *Library link (n of m files)*: any wanted file of any member has a library link;
   - *Minimum not reached*: a member's seed time is below its tracker's minimum.
4. **Remove (group)**: none of the above.

Not inputs: paused (a paused member's seed time just stands still), moved or renamed data (groups are rebuilt every
run), tracker errors (`error = 1`, or `error = 2` not unregistered), expired and stale overrides.

### 3.6 Forecast

Each **Keep** verdict with reason *Minimum not reached* carries an expected date: now + the largest remaining
`minimum − secondsSeeding` over the members, assuming continuous seeding. If the member that sets that date is paused,
there is no date: "clock stopped (paused)". Other Keep reasons have no date, except an override with an end date, which
shows it.

## 4. Removing and deleting

Per removal (one member for UC4, or every member of a group):

1. `torrent_remove` with `delete_local_data = false` for each torrent. If one call fails, stop this group, delete no
   files, record the error. The next run finds the rest still due and finishes it.
2. Re-read the torrent list and rebuild the set of `(dev, ino)` the remaining torrents resolve to.
3. For each path of each removed torrent (the candidate paths that exist, §3.1):
   - a **symlink**: delete it if no remaining torrent has that exact path;
   - a **regular file**: delete it if no remaining torrent resolves to its `(dev, ino)`. Count it towards **space
     freed** only if its link count was 1. A library link keeps the inode alive, so the library is never affected.
4. Delete directories that became empty, walking up from each deleted path to, but never including, the torrent's
   download dir (or incomplete dir).

An unregistered symlinked *original* therefore leaves, but its data stays until the last member leaves. No data is ever
moved. Never delete anything outside the downloads root (and incomplete dir), and nothing under a library root.

## 5. A run

1. Take the run lock. A scheduled run that finds it taken is skipped. **Run now** returns "already running".
2. `session_get`. If Transmission doesn't answer or is older than 4.1, the run ends `failed` with one reason. No
   verdicts.
3. Build the snapshot (§3.1), call `Decide`, carry out the removals (§4).
4. Update the overrides file: for each override whose matching group gained members, add their hashes (§7).
5. Write the history (§8) and the metrics (§10). Log one JSON line per action.

## 6. Configuration

`seedkeeper.yaml` (read with `quarkus-config-yaml` into a `@ConfigMapping`), plain and safe to commit:

```yaml
seedkeeper:
  schedule: "0 0 6 * * ?"          # Quarkus cron, in time-zone
  time-zone: Europe/Brussels
  transmission:
    url: http://transmission:9091/transmission/rpc
  downloads-root: /downloads        # orphans are looked for here; nothing outside it is deleted
  library-roots: [/library/movies, /library/tv]
  default-minimum-seed-time: 30d
  trackers:
    tracker-a:
      hosts: [tracker-a.example]
    tracker-b:
      hosts: [tracker-b.example, tracker-b.example.org]
      minimum-seed-time: 14d        # optional, else the default
  public:                           # optional; absent = public torrents are unmanaged
    minimum-seed-time: 1d
  auth:
    username: admin                 # password: env SEEDKEEPER_AUTH_PASSWORD
  data-dir: /data                   # overrides.yaml and seedkeeper.db
```

Secrets come only from the environment: `SEEDKEEPER_TRANSMISSION_USERNAME`, `SEEDKEEPER_TRANSMISSION_PASSWORD`, `SEEDKEEPER_AUTH_PASSWORD`,
`SEEDKEEPER_API_KEY`, `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` (stable, so logins survive restarts). Missing
secrets → the app doesn't start. Transmission's paths must be the same inside seedkeeper's container (mount the
downloads at the same path), otherwise everything shows as *Missing data*.

**Adding a tracker** is one entry under `trackers` and a restart. The Groups view shows the forecast for its torrents
right away, before anything is removed.

## 7. Keep overrides

`<data-dir>/overrides.yaml`, owned by the app, editable in the UI or by hand:

```yaml
overrides:
  - id: 7f3c…                       # generated
    hashes: [<info hash>, …]         # every member when it was set
    note: "Rewatching this season"
    until: 2027-03-01                # optional, inclusive, in time-zone
    created: 2026-11-20T10:12:00+01:00
```

- An override **matches** every current group that contains at least one of its hashes.
- Each run adds the hashes of new members of a matched group. Hashes are never removed.
- Read at the start of every run and reloaded when the file changes (file watcher), so the UI shows hand edits.
- Written in full and atomically (temp file in the same dir, then rename). Comments added by hand are lost.
- Expired and stale overrides stay, marked, until someone deletes them.

## 8. Storage

SQLite (`<data-dir>/seedkeeper.db`, Quarkiverse `quarkus-jdbc-sqlite`, Flyway migrations, plain JDBC). **History for
display only**: runs (start, end, status, error), removals (group, members with name, hash, tracker, reason, tracker
message, unfinished flag, space freed, files deleted), and per group the reason it has had since when (`reasonSince`).
Rows older than a year are deleted. Deleting the database loses only history.

## 9. UI and API

Server-rendered Qute templates, htmx and one classless CSS file, all shipped in the app (no npm, no CDN). The UI calls
the same service code as the API, not the API itself. Every page needs a login.

| View | Shows | Actions |
|---|---|---|
| **Groups** | Every group (managed, unmanaged, single-torrent), its members (name, tracker, seed time / minimum, status), library links, verdict, reason, secondary reasons, forecast. Filter by verdict and reason. | Set a keep override |
| **Activity** | Runs and what each removed, why, space freed, errors. | — |
| **Overrides** | All overrides, marked active / expired / stale, with the group they match. | Add, edit, delete |
| **Orphans** | Files and dangling symlinks under the downloads root no torrent references, listed separately with path, size, age and total. | — |
| **Settings** | The config, read-only. Secrets shown as "set" / "missing". | — |
| **System** | Version, last and next run, Transmission version and reachability. | **Run now** |

**JSON API** (`/api/v1`, Jackson), same data as the views, documented in the README:
`GET runs?since=`, `GET removals?since=`, `GET groups?verdict=&reason=` (with `reasonSince`; verdicts and reasons
as kebab-case codes: `remove`, `left-alone`, `keep`; `unregistered`, `unmanaged`, `missing-data`, `local-error`,
`verifying`, `not-finished`, `keep-override`, `library-link`, `minimum-not-reached`, `all-met`), `GET overrides` (with
`stale` and `expired`), `GET orphans`, `GET system`. Read-only: overrides and Run now are UI-only.

**Auth**: Quarkus form login (one user, encrypted session cookie). Our own `HttpAuthenticationMechanism` accepts
`X-Api-Key` or `?apikey=` on `/api/**` (constant-time compare). `quarkus-rest-csrf` on every form. `/q/health` and
`/q/metrics` need no auth.

## 10. Observability

- Logs: `quarkus-logging-json`, one line per run start/end, removal, deleted file and error.
- Metrics (`quarkus-micrometer`, Prometheus, `/q/metrics`): `seedkeeper_last_successful_run_timestamp_seconds`,
  `seedkeeper_runs_total{status}`, `seedkeeper_removed_groups_total`, `seedkeeper_space_freed_bytes_total`,
  `seedkeeper_groups{verdict,reason}`.
- Health: `/q/health` (Transmission reachability is a readiness check, not liveness).

## 11. Stack and build

Decided in [ADR 0004](https://github.com/Ghostwritertje/homelab/blob/main/docs/adr/0004-seedkeeper-in-java-quarkus-native.md)
(homelab repo, private; summarised here):

- Java 25, Quarkus, Maven wrapper. Records, no Lombok. Blocking code on virtual threads (`@RunOnVirtualThread`), no
  Mutiny. `quarkus-scheduler` (`@Scheduled`, skip when running), `quarkus-rest` + Jackson, Qute.
- Transmission: a Quarkus REST Client for JSON-RPC 2.0 behind our own interface. A filter handles the
  `X-Transmission-Session-Id` 409 handshake (one retry).
- Filesystem: `java.nio.file`, `unix:dev` / `unix:ino` / `unix:nlink` attributes.
- Native `linux/amd64` with Mandrel; image on `quay.io/quarkus/quarkus-micro-image`.
- **Tests**, run with `./mvnw verify` on a plain JDK (no Docker, no Dev Services):
  - `Decide` built test-first with JUnit 5 parameterized tables: one row per rule, per edge case in §3.5 and per
    classifier pattern;
  - the Transmission client against a fake on the JDK's `HttpServer` (including the 409 handshake);
  - groups, library links, deletion and space freed in `@TempDir` with real symlinks and hardlinks;
  - one `@QuarkusTest` end to end.
- **CI** (GitHub Actions): `verify` on every PR, the native build and `@QuarkusIntegrationTest` too. A tag `vX.Y.Z`
  publishes `ghcr.io/ghostwritertje/seedkeeper:X.Y.Z`. Dependabot monthly (Maven, Actions). Every PR is merged by a
  human after CI passes.

## 12. Build order

0. **Native spike**, before anything else: a tiny native binary, run on the target NAS, proves the SQLite driver,
   `unix:ino`, Jackson YAML and symlink resolution work in a native image. A part that fails gets a fallback (for
   example `stat` for inodes), nothing more.
1. Repo skeleton, CI (JVM + native), release workflow, Dependabot.
2. Domain model and `Decide` with its table tests (no I/O).
3. Snapshot: Transmission client, path resolution, library index, groups.
4. Removal and deletion (§4), space freed.
5. Run orchestration, scheduler, lock, config, overrides file.
6. SQLite history, JSON API, auth.
7. UI views.
8. Metrics, health, README (API documentation, config reference).
