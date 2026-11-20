# seedkeeper

## Quality build

`./mvnw verify` is the fast loop: compile and tests. `./mvnw verify -Pquality` adds the Java quality checks (today:
formatting with Spotless/palantir-java-format, a sorted POM with sortpom, Error Prone, NullAway on every package
(each carries `@NullMarked` in its `package-info.java`; mark what may be null `@Nullable`), every compiler warning as an
error with `-Xlint:all -Werror`, JaCoCo's 80% line coverage floor and PIT mutation testing). Run it once when the work
is done and fix everything it reports before you push; CI's `Verify (quality)` job runs the same build. Fix a finding
rather than suppress it; when a suppression is the right call, put it inline (`@SuppressWarnings("<check>")` on the
narrowest element) with a comment that says why.

JaCoCo's coverage counts the `@QuarkusTest` tests; the report is in `target/site/jacoco`.

PIT mutates only the main classes changed since the merge base with `origin/main` (uncommitted changes count, new
untracked files don't), and runs only the tests that don't start the app. The tests that reach those mutants must kill
80% of them (test strength); the report is in `target/pit-reports/`. Compare with another ref with
`-Dpit.base=<ref>`.

Apply the formatting, Java and POM both, with one command:

```sh
./mvnw spotless:apply sortpom:sort
```

## Native build and image: CI only

Verify locally on the JVM: `./mvnw verify` (needs no Docker), and run the app with the `run-app` skill.

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

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for `Ghostwritertje/seedkeeper` (via `gh`). See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
