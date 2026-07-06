#!/usr/bin/env bash
# verify.sh — run the project's gate (build -> test -> lint -> e2e) and report.
#
# Usage:
#   verify.sh            # run all configured phases
#   verify.sh --quick    # skip E2E (Playwright etc.) for a faster inner loop
#
# Exit code 0 = everything passed. Non-zero = at least one phase failed.
# On failure, a concise summary of the failing phase's output is printed to
# stdout so a caller (or a Claude hook) can feed it back into the loop.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/detect.sh"

QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

run_phase() {
  local name="$1" cmd="$2"
  [ -z "$cmd" ] && return 0
  echo "==> $name: $cmd"
  if ( cd "$ROOT" && eval "$cmd" ) >"$LOG" 2>&1; then
    echo "    PASS ($name)"
    return 0
  fi
  echo "    FAIL ($name)"
  echo "----- last 60 lines of $name output -----"
  tail -n 60 "$LOG"
  echo "------------------------------------------"
  return 1
}

fail=0
run_phase "build" "$BUILD_CMD" || fail=1
[ $fail -eq 0 ] && { run_phase "test" "$TEST_CMD" || fail=1; }
[ $fail -eq 0 ] && { run_phase "lint" "$LINT_CMD" || fail=1; }
if [ $fail -eq 0 ] && [ $QUICK -eq 0 ]; then
  run_phase "e2e" "$E2E_CMD" || fail=1
fi

if [ $fail -eq 0 ]; then
  echo "VERIFY_OK"
else
  echo "VERIFY_FAILED"
fi
exit $fail
