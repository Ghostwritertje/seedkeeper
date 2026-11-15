#!/usr/bin/env bash
# The facts the auto-merge gate (auto-merge-gate.sh) decides on, from a pull request and its reviews. Reads the
# reviews, as GET /repos/{owner}/{repo}/pulls/{n}/reviews returns them (one or more pages of JSON arrays, oldest
# first), and prints, as GITHUB_OUTPUT lines:
#   implementer=true|false   the implementer App's bot opened the pull request in <pr.json>
#   approves=true|false      the reviewer App's latest review approves the pull request's current head
# Logins compare case-insensitively, as GitHub does. No network access of its own.
# Usage: gh api --paginate repos/<owner>/<repo>/pulls/<n>/reviews \
#          | auto-merge-inputs.sh <pr.json> <implementer login> <reviewer login>
set -euo pipefail

pr="$1" implementer="$2" reviewer="$3"

jq -rn --slurpfile pr "$pr" --arg implementer "$implementer" --arg reviewer "$reviewer" '
  def same($a; $b): ($a // "" | ascii_downcase) == ($b | ascii_downcase);
  $pr[0] as $pr
  | ([inputs[] | select(same(.user.login; $reviewer))] | last) as $latest
  | "implementer=\(same($pr.user.login; $implementer))",
    "approves=\($latest != null and $latest.state == "APPROVED" and $latest.commit_id == $pr.head.sha)"'
