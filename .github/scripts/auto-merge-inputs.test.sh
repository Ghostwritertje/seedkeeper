#!/usr/bin/env bash
# Table-driven tests for auto-merge-inputs.sh: the facts the auto-merge gate decides on, whether the implementer
# opened the pull request and whether the reviewer's latest review approves its current head. Each case is a pull
# request and its reviews as the REST API returns them. Needs only bash and jq.
# Usage: auto-merge-inputs.test.sh
set -euo pipefail

script="$(dirname "$0")/auto-merge-inputs.sh"
failures=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

implementer='seedkeeper-implementer[bot]' reviewer='seedkeeper-reviewer[bot]' head=abc123

# pull_request <author>: a pull request as GET /repos/{owner}/{repo}/pulls/{n} returns it, at head $head.
pull_request() { jq -nc --arg login "$1" --arg head "$head" '{user: {login: $login}, head: {sha: $head}}'; }

# review <login> <state> <commit>: a review as GET /repos/{owner}/{repo}/pulls/{n}/reviews returns it.
review() { jq -nc --arg login "$1" --arg state "$2" --arg commit "$3" '{user: {login: $login}, state: $state, commit_id: $commit}'; }

# page <review>...: one page of reviews, oldest first.
page() { jq -nc '$ARGS.positional | map(fromjson)' --args "$@"; }

# check <name> <pull request> <reviews> <expected implementer> <expected approves>
check() {
  local name="$1" expected actual
  printf '%s' "$2" > "$tmp/pr.json"
  expected="implementer=$4"$'\n'"approves=$5"
  actual="$(printf '%s' "$3" | "$script" "$tmp/pr.json" "$implementer" "$reviewer")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [${expected//$'\n'/ }], got [${actual//$'\n'/ }]" >&2
    failures=$((failures + 1))
  fi
}

agent="$(pull_request "$implementer")"

check "an implementer pull request the reviewer approved on its head" \
  "$agent" "$(page "$(review "$reviewer" APPROVED "$head")")" true true
check "a pull request a user opened" \
  "$(pull_request Ghostwritertje)" "$(page "$(review "$reviewer" APPROVED "$head")")" false true
check "another bot's pull request" \
  "$(pull_request 'renovate[bot]')" "$(page)" false false
check "logins match in any case" \
  "$(pull_request 'Seedkeeper-Implementer[bot]')" "$(page "$(review 'SEEDKEEPER-reviewer[bot]' APPROVED "$head")")" true true
check "no reviews" "$agent" "$(page)" true false
check "an approval of an earlier head doesn't approve this one" \
  "$agent" "$(page "$(review "$reviewer" APPROVED old456)")" true false
check "a later comment review replaces an approval" \
  "$agent" "$(page "$(review "$reviewer" APPROVED "$head")" "$(review "$reviewer" COMMENTED "$head")")" true false
check "a later approval replaces a comment review" \
  "$agent" "$(page "$(review "$reviewer" COMMENTED old456)" "$(review "$reviewer" APPROVED "$head")")" true true
check "someone else's approval doesn't count" \
  "$agent" "$(page "$(review Ghostwritertje APPROVED "$head")")" true false
check "someone else's later review doesn't replace the reviewer's" \
  "$agent" "$(page "$(review "$reviewer" APPROVED "$head")" "$(review Ghostwritertje COMMENTED "$head")")" true true
check "the latest review across pages counts" \
  "$agent" "$(page "$(review "$reviewer" APPROVED "$head")")$(page "$(review "$reviewer" COMMENTED "$head")")" true false
check "a review by a deleted account doesn't count" \
  "$agent" "$(page "$(review "$reviewer" APPROVED "$head")" | jq -c '.[0].user = null')" true false

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
