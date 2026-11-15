#!/usr/bin/env bash
# Table-driven tests for pull-request-pick-up.sh: whether the implementer starts on a pull request labelled
# `ready-for-agent`. Each case is a pull request as GET /repos/{owner}/{repo}/pulls/{n} returns it, and the expected
# output: `start`, or `skip: <reason>`. Needs only bash and jq.
# Usage: pull-request-pick-up.test.sh
set -euo pipefail

script="$(dirname "$0")/pull-request-pick-up.sh"
failures=0

# pull_request [jq assignment...]: a startable pull request, changed by the assignments.
pull_request() {
  local changes='.'
  for change in "$@"; do changes="$changes | $change"; done
  jq -nc "{
    state: \"open\",
    user: {login: \"seedkeeper-implementer[bot]\"},
    labels: [{name: \"ready-for-agent\"}],
    head: {ref: \"agent/issue-7-add-a-verdict\", repo: {full_name: \"Ghostwritertje/seedkeeper\"}},
    base: {ref: \"main\", repo: {full_name: \"Ghostwritertje/seedkeeper\"}}
  } | $changes"
}

check() {
  local name="$1" input="$2" expected="$3" actual
  actual="$(printf '%s' "$input" | "$script" main)"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [$expected], got [$actual]" >&2
    failures=$((failures + 1))
  fi
}

check "an open, labelled agent pull request starts" "$(pull_request)" 'start'
check "the maintainer's pull request starts" "$(pull_request '.user.login = "Ghostwritertje"' '.head.ref = "fix-typo"')" 'start'
check "a branch with dots, underscores and digits starts" "$(pull_request '.head.ref = "ticket/35_v1.2"')" 'start'
check "a closed pull request is left alone" "$(pull_request '.state = "closed"')" \
  'skip: the pull request is closed'
check "a pull request no longer labelled ready-for-agent is left alone" \
  "$(pull_request '.labels = [{name: "agent:in-progress"}]')" \
  'skip: the pull request is no longer labelled ready-for-agent'
check "a pull request from a fork is left alone" \
  "$(pull_request '.head.repo.full_name = "someone/seedkeeper"')" \
  'skip: the pull request comes from another repository'
check "a pull request from a deleted fork is left alone" "$(pull_request '.head.repo = null')" \
  'skip: the pull request comes from another repository'
check "a Dependabot pull request is left alone" "$(pull_request '.user.login = "dependabot[bot]"')" \
  'skip: Dependabot pull requests merge on their own'
check "a pull request from the default branch is left alone" "$(pull_request '.head.ref = "main"')" \
  'skip: the pull request comes from the default branch'
check "a branch name with a space is left alone" "$(pull_request '.head.ref = "fix it"')" \
  'skip: the branch name has characters other than letters, digits and ./_-'
check "a branch name with a quote is left alone" "$(pull_request '.head.ref = "fix\"it"')" \
  'skip: the branch name has characters other than letters, digits and ./_-'
check "a branch name with a parenthesis is left alone" "$(pull_request '.head.ref = "fix)"')" \
  'skip: the branch name has characters other than letters, digits and ./_-'
check "a branch name with a comma is left alone" "$(pull_request '.head.ref = "a,b"')" \
  'skip: the branch name has characters other than letters, digits and ./_-'
check "a branch name starting with a dash is left alone" "$(pull_request '.head.ref = "-f"')" \
  'skip: the branch name has characters other than letters, digits and ./_-'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
