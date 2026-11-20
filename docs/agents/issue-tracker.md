# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --json number,title,body,labels,comments`.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Make an issue a sub-issue of a parent**: `gh issue create --parent <parent> ...`, or `gh issue edit <parent> --add-sub-issue <child>` afterwards (`gh` 2.94+). Older `gh`: `gh api --method POST repos/<owner>/<repo>/issues/<parent>/sub_issues -F sub_issue_id=<child-db-id>` (database id, as in **Blocking** below). Without sub-issues, put `Part of #<parent>` at the top of the child body.
- **Blocking**: GitHub's **native issue dependencies**, the canonical, UI-visible representation. Add an edge with `gh issue create --blocked-by <n>,<n> ...` or `gh issue edit <child> --add-blocked-by <blocker>` (`gh` 2.94+). Older `gh`: `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, where `<blocker-db-id>` is the blocker's numeric **database id** (`gh api repos/<owner>/<repo>/issues/<n> --jq .id`, _not_ the `#number` or `node_id`). Where dependencies aren't available, fall back to a `Blocked by: #<n>, #<n>` line at the top of the child body. A ticket is unblocked when every blocker is closed.
- **Count open blockers**: `gh api repos/<owner>/<repo>/issues/<n> --jq .issue_dependencies_summary.blocked_by` (open blockers only; 0 means startable). `gh issue view <n> --json blockedBy` also lists closed blockers, so filter it: `--jq '[.blockedBy.nodes[] | select(.state == "OPEN")] | length'`.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: post the explanation with `gh issue comment <number> --body "..."`, then `gh issue close <number>`. On an already-closed issue, `gh issue close --comment` drops the comment and still exits 0.

Infer the repo from `git remote -v`; `gh` does this automatically when run inside a clone.

File issues only into the user's own repo. Never file into `mattpocock/skills` (the repo these skills come from) unless the user owns it. In a fork, `gh` can default to the upstream parent, so before the first `gh issue create` of a session, check the target with `gh repo view --json nameWithOwner` and confirm it with the user if it is not their repo.

## Agent labels

The implementer and reviewer agents run in GitHub Actions (`.github/workflows/implementer.yml`, which runs
`.github/workflows/implement-issue.yml`, and `.github/workflows/reviewer.yml`) and drive their work with these
labels. Interactive sessions use them the same way.

| Label               | Who adds it                                                         | What it means                                                                                                       |
|---------------------|---------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `ready-for-agent`   | the maintainer, `to-spec`, `to-tickets`; the reviewer on a pull request | The implementer may take it. On an issue, adding it starts the implementer, and so does closing its last open blocker. On a pull request, adding it makes the implementer address the review feedback on that pull request's branch. |
| `agent:in-progress` | the implementer, when it starts                                     | An implementer run is working on the issue or pull request. It goes when the run ends.                              |
| `agent:blocked`     | the implementer, or a failed agent job                              | The run couldn't finish: `./mvnw verify` or the quality build (`./mvnw verify -Pquality`) stayed red (a draft pull request says where it got stuck), or the job crashed, timed out, ran out of quota or ended in none of its outcomes (a comment links the run). |
| `needs-decision`    | either agent                                                        | A choice that is the maintainer's to make. The comment or review gives the question, the options and a recommendation. |
| `auto-merge`        | the reviewer, on an implementer pull request it approves                  | The pull request is a two-way door with a small blast radius, and may merge without the maintainer: the `Auto-merge gate` job turns on a squash auto-merge if it touches no human-only path (`docs/agents/auto-merge.md`). A new push removes it. |

The life of an issue:

1. **Labelled.** A user with write access adds `ready-for-agent`. Any other label, or a labeller without write
   access, starts nothing.
2. **Picked up.** An issue with sub-issues is a spec: the implementer comments, removes `ready-for-agent`, and
   doesn't start; label its sub-issues instead. An issue with open blockers (see **Count open blockers**) gets a
   comment and keeps `ready-for-agent`. Otherwise the implementer swaps `ready-for-agent` for `agent:in-progress`.

   **Unblocked.** When a user with write access closes an issue (or merges the pull request that closes it), each
   issue it was blocking that is open, still labelled `ready-for-agent` and has no open blocker left is picked up
   the same way, as if the user who last added its `ready-for-agent` had just added it. Dependents that are still
   blocked, or carry no `ready-for-agent`, are left alone. The blocker's close reason doesn't matter: "not planned"
   unblocks too.

   **Not after an auto-merge.** An auto-merged pull request (`docs/agents/auto-merge.md`) closes its issue when it
   merges, but the merge is made by the reviewer App, not a user with write access, so the close starts no
   dependents. Start each
   unblocked dependent by hand: remove its `ready-for-agent` and add it again. Why:
   `docs/adr/0002-auto-merge-policy.md`.
