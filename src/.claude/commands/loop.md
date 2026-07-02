---
description: Autonomously implement a change in a module and keep working until that module's build + tests pass.
argument-hint: [what to build or fix]
allowed-tools: Read, Edit, Write, Grep, Glob, Bash
---

# Task

$ARGUMENTS

# How to work

Operate as an autonomous build→test→fix loop, scoped to the module you are
changing. This repo's rule stands: "tested" means a Vitest/Playwright or JUnit
test actually ran against real functionality — never a fake test, never
"looks done".

1. Identify the target module. If it is not obvious from the task, ask or set it:
   `export LOOP_MODULE=<ModuleDir>` (e.g. AccountManagerUx752).
2. Explore the relevant files and that module's CLAUDE.md/claude.md before editing.
3. Make the smallest change that moves toward the goal.
4. Run the gate yourself: `bash .claude/loop/verify.sh --quick`
   Before finishing, run the full gate (adds Playwright): `bash .claude/loop/verify.sh`
5. Read failures, fix, re-run. Repeat until you see `VERIFY_OK`.
6. Do not weaken, skip, or delete tests to go green. Do not use the admin user
   for tests — use `ensureSharedTestUser()`.
7. When green, hand off to the `verifier` subagent for an independent check,
   then summarize what changed. Do not commit or push unless asked.
