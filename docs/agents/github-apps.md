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

## Labels

The agents use these labels; `docs/agents/issue-tracker.md` describes their lifecycle. Create any that are
missing:

```sh
gh label create needs-decision --color d93f0b --description "Needs a decision from the maintainer"
gh label create agent:in-progress --color fbca04 --description "An agent is working on this issue"
gh label create agent:blocked --color b60205 --description "An agent job failed; see the linked run"
```
