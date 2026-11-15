#!/usr/bin/env bash
# Table-driven tests for auto-merge-gate.sh: whether an agent pull request may merge without the maintainer, and why.
# Each case is whether the implementer opened the pull request, whether the reviewer's latest review approves it, its
# labels, its changed paths, and the expected decision. Needs only bash.
# Usage: auto-merge-gate.test.sh
set -euo pipefail

script="$(dirname "$0")/auto-merge-gate.sh"
failures=0

# check <name> <author is implementer> <latest review approves> <labels> <paths> <expected merge> <expected reason>
check() {
  local name="$1" implementer="$2" approves="$3" labels="$4" paths="$5" merge="$6" reason="$7" expected actual
  expected="merge=$merge"$'\n'"reason=$reason"
  actual="$(printf '%s' "$paths" | "$script" "$implementer" "$approves" "$labels")"
  if [ "$actual" = "$expected" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [${expected//$'\n'/ }], got [${actual//$'\n'/ }]" >&2
    failures=$((failures + 1))
  fi
}

code=$'src/main/java/be/seedkeeper/Verdicts.java\nsrc/test/java/be/seedkeeper/VerdictsTest.java'

check "a qualifying implementer pull request merges" \
  true true 'auto-merge' "$code" \
  true "the implementer opened it, the reviewer approved it, it carries auto-merge and touches no human-only path"
check "auto-merge among other labels still merges" \
  true true $'enhancement\nauto-merge\njava' "$code" \
  true "the implementer opened it, the reviewer approved it, it carries auto-merge and touches no human-only path"

check "a pull request someone else opened doesn't merge" \
  false true 'auto-merge' "$code" \
  false "the implementer didn't open it"
check "a latest review that isn't an approval doesn't merge" \
  true false 'auto-merge' "$code" \
  false "the reviewer's latest review doesn't approve it"
check "a missing auto-merge label doesn't merge" \
  true true $'enhancement\njava' "$code" \
  false "it doesn't carry auto-merge"
check "no labels at all doesn't merge" \
  true true '' "$code" \
  false "it doesn't carry auto-merge"
check "a label that only contains auto-merge doesn't count" \
  true true $'no-auto-merge\nauto-merge-later' "$code" \
  false "it doesn't carry auto-merge"
check "anything but true for the author doesn't merge" \
  yes true 'auto-merge' "$code" \
  false "the implementer didn't open it"
check "anything but true for the review doesn't merge" \
  true APPROVED 'auto-merge' "$code" \
  false "the reviewer's latest review doesn't approve it"

# Each human-only path, with the reason naming it.
human_only() {
  check "$1 is human-only" true true 'auto-merge' "$1" false "it touches the human-only path $1"
}
human_only .github/workflows/ci.yml
human_only .github/prompts/reviewer.md
human_only .github/scripts/auto-merge-gate.sh
human_only .claude/settings.json
human_only .claude/skills/tdd/SKILL.md
human_only CLAUDE.md
human_only apm.yml
human_only apm.lock.yaml
human_only docs/adr/0002-auto-merge-policy.md
human_only src/main/docker/Dockerfile.native-micro
human_only src/main/resources/db/migration/V2__keep_overrides.sql

# Claude Code also reads nested CLAUDE.md files and .claude/ directories.
check "a CLAUDE.md in a subdirectory is human-only too" \
  true true 'auto-merge' 'src/main/java/CLAUDE.md' \
  false "it touches the human-only path src/main/java/CLAUDE.md"
check "a .claude/ directory in a subdirectory is human-only too" \
  true true 'auto-merge' 'src/.claude/settings.json' \
  false "it touches the human-only path src/.claude/settings.json"
check "a .github/ directory in a subdirectory is human-only too" \
  true true 'auto-merge' 'src/.github/workflows/x.yml' \
  false "it touches the human-only path src/.github/workflows/x.yml"
check "a mix of allowed and human-only paths doesn't merge" \
  true true 'auto-merge' $'src/main/java/be/seedkeeper/Verdicts.java\n.github/workflows/reviewer.yml\nREADME.md' \
  false "it touches the human-only path .github/workflows/reviewer.yml"
check "paths that only look like human-only paths merge" \
  true true 'auto-merge' $'docs/adr.md\ndocs/agents/review.md\nsrc/main/dockerfiles/x\nsrc/main/resources/db/seed.sql\napm.yml.bak\nNOTCLAUDE.md\nsrc/x.github/y\nsrc/main/java/docs/adr/x.java\nGLOSSARY.md' \
  true "the implementer opened it, the reviewer approved it, it carries auto-merge and touches no human-only path"
check "no changed paths doesn't merge" \
  true true 'auto-merge' '' \
  false "it changes no paths"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
