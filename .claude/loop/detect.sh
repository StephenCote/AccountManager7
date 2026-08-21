#!/usr/bin/env bash
# detect.sh — monorepo-aware, per-module detection for the loop system.
# Sourced by verify.sh. Provides:
#   PROJECT_ROOT              -> the dir that contains .claude/ (= the git/session root)
#   ROOT                      -> the MODULE root: the dir the module dirs actually sit in
#   loop_target_modules       -> prints module dirs (relative to ROOT) to gate
#   loop_cmds_for <abs-dir>   -> sets BUILD_CMD/TEST_CMD/LINT_CMD/E2E_CMD for a module
#
# Module selection order:
#   1. $LOOP_MODULE           (e.g. LOOP_MODULE=AccountManagerUx752)
#   2. $MODULES               (space-separated, set in loop.conf)
#   3. modules with uncommitted git changes (staged, unstaged, untracked)
# A module is any immediate subdir of ROOT containing pom.xml or package.json.

_SELF="${BASH_SOURCE[0]}"

# PROJECT_ROOT and ROOT are NOT the same thing here, and conflating them is a
# silent-vacuous-pass bug. Sessions open at the git root, so .claude/ lives there, but the
# Maven aggregator and every module live one level down, in src/. Deriving the module root as
# "the .claude parent" -- the pre-migration rule, valid only while .claude was at src/.claude
# -- makes _is_module false for every module, so loop_target_modules prints nothing and
# verify.sh reports VERIFY_OK having compiled and tested absolutely nothing.
PROJECT_ROOT="$(cd "$(dirname "$_SELF")/../.." && pwd)"

# Module root: explicit override first, else wherever the aggregator pom.xml actually is.
if [ -n "${LOOP_ROOT:-}" ]; then
  ROOT="$(cd "$LOOP_ROOT" && pwd)"
elif [ -f "$PROJECT_ROOT/pom.xml" ]; then
  ROOT="$PROJECT_ROOT"
elif [ -f "$PROJECT_ROOT/src/pom.xml" ]; then
  ROOT="$PROJECT_ROOT/src"
else
  ROOT="$PROJECT_ROOT"
fi

_is_module() { [ -f "$ROOT/$1/pom.xml" ] || [ -f "$ROOT/$1/package.json" ]; }

loop_target_modules() {
  if [ -n "${LOOP_MODULE:-}" ]; then echo "$LOOP_MODULE"; return; fi
  if [ -n "${MODULES:-}" ];    then printf '%s\n' $MODULES; return; fi

  # Git prints paths relative to the repo root, which may be an ancestor of ROOT
  # (e.g. repo=.../AccountManager7, ROOT=.../AccountManager7/src). Strip that
  # prefix so the first path component is the module dir.
  #
  # Ask git for the prefix directly rather than deriving it by string-subtracting
  # `rev-parse --show-toplevel` from `pwd`: on Windows/git-bash those two use
  # different path formats (`C:/Projects/...` vs `/c/Projects/...`), so the
  # subtraction silently leaves ROOT intact, nothing matches, zero modules are
  # selected, and verify.sh reports a VACUOUS "VERIFY_OK" having tested nothing.
  local prefix
  prefix="$(git -C "$ROOT" rev-parse --show-prefix 2>/dev/null)"

  {
    git -C "$ROOT" diff --name-only HEAD 2>/dev/null
    git -C "$ROOT" diff --name-only --cached HEAD 2>/dev/null
    git -C "$ROOT" ls-files --others --exclude-standard --full-name 2>/dev/null
  } | sed "s#^${prefix}##" | awk -F/ 'NF>1{print $1}' | sort -u | while read -r m; do
    [ -n "$m" ] && _is_module "$m" && echo "$m"
  done
}

loop_cmds_for() {
  local dir="$1"
  BUILD_CMD=""; TEST_CMD=""; LINT_CMD=""; E2E_CMD=""

  if [ -f "$dir/pom.xml" ]; then
    BUILD_CMD="mvn -q -B -DskipTests test-compile"
    TEST_CMD="mvn -q -B test"
  fi

  if [ -f "$dir/package.json" ]; then
    BUILD_CMD="npm run --if-present build"
    if grep -q '"vitest"' "$dir/package.json" 2>/dev/null; then
      TEST_CMD="npx vitest run"
    else
      TEST_CMD="npm run --if-present test"
    fi
    LINT_CMD="npm run --if-present lint"
    if ls "$dir"/playwright.config.* >/dev/null 2>&1; then
      E2E_CMD="npx playwright test"
    fi
  fi
}

# Config lives next to this script (PROJECT_ROOT/.claude/loop), which is no longer under ROOT.
_CFG="$(cd "$(dirname "$_SELF")" && pwd)"
[ -f "$_CFG/loop.conf" ]       && . "$_CFG/loop.conf"
[ -f "$_CFG/loop.local.conf" ] && . "$_CFG/loop.local.conf"

export PROJECT_ROOT ROOT
