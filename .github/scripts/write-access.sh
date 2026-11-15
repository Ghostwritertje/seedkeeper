#!/usr/bin/env bash
# Reads a user's permission on the repo, as GET /repos/{owner}/{repo}/collaborators/{username}/permission returns it,
# and prints whether they have write access: `true` for admin or write (a maintainer is reported as write), `false`
# for anything else, including no input. Only a user with write access starts an agent, or is listened to by one. No
# network access of its own.
# Usage: gh api repos/<owner>/<repo>/collaborators/<user>/permission | write-access.sh
set -euo pipefail

jq -rn '[inputs] | first // {} | .permission == "admin" or .permission == "write"'
