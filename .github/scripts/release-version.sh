#!/usr/bin/env bash
# Works out the version a run of release.yml publishes, or fails with the reason. A pushed tag `vX.Y.Z` is released
# as X.Y.Z. A run started by hand (workflow_dispatch) must run on main and is given a bump, `patch`, `minor` or
# `major`, which it applies to the latest release tag `vX.Y.Z` (0.0.0 when there is none yet): the tags are what has
# really been released, not the POM. No network access of its own: the caller looks up the latest tag.
# Prints, for $GITHUB_OUTPUT:
#   version=X.Y.Z
#   tag=vX.Y.Z
#   next=X.Y.(Z+1)-SNAPSHOT   the POM version on main after the release
# Usage: release-version.sh <event name> <ref name> [<bump> [<latest tag>]]
set -euo pipefail

event="$1" ref="$2" bump="${3:-}" latest="${4:-}"
semver='^([0-9]+)\.([0-9]+)\.([0-9]+)$'

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
    base="${latest:-v0.0.0}"
    if [[ "$base" != v* || ! "${base#v}" =~ $semver ]]; then
      echo "The latest tag '$latest' is not vX.Y.Z" >&2
      exit 1
    fi
    major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}"
    case "$bump" in
      patch) version="$major.$minor.$((patch + 1))" ;;
      minor) version="$major.$((minor + 1)).0" ;;
      major) version="$((major + 1)).0.0" ;;
      *)
        echo "Bump '$bump' is not patch, minor or major" >&2
        exit 1
        ;;
    esac
    ;;
  *)
    echo "release.yml doesn't run on '$event'" >&2
    exit 1
    ;;
esac

[[ "$version" =~ $semver ]]
echo "version=$version"
echo "tag=v$version"
echo "next=${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))-SNAPSHOT"
