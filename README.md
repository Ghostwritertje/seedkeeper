# seedkeeper

seedkeeper removes torrents from Transmission once they may leave: no file has a link into the media library any more
and every tracker's hit-and-run rule is met. It treats cross-seeded torrents as one group and never deletes data
another torrent still uses. It is a small web app with a scheduled run, a UI that shows what it will do and why, and a
read-only JSON API.

**Status: under construction.** So far seedkeeper only connects to Transmission and shows on its System page whether
that works; see the [spec](https://github.com/Ghostwritertje/seedkeeper/issues/5) for what is being built and in which
order.

## Configuration

seedkeeper needs Transmission 4.1 or newer (it speaks JSON-RPC 2.0, which older versions don't).

Settings that are safe to commit go in `seedkeeper.yaml`, mounted in the container as `/work/config/application.yaml`:

```yaml
seedkeeper:
  transmission:
    url: http://transmission:9091/transmission/rpc   # Transmission's RPC endpoint
```

| Key | Required | Meaning |
|---|---|---|
| `seedkeeper.transmission.url` | yes | URL of Transmission's RPC endpoint. |

Secrets come from the environment only. seedkeeper doesn't start when one is missing:

| Environment variable | Meaning |
|---|---|
| `SEEDKEEPER_TRANSMISSION_USERNAME` | Transmission's RPC username. |
| `SEEDKEEPER_TRANSMISSION_PASSWORD` | Transmission's RPC password. |
| `SEEDKEEPER_AUTH_PASSWORD` | Password of seedkeeper's own login (the login itself is not built yet). |
| `SEEDKEEPER_API_KEY` | Key for the JSON API (not checked yet). |
| `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` | Encrypts the session cookie; at least 16 characters. Keep it the same across restarts, so logins survive them. |

An environment variable also overrides a key of the file: `SEEDKEEPER_TRANSMISSION_URL` wins over
`seedkeeper.transmission.url`.

## Pages

| Page | Shows |
|---|---|
| `/system` | seedkeeper's version, and whether Transmission is reachable, its version and, when seedkeeper can't work with it, the reason. |

## JSON API

Read-only, under `/api/v1`.

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

## Health

`/q/health/live` is up whenever seedkeeper runs. `/q/health/ready` (and so `/q/health`) is down while Transmission
doesn't answer, with the reason in the check named `Transmission`. Neither needs a login.

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
