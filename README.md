# seedkeeper

seedkeeper removes torrents from Transmission once they may leave: no file has a link into the media library any more
and every tracker's hit-and-run rule is met. It treats cross-seeded torrents as one group and never deletes data
another torrent still uses. It is a small web app with a scheduled run, a UI that shows what it will do and why, and a
read-only JSON API.

**Status: under construction.** So far seedkeeper connects to Transmission and shows, per torrent, the verdict it would
give, behind a login and an API key. It removes nothing yet, and it doesn't know cross-seed groups, missing data,
unregistered torrents or keep overrides yet; see the [spec](https://github.com/Ghostwritertje/seedkeeper/issues/5) for
what is being built and in which order.

## Configuration

seedkeeper needs Transmission 4.1 or newer (it speaks JSON-RPC 2.0, which older versions don't).

Settings that are safe to commit go in `seedkeeper.yaml`, mounted in the container as `/work/config/application.yaml`:

```yaml
seedkeeper:
  transmission:
    url: http://transmission:9091/transmission/rpc   # Transmission's RPC endpoint
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
```

| Key | Required | Meaning |
|---|---|---|
| `seedkeeper.transmission.url` | yes | URL of Transmission's RPC endpoint. |
| `seedkeeper.library-roots` | yes | The directories of the media library. A downloaded file that has a hardlink under one of them has a **library link**, which keeps its torrent. Symlinks under a library root don't count and are not followed. When a root can't be read there are no verdicts at all. |
| `seedkeeper.default-minimum-seed-time` | no, `30d` | The minimum seed time of a tracker that doesn't set its own. |
| `seedkeeper.trackers.<name>.hosts` | yes, per tracker | The hosts of the tracker's announce URLs, matched exactly (a subdomain is another host). A torrent belongs to the tracker when every one of its announce hosts is in this list. |
| `seedkeeper.trackers.<name>.minimum-seed-time` | no | The seed time the tracker requires before a torrent may leave; the default when absent. |
| `seedkeeper.public.minimum-seed-time` | no | Adds the tracker `public`: every torrent that is not private and belongs to no single managed tracker gets it, with this minimum. Without the `public` entry such torrents are unmanaged. |
| `seedkeeper.auth.username` | no | Username of seedkeeper's login. Default `admin`. |

A duration is a number with a unit: `30d`, `12h`, `90m`; a bare number is seconds.

A torrent that belongs to no tracker (none of its hosts is listed, or its hosts are of two trackers) is **unmanaged**:
seedkeeper leaves it alone. Seed time is Transmission's `secondsSeeding`: time actually spent seeding, so it stands
still while a torrent is paused.

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
| `/groups` | Every torrent with its name, tracker, seed time and minimum, status, library links, and the verdict seedkeeper would give it right now with the reason. Read from Transmission and the disk on every load. |
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

The same data as the Groups page: every group with the verdict seedkeeper would give it right now. For now every
torrent is a group of its own, so `members` has one entry.

```json
[
  {
    "verdict": "keep",
    "reason": "library-link",
    "libraryLinks": 1,
    "files": 2,
    "members": [
      {
        "hash": "0123456789abcdef0123456789abcdef01234567",
        "name": "Some.Show.S01",
        "tracker": "tracker-a",
        "status": "seeding",
        "seedTimeSeconds": 3456000,
        "minimumSeedTimeSeconds": 2592000
      }
    ]
  }
]
```

| Field | Meaning |
|---|---|
| `verdict` | `remove`, `left-alone` or `keep`. |
| `reason` | Why; the first of these that holds: `unmanaged` (left alone: a member belongs to no tracker), `not-finished` (keep: the download isn't complete), `library-link` (keep: a file has a library link), `minimum-not-reached` (keep: the seed time is below the tracker's minimum), `all-met` (remove). |
| `libraryLinks` | How many of the group's wanted files have a library link. |
| `files` | How many wanted files the group has. Files deselected in Transmission are not counted anywhere. |
| `members[].hash`, `members[].name` | The torrent's info hash and name. |
| `members[].tracker` | The name of the tracker from the config, `public`, or `null` when the torrent is unmanaged. |
| `members[].status` | Transmission's status: `stopped`, `check-wait`, `checking`, `download-wait`, `downloading`, `seed-wait` or `seeding`. |
| `members[].seedTimeSeconds` | The seed time, in seconds. |
| `members[].minimumSeedTimeSeconds` | The tracker's minimum seed time, in seconds; `null` when the torrent is unmanaged. |

Answers 503 with `{"reason": "…"}` when there are no verdicts: Transmission doesn't answer or is older than 4.1, or a
library root can't be read.

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
