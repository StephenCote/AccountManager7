#!/usr/bin/env bash
# detect.sh — monorepo-aware, per-module detection for the loop system.
# Sourced by verify.sh. Provides:
#   ROOT                      -> the src/ root (dir that contains .claude/)
#   loop_target_modules       -> prints module dirs (relative to ROOT) to gate
#   loop_cmds_for <abs-dir>   -> sets BUILD_CMD/TEST_CMD/LINT_CMD/E2E_CMD for a module
#
# Module selection order:
#   1. $LOOP_MODULE           (e.g. LOOP_MODULE=AccountManagerUx752)
#   2. $MODULES               (space-separated, set in loop.conf)
#   3. modules with uncommitted git changes (staged, unstaged, untracked)
# A module is any immediate subdir of ROOT containing pom.xml or package.json.

_SELF="${BASH_SOURCE[0]}"
ROOT="$(cd "$(dirname "$_SELF")/../.." && pwd)"

_is_module() { [ -f "$ROOT/$1/pom.xml" ] || [ -f "$ROOT/$1/package.json" ]; }

loop_target_modules() {
  if [ -n "${LOOP_MODULE:-}" ]; then echo "$LOOP_MODULE"; return; fi
  if [ -n "${MODULES:-}" ];    then printf '%s\n' $MODULES; return; fi

  # Git prints paths relative to the repo root, which may be an ancestor of ROOT
  # (e.g. repo=.../AccountManager7, ROOT=.../AccountManager7/src). Compute that
  # prefix and strip it so the first path component is the module dir.
  local top prefix
  top="$(git -C "$ROOT" rev-parse --show-toplevel 2>/dev/null)"
  prefix=""
  if [ -n "$top" ] && [ "$top" != "$ROOT" ]; then prefix="${ROOT#"$top"/}/"; fi

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

[ -f "$ROOT/.claude/loop/loop.conf" ]       && . "$ROOT/.claude/loop/loop.conf"
[ -f "$ROOT/.claude/loop/loop.local.conf" ] && . "$ROOT/.claude/loop/loop.local.conf"

export ROOT
