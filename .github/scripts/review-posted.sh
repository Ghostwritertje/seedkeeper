#!/usr/bin/env bash
# Reads a pull request's reviews, as GET /repos/{owner}/{repo}/pulls/{n}/reviews returns them (one or more pages of
# JSON arrays), and prints, as a GITHUB_OUTPUT line, whether the reviewer submitted this round's summary review:
#   posted=true    a review by the reviewer, on the head commit, whose body has the marker
#                  <!-- seedkeeper-review-round: <round> -->
#   posted=false   otherwise
# The agent can end its run without reviewing and still report success; this is how the workflow tells. No network
# access of its own.
# Usage: gh api --paginate repos/<owner>/<repo>/pulls/<n>/reviews \
#          | review-posted.sh <reviewer login> <head sha> <round>
set -euo pipefail

reviewer="$1" head="$2" round="$3"

jq -rn --arg reviewer "$reviewer" --arg head "$head" --arg marker "<!-- seedkeeper-review-round: $round -->" '
  [inputs[]
   | select((.user.login // "" | ascii_downcase) == ($reviewer | ascii_downcase))
   | select(.commit_id == $head)
   | select((.body // "") | contains($marker))]
  | "posted=\(length > 0)"'
