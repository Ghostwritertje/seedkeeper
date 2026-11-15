#!/usr/bin/env bash
# Reads a pull request's reviews, as GET /repos/{owner}/{repo}/pulls/{n}/reviews returns them (one or more pages of
# JSON arrays), and prints, as GITHUB_OUTPUT lines, the round of the review the reviewer is about to submit and how
# it may hand a clear fix back:
#   round=<n>          the reviewer's earlier summary reviews with a round marker, plus one
#   handback=<label>   ready-for-agent on an implementer pull request for the first three rounds; needs-decision on
#                      one after three rounds; none on anybody else's pull request
# Only the reviewer's own reviews with the marker <!-- seedkeeper-review-round: <n> --> count. No network access of
# its own.
# Usage: gh api --paginate repos/<owner>/<repo>/pulls/<n>/reviews \
#          | review-round.sh <reviewer login> <pull request author> <implementer login>
set -euo pipefail

reviewer="$1" author="$2" implementer="$3"
max_rounds=3

earlier="$(jq -n --arg reviewer "$reviewer" '
  [inputs[]
   | select((.user.login // "" | ascii_downcase) == ($reviewer | ascii_downcase))
   | select((.body // "") | contains("<!-- seedkeeper-review-round:"))]
  | length')"

lower() { tr '[:upper:]' '[:lower:]' <<<"$1"; }

if [ "$(lower "$author")" != "$(lower "$implementer")" ]; then
  handback=none
elif [ "$earlier" -lt "$max_rounds" ]; then
  handback=ready-for-agent
else
  handback=needs-decision
fi

echo "round=$((earlier + 1))"
echo "handback=$handback"
