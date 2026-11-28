#!/usr/bin/env bash
# Table-driven tests for ci-handback.sh: whether a failed CI run sends an implementer pull request back, which jobs
# failed, what of their logs the comment quotes, and the comment itself. Needs only bash and jq.
# Usage: ci-handback.test.sh
set -euo pipefail

script="$(dirname "$0")/ci-handback.sh"
failures=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

reviewer='seedkeeper-reviewer[bot]'
implementer='seedkeeper-implementer[bot]'
head=1111111111111111111111111111111111111111
older=2222222222222222222222222222222222222222

expect() { # name expected actual
  if [ "$3" = "$2" ]; then
    echo "ok   $1"
  else
    echo "FAIL $1: expected [${2//$'\n'/ }], got [${3//$'\n'/ }]" >&2
    failures=$((failures + 1))
  fi
}

# pull_request [jq update]: an open implementer pull request from this repository whose head is $head.
pull_request() {
  jq -nc --arg head "$head" --arg author "$implementer" "{
    state: \"open\", draft: false, user: {login: \$author}, labels: [],
    head: {sha: \$head, repo: {full_name: \"o/seedkeeper\"}}, base: {repo: {full_name: \"o/seedkeeper\"}}
  } | ${1:-.}"
}

# round <n> <commit>: a summary review the reviewer marked as round <n>, on <commit>.
round() {
  jq -nc --arg login "$reviewer" --arg body "<!-- seedkeeper-review-round: $1 -->" --arg commit "$2" \
    '{user: {login: $login}, state: "COMMENTED", body: $body, commit_id: $commit}'
}

# comment <author> <body>
comment() {
  jq -nc --arg login "$1" --arg body "$2" '{user: {login: $login}, body: $body}'
}

# decide <name> <expected handback> <pull request> <reviews> [comments]
decide() {
  local name="$1" expected="$2" actual
  printf '%s' "$3" > "$tmp/pr.json"
  printf '%s' "${5:-[]}" > "$tmp/comments.json"
  actual="$(printf '%s' "$4" | "$script" decide "$head" "$implementer" "$reviewer" "$tmp/pr.json" "$tmp/comments.json" \
    | sed -n 's/^handback=//p')"
  expect "$name" "$expected" "$actual"
}

decide "a first failure on an implementer pull request hands back" ready-for-agent "$(pull_request)" '[]'
decide "a failure after an approval of this head hands back" ready-for-agent \
  "$(pull_request)" "[$(round 1 "$older"),$(round 2 "$head")]"
decide "two rounds on earlier heads still hand back" ready-for-agent \
  "$(pull_request)" "[$(round 1 "$older"),$(round 2 "$older"),$(round 3 "$head")]"
decide "three rounds on earlier heads ask for a decision" needs-decision \
  "$(pull_request)" "[$(round 1 "$older"),$(round 2 "$older"),$(round 3 "$older")]"
decide "rounds across pages count" needs-decision \
  "$(pull_request)" "[$(round 1 "$older"),$(round 2 "$older")][$(round 3 "$older"),$(round 4 "$head")]"
decide "someone else's review with the marker doesn't count" ready-for-agent \
  "$(pull_request)" "[$(jq -c '.user.login = "Ghostwritertje"' <<<"$(round 1 "$older")"),$(round 2 "$older"),$(round 3 "$older")]"
decide "an outdated head isn't handed back" none "$(pull_request ".head.sha = \"$older\"")" '[]'
decide "a closed pull request isn't handed back" none "$(pull_request '.state = "closed"')" '[]'
decide "a draft isn't handed back" none "$(pull_request '.draft = true')" '[]'
decide "the maintainer's pull request isn't handed back" none "$(pull_request '.user.login = "Ghostwritertje"')" '[]'
decide "a Dependabot pull request isn't handed back" none "$(pull_request '.user.login = "dependabot[bot]"')" '[]'
decide "a pull request from a fork isn't handed back" none \
  "$(pull_request '.head.repo.full_name = "fork/seedkeeper"')" '[]'
decide "logins match case-insensitively" ready-for-agent \
  "$(pull_request '.user.login = "Seedkeeper-Implementer[bot]"')" '[]'
for label in ready-for-agent agent:in-progress needs-decision agent:blocked; do
  decide "a pull request labelled $label isn't handed back" none \
    "$(pull_request ".labels = [{name: \"bug\"}, {name: \"$label\"}]")" '[]'
done
decide "another label doesn't stop it" ready-for-agent "$(pull_request '.labels = [{name: "bug"}]')" '[]'
decide "a head already handed back isn't handed back again" none "$(pull_request)" '[]' \
  "[$(comment "$reviewer" "<!-- seedkeeper-ci-handback: $head -->
## CI failed")]"
decide "an earlier head's handback doesn't count" ready-for-agent "$(pull_request)" '[]' \
  "[$(comment "$reviewer" "<!-- seedkeeper-ci-handback: $older -->")]"
decide "someone else's marker doesn't count" ready-for-agent "$(pull_request)" '[]' \
  "[$(comment 'stranger' "<!-- seedkeeper-ci-handback: $head -->")]"

printf '%s' "$(pull_request ".head.sha = \"$older\"")" > "$tmp/pr.json"
expect "the reason names the outdated head" \
  $'handback=none\nreason=the run is for '"$head"', no longer the head' \
  "$(echo '[]' | "$script" decide "$head" "$implementer" "$reviewer" "$tmp/pr.json" "$tmp/comments.json")"

