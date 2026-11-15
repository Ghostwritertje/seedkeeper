# Review

What the reviewer agent checks on a pull request and how it reports. The reviewer runs in GitHub Actions
(`.github/workflows/reviewer.yml`, prompt in `.github/prompts/reviewer.md`) as the reviewer App, and reads this doc
from the pull request's base branch, so a change to it applies from the next pull request on.

## When it runs

On every pull request that is opened, reopened, marked ready for review or pushed to, except:

- **Drafts.** Mark a pull request as a draft to silence the reviewer; marking it ready starts a review.
- **Dependabot.** Its pull requests merge through `dependabot-auto-merge.yml`.
- **Forks.** They get no secrets.

Only users with write access, and no bots, start it. A new push cancels the review in progress and starts a new one.

## What it checks

It runs the `code-review` skill against the pull request's base, along two axes, reported separately:

- **Standards.** Does the code follow this repo's standards: `CLAUDE.md`, the ADRs in `docs/adr/`, the docs in
  `docs/agents/`, and the domain language of `GLOSSARY.md`? It also flags the skill's code smells, as judgement
  calls.
- **Spec.** Does the change do what the issue asked for? The spec is the issue the pull request closes or names,
  with its parent spec. It reports what is missing or partial, what wasn't asked for, and what looks implemented but
  wrong, quoting the issue.

It doesn't run the build: CI's `Verify (JVM)` and `Verify (native)` jobs do.

## How it reports

- **Inline comments.** Each finding that belongs to a line is an inline comment on that line. A finding says what is
  wrong, why, with the standard or the spec line it breaks, and the fix when the fix is clear.
- **One summary review**, submitted last, as a comment review:

  ```markdown
  ## Standards

  <findings, or "No findings.">

  ## Spec

  <findings, or "No findings.", or "No spec available.">

  ## Verdict

  <one line: the number of findings per axis and the worst one in each>
  ```

- **Decisions.** When the pull request needs a choice that is the maintainer's to make (a requirement the issue
  leaves open, a conflict with an ADR, a trade-off with no clear winner), it adds `needs-decision` and ends the
  summary with:

  ```markdown
  ## Decision needed

  **Question:** <what has to be decided>

  **Options:**
  1. <option>: <what follows from it>
  2. <option>: <what follows from it>

  **Recommendation:** <the option, and why>
  ```

## What it never does

It never edits files, commits, pushes, merges or calls `gh api`. Its tool allowlist and its App's read-only contents
permission enforce this.

## When it fails

A crash, a timeout or an exhausted quota adds `agent:blocked` to the pull request, with a comment linking the
workflow run. Nothing retries on its own: push a commit, or close and reopen the pull request, to review it again.
