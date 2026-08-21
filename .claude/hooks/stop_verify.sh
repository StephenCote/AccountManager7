#!/usr/bin/env bash
# stop_verify.sh — Stop hook. DISABLED by default; enable per session with:
#   export LOOP_VERIFY=on         (or set LOOP_VERIFY=on in loop.local.conf)
# When on, runs the quick per-module gate; if it fails, blocks the stop and
# feeds failures back so Claude keeps fixing (Claude Code caps at 8 continuations).
set -uo pipefail
INPUT="$(cat)"

# opt-in switch (default off)
if [ "${LOOP_VERIFY:-off}" != "on" ]; then exit 0; fi

# avoid re-entrancy while already looping
if command -v jq >/dev/null 2>&1; then
  [ "$(printf '%s' "$INPUT" | jq -r '.stop_hook_active // false')" = "true" ] && exit 0
fi

DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
OUTPUT="$(bash "$DIR/.claude/loop/verify.sh" --quick 2>&1)"; STATUS=$?
[ $STATUS -eq 0 ] && exit 0

REASON="$(printf '%s' "$OUTPUT" | tail -n 80)"
if command -v jq >/dev/null 2>&1; then
  jq -n --arg r "The verification gate is still failing. Fix these issues, then finish.

$REASON" '{decision:"block", reason:$r}'
else
  printf 'Verification gate failing:\n%s\n' "$REASON" >&2
  exit 2
fi
