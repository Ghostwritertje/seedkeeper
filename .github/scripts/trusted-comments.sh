#!/usr/bin/env bash
# The comments and reviews on an issue or pull request that reach the implementer. The repo is public, so anyone can
# comment: the implementer reads only what the trusted logins wrote. No network access of its own.
#
#   authors   Reads comments or reviews, as GET .../issues/{n}/comments or .../pulls/{n}/reviews return them (one or
#             more pages of JSON arrays), and prints each author's login once. The workflow checks which of them it
#             trusts.
#   keep      Reads the same, and prints those whose author is a login in <trusted.txt> (one per line, compared
#             case-insensitively), in order, as a JSON array of {author, at, body}, with a review's state too.
#
# Usage: gh api --paginate repos/<owner>/<repo>/issues/<n>/comments > comments.json
#        trusted-comments.sh authors < comments.json > authors.txt
#        trusted-comments.sh keep trusted.txt < comments.json > trusted-comments.json
set -euo pipefail

items='[inputs[] | {author: (.user.login // "ghost"), at: (.submitted_at // .created_at)}
        + (if has("state") then {state} else {} end) + {body}]'

case "${1:-}" in
  authors)
    jq -rn "$items"' | [.[].author] | reduce .[] as $a ([]; if index([$a]) then . else . + [$a] end) | .[]'
    ;;
  keep)
    jq -n --rawfile trusted "$2" "$items"'
      | ($trusted | ascii_downcase | split("\n") | map(gsub("^\\s+|\\s+$"; "") | select(. != ""))) as $trusted
      | map(select(.author | ascii_downcase as $a | $trusted | index([$a]) != null))'
    ;;
  *)
    echo "Usage: $0 authors | keep <trusted.txt>" >&2
    exit 2
    ;;
esac
