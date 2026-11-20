#!/usr/bin/env bash
# Table-driven tests for agent-outcome.sh: whether an implementer run ended in one of its documented outcomes. Each
# issue case is the issue's labels and the agent branch's open pull requests; each pull request case is its labels
# and head. Both expect the outcome line. Needs only bash and jq.
# Usage: agent-outcome.test.sh
set -euo pipefail

script="$(dirname "$0")/agent-outcome.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

before='e72238a000000000000000000000000000000000'
after='a8792eb000000000000000000000000000000000'

# labels <name>...: a labels array as `gh ... --json labels` prints it.
labels() {
  jq -nc '[$ARGS.positional[] | {name: .}]' --args "$@"
}

# pull <number> <draft> <body>
pull() {
  jq -nc --argjson number "$1" --argjson draft "$2" --arg body "$3" '{number: $number, isDraft: $draft, body: $body}'
}

# check_issue <name> <labels> <pulls> <expected outcome line>
check_issue() {
  local name="$1" labels="$2" pulls="$3" expected="$4" actual
  printf '%s' "$pulls" > "$work/pulls.json"
  actual="$(jq -nc --argjson labels "$labels" '{labels: $labels}' | "$script" issue 88 "$work/pulls.json" | head -1)"
  report "$name" "$expected" "$actual"
}

# check_pull_request <name> <labels> <head> <expected outcome line>
check_pull_request() {
  local name="$1" labels="$2" head="$3" expected="$4" actual
  actual="$(jq -nc --argjson labels "$labels" --arg head "$head" '{headRefOid: $head, labels: $labels}' \
    | "$script" pull-request "$before" | head -1)"
  report "$name" "$expected" "$actual"
}

report() {
  if [ "$3" = "$2" ]; then
    echo "ok   $1"
  else
    echo "FAIL $1: expected [$2], got [$3]" >&2
    failures=$((failures + 1))
  fi
}

check_issue "nothing at all is no outcome" \
  "$(labels enhancement)" '[]' 'outcome=none'
check_issue "a ready pull request that closes the issue" \
  '[]' "[$(pull 102 false $'## Summary\n\nCloses #88')]" 'outcome=pull-request'
check_issue "Closes matches case-insensitively" \
  '[]' "[$(pull 102 false 'closes #88')]" 'outcome=pull-request'
check_issue "Closes another issue whose number starts the same doesn't count" \
  '[]' "[$(pull 102 false 'Closes #880')]" 'outcome=none'
check_issue "a ready pull request that doesn't close the issue is no outcome" \
  '[]' "[$(pull 102 false 'Part of #88')]" 'outcome=none'
check_issue "a pull request with a null body is no outcome" \
  '[]' '[{"number":102,"isDraft":false,"body":null}]' 'outcome=none'
check_issue "a draft pull request without agent:blocked is no outcome" \
  '[]' "[$(pull 102 true 'Closes #88')]" 'outcome=none'
check_issue "a draft pull request and agent:blocked" \
  "$(labels agent:blocked)" "[$(pull 102 true 'Closes #88')]" 'outcome=blocked'
check_issue "agent:blocked without a pull request" \
  "$(labels agent:blocked)" '[]' 'outcome=blocked'
check_issue "needs-decision without a pull request" \
  "$(labels enhancement needs-decision)" '[]' 'outcome=needs-decision'
check_issue "agent:in-progress alone is no outcome" \
  "$(labels agent:in-progress)" '[]' 'outcome=none'

printf '[]' > "$work/pulls.json"
reason="$(jq -nc '{labels: []}' | "$script" issue 88 "$work/pulls.json" | sed -n 's/^reason=//p')"
report "no outcome gives a reason" \
  'No pull request, and no needs-decision or agent:blocked on #88.' "$reason"

check_pull_request "nothing pushed and no label is no outcome" \
  '[]' "$before" 'outcome=none'
check_pull_request "a push is an outcome" \
  '[]' "$after" 'outcome=pushed'
check_pull_request "needs-decision" \
  "$(labels needs-decision)" "$before" 'outcome=needs-decision'
check_pull_request "agent:blocked" \
  "$(labels agent:blocked)" "$before" 'outcome=blocked'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
