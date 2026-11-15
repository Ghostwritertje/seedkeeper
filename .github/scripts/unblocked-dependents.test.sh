#!/usr/bin/env bash
# Table-driven tests for unblocked-dependents.sh: which of the issues a closed issue was blocking the implementer
# starts. Each case is an issue list as GET /repos/{owner}/{repo}/issues/{n}/dependencies/blocking returns it, and
# the issue numbers expected on stdout, one per line. Needs only bash and jq.
# Usage: unblocked-dependents.test.sh
set -euo pipefail

script="$(dirname "$0")/unblocked-dependents.sh"
failures=0

# issue <number> <state> <open blockers> [label...]
issue() {
  local number="$1" state="$2" blockers="$3"
  shift 3
  jq -nc --argjson number "$number" --arg state "$state" --argjson blockers "$blockers" '
    {number: $number, state: $state,
     labels: ($ARGS.positional | map({name: .})),
     issue_dependencies_summary: {blocked_by: $blockers, total_blocked_by: ($blockers + 1)}}' --args "$@"
}

check() {
  local name="$1" input="$2" expected="$3" actual
  actual="$(printf '%s' "$input" | "$script")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [${expected//$'\n'/ }], got [${actual//$'\n'/ }]" >&2
    failures=$((failures + 1))
  fi
}

check "blocks nothing" '[]' ''
check "a labelled dependent with no open blocker starts" \
  "[$(issue 7 open 0 ready-for-agent)]" '7'
check "a dependent that is still blocked is left alone" \
  "[$(issue 7 open 1 ready-for-agent)]" ''
check "a dependent without ready-for-agent is left alone" \
  "[$(issue 7 open 0 enhancement)]" ''
check "a dependent with no labels is left alone" \
  "[$(issue 7 open 0)]" ''
check "a closed dependent is left alone" \
  "[$(issue 7 closed 0 ready-for-agent)]" ''
check "a dependent already in progress is left alone" \
  "[$(issue 7 open 0 agent:in-progress)]" ''
check "only the startable dependents of several start" \
  "[$(issue 7 open 0 ready-for-agent),$(issue 8 open 2 ready-for-agent),$(issue 9 open 0),$(issue 10 open 0 enhancement ready-for-agent)]" \
  $'7\n10'
check "dependents across pages" \
  "[$(issue 7 open 0 ready-for-agent)][$(issue 8 open 0 ready-for-agent)]" \
  $'7\n8'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
