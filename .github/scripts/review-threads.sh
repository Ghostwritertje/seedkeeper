#!/usr/bin/env bash
# The review threads the implementer addresses on a pull request, and those it resolves. No network access of its
# own.
#
#   authors      Reads a pull request's reviewThreads, as the GraphQL query in address-review.yml returns them (one
#                or more pages), and prints the login of everyone with a comment on an unresolved thread, once each.
#                A bot's login ends in [bot], as REST gives it. The workflow checks which of them it trusts.
#   unresolved   Reads the same, and prints the unresolved threads that a login in <trusted.txt> (one per line,
#                compared case-insensitively) started, as a JSON array of
#                {id, path, line, outdated, comments: [{author, body}]}, with only the trusted logins' comments.
#                line is null on an outdated thread. The repo is public, so anyone can start or reply to a thread:
#                only what the trusted logins wrote reaches the implementer.
#   to-resolve   Reads the thread ids the implementer says it handled, one per line, and prints those that are in
#                <unresolved.json>, the array `unresolved` printed, once each and in the order given. So a run can
#                only resolve threads it was given.
#
# Usage: gh api graphql --paginate ... > threads.json
#        review-threads.sh authors < threads.json > authors.txt
#        review-threads.sh unresolved trusted.txt < threads.json > unresolved.json
#        review-threads.sh to-resolve unresolved.json < handled.txt
set -euo pipefail

# The unresolved threads, with each comment's author as REST names it: GraphQL drops a bot's [bot] suffix, and a
# deleted account has no author.
threads='
  [inputs.data.repository.pullRequest.reviewThreads.nodes[]
   | select(.isResolved | not)
   | {id, path, line, outdated: .isOutdated,
      comments: [.comments.nodes[]
        | {author: (if .author == null then "ghost"
                    elif .author.__typename == "Bot" and (.author.login | endswith("[bot]") | not)
                    then .author.login + "[bot]"
                    else .author.login end),
           body}]}]'

case "${1:-}" in
  authors)
    jq -rn "$threads"' | [.[].comments[].author] | reduce .[] as $a ([]; if index([$a]) then . else . + [$a] end)
      | .[]'
    ;;
  unresolved)
    jq -n --rawfile trusted "$2" "$threads"'
      | ($trusted | ascii_downcase | split("\n") | map(gsub("^\\s+|\\s+$"; "") | select(. != ""))) as $trusted
      | def trusted: ascii_downcase as $a | $trusted | index([$a]) != null;
      map(select(.comments[0].author // "" | trusted)
          | .comments |= map(select(.author | trusted)))'
    ;;
  to-resolve)
    jq -rR --slurpfile unresolved "$2" '
      [inputs | gsub("^\\s+|\\s+$"; "") | select(. != "")]
      | reduce .[] as $id ([]; if index([$id]) then . else . + [$id] end)
      | .[]
      | select(. as $id | $unresolved[0] | any(.id == $id))' -n
    ;;
  *)
    echo "Usage: $0 authors | unresolved <trusted.txt> | to-resolve <unresolved.json>" >&2
    exit 2
    ;;
esac
