#!/usr/bin/env bash
# Table-driven tests for write-access.sh: whether a user's permission on the repo lets them start an agent. Each case
# is the permission JSON as GET /repos/{owner}/{repo}/collaborators/{username}/permission returns it, and the expected
# output. Needs only bash and jq.
# Usage: write-access.test.sh
set -euo pipefail

script="$(dirname "$0")/write-access.sh"
failures=0

check() {
  local name="$1" input="$2" expected="$3" actual
  actual="$(printf '%s' "$input" | "$script")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [$expected], got [$actual]" >&2
    failures=$((failures + 1))
  fi
}

check "an admin has write access" '{"permission":"admin","role_name":"admin"}' 'true'
check "a writer has write access" '{"permission":"write","role_name":"write"}' 'true'
check "a maintainer, reported as write, has write access" '{"permission":"write","role_name":"maintain"}' 'true'
check "a reader doesn't" '{"permission":"read","role_name":"read"}' 'false'
check "a triager, reported as read, doesn't" '{"permission":"read","role_name":"triage"}' 'false'
check "someone with no access doesn't" '{"permission":"none","role_name":"none"}' 'false'
check "a missing permission doesn't" '{}' 'false'
check "a permission in another case doesn't" '{"permission":"Admin"}' 'false'
check "no input at all doesn't" '' 'false'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
