# seedkeeper

seedkeeper removes torrents from Transmission once they may leave: no file has a link into the media library any more
and every tracker's hit-and-run rule is met. It treats cross-seeded torrents as one group and never deletes data
another torrent still uses. It is a small web app with a scheduled run, a UI that shows what it will do and why, and a
read-only JSON API.

**Status: under construction.** This repository holds the skeleton only; see [SPEC.md](SPEC.md) for what is being
built and in which order.

## Build and test

Requires JDK 25. Maven comes with the wrapper.

```sh
./mvnw verify            # compile and run the tests on the JVM; needs no Docker
./mvnw quarkus:dev       # run locally with live reload on http://localhost:8080
```

The native executable is built for `linux/amd64` with Mandrel, inside the builder container, so this needs Docker on
an amd64 Linux host (CI does this on every pull request):

```sh
./mvnw verify -Dnative   # build the executable and run the integration tests against it
docker build -f src/main/docker/Dockerfile.native-micro -t seedkeeper .
docker run --rm -p 8080:8080 --user 1029:65536 seedkeeper
```

The image listens on port 8080 and runs as any non-root uid:gid. `/q/health` reports its health.

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
