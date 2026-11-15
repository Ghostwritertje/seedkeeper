#!/usr/bin/env bash
# Reads a pull request labelled `ready-for-agent`, as GET /repos/{owner}/{repo}/pulls/{n} returns it, and prints
# whether the implementer starts on it: `start`, or `skip: <reason>`. It starts only on an open pull request that
# still carries the label, from a branch of this repository other than the default branch, written by a user or by
# the implementer App's bot (not Dependabot, not any other bot), and whose branch name has only letters, digits and
# ./_- (the name goes into the agent's tool allowlist). No network access of its own.
# Usage: gh api repos/<owner>/<repo>/pulls/<n> | pull-request-pick-up.sh <default branch> <implementer login>
set -euo pipefail

default_branch="$1" implementer="$2"

jq -r --arg default_branch "$default_branch" --arg implementer "$implementer" '
  if .state != "open" then "skip: the pull request is closed"
  elif any(.labels[]; .name == "ready-for-agent") | not then
    "skip: the pull request is no longer labelled ready-for-agent"
  elif .head.repo.full_name != .base.repo.full_name then "skip: the pull request comes from another repository"
  elif .user.login == "dependabot[bot]" then "skip: Dependabot pull requests merge on their own"
  elif .user.type != "User" and (.user.login | ascii_downcase) != ($implementer | ascii_downcase) then
    "skip: the pull request comes from a bot other than the implementer'"'"'s"
  elif .head.ref == $default_branch then "skip: the pull request comes from the default branch"
  elif .head.ref | test("^[A-Za-z0-9._/][A-Za-z0-9._/-]*$") | not then
    "skip: the branch name has characters other than letters, digits and ./_-"
  else "start"
  end'
