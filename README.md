# seedkeeper

seedkeeper removes torrents from Transmission once they may leave: no file has a link into the media library any more
and every tracker's hit-and-run rule is met. It treats cross-seeded torrents as one group and never deletes data
another torrent still uses. It is a small web app with a scheduled run, a UI that shows what it will do and why, and a
read-only JSON API.

**Status: under construction.** So far seedkeeper connects to Transmission, behind a login and an API key. It
shows, per cross-seed group, the verdict it would give and when a group kept for its minimum seed time may leave. It
removes nothing yet, and it doesn't know unregistered torrents or keep overrides yet; see the
[spec](https://github.com/Ghostwritertje/seedkeeper/issues/5) for what is being built and in which order.

## Configuration

seedkeeper needs Transmission 4.1 or newer (it speaks JSON-RPC 2.0, which older versions don't).

Settings that are safe to commit go in `seedkeeper.yaml`, mounted in the container as `/work/config/application.yaml`:

```yaml
seedkeeper:
  schedule: "0 0 6 * * ?"                            # Quarkus cron, in time-zone
  time-zone: Europe/Brussels
  transmission:
    url: http://transmission:9091/transmission/rpc   # Transmission's RPC endpoint
  downloads-root: /downloads                         # orphans are looked for here
  library-roots: [/library/movies, /library/tv]
  default-minimum-seed-time: 30d
  trackers:
    tracker-a:
      hosts: [tracker-a.example]
    tracker-b:
      hosts: [tracker-b.example, tracker-b.example.org]
      minimum-seed-time: 14d                         # optional, else the default
  public:                                            # optional
    minimum-seed-time: 1d
  auth:
    username: admin                                  # the password comes from the environment
  data-dir: /data
```

| Key | Required | Meaning |
|---|---|---|
| `seedkeeper.schedule` | no, `0 0 6 * * ?` | When the scheduled run starts: a Quarkus cron expression (seconds, minutes, hours, day of month, month, day of week), in the time zone below. Not used yet: nothing runs on a schedule so far. |
| `seedkeeper.time-zone` | no, `UTC` | The time zone of the schedule and of the dates seedkeeper shows, as a tz database name, for example `Europe/Brussels`. seedkeeper doesn't start with a name that doesn't exist. |
| `seedkeeper.transmission.url` | yes | URL of Transmission's RPC endpoint. |
| `seedkeeper.downloads-root` | no, `/downloads` | The directory Transmission downloads into. [Orphans](#get-apiv1orphans) are looked for under it. |
| `seedkeeper.library-roots` | yes | The directories of the media library. A downloaded file that has a hardlink under one of them has a **library link**, which keeps its torrent. Symlinks under a library root don't count and are not followed. When a root can't be read there are no verdicts at all. |
| `seedkeeper.default-minimum-seed-time` | no, `30d` | The minimum seed time of a tracker that doesn't set its own. |
| `seedkeeper.trackers.<name>.hosts` | yes, per tracker | The hosts of the tracker's announce URLs, matched exactly (a subdomain is another host). A torrent belongs to the tracker when every one of its announce hosts is in this list. |
| `seedkeeper.trackers.<name>.minimum-seed-time` | no | The seed time the tracker requires before a torrent may leave; the default when absent. |
| `seedkeeper.public.minimum-seed-time` | no | Adds the tracker `public`: every torrent that is not private and belongs to no single managed tracker gets it, with this minimum. Without the `public` entry such torrents are unmanaged. |
| `seedkeeper.auth.username` | no, `admin` | Username of seedkeeper's login. |
| `seedkeeper.data-dir` | no, `/data` | The directory seedkeeper keeps its own files in. Not used yet. |

The [Settings page](#pages) shows the value of every key seedkeeper runs with, defaults included.

A duration is a number with a unit: `30d`, `12h`, `90m`; a bare number is seconds.

A torrent that belongs to no tracker (none of its hosts is listed, or its hosts are of two trackers) is **unmanaged**:
seedkeeper leaves it alone. Seed time is Transmission's `secondsSeeding`: time actually spent seeding, so it stands
still while a torrent is paused.

## Cross-seed groups

Torrents that share data are one **cross-seed group**: two torrents are in one group when a wanted file of the one is
the same file on disk as a wanted file of the other, through a symlink or a hardlink, directly or through a third
torrent. A file deselected in Transmission joins no group. A torrent whose files are nowhere on disk is a group of
its own.

A group gets one verdict. It is removed only when **every** member has met the minimum seed time of its own tracker,
and a library link on a file of any member keeps the whole group. One unmanaged member leaves the whole group alone.

### Verdicts and reasons

A verdict is **Remove** (`remove`), **Left alone** (`left-alone`: seedkeeper doesn't judge the group, someone should
look at it) or **Keep** (`keep`: not yet). Each comes with a reason. The rules are tried in this order and the first
that holds for any member decides; every other rule that holds too is listed as a **secondary reason**, in the same
order.

| Verdict | Reason | Code | Holds when |
|---|---|---|---|
| Left alone | Unmanaged | `unmanaged` | A member belongs to no tracker of the config. |
| Left alone | Missing data (n of m files) | `missing-data` | A wanted file of a finished member is nowhere on disk. A member that isn't finished is never missing data: its files may not exist yet. |
| Left alone | Local error | `local-error` | Transmission reports a local error on a member (its `error` is 3), for example files it can't read. |
| Keep | Verifying | `verifying` | A member is being verified or is queued for it (status `check-wait` or `checking`). |
| Keep | Not finished | `not-finished` | A member's download isn't complete. |
| Keep | Library link (n of m files) | `library-link` | A wanted file of a member has a library link. |
| Keep | Minimum not reached | `minimum-not-reached` | A member's seed time is below its tracker's minimum. |
| Remove | All met | `all-met` | None of the above. |

Two more reasons are in the spec and not built yet: `unregistered` (remove one member the tracker has deleted) and
`keep-override` (keep, by hand).

These play no part: a paused member (its seed time just stands still), a tracker warning or tracker error (`error` 1
or 2), and data that was moved or renamed (groups are rebuilt from the disk every time). The same torrents and files
always give the same verdicts: nothing seedkeeper remembers is an input.

A group kept because a minimum is not reached has a **forecast**: now plus the longest seed time any member still has
to go, which is when the group may leave if it keeps seeding. When the member with the longest time to go is paused
there is no date: its clock is stopped.

Transmission's paths must be the same inside seedkeeper's container: mount the downloads at the same path as in
Transmission's container, and the library on the same filesystem as the downloads, or no library link is seen.

Secrets come from the environment only. seedkeeper doesn't start when one is missing:

| Environment variable | Meaning |
|---|---|
| `SEEDKEEPER_TRANSMISSION_USERNAME` | Transmission's RPC username. |
| `SEEDKEEPER_TRANSMISSION_PASSWORD` | Transmission's RPC password. |
| `SEEDKEEPER_AUTH_PASSWORD` | Password of seedkeeper's own [login](#login). |
| `SEEDKEEPER_API_KEY` | [Key for the JSON API](#api-key). Pick a long random one, for example `openssl rand -hex 32`. |
| `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` | Encrypts the session cookie; at least 16 characters. Keep it the same across restarts, so logins survive them. |

An environment variable also overrides a key of the file: `SEEDKEEPER_TRANSMISSION_URL` wins over
`seedkeeper.transmission.url`.

## Login

Every page needs a login. There is one user: the username is `seedkeeper.auth.username` (default `admin`), the
password is `SEEDKEEPER_AUTH_PASSWORD`. Without a login a page redirects to the form at `/login`; **Log out** is at
the top of every page.

A login is kept in a cookie encrypted with `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY`, so it survives a restart of
seedkeeper as long as that key stays the same. It ends after 30 minutes without a request. Changing the key logs
everyone out.

Every form carries a CSRF token; a form POST without it is rejected with 400.

## Pages

| Page | Shows |
|---|---|
| `/groups` | Every [cross-seed group](#cross-seed-groups) with its members (name, tracker, seed time and minimum, status), its library links, the verdict seedkeeper would give it right now with the [reason and the secondary reasons](#verdicts-and-reasons), and the forecast: a date in the configured time zone, or "clock stopped (paused)". The list can be filtered by verdict and by reason (`/groups?verdict=keep&reason=library-link`). Read from Transmission and the disk on every load. |
| `/orphans` | The **orphans**: what is under the downloads root that no torrent references, as two lists, files and dangling symlinks, each with path, size, age (time since it was last modified) and a total. seedkeeper only shows them and never deletes them. |
| `/settings` | The configuration seedkeeper runs with, read-only: every key of the [configuration](#configuration) with its default filled in, each tracker with its hosts and the minimum seed time that applies to it (marked "(default)" when it sets none of its own), and for each secret only whether it is set or missing, never its value. |
| `/system` | seedkeeper's version, and whether Transmission is reachable, its version and, when seedkeeper can't work with it, the reason. |

## JSON API

Read-only, under `/api/v1`.

### API key

Every request under `/api/` needs the API key (`SEEDKEEPER_API_KEY`), in the `X-Api-Key` header or in the `apikey`
query parameter:

```sh
curl -H "X-Api-Key: $SEEDKEEPER_API_KEY" http://localhost:8080/api/v1/system
curl "http://localhost:8080/api/v1/system?apikey=$SEEDKEEPER_API_KEY"
```

Without a key, or with a wrong one, the answer is `401`. Prefer the header: a query parameter ends up in access logs
and browser history. The key only opens `/api/`, not the pages. Someone who is logged in can also open the API in
the same browser without a key.

### `GET /api/v1/system`

The same data as the System page. Always answers 200, also when Transmission is down.

```json
{
  "version": "0.1.0",
  "transmission": {
    "reachable": true,
    "version": "4.1.0 (5e6a9e0a3d)",
    "reason": null
  }
}
```

| Field | Meaning |
|---|---|
| `version` | seedkeeper's version. |
| `transmission.reachable` | `true` when Transmission answered. |
| `transmission.version` | The version as Transmission reports it; `null` when it didn't say. |
| `transmission.reason` | Why seedkeeper can't work with this Transmission: it doesn't answer, it rejects the credentials, or it is older than 4.1 (then `reachable` is `true`). `null` when all is well. |

### `GET /api/v1/groups`

The same data as the Groups page: every [cross-seed group](#cross-seed-groups) with the verdict seedkeeper would
give it right now. Groups are in the order of their first member in Transmission.

| Query parameter | Meaning |
|---|---|
| `verdict` | Only the groups with this verdict: `remove`, `left-alone` or `keep`. |
| `reason` | Only the groups with this reason, by its [code](#verdicts-and-reasons). It is compared with `reason`, not with `secondaryReasons`. |

Both are optional and can be combined: `GET /api/v1/groups?verdict=keep&reason=library-link`. An empty value is no
filter. A code that doesn't exist answers 400 with `{"reason": "…"}`.

```json
[
  {
    "verdict": "keep",
    "reason": "minimum-not-reached",
    "secondaryReasons": [],
    "libraryLinks": 0,
    "missingFiles": 0,
    "files": 2,
    "forecast": {
      "date": "2026-11-25T10:00:00+01:00",
      "clockStopped": false
    },
    "members": [
      {
        "hash": "0123456789abcdef0123456789abcdef01234567",
        "name": "Some.Show.S01",
        "tracker": "tracker-a",
        "status": "seeding",
        "seedTimeSeconds": 1728000,
        "minimumSeedTimeSeconds": 2592000
      },
      {
        "hash": "89abcdef0123456789abcdef0123456789abcdef",
        "name": "Some.Show.S01.1080p",
        "tracker": "tracker-b",
        "status": "stopped",
        "seedTimeSeconds": 1123200,
        "minimumSeedTimeSeconds": 1209600
      }
    ]
  }
]
```

| Field | Meaning |
|---|---|
| `verdict` | `remove`, `left-alone` or `keep`. |
| `reason` | Why: the code of the first [rule](#verdicts-and-reasons) that holds: `unmanaged`, `missing-data`, `local-error`, `verifying`, `not-finished`, `library-link`, `minimum-not-reached` or `all-met`. |
| `secondaryReasons` | The codes of every other rule that holds too, in the same order; empty when there is none. |
| `libraryLinks` | How many of the group's wanted files have a library link. |
| `missingFiles` | How many wanted files of finished members are nowhere on disk; more than 0 means `missing-data` holds. |
| `files` | How many wanted files the group has. A file that several members share counts once. Files deselected in Transmission are not counted anywhere. |
| `forecast` | `null` unless the reason is `minimum-not-reached`. |
| `forecast.date` | When every member is expected to have met its minimum if the group keeps seeding: ISO 8601, to the second, with the offset of `seedkeeper.time-zone`. `null` when the clock is stopped. |
| `forecast.clockStopped` | `true` when the member with the longest seed time to go is paused, so there is no date. |
| `members[].hash`, `members[].name` | The torrent's info hash and name. |
| `members[].tracker` | The name of the tracker from the config, `public`, or `null` when the torrent is unmanaged. |
| `members[].status` | Transmission's status: `stopped`, `check-wait`, `checking`, `download-wait`, `downloading`, `seed-wait` or `seeding`. |
| `members[].seedTimeSeconds` | The seed time, in seconds. |
| `members[].minimumSeedTimeSeconds` | The tracker's minimum seed time, in seconds; `null` when the torrent is unmanaged. |

Answers 503 with `{"reason": "…"}` when there are no verdicts: Transmission doesn't answer or is older than 4.1, or a
library root can't be read.

### `GET /api/v1/orphans`

The same data as the Orphans page: what is under `seedkeeper.downloads-root` that no torrent references. Nothing is
ever deleted. Read from Transmission and the disk on every request.

```json
{
  "files": [
    {
      "path": "/downloads/Removed.Movie/movie.mkv",
      "sizeBytes": 4294967296,
      "lastModified": "2026-03-01T10:00:00Z"
    }
  ],
  "danglingSymlinks": [
    {
      "path": "/downloads/cross-seed/Removed.Movie/movie.mkv",
      "sizeBytes": 34,
      "lastModified": "2026-03-01T10:00:00Z"
    }
  ],
  "totalSizeBytes": 4294967296
}
```

| Field | Meaning |
|---|---|
| `files` | The regular files no torrent references, by path. A torrent references the file each of its files resolves to (in the download dir or, when enabled, the incomplete dir, with or without `.part`, following symlinks), whatever the file's path: a hardlink of it elsewhere under the downloads root is not an orphan. Files deselected in Transmission count as referenced too. |
| `danglingSymlinks` | The symlinks that lead nowhere and whose path is not a path of a torrent's file, by path. A symlink that leads somewhere is never listed. |
| `[].path` | The absolute path. |
| `[].sizeBytes` | The size in bytes; of a symlink, the size of the link itself. |
| `[].lastModified` | When it was last modified, in UTC; its age is the time since then. |
| `totalSizeBytes` | The sum of `sizeBytes` over `files`. |

Answers 503 with `{"reason": "…"}` when the orphans can't be listed: Transmission doesn't answer or is older than 4.1
(without the torrents every file would seem an orphan), or the downloads root can't be read.

## Health

`/q/health/live` is up whenever seedkeeper runs. `/q/health/ready` (and so `/q/health`) is down while Transmission
doesn't answer, with the reason in the check named `Transmission`. `/q/health` and `/q/metrics` need no login and no
API key.

## Build and test

Requires JDK 25. Maven comes with the wrapper.

```sh
./mvnw verify            # compile and run the tests on the JVM; needs no Docker
./mvnw quarkus:dev       # run locally with live reload on http://localhost:8080
```

`quarkus:dev` needs the configuration too: export the environment variables above and `SEEDKEEPER_TRANSMISSION_URL`.

The native executable is built for `linux/amd64` with Mandrel, inside the builder container, so this needs Docker on
an amd64 Linux host (CI does this on every pull request):

```sh
./mvnw verify -Dnative   # build the executable and run the integration tests against it
docker build -f src/main/docker/Dockerfile.native-micro -t seedkeeper .
docker run --rm -p 8080:8080 --user 1029:65536 \
  -v "$PWD/seedkeeper.yaml:/work/config/application.yaml:ro" \
  --env-file seedkeeper.env \
  seedkeeper
```

The image listens on port 8080 and runs as any non-root uid:gid. `seedkeeper.env` holds the secrets
(see [Configuration](#configuration)).

## Continuous integration

Every pull request and every push to `main` runs `./mvnw verify` on the JVM, and the native build with the integration
tests and a start of the image as a non-root user (`.github/workflows/ci.yml`). Dependabot proposes updates monthly.

## Releases

Pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`: it sets the project version to `X.Y.Z` for that build,
builds the native executable, runs the integration tests and publishes `ghcr.io/ghostwritertje/seedkeeper:X.Y.Z`.
There is no `latest` tag; pin the version you want.

```sh
git tag v0.1.0
git push origin v0.1.0
```
