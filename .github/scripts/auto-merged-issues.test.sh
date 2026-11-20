#!/usr/bin/env bash
# Table-driven tests for auto-merged-issues.sh: which issues of the merged implementer pull requests the workflow
# closes. Each case is a GraphQL search response, as close-auto-merged-issues.yml asks for it, and the
# "<issue> <pull request>" pairs expected on stdout, one per line. Needs only bash and jq.
# Usage: auto-merged-issues.test.sh
set -euo pipefail

script="$(dirname "$0")/auto-merged-issues.sh"
failures=0

implementer='seedkeeper-implementer'
repo='Ghostwritertje/seedkeeper'

# comment <author> <body>
comment() {
  jq -nc --arg login "$1" --arg body "$2" '{author: {login: $login}, body: $body}'
}

# issue_in <repo> <number> <state> [comment...]
issue_in() {
  local in="$1" number="$2" state="$3"
  shift 3
  jq -nc --arg repo "$in" --argjson number "$number" --arg state "$state" '
    {number: $number, state: $state, repository: {nameWithOwner: $repo},
     comments: {nodes: ($ARGS.positional | map(fromjson))}}' --args "$@"
}

# issue <number> <state> [comment...], in this repository
issue() {
  issue_in "$repo" "$@"
}

# pr <number> <author> <merged by> [issue...]
pr() {
  local number="$1" author="$2" merger="$3"
  shift 3
  jq -nc --argjson number "$number" --arg author "$author" --arg merger "$merger" '
    {number: $number, author: {login: $author}, mergedBy: {login: $merger},
     closingIssuesReferences: {nodes: ($ARGS.positional | map(fromjson))}}' --args "$@"
}

# search [pr...]
search() {
  jq -nc '{data: {search: {nodes: ($ARGS.positional | map(fromjson))}}}' --args "$@"
}

check() {
  local name="$1" input="$2" expected="$3" actual
  actual="$(printf '%s' "$input" | "$script" "$implementer" "$repo")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [${expected//$'\n'/ }], got [${actual//$'\n'/ }]" >&2
    failures=$((failures + 1))
  fi
}

marker='<!-- seedkeeper-auto-merge-closed: #93 -->'

check "no merged pull requests" "$(search)" ''
check "an auto-merged implementer pull request closes its open issue" \
  "$(search "$(pr 93 "$implementer" github-actions "$(issue 86 OPEN)")")" '86 93'
check "logins compare case-insensitively" \
  "$(search "$(pr 93 Seedkeeper-Implementer GitHub-Actions "$(issue 86 OPEN)")")" '86 93'
check "a pull request the maintainer merged is left alone: GitHub closed its issues" \
  "$(search "$(pr 77 "$implementer" Ghostwritertje "$(issue 24 OPEN)")")" ''
check "a pull request the maintainer opened is left alone" \
  "$(search "$(pr 80 Ghostwritertje github-actions "$(issue 79 OPEN)")")" ''
check "a pull request another bot opened is left alone" \
  "$(search "$(pr 81 dependabot github-actions "$(issue 79 OPEN)")")" ''
check "a closed issue is left alone" \
  "$(search "$(pr 44 "$implementer" github-actions "$(issue 25 CLOSED)")")" ''
check "an issue in another repository is left alone" \
  "$(search "$(pr 93 "$implementer" github-actions "$(issue_in someone/else 86 OPEN)")")" ''
check "an issue reopened after the workflow's comment stays open" \
  "$(search "$(pr 93 "$implementer" github-actions \
    "$(issue 86 OPEN "$(comment github-actions "Closed by #93. $marker")")")")" ''
check "the marker from someone else doesn't keep an issue open" \
  "$(search "$(pr 93 "$implementer" github-actions \
    "$(issue 86 OPEN "$(comment Ghostwritertje "$marker")")")")" '86 93'
check "the marker for another pull request doesn't keep an issue open" \
  "$(search "$(pr 93 "$implementer" github-actions \
    "$(issue 86 OPEN "$(comment github-actions '<!-- seedkeeper-auto-merge-closed: #90 -->')")")")" '86 93'
check "a pull request with no closing issues" \
  "$(search "$(pr 93 "$implementer" github-actions)")" ''
check "every open closing issue of every auto-merged pull request" \
  "$(search \
    "$(pr 93 "$implementer" github-actions "$(issue 86 OPEN)" "$(issue 87 OPEN)" "$(issue 88 CLOSED)")" \
    "$(pr 77 "$implementer" Ghostwritertje "$(issue 24 OPEN)")" \
    "$(pr 71 "$implementer" github-actions "$(issue 57 OPEN)")")" \
  $'86 93\n87 93\n57 71'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
