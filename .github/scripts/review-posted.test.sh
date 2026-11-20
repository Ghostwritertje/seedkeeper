#!/usr/bin/env bash
# Table-driven tests for review-posted.sh: whether the reviewer submitted this round's summary review on the head
# commit. Each case is the pull request's reviews as GET /repos/{owner}/{repo}/pulls/{n}/reviews returns them, the
# round, and the expected output. Needs only bash and jq.
# Usage: review-posted.test.sh
set -euo pipefail

script="$(dirname "$0")/review-posted.sh"
failures=0

reviewer='seedkeeper-reviewer[bot]'
maintainer='Ghostwritertje'
head='e72238a000000000000000000000000000000000'
older='a8792eb000000000000000000000000000000000'

# review <author> <commit> <body>
review() {
  jq -nc --arg login "$1" --arg commit "$2" --arg body "$3" \
    '{user: {login: $login}, commit_id: $commit, state: "COMMENTED", body: $body}'
}

# The reviewer's summary review marked as round <n>, on <commit>.
summary() {
  review "$reviewer" "$2" "<!-- seedkeeper-review-round: $1 -->
## Standards

No findings."
}

check() {
  local name="$1" input="$2" round="$3" expected="$4" actual
  actual="$(printf '%s' "$input" | "$script" "$reviewer" "$head" "$round")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [$expected], got [$actual]" >&2
    failures=$((failures + 1))
  fi
}

check "no reviews at all" \
  '[]' 1 'posted=false'
check "this round's summary on the head is posted" \
  "[$(summary 1 "$head")]" 1 'posted=true'
check "an earlier round's summary isn't this round's" \
  "[$(summary 1 "$older")]" 2 'posted=false'
check "this round's summary after earlier rounds is posted" \
  "[$(summary 1 "$older"),$(summary 2 "$head")]" 2 'posted=true'
check "this round's marker on an older commit doesn't count" \
  "[$(summary 2 "$older")]" 2 'posted=false'
check "inline comments without the marker don't count" \
  "[$(review "$reviewer" "$head" ''),$(review "$reviewer" "$head" 'A finding')]" 1 'posted=false'
check "round 1 doesn't match the marker of round 10" \
  "[$(summary 10 "$head")]" 1 'posted=false'
check "someone else's review with the marker doesn't count" \
  "[$(review "$maintainer" "$head" '<!-- seedkeeper-review-round: 1 -->')]" 1 'posted=false'
check "reviews across pages count" \
  "[$(summary 1 "$older")][$(summary 2 "$head")]" 2 'posted=true'
check "a review with a null body doesn't count" \
  "[{\"user\":{\"login\":\"$reviewer\"},\"commit_id\":\"$head\",\"state\":\"COMMENTED\",\"body\":null}]" 1 \
  'posted=false'
check "logins match case-insensitively" \
  "[$(review 'Seedkeeper-Reviewer[bot]' "$head" '<!-- seedkeeper-review-round: 1 -->')]" 1 'posted=true'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
