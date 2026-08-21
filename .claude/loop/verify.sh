#!/usr/bin/env bash
# verify.sh — run the per-module gate (build -> test -> lint [-> e2e]) and report.
# Usage: verify.sh [--quick]     (--quick skips E2E/Playwright)
# Exit 0 = all targeted modules passed. Non-zero = at least one failed.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/detect.sh"

QUICK=0; [ "${1:-}" = "--quick" ] && QUICK=1
LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT

mapfile -t MODS < <(loop_target_modules)
if [ "${#MODS[@]}" -eq 0 ]; then
  echo "No target module (no changed module and no LOOP_MODULE set) — nothing to verify."
  echo "VERIFY_OK"; exit 0
fi

run_phase() {
  local name="$1" cmd="$2" dir="$3"
  [ -z "$cmd" ] && return 0
  echo "==> [$(basename "$dir")] $name: $cmd"
  if ( cd "$dir" && eval "$cmd" ) >"$LOG" 2>&1; then
    echo "    PASS ($name)"; return 0
  fi
  echo "    FAIL ($name) in $(basename "$dir")"
  echo "----- last 60 lines -----"; tail -n 60 "$LOG"; echo "-------------------------"
  return 1
}

fail=0
for m in "${MODS[@]}"; do
  dir="$ROOT/$m"
  loop_cmds_for "$dir"
  run_phase "build" "$BUILD_CMD" "$dir" || { fail=1; continue; }
  run_phase "test"  "$TEST_CMD"  "$dir" || { fail=1; continue; }
  run_phase "lint"  "$LINT_CMD"  "$dir" || { fail=1; continue; }
  if [ $QUICK -eq 0 ]; then run_phase "e2e" "$E2E_CMD" "$dir" || fail=1; fi
done

[ $fail -eq 0 ] && echo "VERIFY_OK" || echo "VERIFY_FAILED"
exit $fail
