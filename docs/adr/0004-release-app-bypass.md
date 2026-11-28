# A dedicated release App is the main ruleset's only bypass actor, and pushes only the POM bump after a release

After each release, `pom.xml` on `main` moves to the next patch snapshot (`Prepare X.Y.(Z+1)-SNAPSHOT`), so a build
of `main` reports a version past the last release. The `main` ruleset requires a pull request and the required
checks, so the last job of `.github/workflows/release.yml` pushes that commit as a dedicated GitHub App, the release
App, which is the ruleset's only bypass actor, set to **Always**. Its token asks for **Contents: write** only, and is
minted only in that job, after the image and the GitHub Release are out.

## Considered options

- **A dedicated App, not the reviewer App.** The reviewer App already has Contents write, so that its `Auto-merge
  gate` token can turn auto-merge on, and GitHub then merges as the reviewer App (ADR 0002). As a bypass actor on
  **Always**, those merges would skip the required checks, which ADR 0003 says no merge does, and the reviewer, which
  reads text a prompt injection can steer, would hold a token that can push to `main` directly. The release App has
  no other job, runs no agent, and its key is read only by the release workflow.
- **Not the workflow's own token.** `GITHUB_TOKEN` can't be a bypass actor, and a push made with it starts no
  workflow, so CI wouldn't run on the bump commit.
- **Not a pull request.** A bump pull request would need a review and a merge for every release: manual work for a
  one-line change that the release itself decides.
- **The next snapshot, not the released version.** Committing `X.Y.Z` itself is simpler, but every build of `main`
  after the release would then report the released version. `X.Y.(Z+1)-SNAPSHOT` is the Maven convention and says
  "after X.Y.Z". The release itself takes its version from the tag (a dispatched run bumps the latest `vX.Y.Z` tag),
  never from the POM, so the snapshot never has to guess the next bump.
- **Always, not pull requests only.** "Pull requests only" lets the App merge a pull request past the rules, not push.
  The bump is a push.

## Consequences

- The bump job checks out `main` as it is, not the released commit, and pushes without force. When `main` moved on
  in between, the push isn't a fast-forward and GitHub refuses it: nothing changes, and re-running only that job
  bumps on top of the new head. History stays linear. The POM only moves forward: a tag pushed for an older line, or
  a re-run after the bump went through, pushes nothing.
- If the bump job fails, the release still stands: the image, the tag and the GitHub Release are out before it runs.
- A push from the App token starts workflows, so CI runs on the bump commit, and the next dispatched release waits for
  it to pass, as it does for any head of `main`.
- No build job can use the release App's token: the secrets are read in the bump job only, and that job builds
  nothing but runs Maven to read and set the version. Adding the release App's secrets to another job, or another
  bypass actor to the ruleset, is a change to this decision.
- Setting up the App and the bypass is the maintainer's: `docs/agents/github-apps.md`, **The release App**.
