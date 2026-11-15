# Review

What the reviewer agent checks on a pull request and how it reports. The reviewer runs in GitHub Actions
(`.github/workflows/reviewer.yml`, prompt in `.github/prompts/reviewer.md`) as the reviewer App, and reads this doc
from the pull request's base branch, so a change to it applies from the next pull request on.

## When it runs

On every pull request that is opened, reopened, marked ready for review or pushed to, except:

- **Drafts.** Mark a pull request as a draft to silence the reviewer; marking it ready starts a review.
- **Dependabot.** Its pull requests merge through `dependabot-auto-merge.yml`.
- **Forks.** They get no secrets.

Only users with write access start it, and of the bots only the implementer App's, so the implementer's pushes are
reviewed again. A new push cancels the review in progress and starts a new one.

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
- **One summary review**, submitted last, as a comment review, or as an approval when it approves (see
  **Approving**). Its first line is a hidden round marker, which the
  workflow counts (see **The review/fix loop**):

  ```markdown
  <!-- seedkeeper-review-round: <n> -->

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

## The review/fix loop

On an implementer pull request, the reviewer and the implementer hand the work back and forth without the
maintainer:

1. **Handing back.** When every finding has a clear fix, the reviewer adds `ready-for-agent`. It gives the findings
   that have no line in the summary review.
2. **Fixing.** `ready-for-agent` on a pull request starts the implementer on that pull request's branch
   (`.github/workflows/address-review.yml`). It addresses the unresolved review threads and the latest summary
   review, pushes, and resolves the threads it handled.
3. **Reviewing again.** The implementer's push starts a new review, the next round.

**Rounds.** A round is one summary review by the reviewer App with the round marker. Before it reviews, the workflow
counts the earlier ones (`.github/scripts/review-round.sh`). After three rounds the reviewer adds `needs-decision`
instead of `ready-for-agent`, and asks whether to fix the remaining findings, accept the pull request as it is, or
close it. The workflow only gives the reviewer the tool to add `ready-for-agent` while a handback is allowed.

**Only agent pull requests are handed back.** On a pull request someone else wrote, the reviewer adds no
`ready-for-agent`; the author can add it to have the implementer address the review.

**Resuming after a decision.** Answer in a comment on the pull request, remove `needs-decision` and add
`ready-for-agent`. The implementer reads the answer and carries on. The round count isn't reset: each later review
past the third asks for a decision again.

## Approving

The reviewer approves only an implementer pull request, and only when the review has no findings, no decision is
needed, and the change meets the criteria in `docs/agents/auto-merge.md`. It then adds `auto-merge`. It never
approves a pull request a human opened: the workflow gives it the tools to approve and to add `auto-merge` only on
the implementer's pull requests.

Approving doesn't merge. The `Auto-merge gate` job decides after the review, and turns on a squash auto-merge only
when the gate passes (`docs/agents/auto-merge.md`, **The gate**). A new push to an implementer pull request turns an
earlier auto-merge off and removes `auto-merge`, so the new head is reviewed and approved on its own.

## What it never does

It never edits files, commits, pushes, merges, requests changes or calls `gh api`. Its tool allowlist and its App's
read-only contents permission enforce this.

## When it fails

A crash, a timeout or an exhausted quota adds `agent:blocked` to the pull request, with a comment linking the
workflow run. Nothing retries on its own: push a commit, or close and reopen the pull request, to review it again.
