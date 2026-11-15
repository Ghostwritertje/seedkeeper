#!/usr/bin/env bash
# Table-driven tests for review-threads.sh: which of a pull request's review threads the implementer is given to
# address, and which of the threads it says it handled get resolved. Needs only bash and jq.
# Usage: review-threads.test.sh
set -euo pipefail

script="$(dirname "$0")/review-threads.sh"
failures=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# thread <id> <resolved> <outdated> <path> <line|null> [author body]...
thread() {
  local id="$1" resolved="$2" outdated="$3" path="$4" line="$5"
  shift 5
  jq -nc --arg id "$id" --argjson resolved "$resolved" --argjson outdated "$outdated" --arg path "$path" \
    --argjson line "$line" '
    {id: $id, isResolved: $resolved, isOutdated: $outdated, path: $path, line: $line,
     comments: {nodes: [$ARGS.positional | _nwise(2) | {author: {login: .[0]}, body: .[1]}]}}' --args "$@"
}

# page <thread>...: one page of the GraphQL response for a pull request's reviewThreads.
page() {
  jq -nc '{data: {repository: {pullRequest: {reviewThreads: {nodes: $ARGS.positional | map(fromjson)}}}}}' \
    --args "$@"
}

check() {
  local name="$1" actual="$2" expected="$3"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [${expected//$'\n'/ }], got [${actual//$'\n'/ }]" >&2
    failures=$((failures + 1))
  fi
}

unresolved() { printf '%s' "$1" | "$script" unresolved | jq -c .; }

check "no threads" "$(unresolved "$(page)")" '[]'
check "an unresolved thread is given with its comments" \
  "$(unresolved "$(page "$(thread T1 false false src/A.java 12 'seedkeeper-reviewer[bot]' 'Rename this.' Ghostwritertje 'Agreed.')")")" \
  '[{"id":"T1","path":"src/A.java","line":12,"outdated":false,"comments":[{"author":"seedkeeper-reviewer[bot]","body":"Rename this."},{"author":"Ghostwritertje","body":"Agreed."}]}]'
check "a resolved thread is left out" \
  "$(unresolved "$(page "$(thread T1 true false src/A.java 12 r 'x')" "$(thread T2 false false src/B.java 3 r 'y')")" | jq -c 'map(.id)')" \
  '["T2"]'
check "an outdated unresolved thread is given, marked outdated" \
  "$(unresolved "$(page "$(thread T1 false true src/A.java null r 'x')")" | jq -c 'map({id, line, outdated})')" \
  '[{"id":"T1","line":null,"outdated":true}]'
check "a comment whose author was deleted is given as ghost" \
  "$(printf '%s' "$(page "$(thread T1 false false a 1 r 'x')")" | jq -c '.data.repository.pullRequest.reviewThreads.nodes[0].comments.nodes[0].author = null' \
     | "$script" unresolved | jq -c '.[0].comments[0].author')" \
  '"ghost"'
check "threads across pages" \
  "$(unresolved "$(page "$(thread T1 false false a 1 r 'x')")$(page "$(thread T2 false false b 2 r 'y')")" | jq -c 'map(.id)')" \
  '["T1","T2"]'

printf '%s' "$(page "$(thread T1 false false a 1 r 'x')" "$(thread T2 false false b 2 r 'y')" "$(thread T3 true false c 3 r 'z')")" \
  | "$script" unresolved > "$tmp/unresolved.json"
to_resolve() { printf '%s' "$1" | "$script" to-resolve "$tmp/unresolved.json"; }

check "nothing handled resolves nothing" "$(to_resolve '')" ''
check "a handled unresolved thread is resolved" "$(to_resolve $'T1\n')" 'T1'
check "several handled threads are resolved once each, in the order given" "$(to_resolve $'T2\nT1\nT2\n')" $'T2\nT1'
check "a thread that wasn't given to address isn't resolved" "$(to_resolve $'T1\nT3\nT9\n')" 'T1'
check "blank lines and surrounding spaces are ignored" "$(to_resolve $'\n  T1  \n\nT2')" $'T1\nT2'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
