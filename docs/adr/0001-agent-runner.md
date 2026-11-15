# Agents run in GitHub Actions with claude-code-action, as two GitHub Apps, on the Claude subscription

The implementer and reviewer agents run in GitHub Actions with `anthropics/claude-code-action` in automation mode: a
prompt file, a job-level filter on the event and label, and an explicit tool allowlist. Each agent acts as its own
private GitHub App, and both are billed to the maintainer's Claude subscription through `CLAUDE_CODE_OAUTH_TOKEN`.

## Considered options

- **claude-code-action, not Sandcastle.** Sandcastle needs an npm toolchain in a Maven repo, and it ships no
  triggers, so we would write the event handling ourselves anyway. claude-code-action brings the write-access check,
  the bot filter, the inline-comment tool, and restores `CLAUDE.md` and `.claude/` from the base branch on pull
  requests, so a pull request can't change its own reviewer's instructions. Its tag mode isn't used: its label
  trigger only posts a "Create PR" link.
- **Two GitHub Apps, not the workflow token or one App.** Events made with the default `GITHUB_TOKEN` don't start
  other workflows, so CI wouldn't run on an agent's pull request and the review/fix handoff wouldn't work; App tokens
  do start them. Two Apps give each agent only what it needs: the reviewer's contents permission is read-only, so it
  can't push even if its allowlist failed, and the implementer has no workflows permission, so GitHub rejects any
  push it makes to `.github/workflows/`. Because the reviewer isn't the author of the implementer's pull requests, it
  can approve them. Each App is private and installed on this repo only.
- **GitHub Actions, not local Docker.** Docker on the maintainer's machine is a shared Rancher Desktop VM that runs
  other services (see `CLAUDE.md`). Actions also gives the triggers, the secrets, the concurrency groups and the run
  log for free, and work moves while the maintainer is away from the keyboard.
- **The subscription token, not an API key.** The agents' usage comes out of the Claude subscription the maintainer
  already pays for, rather than separate metered API billing. The cost is the subscription's usage window: an
  exhausted quota fails the job, which adds `agent:blocked`, and nothing retries automatically. Turn and time limits
  on each agent keep one bad task from using up the window.

## Consequences

- Creating the Apps and the secrets stays a manual step (`docs/agents/github-apps.md`).
- The allowlist is strict only with `--permission-mode default`: the action's default auto mode lets a safety review
  pass tool calls the allowlist doesn't cover. Denies go in `claude_args`, where they also bind the action's own git
  scripts.
- The allowlist and the denies are defence in depth, not a security boundary against a prompt-injected implementer.
  It may `Edit` and `Write` and run `./mvnw`, so it can make the build run any shell, with the App token and
  `CLAUDE_CODE_OAUTH_TOKEN` in its environment. What actually bounds it is the App's permissions (no workflows
  permission, so no push to `.github/workflows/`) and the `main` ruleset. Keeping injected instructions out in the
  first place is the main defence: see the next point.
- The repo is public, so anyone can comment on an issue or a pull request and start or reply to a review thread.
  Only trusted authors reach an agent: the workflow collects the review threads, the comments and the reviews from
  the agents' bots and users with write access (`.github/scripts/review-threads.sh`,
  `.github/scripts/trusted-comments.sh`), and the prompts work from those files and treat anything else as data. A
  thread a stranger started is never given to the implementer, so it stays unresolved until the maintainer resolves
  it. The reviewer reads the pull request's comments itself, and its prompt tells it to ignore untrusted authors.
- Workflows use `pull_request`, never `pull_request_target`, so pull requests from forks get no secrets.
- The action restores `CLAUDE.md` and `.claude/` from the base branch only on pull requests. On an issue the
  implementer starts from the default branch, and a retry that continues an earlier agent branch restores those
  paths from the default branch itself, so an earlier run can't loosen the next one's settings.
- The action's own branch for an issue is `issue-<n>`; the implementer sets `CLAUDE_BRANCH` to
  `agent/issue-<n>-<slug>` instead, and allows pushes to that branch only.
- The two agents start each other through their Apps' bots, for the review/fix loop, and each workflow filters its
  job on the sender: a user, or the other agent's bot, named by the `IMPLEMENTER_APP_SLUG` and `REVIEWER_APP_SLUG`
  repository variables. The reviewer's `allowed_bots` is the implementer's bot. The implementer's, on a pull
  request, is the reviewer's bot and its own: when a bot acts on a pull request, the action also checks that the
  pull request's author is allowed, and the implementer writes most of those pull requests
  ([`checkBotActorPullRequestAuthor`](https://github.com/anthropics/claude-code-action/blob/f3b070c88125d33dbae5653df5f143101d4ea9dc/src/github/validation/permissions.ts#L105-L143):
  an author ending in `[bot]` must be in `allowed_bots`). The sender filter in `implementer.yml` keeps the
  implementer from starting itself, and `pull-request-pick-up.sh` leaves every other bot's pull request alone.
- The action counts a pull request written by a bot as untrusted and denies `./mvnw` on it. The implementer turns
  that off with `restrict_untrusted_prs: false` on pull requests: forks never get secrets, and the code on its own
  pull requests is its own, which it ran when it wrote it.
- The action has no tool for resolving review threads, and resolving one takes `gh api graphql`, which the agents
  are denied. The workflow lists the unresolved threads before the run and resolves, after it, the ones the agent
  says it handled, if they were on that list.
- An issue's closing starts its unblocked dependents in the same workflow run, through the reusable
  `implement-issue.yml`, rather than by dispatching a new run. A run dispatched with the default workflow token has
  `github-actions[bot]` as its actor, which the action's access check rejects unless it is in `allowed_bots`. In the
  same run the actor is whoever closed the blocker, so the action checks that user's access; the pick-up job checks
  the access of whoever last added the dependent's `ready-for-agent`.
