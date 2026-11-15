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
- Workflows use `pull_request`, never `pull_request_target`, so pull requests from forks get no secrets.
- The action restores `CLAUDE.md` and `.claude/` from the base branch only on pull requests. On an issue the
  implementer starts from the default branch, and a retry that continues an earlier agent branch restores those
  paths from the default branch itself, so an earlier run can't loosen the next one's settings.
- The action's own branch for an issue is `issue-<n>`; the implementer sets `CLAUDE_BRANCH` to
  `agent/issue-<n>-<slug>` instead, and allows pushes to that branch only.
