# seedkeeper

## Native build and image: CI only

Verify locally on the JVM: `./mvnw verify` (needs no Docker) and `./mvnw quarkus:dev`.

The native build (`-Dnative`), the image build (`Dockerfile.native-micro`) and the image smoke test
(`.github/scripts/smoke-test-image.sh`) run in GitHub Actions, never on this machine. Docker here is a shared Rancher
Desktop VM that also runs other services; the amd64 Mandrel build runs emulated on Apple Silicon, takes the whole VM
and has taken those services down. A hook in `.claude/settings.json` blocks these commands.

To verify the native build, push the branch and run the `Verify (native)` job:

```sh
gh workflow run ci.yml --ref "$(git branch --show-current)"
gh run watch
```

A pull request runs the same job.
