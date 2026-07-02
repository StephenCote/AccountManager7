#!/usr/bin/env bash
# claude-loop.sh — unattended headless loop for one module using `claude -p`.
# Usage: bash .claude/loop/claude-loop.sh "<goal>" <ModuleDir> [max_iterations]
# Run from the src/ directory. Requires the `claude` CLI and ANTHROPIC_API_KEY.
set -uo pipefail
GOAL="${1:?Usage: claude-loop.sh \"<goal>\" <ModuleDir> [max]}"
export LOOP_MODULE="${2:?Provide the module dir, e.g. AccountManagerUx752}"
MAX="${3:-6}"

ARGS=( --permission-mode acceptEdits --allowedTools "Bash,Read,Edit,Write,Grep,Glob" --output-format json )
jqr(){ command -v jq >/dev/null && jq -r '.result' || cat; }

echo "== iteration 1 (module: $LOOP_MODULE) =="
claude -p "$GOAL

Work only in module $LOOP_MODULE. When done run: bash .claude/loop/verify.sh
Keep fixing until it prints VERIFY_OK. Do not write fake tests." "${ARGS[@]}" | jqr

for i in $(seq 2 "$MAX"); do
  if bash .claude/loop/verify.sh; then echo "== passed after $((i-1)) iter =="; exit 0; fi
  FAIL="$(bash .claude/loop/verify.sh 2>&1 | tail -n 80)"
  echo "== iteration $i =="
  claude -p "The gate is still failing in $LOOP_MODULE:

$FAIL

Fix and re-run 'bash .claude/loop/verify.sh' until VERIFY_OK." --continue "${ARGS[@]}" | jqr
done
bash .claude/loop/verify.sh && { echo "== passed =="; exit 0; } || { echo "== still failing after $MAX =="; exit 1; }
