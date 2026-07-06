#!/usr/bin/env bash
# stop_verify.sh — Stop hook that turns Claude Code into a self-correcting loop.
#
# Registered on the "Stop" event. When Claude thinks it is done, this runs the
# project's verify gate. If the gate fails, it emits a JSON "block" decision,
# which prevents Claude from stopping and feeds the failure output back so it
# keeps fixing. If the gate passes, it stays silent and Claude stops normally.
#
# Safety: Claude Code caps this at 8 consecutive continuations, and we also
# honor `stop_hook_active` to avoid re-triggering while already looping.
set -uo pipefail

# Read the hook's JSON input from stdin.
INPUT="$(cat)"

# If we are already inside a stop-hook continuation, do nothing (belt & braces;
# Claude Code also enforces its own 8-continuation cap).
if command -v jq >/dev/null 2>&1; then
  ACTIVE="$(printf '%s' "$INPUT" | jq -r '.stop_hook_active // false')"
  [ "$ACTIVE" = "true" ] && exit 0
fi

# Opt-out switch: `export LOOP_VERIFY=off` (or set in loop.local.conf) disables the gate.
[ "${LOOP_VERIFY:-on}" = "off" ] && exit 0

DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
VERIFY="$DIR/.claude/loop/verify.sh"
[ -x "$VERIFY" ] || VERIFY="bash $DIR/.claude/loop/verify.sh"

# Run the quick gate on Stop (build + unit/system tests + lint; skip slow E2E).
OUTPUT="$($VERIFY --quick 2>&1)"
STATUS=$?

if [ $STATUS -eq 0 ]; then
  # Green — let Claude stop.
  exit 0
fi

# Red — block the stop and hand the failure back to Claude.
# Keep the reason bounded so it doesn't blow up the context.
REASON="$(printf '%s' "$OUTPUT" | tail -n 80)"

if command -v jq >/dev/null 2>&1; then
  jq -n --arg r "The verification gate is still failing. Fix these issues, then finish.

$REASON" '{decision:"block", reason:$r}'
else
  # Fallback without jq: exit code 2 also blocks the Stop and shows stderr to Claude.
  printf 'Verification gate failing:\n%s\n' "$REASON" >&2
  exit 2
fi
