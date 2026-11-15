#!/usr/bin/env bash
# Table-driven tests for ready-for-agent-labeller.sh: which user last added `ready-for-agent` to an issue. Each case
# is an event list as GET /repos/{owner}/{repo}/issues/{n}/events returns it, oldest first, and the login expected
# on stdout. Needs only bash and jq.
# Usage: ready-for-agent-labeller.test.sh
set -euo pipefail

script="$(dirname "$0")/ready-for-agent-labeller.sh"
failures=0

# event <event> <label> <login> <type>
event() {
  jq -nc --arg event "$1" --arg label "$2" --arg login "$3" --arg type "$4" '
    {event: $event, actor: {login: $login, type: $type}}
    + (if $label == "" then {} else {label: {name: $label}} end)'
}

check() {
  local name="$1" input="$2" expected="$3" actual
  actual="$(printf '%s' "$input" | "$script")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [$expected], got [$actual]" >&2
    failures=$((failures + 1))
  fi
}

check "never labelled" '[]' ''
check "the user who added it" \
  "[$(event labeled ready-for-agent alice User)]" 'alice'
check "the latest of several" \
  "[$(event labeled ready-for-agent alice User),$(event unlabeled ready-for-agent 'seedkeeper-implementer[bot]' Bot),$(event labeled ready-for-agent bob User)]" \
  'bob'
check "other labels don't count" \
  "[$(event labeled ready-for-agent alice User),$(event labeled needs-decision bob User)]" 'alice'
check "other events don't count" \
  "[$(event labeled ready-for-agent alice User),$(event commented '' bob User)]" 'alice'
check "a bot that added it doesn't count" \
  "[$(event labeled ready-for-agent 'seedkeeper-reviewer[bot]' Bot)]" ''
check "a bot that re-added it after a user doesn't count" \
  "[$(event labeled ready-for-agent alice User),$(event labeled ready-for-agent 'seedkeeper-reviewer[bot]' Bot)]" ''
check "events across pages" \
  "[$(event labeled ready-for-agent alice User)][$(event labeled ready-for-agent bob User)]" 'bob'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
