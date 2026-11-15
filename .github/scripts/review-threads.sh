#!/usr/bin/env bash
# The review threads the implementer addresses on a pull request, and those it resolves. No network access of its
# own.
#
#   unresolved   Reads a pull request's reviewThreads, as the GraphQL query in address-review.yml returns them (one
#                or more pages), and prints the unresolved ones as a JSON array of
#                {id, path, line, outdated, comments: [{author, body}]}. line is null on an outdated thread.
#   to-resolve   Reads the thread ids the implementer says it handled, one per line, and prints those that are in
#                <unresolved.json>, the array `unresolved` printed, once each and in the order given. So a run can
#                only resolve threads it was given.
#
# Usage: gh api graphql --paginate ... | review-threads.sh unresolved > unresolved.json
#        review-threads.sh to-resolve unresolved.json < handled.txt
set -euo pipefail

case "$1" in
  unresolved)
    jq -n '
      [inputs.data.repository.pullRequest.reviewThreads.nodes[]
       | select(.isResolved | not)
       | {id, path, line, outdated: .isOutdated,
          comments: [.comments.nodes[] | {author: (.author.login // "ghost"), body}]}]'
    ;;
  to-resolve)
    jq -rR --slurpfile unresolved "$2" '
      [inputs | gsub("^\\s+|\\s+$"; "") | select(. != "")]
      | reduce .[] as $id ([]; if index([$id]) then . else . + [$id] end)
      | .[]
      | select(. as $id | $unresolved[0] | any(.id == $id))' -n
    ;;
  *)
    echo "Usage: $0 unresolved | to-resolve <unresolved.json>" >&2
    exit 2
    ;;
esac
