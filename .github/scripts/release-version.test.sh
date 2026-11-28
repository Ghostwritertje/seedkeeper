#!/usr/bin/env bash
# Table-driven tests for release-version.sh: which version a run of release.yml publishes, from the event that
# started it, its ref, the bump input and the latest release tag, and which snapshot the POM moves to after it.
# Needs only bash.
# Usage: release-version.test.sh
set -euo pipefail

script="$(dirname "$0")/release-version.sh"
failures=0

# expected is `X.Y.Z next` (the released version and the POM's next snapshot), or `fail` when the script must refuse.
check() {
  local name="$1" event="$2" ref="$3" bump="$4" latest="$5" expected="$6" output actual
  if output="$("$script" "$event" "$ref" "$bump" "$latest" 2>/dev/null)"; then
    actual="$(sed -n 's/^version=//p' <<<"$output") $(sed -n 's/^next=//p' <<<"$output")"
    if [ "$(sed -n 's/^tag=//p' <<<"$output")" != "v${actual%% *}" ]; then
      actual="$actual, tag [$(sed -n 's/^tag=//p' <<<"$output")]"
    fi
  else
    actual="fail"
  fi
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [$expected], got [$actual]" >&2
    failures=$((failures + 1))
  fi
}

check "a pushed tag vX.Y.Z is released as X.Y.Z" push v0.1.0 '' '' '0.1.0 0.1.1-SNAPSHOT'
check "a pushed tag with more digits" push v10.20.30 '' '' '10.20.30 10.20.31-SNAPSHOT'
check "a pushed tag ignores the latest tag" push v0.5.1 '' v0.7.0 '0.5.1 0.5.2-SNAPSHOT'
check "a pushed tag without the v is refused" push 0.1.0 '' '' 'fail'
check "a pushed tag with a suffix is refused" push v0.1.0-rc1 '' '' 'fail'
check "a pushed tag with two parts is refused" push v0.1 '' '' 'fail'

check "a patch bump" workflow_dispatch main patch v0.5.0 '0.5.1 0.5.2-SNAPSHOT'
check "a minor bump" workflow_dispatch main minor v0.5.0 '0.6.0 0.6.1-SNAPSHOT'
check "a major bump" workflow_dispatch main major v0.5.0 '1.0.0 1.0.1-SNAPSHOT'
check "a minor bump resets the patch" workflow_dispatch main minor v0.5.3 '0.6.0 0.6.1-SNAPSHOT'
check "a major bump resets the minor and the patch" workflow_dispatch main major v1.4.2 '2.0.0 2.0.1-SNAPSHOT'
check "a patch bump past 9" workflow_dispatch main patch v0.9.9 '0.9.10 0.9.11-SNAPSHOT'
check "a patch bump without a tag starts from 0.0.0" workflow_dispatch main patch '' '0.0.1 0.0.2-SNAPSHOT'
check "a minor bump without a tag starts from 0.0.0" workflow_dispatch main minor '' '0.1.0 0.1.1-SNAPSHOT'
check "a major bump without a tag starts from 0.0.0" workflow_dispatch main major '' '1.0.0 1.0.1-SNAPSHOT'
check "a run by hand on another branch is refused" workflow_dispatch feature minor v0.5.0 'fail'
check "a run by hand on a tag is refused" workflow_dispatch v0.5.0 minor v0.5.0 'fail'
check "a run by hand without a bump is refused" workflow_dispatch main '' v0.5.0 'fail'
check "an unknown bump is refused" workflow_dispatch main huge v0.5.0 'fail'
check "a version as the bump is refused" workflow_dispatch main 0.6.0 v0.5.0 'fail'
check "a bump in capitals is refused" workflow_dispatch main Minor v0.5.0 'fail'
check "a latest tag without the v is refused" workflow_dispatch main minor 0.5.0 'fail'
check "a latest tag with a suffix is refused" workflow_dispatch main minor v0.5.0-rc1 'fail'
check "a latest tag with two parts is refused" workflow_dispatch main minor v0.5 'fail'
check "a latest tag with spaces is refused" workflow_dispatch main minor ' v0.5.0' 'fail'
check "another event is refused" pull_request main minor v0.5.0 'fail'

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
