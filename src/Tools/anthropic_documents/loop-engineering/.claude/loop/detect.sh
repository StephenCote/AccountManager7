#!/usr/bin/env bash
# detect.sh — language-agnostic project detection for the loop-engineering system.
#
# Sets BUILD_CMD, TEST_CMD, LINT_CMD, E2E_CMD for the current repo.
# Resolution order (later wins):
#   1. built-in detection from files in the repo root
#   2. .claude/loop/loop.conf        (committed per-repo overrides)
#   3. .claude/loop/loop.local.conf  (gitignored personal overrides)
#   4. environment variables already set by the caller
#
# Any command may be empty ("") to skip that phase.
# This file is meant to be `source`d, not executed.

ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

: "${BUILD_CMD:=}"
: "${TEST_CMD:=}"
: "${LINT_CMD:=}"
: "${E2E_CMD:=}"

_has()  { [ -e "$ROOT/$1" ]; }
_glob() { compgen -G "$ROOT/$1" >/dev/null 2>&1; }

detect_stack() {
  # Java — Maven
  if _has "pom.xml"; then
    : "${BUILD_CMD:=mvn -q -B compile}"
    : "${TEST_CMD:=mvn -q -B test}"
  # Java/Kotlin — Gradle
  elif _has "build.gradle" || _has "build.gradle.kts"; then
    local g="gradle"; [ -x "$ROOT/gradlew" ] && g="./gradlew"
    : "${BUILD_CMD:=$g classes}"
    : "${TEST_CMD:=$g test}"
  fi

  # Node / JavaScript / TypeScript
  if _has "package.json"; then
    : "${BUILD_CMD:=npm run --if-present build}"
    : "${TEST_CMD:=npm test --silent}"
    : "${LINT_CMD:=npm run --if-present lint}"
    if _has "playwright.config.ts" || _has "playwright.config.js"; then
      : "${E2E_CMD:=npx playwright test}"
    fi
  fi

  # Python
  if _has "pyproject.toml" || _has "setup.py" || _has "pytest.ini" || _has "tox.ini"; then
    : "${TEST_CMD:=pytest -q}"
  fi

  # Go
  if _has "go.mod"; then
    : "${BUILD_CMD:=go build ./...}"
    : "${TEST_CMD:=go test ./...}"
  fi

  # Rust
  if _has "Cargo.toml"; then
    : "${BUILD_CMD:=cargo build}"
    : "${TEST_CMD:=cargo test}"
  fi

  # .NET
  if _glob "*.sln" || _glob "*.csproj"; then
    : "${BUILD_CMD:=dotnet build}"
    : "${TEST_CMD:=dotnet test}"
  fi

  # Generic Makefile fallback
  if _has "Makefile"; then
    : "${BUILD_CMD:=make build}"
    : "${TEST_CMD:=make test}"
  fi
}

detect_stack

[ -f "$ROOT/.claude/loop/loop.conf" ]       && . "$ROOT/.claude/loop/loop.conf"
[ -f "$ROOT/.claude/loop/loop.local.conf" ] && . "$ROOT/.claude/loop/loop.local.conf"

export ROOT BUILD_CMD TEST_CMD LINT_CMD E2E_CMD
