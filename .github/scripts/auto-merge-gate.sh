#!/usr/bin/env bash
# The auto-merge gate: decides whether an agent pull request may merge without the maintainer. It may only if the
# implementer opened it, the reviewer's latest review approves it, it carries `auto-merge`, and none of its changed
# paths is human-only. Prints, as GITHUB_OUTPUT lines:
#   merge=true|false
#   reason=<why: the first condition that fails, or that all hold>
# Anything but `true` for the author or the review counts as false. No network access of its own. The policy:
# docs/agents/auto-merge.md and docs/adr/0002-auto-merge-policy.md.
# Usage: <changed paths, one per line> | auto-merge-gate.sh <author is implementer> <latest review approves> <labels>
set -euo pipefail

implementer="$1" approves="$2" labels="$3"

decide() {
  echo "merge=$1"
  echo "reason=$2"
  exit 0
}

[ "$implementer" = true ] || decide false "the implementer didn't open it"
[ "$approves" = true ] || decide false "the reviewer's latest review doesn't approve it"
grep -qxF auto-merge <<<"$labels" || decide false "it doesn't carry auto-merge"

# The human-only paths: an agent pull request that touches one never merges itself, whatever the reviewer says.
# .github/, .claude/ and CLAUDE.md count at any depth, because Claude Code also reads nested ones; the others count
# where they are in this repo.
human_only() {
  case "$1" in
    .github/* | */.github/* | .claude/* | */.claude/* | CLAUDE.md | */CLAUDE.md) return 0 ;;
    apm.yml | apm.lock.yaml) return 0 ;;
    docs/adr/*) return 0 ;;
    src/main/docker/*) return 0 ;;
    src/main/resources/db/migration/*) return 0 ;;
  esac
  return 1
}

paths=0
while IFS= read -r path || [ -n "$path" ]; do
  [ -n "$path" ] || continue
  paths=$((paths + 1))
  if human_only "$path"; then
    decide false "it touches the human-only path $path"
  fi
done
[ "$paths" -gt 0 ] || decide false "it changes no paths"

decide true "the implementer opened it, the reviewer approved it, it carries auto-merge and touches no human-only path"
