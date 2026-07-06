#!/usr/bin/env bash
# claude-loop.sh — unattended headless loop using `claude -p`.
#
# Runs Claude Code non-interactively against a goal, then verifies. If the gate
# fails, it feeds the failures back and continues the same session, up to N
# iterations. Use for CI or overnight runs.
#
# Usage:
#   scripts/claude-loop.sh "Implement X and make all tests pass" [max_iterations]
#
# Requirements: `claude` CLI on PATH, run from the repo root, ANTHROPIC_API_KEY set.
set -uo pipefail

GOAL="${1:?Usage: claude-loop.sh \"<goal>\" [max_iterations]}"
MAX="${2:-6}"
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

# Tools/permissions for an unattended run. acceptEdits lets Claude write files
# and use common fs commands without prompting; the explicit test/build commands
# cover the gate. Tighten or widen to taste.
COMMON_ARGS=(
  --permission-mode acceptEdits
  --allowedTools "Bash,Read,Edit,Write,Grep,Glob"
  --output-format json
)

echo "== iteration 1 =="
claude -p "$GOAL

When done, run: bash .claude/loop/verify.sh
Keep fixing until it prints VERIFY_OK." "${COMMON_ARGS[@]}" | tee /tmp/loop-out.json | (command -v jq >/dev/null && jq -r '.result' || cat)

for i in $(seq 2 "$MAX"); do
  if bash .claude/loop/verify.sh; then
    echo "== gate passed after $((i-1)) iteration(s) =="
    exit 0
  fi
  FAILURES="$(bash .claude/loop/verify.sh 2>&1 | tail -n 80)"
  echo "== iteration $i =="
  claude -p "The verification gate is still failing:

$FAILURES

Fix these and re-run 'bash .claude/loop/verify.sh' until it prints VERIFY_OK." \
    --continue "${COMMON_ARGS[@]}" | (command -v jq >/dev/null && jq -r '.result' || cat)
done

# Final check
if bash .claude/loop/verify.sh; then
  echo "== gate passed =="
  exit 0
fi
echo "== gate still failing after $MAX iterations =="
exit 1
