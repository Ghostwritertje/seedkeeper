# GitHub Apps and secrets for the agents

The agents run in GitHub Actions, each as its own private GitHub App (see `docs/adr/0001-agent-runner.md`). Creating
the Apps and the secrets is a manual step for the maintainer: no workflow or agent does it.

## The Claude subscription token

1. On a machine logged in to the Claude subscription, run `claude setup-token` and copy the token it prints.
2. In the repo, open **Settings → Secrets and variables → Actions → New repository secret**.
3. Name it `CLAUDE_CODE_OAUTH_TOKEN` and paste the token.

Both agents use this secret.

## The reviewer App

1. Open <https://github.com/settings/apps/new> (**Settings → Developer settings → GitHub Apps → New GitHub App**).
2. Fill in:
   - **GitHub App name:** a name that is free, for example `seedkeeper-reviewer`. Its bot,
     `<name>[bot]`, is who posts the reviews.
   - **Homepage URL:** `https://github.com/Ghostwritertje/seedkeeper`.
   - **Webhook:** clear **Active**. The workflow needs no webhook.
3. Under **Repository permissions**, set exactly these and leave every other permission at **No access**:
   - **Checks:** Read-only
   - **Contents:** Read-only
   - **Issues:** Read and write
   - **Metadata:** Read-only (GitHub sets this)
   - **Pull requests:** Read and write
4. Leave **Account permissions** and **Subscribe to events** empty.
5. Under **Where can this GitHub App be installed?**, choose **Only on this account**, then **Create GitHub App**.
6. On the App's page, note the **App ID** near the top.
7. Under **Private keys**, click **Generate a private key**. GitHub downloads a `.pem` file.
8. Click **Install App** in the sidebar, install it on your account, choose **Only select repositories** and select
   `seedkeeper` only.
9. In the repo, add two repository secrets, as for the subscription token:
   - `REVIEWER_APP_ID`: the App ID.
   - `REVIEWER_APP_KEY`: the whole contents of the `.pem` file, including the `BEGIN` and `END` lines.
10. Delete the downloaded `.pem` file. To rotate the key, generate a new one, update `REVIEWER_APP_KEY` and delete the
    old key on the App's page.

To check the setup, open a pull request that isn't a draft, or mark one ready for review: the `Reviewer` workflow
runs, and its review and comments come from `<name>[bot]`.

## The implementer App

Create it as for the reviewer App, steps 1 to 10, with these differences:

- **GitHub App name:** for example `seedkeeper-implementer`. Its bot, `<name>[bot]`, is who pushes the agent
  branches, opens the pull requests and comments on the issues.
- **Repository permissions**, exactly these, every other permission at **No access**:
  - **Contents:** Read and write
  - **Issues:** Read and write
  - **Metadata:** Read-only (GitHub sets this)
  - **Pull requests:** Read and write

  Leave **Workflows** at **No access**: GitHub then rejects any push from the App that changes
  `.github/workflows/`.
- **Install** it on `seedkeeper` only, through **Only select repositories**.
- **Secrets:**
  - `IMPLEMENTER_APP_ID`: the App ID.
  - `IMPLEMENTER_APP_KEY`: the whole contents of the `.pem` file, including the `BEGIN` and `END` lines.

To check the setup, add `ready-for-agent` to a small issue that has no sub-issues and no open blockers: the
`Implementer` workflow runs, `<name>[bot]` swaps the label for `agent:in-progress`, and the pull request it opens
comes from `<name>[bot]` and starts the `CI` workflow. A pull request opened with the default workflow token
wouldn't start it.

## The App variables

Each agent lets the other agent's bot start it, and no other bot: the implementer's pushes start a review, and the
reviewer's `ready-for-agent` starts the implementer. The workflows build each bot's login, `<slug>[bot]`, from two
repository variables. A workflow mints only its own App's token, so it can't look up the other App's slug.

1. On each App's page, the slug is the last part of its URL, `https://github.com/apps/<slug>`: the App's name in
   lowercase, with dashes for spaces.
2. In the repo, open **Settings → Secrets and variables → Actions → Variables → New repository variable**, and add:
   - `IMPLEMENTER_APP_SLUG`: the implementer App's slug, for example `seedkeeper-implementer`.
   - `REVIEWER_APP_SLUG`: the reviewer App's slug, for example `seedkeeper-reviewer`.

Without them each agent still runs for users, but not for the other agent's bot: the review/fix loop stops, and the
reviewer hands nothing back. Update them when you rename an App.

To check the setup, have the reviewer find a clear fix on an implementer pull request: it adds `ready-for-agent`,
the `Implementer` workflow runs on the pull request's branch, and its push starts a new `Reviewer` run.

## Labels

The agents use these labels; `docs/agents/issue-tracker.md` describes their lifecycle. Create any that are
missing:

```sh
gh label create needs-decision --color d93f0b --description "Needs a decision from the maintainer"
gh label create agent:in-progress --color fbca04 --description "An agent is working on this issue"
gh label create agent:blocked --color b60205 --description "An agent job failed; see the linked run"
gh label create auto-merge --color 0e8a16 --description "The reviewer approved this implementer pull request to merge without the maintainer"
```

## Auto-merge

The `Auto-merge gate` job turns on auto-merge with the workflow's own token (`docs/agents/auto-merge.md`). It needs
**Settings → General → Pull Requests → Allow auto-merge** on, as `dependabot-auto-merge.yml` already does, and
squash merging allowed. Neither App needs a permission for it: the reviewer App's contents stay read-only.

To check the setup, have the reviewer approve a small implementer pull request that touches no human-only path: it
adds `auto-merge`, the `Auto-merge gate` job logs that auto-merge is on, and GitHub squash-merges the pull request
once `Verify (JVM)` and `Verify (native)` pass. On a pull request that touches `.github/`, the job logs the
human-only path and leaves the pull request open.
