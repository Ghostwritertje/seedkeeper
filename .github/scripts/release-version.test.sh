#!/usr/bin/env bash
# Table-driven tests for release-version.sh: which version a run of release.yml publishes, from the event that
# started it, its ref and the version input. Needs only bash.
# Usage: release-version.test.sh
set -euo pipefail

script="$(dirname "$0")/release-version.sh"
failures=0

# expected is the printed version, or `fail` when the script must refuse.
check() {
  local name="$1" event="$2" ref="$3" input="$4" expected="$5" actual
  if actual="$("$script" "$event" "$ref" "$input" 2>/dev/null)"; then :; else actual="fail"; fi
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [$expected], got [$actual]" >&2
    failures=$((failures + 1))
  fi
}

check "a pushed tag vX.Y.Z is released as X.Y.Z" push v0.1.0 '' '0.1.0'
check "a pushed tag with more digits" push v10.20.30 '' '10.20.30'
check "a pushed tag without the v is refused" push 0.1.0 '' 'fail'
check "a pushed tag with a suffix is refused" push v0.1.0-rc1 '' 'fail'
check "a pushed tag with two parts is refused" push v0.1 '' 'fail'
check "a run by hand on main takes the input" workflow_dispatch main '0.2.0' '0.2.0'
check "a run by hand with a leading v is refused" workflow_dispatch main 'v0.2.0' 'fail'
check "a run by hand on another branch is refused" workflow_dispatch feature '0.2.0' 'fail'
check "a run by hand on a tag is refused" workflow_dispatch v0.2.0 '0.2.0' 'fail'
check "a run by hand without a version is refused" workflow_dispatch main '' 'fail'
check "a run by hand with a suffix is refused" workflow_dispatch main '0.2.0-rc1' 'fail'
check "a run by hand with spaces is refused" workflow_dispatch main ' 0.2.0' 'fail'
check "another event is refused" pull_request main '0.2.0' 'fail'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
