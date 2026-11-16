#!/usr/bin/env bash
# Reads an issue's events, as GET /repos/{owner}/{repo}/issues/{n}/events returns them (one or more pages of JSON
# arrays, oldest first), and prints the login of whoever last added `ready-for-agent`. Prints nothing when nobody
# did, or when the last to add it is not a user (a bot or an App). No network access of its own.
# Usage: gh api --paginate repos/<owner>/<repo>/issues/<n>/events | ready-for-agent-labeller.sh
set -euo pipefail

jq -rn '
  [inputs[] | select(.event == "labeled" and .label.name == "ready-for-agent")]
  | last
  | select(. != null and .actor.type == "User")
  | .actor.login'
