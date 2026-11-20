# Auto-merge

When an implementer pull request may merge without the maintainer. Two parts decide it: the reviewer agent judges the
change against the criteria below, and a deterministic gate then checks paths and facts the reviewer can't be
trusted with. The reviewer reads this doc from the pull request's base branch, so a change to it applies from the next
pull request on. Why the policy is what it is: `docs/adr/0002-auto-merge-policy.md`.

## The reviewer's part

The reviewer approves a pull request and adds `auto-merge` only when all of these hold:

- **The implementer App opened it.** Never a pull request a human opened, nor one another bot opened. The workflow
  only gives the reviewer the tools to approve and add the label on the implementer's pull requests.
- **The review has no findings,** on either axis, and needs no decision (`docs/agents/review.md`).
- **The change is a two-way door with a small blast radius:** none of the criteria below applies.

Otherwise it submits its summary review as a comment, adds no `auto-merge`, and says in the verdict which criterion
keeps the pull request for the maintainer.

### Hard to revert

- **A database migration:** a new or changed file under `src/main/resources/db/migration/`. Once a release has run
  it, the data is changed.
- **A persisted or on-disk format:** what seedkeeper writes and reads back later, such as the run history in the
  database or the keep overrides file.
- **Config keys users have already set:** renaming, removing or changing the meaning of a key in the config
  (`SeedkeeperConfig`). Adding a key with a default is fine.
- **Which data a Removal deletes:** any change to the Verdict or Removal behaviour, that is, to which verdict groups
  are removed and which files are deleted. Once released, a mistake deletes data for good.

### Large blast radius

- **Build, CI, release or image changes:** `pom.xml` build configuration, the Maven wrapper, workflows, the release,
  the Dockerfile.
- **Agent setup:** prompts, skills, `CLAUDE.md`, `.claude/`, the APM manifest and lock file, the agent docs.
- **New or upgraded dependencies or plugins.**
- **Anything security-sensitive:** authentication, secrets, permissions, input that crosses a trust boundary,
  anything that runs a shell or touches paths outside the downloads and library roots.

### What doesn't count

- **The size of the change.** A large change is fine when none of the criteria applies.
- **That behaviour changes.** The maintainer approved the issue; the pull request doesn't need approving twice.
  Verdict and Removal behaviour is the one exception, above.

## The gate

After a review, the `Auto-merge gate` job in `.github/workflows/reviewer.yml` runs
`.github/scripts/auto-merge-gate.sh` from the base branch. It turns on a squash auto-merge only when all of these
hold, and otherwise logs the reason and does nothing else:

- the implementer App opened the pull request;
- the reviewer App's latest review approves it, on the pull request's current head;
- it carries `auto-merge`;
- it touches no human-only path, counting both sides of a rename.

The human-only paths, enforced whatever the reviewer says:

- the `.github/` and `.claude/` directories, and `CLAUDE.md`, at any depth;
- the APM manifest and lock file, `apm.yml` and `apm.lock.yaml`;
- the ADR directory, `docs/adr/`;
- the Docker directory, `src/main/docker/`;
- the database migration directory, `src/main/resources/db/migration/`.

The gate turns on the auto-merge with a reviewer App token, so GitHub merges as the reviewer App. Not with the
workflow's own token: a merge made with it doesn't act on `Closes #<n>` and starts no workflow, not even CI on `main`
(#47). Only the gate's token writes contents; the reviewer agent's token stays read-only. GitHub then merges only
once the `main` ruleset is satisfied: `Verify (JVM)`, `Verify (native)`, `Verify (quality)`, `Lint (non-Java)` and
`Test the CI scripts` green, and every review thread resolved.

**A new push withdraws it.** Any push to an implementer pull request turns off its auto-merge and removes
`auto-merge` before the next review, so each head is approved on its own.

## What the maintainer does

- **To stop an auto-merge:** turn off auto-merge on the pull request. Removing `auto-merge` stops the gate from
  turning it on, but doesn't turn off an auto-merge that is already on.
- **To change the criteria or the human-only paths:** change this doc, the gate script and its tests, in a pull
  request you merge yourself. The gate script and its tests are on a human-only path. This doc isn't: the gate
  doesn't cover `docs/agents/`, so a change to it is held back by the reviewer's **Agent setup** criterion.
- **After an auto-merge:** the merge closes the pull request's `Closes #<n>` issues, as any merge does. The reviewer
  App closes them, not a user, so the issues the merged one was blocking aren't started on their own: start each one
  by hand. See `docs/agents/issue-tracker.md`, **Unblocked**.
