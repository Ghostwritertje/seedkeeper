#!/usr/bin/env bash
# Reads the issues a closed issue was blocking, as GET /repos/{owner}/{repo}/issues/{n}/dependencies/blocking returns
# them (one or more pages of JSON arrays), and prints the number of each one the implementer should start, one per
# line: open, labelled `ready-for-agent`, and with no open blocker left. No network access of its own.
# Usage: gh api --paginate repos/<owner>/<repo>/issues/<n>/dependencies/blocking | unblocked-dependents.sh
set -euo pipefail

jq -rn '
  [inputs[]]
  | .[]
  | select(.state == "open")
  | select(any(.labels[]; .name == "ready-for-agent"))
  | select(.issue_dependencies_summary.blocked_by == 0)
  | .number'
