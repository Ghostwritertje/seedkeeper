#!/usr/bin/env bash
# Reads an agent session's JSON log, the execution_file output of anthropics/claude-code-action (a JSON array of the
# session's messages), and prints a Markdown summary for $GITHUB_STEP_SUMMARY: the result message's turns, cost and
# number of permission denials, then every denial, with its tool name and its input (for Bash, the command), cut to
# 200 characters, and the agent's final message, cut to 1000. Only the result message is read, so the summary shows
# nothing else of the transcript. The inputs and the message are HTML-escaped inside <pre>, so they can't add Markdown
# of their own to the summary, and GitHub tokens in them are redacted. No network access.
# Usage: session-summary.sh <execution file> | tee -a "$GITHUB_STEP_SUMMARY"
#   tee: the job log shows it too, so `gh run view --log` has the denials and the final message.
#   An empty or missing path, as when the action step failed before it wrote the log, prints a note instead.
set -euo pipefail

file="${1:-}"

echo '## Agent session'
echo
if [ -z "$file" ] || [ ! -s "$file" ]; then
  echo 'No session log: the session ended before the action wrote one.'
  echo
  echo '### Permission denials'
  echo
  echo 'Unknown.'
  exit 0
fi

jq -rn '
  def html: gsub("&"; "&amp;") | gsub("<"; "&lt;") | gsub(">"; "&gt;");
  def redact: gsub("(gh[opsur]_|github_pat_)[A-Za-z0-9_]+"; "[redacted]");
  def cut($n): if length > $n then .[0:$n] + "…" else . end;
  def cents: (. * 100 | round) as $c
    | "$\($c / 100 | floor).\($c % 100 | tostring | if length < 2 then "0" + . else . end)";
  def input_text:
    if (.tool_input | type) == "object" and (.tool_input.command | type) == "string" then .tool_input.command
    elif (.tool_input | type) == "string" then .tool_input
    else .tool_input | tojson
    end;

  [inputs | if type == "array" then .[] else . end | select(type == "object" and .type == "result")] | last
  | if . == null then
      "No result message: the session crashed, timed out or was cancelled before it finished.\n",
      "### Permission denials\n",
      "Unknown."
    else
      (.permission_denials // []) as $denials
      | "| Turns | Cost | Permission denials |",
        "| --- | --- | --- |",
        "| \(.num_turns // "?") | \(.total_cost_usd | if type == "number" then cents else "?" end) | \($denials | length) |",
        "",
        "### Permission denials",
        "",
        if ($denials | length) == 0 then "None."
        else
          $denials | to_entries[]
          | "\(.key + 1). **\(.value.tool_name // "?" | tostring | html)**\n\n<pre>\(.value | input_text | redact | cut(200) | html)</pre>\n"
        end,
        "",
        "### Final message",
        "",
        if (.result | type) == "string" and (.result | length) > 0
        then "<pre>\(.result | redact | cut(1000) | html)</pre>"
        else "None."
        end
    end' "$file"
