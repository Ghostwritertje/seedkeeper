#!/usr/bin/env bash
# Table-driven tests for review-round.sh: which review round the reviewer is about to submit on a pull request, and
# how it may hand a clear fix back. Each case is the pull request's reviews as GET
# /repos/{owner}/{repo}/pulls/{n}/reviews returns them, the pull request's author, and the expected output. Needs
# only bash and jq.
# Usage: review-round.test.sh
set -euo pipefail

script="$(dirname "$0")/review-round.sh"
failures=0

reviewer='seedkeeper-reviewer[bot]'
implementer='seedkeeper-implementer[bot]'
maintainer='Ghostwritertje'

# review <author> <body>
review() {
  jq -nc --arg login "$1" --arg body "$2" '{user: {login: $login}, state: "COMMENTED", body: $body}'
}

# A summary review the reviewer marked as round <n>.
round() {
  review "$reviewer" "<!-- seedkeeper-review-round: $1 -->
## Standards

No findings."
}

check() {
  local name="$1" input="$2" author="$3" expected="$4" actual
  actual="$(printf '%s' "$input" | "$script" "$reviewer" "$author" "$implementer")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [${expected//$'\n'/ }], got [${actual//$'\n'/ }]" >&2
    failures=$((failures + 1))
  fi
}

check "a first review of an implementer pull request hands back" \
  '[]' "$implementer" $'round=1\nhandback=ready-for-agent'
check "the third review still hands back" \
  "[$(round 1),$(round 2)]" "$implementer" $'round=3\nhandback=ready-for-agent'
check "after three rounds it asks for a decision" \
  "[$(round 1),$(round 2),$(round 3)]" "$implementer" $'round=4\nhandback=needs-decision'
check "after more than three rounds it still asks for a decision" \
  "[$(round 1),$(round 2),$(round 3),$(round 4)]" "$implementer" $'round=5\nhandback=needs-decision'
check "reviews across pages count" \
  "[$(round 1),$(round 2)][$(round 3)]" "$implementer" $'round=4\nhandback=needs-decision'
check "the reviewer's reviews without the marker don't count" \
  "[$(round 1),$(review "$reviewer" 'An older review'),$(review "$reviewer" '')]" "$implementer" \
  $'round=2\nhandback=ready-for-agent'
check "someone else's review with the marker doesn't count" \
  "[$(review "$maintainer" '<!-- seedkeeper-review-round: 1 -->'),$(review "$implementer" '<!-- seedkeeper-review-round: 2 -->'),$(review 'other[bot]' '<!-- seedkeeper-review-round: 3 -->')]" \
  "$implementer" $'round=1\nhandback=ready-for-agent'
check "a review with a null body doesn't count" \
  "[{\"user\":{\"login\":\"$reviewer\"},\"state\":\"COMMENTED\",\"body\":null}]" "$implementer" \
  $'round=1\nhandback=ready-for-agent'
check "logins match case-insensitively" \
  "[$(review 'Seedkeeper-Reviewer[bot]' '<!-- seedkeeper-review-round: 1 -->')]" 'Seedkeeper-Implementer[bot]' \
  $'round=2\nhandback=ready-for-agent'
check "the maintainer's pull request is never handed back" \
  '[]' "$maintainer" $'round=1\nhandback=none'
check "the maintainer's pull request is never handed back after three rounds either" \
  "[$(round 1),$(round 2),$(round 3)]" "$maintainer" $'round=4\nhandback=none'
check "another bot's pull request is never handed back" \
  '[]' 'renovate[bot]' $'round=1\nhandback=none'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
