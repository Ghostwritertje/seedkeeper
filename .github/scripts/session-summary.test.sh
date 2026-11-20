#!/usr/bin/env bash
# Table-driven tests for session-summary.sh: the job summary of an agent session's permission denials. Each case is
# the session's JSON log, as claude-code-action's execution_file holds it, and a line the summary must or must not
# contain. Needs only bash and jq.
# Usage: session-summary.test.sh
set -euo pipefail

script="$(dirname "$0")/session-summary.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# result <turns> <cost> <denials as a JSON array>: a session log ending in a result message.
result() {
  jq -nc --argjson turns "$1" --argjson cost "$2" --argjson denials "$3" '[
    {type: "system", subtype: "init"},
    {type: "assistant", message: {content: [{type: "text", text: "a secret thought"}]}},
    {type: "result", subtype: "success", is_error: false, num_turns: $turns, total_cost_usd: $cost,
     permission_denials: $denials}]'
}

# denial <tool> <input as JSON>
denial() {
  jq -nc --arg tool "$1" --argjson input "$2" '{tool_name: $tool, tool_use_id: "toolu_1", tool_input: $input}'
}

bash_denial() {
  denial Bash "$(jq -nc --arg command "$1" '{command: $command}')"
}

summarize() {
  local log="$work/log.json"
  printf '%s' "$1" > "$log"
  "$script" "$log"
}

# check <name> <log> <contains|lacks> <text>
check() {
  local name="$1" log="$2" how="$3" text="$4" actual
  actual="$(summarize "$log")"
  if { [ "$how" = contains ] && [[ "$actual" == *"$text"* ]]; } \
    || { [ "$how" = lacks ] && [[ "$actual" != *"$text"* ]]; }; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected the summary to $how [$text], got:" >&2
    echo "$actual" >&2
    failures=$((failures + 1))
  fi
}

long="$(printf 'x%.0s' {1..250})"

check "no denials says None." \
  "$(result 12 0.5 '[]')" contains $'### Permission denials\n\nNone.'
check "the counts show turns, cost and denials" \
  "$(result 63 4.071 "[$(bash_denial 'git fetch')]")" contains "| 63 | \$4.07 | 1 |"
check "a cost under a dime keeps two decimals" \
  "$(result 1 0.05 '[]')" contains "| 1 | \$0.05 | 0 |"
check "a Bash denial shows its command" \
  "$(result 3 1 "[$(bash_denial 'gh api repos/x/y')]")" contains $'1. **Bash**\n\n<pre>gh api repos/x/y</pre>'
check "every denial is numbered" \
  "$(result 3 1 "[$(bash_denial 'one'),$(bash_denial 'two')]")" contains $'2. **Bash**\n\n<pre>two</pre>'
check "another tool shows its input as JSON" \
  "$(result 3 1 "[$(denial Write '{"file_path":"/etc/hosts"}')]")" contains '<pre>{"file_path":"/etc/hosts"}</pre>'
check "a long input is cut to 200 characters" \
  "$(result 3 1 "[$(bash_denial "$long")]")" contains "<pre>${long:0:200}…</pre>"
check "a long input loses the rest" \
  "$(result 3 1 "[$(bash_denial "$long")]")" lacks "${long:0:201}"
check "HTML in a command is escaped" \
  "$(result 3 1 "[$(bash_denial 'cat <<EOF > a && echo </pre>')]")" contains '<pre>cat &lt;&lt;EOF &gt; a &amp;&amp; echo &lt;/pre&gt;</pre>'
check "the rest of the transcript stays out" \
  "$(result 3 1 '[]')" lacks 'a secret thought'
check "a tool name that isn't a string still shows" \
  "$(result 3 1 '[{"tool_name":7,"tool_input":{"command":"ls"}}]')" contains $'1. **7**\n\n<pre>ls</pre>'
check "a log without a result says so" \
  '[{"type":"system","subtype":"init"}]' contains 'No result message'
check "a log as JSON lines is read too" \
  "$(result 7 1 '[]' | jq -c '.[]')" contains "| 7 | \$1.00 | 0 |"

actual="$("$script" '')"
if [[ "$actual" == *'No session log'* ]]; then
  echo "ok   no execution file says so"
else
  echo "FAIL no execution file: got [$actual]" >&2
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
