#!/usr/bin/env bash
# Reads the merged implementer pull requests, as the GraphQL search in close-auto-merged-issues.yml returns them, and
# prints the issues to close, one "<issue> <pull request>" pair per line: each issue in a pull request's
# closingIssuesReferences, when the implementer App opened the pull request, `github-actions` merged it (the
# auto-merge gate's path), the issue is in <repo> and open, and the workflow hasn't already commented on it for that
# pull request (the comment has the marker <!-- seedkeeper-auto-merge-closed: #<pull request> -->). So an issue the
# maintainer reopened after that comment stays open. Logins are GraphQL's, without `[bot]`, and compare
# case-insensitively, as GitHub does. No network access of its own.
# Usage: gh api graphql ... | auto-merged-issues.sh <implementer App slug> <owner/repo>
set -euo pipefail

implementer="$1" repo="$2"

jq -r --arg implementer "$implementer" --arg repo "$repo" '
  def same($a; $b): ($a // "" | ascii_downcase) == ($b | ascii_downcase);
  .data.search.nodes[]
  | select(same(.author.login; $implementer) and same(.mergedBy.login; "github-actions"))
  | .number as $pr
  | .closingIssuesReferences.nodes[]
  | select(same(.repository.nameWithOwner; $repo) and .state == "OPEN")
  | select(any(.comments.nodes[];
      same(.author.login; "github-actions")
      and (.body // "" | contains("<!-- seedkeeper-auto-merge-closed: #\($pr) -->"))) | not)
  | "\(.number) \($pr)"'
