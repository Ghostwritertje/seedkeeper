#!/usr/bin/env bash
# Prints the version a run of release.yml publishes, as X.Y.Z, or fails with the reason. A pushed tag `vX.Y.Z` is
# released as X.Y.Z. A run started by hand (workflow_dispatch) must run on main and is given the version as input,
# as X.Y.Z without a `v`, so that it names the same concurrency group as the tag vX.Y.Z. No network access of its own.
# Usage: release-version.sh <event name> <ref name> [<version input>]
set -euo pipefail

event="$1" ref="$2" input="${3:-}"
semver='^[0-9]+\.[0-9]+\.[0-9]+$'

case "$event" in
  push)
    version="${ref#v}"
    if [[ "$ref" != v* || ! "$version" =~ $semver ]]; then
      echo "Tag '$ref' is not vX.Y.Z" >&2
      exit 1
    fi
    ;;
  workflow_dispatch)
    if [ "$ref" != "main" ]; then
      echo "A release is started from main, not from '$ref'" >&2
      exit 1
    fi
    version="$input"
    if [[ ! "$version" =~ $semver ]]; then
      echo "Version '$input' is not X.Y.Z (no leading v)" >&2
      exit 1
    fi
    ;;
  *)
    echo "release.yml doesn't run on '$event'" >&2
    exit 1
    ;;
esac
echo "$version"