# failed-jobs
# shellcheck disable=SC2016 # the backticks are a job name's, for the script to strip
jobs='{"total_count":3,"jobs":[
  {"id":1,"name":"Verify (JVM)","html_url":"https://j/1","conclusion":"success","steps":[]},
  {"id":2,"name":"Verify (native)","html_url":"https://j/2","conclusion":"failure",
   "steps":[{"name":"Set up job","conclusion":"success"},{"name":"Run ./mvnw -B verify -Dnative","conclusion":"failure"},
            {"name":"Build the image","conclusion":"skipped"}]}]}
{"total_count":3,"jobs":[{"id":3,"name":"Lint `x`","html_url":"https://j/3","conclusion":"timed_out"}]}'
expect "failed-jobs lists the failed and timed-out jobs across pages, with the failed step" \
  '[{"id":2,"name":"Verify (native)","url":"https://j/2","step":"Run ./mvnw -B verify -Dnative"},{"id":3,"name":"Lint x","url":"https://j/3","step":null}]' \
  "$("$script" failed-jobs <<<"$jobs")"
expect "failed-jobs of a run with no failed job is empty" '[]' \
  "$("$script" failed-jobs <<<'{"jobs":[{"id":1,"name":"a","html_url":"u","conclusion":"cancelled"}]}')"

# excerpt
maven_log=$'2026-11-24T22:46:50.1234567Z ##[group]Run ./mvnw -B verify -Dnative\r
2026-11-24T22:46:50.2Z [INFO] Building seedkeeper\r
2026-11-24T22:46:51.0Z \e[1;31m[ERROR]\e[m Tests run: 3, Failures: 1, Errors: 0 <<< FAILURE! -- in GroupsIT\r
2026-11-24T22:46:51.1Z [ERROR] GroupsIT.listsGroups:42 expected: <200> but was: <500>\r
2026-11-24T22:46:51.2Z [ERROR] \r
2026-11-24T22:46:51.3Z [ERROR] -> [Help 1]\r
2026-11-24T22:46:51.4Z [ERROR] Re-run Maven using the -X switch to enable full debug logging.\r
2026-11-24T22:46:51.5Z ##[error]Process completed with exit code 1.\r'
expect "excerpt keeps the error and failure lines, without timestamps, colours or Maven's boilerplate" \
  $'[ERROR] Tests run: 3, Failures: 1, Errors: 0 <<< FAILURE! -- in GroupsIT\n[ERROR] GroupsIT.listsGroups:42 expected: <200> but was: <500>' \
  "$("$script" excerpt <<<"$maven_log")"
expect "excerpt keeps the CI scripts' FAIL lines and error annotations" \
  $'FAIL a case: expected [x], got [y]\n##[error]foo.yml:3:1: bad [syntax]' \
  "$("$script" excerpt <<<$'2026-11-24T00:00:00.0Z ok   another case\n2026-11-24T00:00:00.0Z FAIL a case: expected [x], got [y]\n2026-11-24T00:00:00.0Z ##[error]foo.yml:3:1: bad [syntax]')"
expect "excerpt without error lines gives the lines before the first error" \
  $'In script.sh line 3:\nfoo $bar\n    ^-- SC2086' \
  "$("$script" excerpt <<<$'In script.sh line 3:\nfoo $bar\n    ^-- SC2086\n##[error]Process completed with exit code 1.\nPost job cleanup.')"
expect "excerpt without any error gives the last 20 lines" "$(seq 6 25)" "$(seq 1 25 | "$script" excerpt)"
expect "excerpt keeps at most 30 lines" 30 "$(for i in $(seq 1 40); do echo "[ERROR] line $i"; done | "$script" excerpt | wc -l | tr -d ' ')"
expect "excerpt cuts long lines" 250 "$(printf '[ERROR] %0300d\n' 0 | "$script" excerpt | awk '{ print length }')"
expect "excerpt can't close the comment's fence" '[ERROR] ~~ ~~' "$("$script" excerpt <<<'[ERROR] ~~~~ ~~~')"

# comment
mkdir -p "$tmp/logs"
printf '%s' "$maven_log" > "$tmp/logs/2.log"
"$script" failed-jobs <<<"$jobs" > "$tmp/failed.json"
body="$("$script" comment "$head" https://run/9 ready-for-agent "$tmp/failed.json" "$tmp/logs")"
expect "the comment starts with the head's marker" "<!-- seedkeeper-ci-handback: $head -->" "$(head -n 1 <<<"$body")"
# shellcheck disable=SC2016 # the backticks are Markdown
expect "the comment links the run and names the short head" 1 "$(grep -c '^\[CI\](https://run/9) failed .*`1111111`' <<<"$body")"
expect "the comment lists each failed job with its step and log" \
  $'- `Verify (native)`, at step `Run ./mvnw -B verify -Dnative` ([log](https://j/2))\n- `Lint x` ([log](https://j/3))' \
  "$(grep '^- ' <<<"$body")"
expect "the comment quotes the failing test" 1 "$(grep -c '^\[ERROR\] GroupsIT.listsGroups:42' <<<"$body")"
expect "the comment says when a job has no log" 1 "$(grep -c '^(no log: open the job)$' <<<"$body")"
expect "a handback hands it to the implementer" 1 "$(grep -c 'Handing it back to the implementer' <<<"$body")"
expect "a handback asks for no decision" 0 "$(grep -c 'Decision needed' <<<"$body" || true)"
body="$("$script" comment "$head" https://run/9 needs-decision "$tmp/failed.json" "$tmp/logs")"
expect "past the cap it asks for a decision" 1 "$(grep -c '^## Decision needed$' <<<"$body")"
expect "past the cap it doesn't hand back" 0 "$(grep -c 'Handing it back' <<<"$body" || true)"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
