# The quality gate is a set of local, deterministic checks that block every pull request at zero violations

Agents write most of seedkeeper's code and some pull requests merge without the maintainer, so CI is the main gate.
`./mvnw verify` stays the fast loop (compile and tests) that an agent runs while it works. The `quality` profile
(`./mvnw verify -Pquality`) adds every Java check: Spotless with palantir-java-format, sortpom, Error Prone, NullAway
on `@NullMarked` code, `-Xlint:all -Werror`, JaCoCo's 80% line coverage floor and PIT on the changed main classes. An
agent runs that build once, when the work is done, and fixes everything in one batch. The `Lint (non-Java)` job runs
shellcheck, actionlint, zizmor and hadolint without Maven or Docker. `Verify (quality)` and `Lint (non-Java)` are
required checks in the `main` ruleset, so no merge skips them, whether it's the maintainer's, an auto-merge
(ADR 0002) or Dependabot's.

## Considered options

- **Deterministic and local, so no Sonar.** A check gives the same result for the same commit, on a laptop and in CI.
  Sonar's "new code" gate depends on its server's analysis history, so a pull request's result would depend on a
  SaaS's state. For the same reason zizmor runs `--offline`: its online audits (`known-vulnerable-actions`,
  `impostor-commit`) ask GitHub's API, whose answer changes from one day to the next. Dependabot's security alerts
  for `github-actions` cover vulnerable actions instead, without blocking pull requests.
- **No PMD, Checkstyle or complexity rules.** Spotless covers style and Error Prone covers bug patterns. Method
  length, class size and cyclomatic complexity limits push code towards many small, shallow pieces, which is the
  opposite of what we want.
- **Deep-module rules instead.** ArchUnit (`ModuleRules`) checks that modules have no dependency cycles, that another
  module uses only a module's module API, that a module has at most 3 module API types that aren't records or enums,
  and that `decide` and `snapshot` don't depend on Quarkus, Jakarta, JDBC or Jackson. The terms are in `GLOSSARY.md`.
- **Two speeds, not every check in `verify`.** Error Prone, NullAway and PIT make the build noticeably slower. The
  agent iterates on the fast build and pays for the quality build once, at the end. `Verify (quality)` catches a
  pull request whose quality pass was skipped.
- **PIT on the changed main classes only**, with only the tests that don't start the app. Its cost grows with the
  diff, not with the codebase, and it needs no history file and no scheduled full run.
- **Zero violations, no baselines.** Each tool started at zero, with what it found fixed when it was added. A
  baseline file tends to stay as big as the day it was made. A suppression is inline, on the narrowest element, with
  a comment that says why, so a reviewer sees each exception.

## Consequences

- Dependabot keeps the Maven quality tools current (Error Prone, NullAway, JaCoCo, PIT, ArchUnit, Spotless,
  sortpom). A bump that brings new findings fails `Verify (quality)`, so it waits for a fix instead of merging
  automatically, and the gate never weakens or breaks `main` on its own.
- **Some tools are bumped by hand.** Dependabot can't see the four linter binaries in `ci.yml` (pinned by version and
  SHA-256) or palantir-java-format, whose version is a Spotless setting in the POM. This is an exception to "pinned
  and updated by Dependabot" (#48, story 37). A manual bump is still a pull request that waits for a green gate.
- The non-Java lint job is an exception to "everything in Maven": these aren't Java tools, and the job stays fast
  without Maven or Docker.
