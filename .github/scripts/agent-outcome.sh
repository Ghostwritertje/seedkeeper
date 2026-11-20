#!/usr/bin/env bash
# Decides whether an implementer run ended in one of its documented outcomes (docs/agents/issue-tracker.md), and
# prints two GITHUB_OUTPUT lines: `outcome=<outcome>` and `reason=<one sentence>`. The agent can end its run without
# any of them and still report success; this is how the workflow tells. No network access of its own.
#
#   agent-outcome.sh issue <issue number> <open pull requests file> < issue.json
#     issue.json: the issue, as `gh issue view <n> --json labels` prints it.
#     The pull requests file: the agent branch's open pull requests, as
#     `gh pr list --head <branch> --state open --json number,isDraft,body` prints them.
#     outcome=needs-decision  the issue has `needs-decision`
#     outcome=blocked         the issue has `agent:blocked`
#     outcome=pull-request    a ready pull request from the agent branch says `Closes #<n>`
#     outcome=none            none of these
#
#   agent-outcome.sh pull-request <head sha before the run> < pull-request.json
#     pull-request.json: the pull request, as `gh pr view <n> --json headRefOid,labels` prints it.
#     outcome=needs-decision  the pull request has `needs-decision`
#     outcome=blocked         the pull request has `agent:blocked`
#     outcome=pushed          its head moved during the run
#     outcome=none            none of these
set -euo pipefail

mode="$1"
shift

case "$mode" in
  issue)
    issue="$1" pulls="$2"
    jq -rn --argjson issue "$issue" --slurpfile pulls "$pulls" '
      input as $it
      | [$it.labels[]?.name] as $labels
      | ($pulls[0] // []) as $prs
      | "#\($issue)" as $ref
      | ($prs | map(select(.isDraft | not))) as $ready
      | ($ready | map(select((.body // "") | test("(?i)\\bcloses #\($issue)(?![0-9])")))) as $closing
      | if any($labels[]; . == "needs-decision") then
          "outcome=needs-decision", "reason=\($ref) has needs-decision."
        elif any($labels[]; . == "agent:blocked") then
          "outcome=blocked", "reason=\($ref) has agent:blocked."
        elif ($closing | length) > 0 then
          "outcome=pull-request", "reason=Pull request #\($closing[0].number) is ready and says Closes \($ref)."
        elif ($ready | length) > 0 then
          "outcome=none", "reason=Pull request #\($ready[0].number) is ready but its body has no Closes \($ref)."
        elif ($prs | length) > 0 then
          "outcome=none", "reason=Pull request #\($prs[0].number) is a draft, but \($ref) has no agent:blocked."
        else
          "outcome=none", "reason=No pull request, and no needs-decision or agent:blocked on \($ref)."
        end'
    ;;
  pull-request)
    before="$1"
    jq -r --arg before "$before" '
      [.labels[]?.name] as $labels
      | if any($labels[]; . == "needs-decision") then
          "outcome=needs-decision", "reason=The pull request has needs-decision."
        elif any($labels[]; . == "agent:blocked") then
          "outcome=blocked", "reason=The pull request has agent:blocked."
        elif (.headRefOid // "") != $before then
          "outcome=pushed", "reason=The branch moved from \($before[0:7]) to \(.headRefOid[0:7])."
        else
          "outcome=none", "reason=Nothing pushed, and no needs-decision or agent:blocked on the pull request."
        end'
    ;;
  *)
    echo "Usage: agent-outcome.sh issue <n> <pulls file> < issue.json | pull-request <sha> < pull-request.json" >&2
    exit 2
    ;;
esac
