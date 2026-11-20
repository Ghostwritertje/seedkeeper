#!/usr/bin/env bash
# Runs agent-browser with the browser limited to the app's hosts: it refuses to open, and the page to fetch, any other.
#   browser.sh <agent-browser command and flags>   e.g. browser.sh open http://localhost:8187/login
# The agent workflows allow this script and not agent-browser itself, so it refuses the flags that would widen the
# hosts: --allowed-domains beats the environment, and a --config file can set the list.
set -euo pipefail

hosts="localhost,127.0.0.1"

for arg in "$@"; do
  case "$arg" in
    --allowed-domains* | --config*)
      echo "browser.sh: $arg is refused: the browser reaches only $hosts." >&2
      exit 2
      ;;
  esac
done

unset AGENT_BROWSER_CONFIG
export AGENT_BROWSER_ALLOWED_DOMAINS="$hosts"
exec agent-browser "$@"
