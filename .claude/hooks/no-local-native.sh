#!/usr/bin/env bash
# PreToolUse hook for Bash: the native build and the image run in CI only (see CLAUDE.md).
# Matches only at command position, so reading or grepping these names stays allowed.
set -euo pipefail

command="$(jq -r '.tool_input.command // empty')"
start='(^|[;&|(])[[:space:]]*(sudo[[:space:]]+)?'

if grep -Eq "${start}([[:alnum:]_./~-]*/)?mvnw?[[:space:]][^;&|]*-[DP]native" <<<"$command" \
  || grep -Eq "${start}(docker|podman|nerdctl)[[:space:]]+(buildx[[:space:]]+)?build\b" <<<"$command" \
  || grep -Eq "${start}(docker|podman|nerdctl)[[:space:]]+run\b[^;&|]*seedkeeper" <<<"$command" \
  || grep -Eq "${start}((bash|sh|exec)[[:space:]]+)?[[:alnum:]_./~-]*smoke-test-image\.sh" <<<"$command"; then
  cat >&2 <<'MSG'
Blocked: the native build, the image build and the image smoke test run in CI only. Locally they starve the
shared Docker VM and take the other containers down. Push the branch and run the native job there:
  gh workflow run ci.yml --ref "$(git branch --show-current)" && gh run watch
MSG
  exit 2
fi
