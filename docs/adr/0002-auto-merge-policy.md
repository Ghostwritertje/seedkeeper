# Low-risk implementer pull requests merge without the maintainer, through a deterministic gate

An implementer pull request merges without the maintainer when the reviewer judges it a two-way door with a small
blast radius, approves it and adds `auto-merge`, and a gate script then finds that the implementer opened it, the
reviewer's latest review approves its current head, it carries `auto-merge` and it touches no human-only path. The
gate turns on a squash auto-merge with the workflow's own token, and the `main` ruleset stays the final check: the
required checks `Verify (JVM)`, `Verify (native)`, `Verify (quality)`, `Lint (non-Java)` and `Test the CI scripts`
green, and every review thread resolved. The criteria and the paths are in `docs/agents/auto-merge.md`.

## Considered options

- **Merges to `main` count as reversible.** Merging doesn't release: a release is started only by a person, with Run
  workflow on `release.yml` or a pushed `vX.Y.Z` tag. A bad change on `main` is reverted with another pull request
  before anyone runs it, so most code changes are two-way doors, and their size, or the fact that behaviour changes,
  doesn't keep them for the maintainer, who already approved the issue.
- **Removal behaviour is the exception.** A change to the Verdict or Removal behaviour, to which data a Removal
  deletes, is reversible on `main` but not once released: a mistake deletes data for good. Starting a release doesn't
  protect against that, because the maintainer doesn't re-review every merged change before releasing. So the reviewer
  keeps it, with the other hard-to-revert changes (migrations, persisted formats, config keys users have set), for the
  maintainer.
- **Human-only paths in the gate, not in the reviewer.** The reviewer reads issue and pull request text, which a
  prompt injection can steer, and an implementer pull request could loosen its own guardrails (the workflows, the
  prompts, the settings, the gate itself) and then approve its way in. The gate is a script with no network access,
  run from the base branch, with table-driven tests: whatever the reviewer says, a pull request that touches
  `.github/`, `.claude/`, `CLAUDE.md`, the APM files, the ADRs, the Docker directory or the migrations never merges
  itself. The reviewer still judges what a path list can't: dependencies, security, Removal behaviour.
- **The workflow's token merges, not an App.** The reviewer App's contents are read-only, so it can't merge even if
  its allowlist failed, and the implementer App never decides on its own work. The gate job uses the workflow's own
  token, as `.github/workflows/dependabot-auto-merge.yml` does, in a job of its own, so the agent's job never holds a
  token that can write contents.
- **Only implementer pull requests.** The maintainer merges their own; the reviewer gets the tools to approve and add
  `auto-merge` only on the implementer's pull requests, and the gate checks the author again.

## Consequences

- An approval holds for one head. GitHub keeps an auto-merge on when the implementer pushes, and the ruleset requires
  no approval, so any new push to an implementer pull request turns its auto-merge off and removes `auto-merge`
  before the next review. The gate only counts an approval on the current head, and turns auto-merge on for that head
  only (`--match-head-commit`).
- Changing the human-only paths or the gate is itself a human-only change. Changing the criteria, in
  `docs/agents/auto-merge.md`, isn't: the gate doesn't cover `docs/agents/`, so a change there is held back by the
  reviewer's Agent setup criterion, not by the gate.
- **An auto-merged pull request starts no dependents.** The issue-closed trigger in `implementer.yml` starts an
  issue's unblocked dependents only when a user with write access closed it. An auto-merge is made by
  `github-actions[bot]` through the workflow's own token, so it either starts no workflow at all (GitHub doesn't
  start workflows for events made with that token) or reaches the trigger with a bot as the closer, which it
  rejects. We keep it that way, as the most reversible option: widening the trigger to bots, or merging with an App
  token that starts workflows, would let a chain of tickets implement and merge itself with no human step between
  them, which is harder to take back than to add later. After an auto-merge the maintainer starts each unblocked
  dependent by removing and re-adding its `ready-for-agent`.
- **A quality tool bump that brings new findings doesn't merge itself.** `Verify (quality)` and `Lint (non-Java)` are
  required checks (ADR 0003), so a Dependabot pull request that bumps a quality tool and makes it report something new
  fails a required check. `dependabot-auto-merge.yml` turns auto-merge on, but GitHub doesn't merge until a fix makes
  the check green.