3. **Worked on.** On the branch `agent/issue-<n>-<slug>`, test-first, until `./mvnw verify` is green. Then the
   quality pass: `./mvnw verify -Pquality` once, everything it reports fixed in one batch (formatting first, with
   `./mvnw spotless:apply sortpom:sort`), and re-run until it is green too. The fast build is the working loop; the
   quality build is slower, so it runs once at the end, before the push. CI's `Verify (quality)` job runs the same
   build.
4. **Ends** in one of these, and `agent:in-progress` goes:
   - a ready pull request that says `Closes #<n>` and lists "Decisions I made";
   - `needs-decision` on the issue, with a comment, and no pull request;
   - `agent:blocked` on the issue, with a comment, and a draft pull request when the run has commits.

   After the agent, the workflow checks for one of these (`.github/scripts/agent-outcome.sh`). A run that ends in
   none of them fails: the issue gets `agent:blocked` and a comment that links the run. The run's job log and job
   summary list the commands the agent was denied and its last message.

The life of a pull request's review feedback (`docs/agents/review.md`, **The review/fix loop**):

1. **Labelled.** A user with write access, or the reviewer App when every finding has a clear fix, adds
   `ready-for-agent` to the pull request. Pull requests from forks and from Dependabot are left alone.
2. **Picked up.** The implementer swaps `ready-for-agent` for `agent:in-progress` on the pull request.
3. **Worked on.** On the pull request's own branch: the unresolved review threads and the latest summary review,
   until `./mvnw verify` is green, then the same quality pass, before the one push.
4. **Ends** in one of these, and `agent:in-progress` goes:
   - the fixes pushed, the handled threads resolved and a comment saying what changed; the push starts a new review;
   - `needs-decision` on the pull request, with a comment;
   - `agent:blocked` on the pull request, with a comment saying where it got stuck.

   The workflow checks for one of these the same way. A run that ends in none of them fails, and the pull request
   gets `agent:blocked` and a comment that links the run.

When the reviewer approves an implementer pull request and adds `auto-merge`, the gate may merge it without the
maintainer (`docs/agents/auto-merge.md`). After three review rounds the reviewer adds `needs-decision` instead of
`ready-for-agent`. To resume after a
decision, answer in a comment, remove `needs-decision` and add `ready-for-agent`.

**Retry.** Nothing retries on its own. Re-adding `ready-for-agent` is the only retry: remove `agent:blocked` or
`needs-decision` first, and after a decision, answer it in a comment. A retry continues on the agent branch an
earlier run pushed.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests; `/triage` reads this flag.)_

When set to `yes`, PRs run through the same labels and states as issues, using the `gh pr` equivalents:

- **Read a PR**: `gh pr view <number>`, then `gh pr view <number> --comments` (off a TTY, `--comments` prints only the comments, not the body), and `gh pr diff <number>` for the diff.
- **List external PRs for triage**: `gh api --paginate 'repos/{owner}/{repo}/pulls?state=open' --jq '.[] | select(.author_association | IN("OWNER","MEMBER","COLLABORATOR") | not) | {number, title, author: .user.login, author_association, labels: [.labels[].name]}'`.
- **Comment / label / close**: `gh pr comment`, `gh pr edit --add-label`/`--remove-label`, `gh pr close`.

GitHub shares one number space across issues and PRs, so a bare `#42` may be either: resolve with `gh pr view 42` and fall back to `gh issue view 42`.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Read it as in **Read an issue** above.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: a single issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body. `gh issue create --label wayfinder:map`.
- **Child ticket**: an issue linked to the map as a GitHub sub-issue (see **Make an issue a sub-issue of a parent**). Where sub-issues aren't enabled, add the child to a task list in the map body and put `Part of #<map>` at the top of the child body. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`). Once claimed, the ticket is assigned to the driving dev.
- **Blocking**: see **Blocking** under Conventions.
- **Frontier query**: list the map's open children (`gh issue list --state open`, scoped to the map's sub-issues / task list), drop any with an open blocker (see **Count open blockers**, or an open issue in the `Blocked by` line) or an assignee; first in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me`, the session's first write.
- **Resolve**: `gh issue comment <n> --body "<answer>"`, then `gh issue close <n>`, then append a context pointer (gist + link) to the map's Decisions-so-far.
