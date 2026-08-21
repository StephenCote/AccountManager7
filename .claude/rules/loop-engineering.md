# Loop engineering (verification gate)

This project has an autonomous build→test→fix loop. The definition of "done" is
a **passing gate for the module you changed**, not the model's judgment.

## Commands
- Quick gate (build + tests, skips Playwright): `bash .claude/loop/verify.sh --quick`
- Full gate (adds Playwright/E2E): `bash .claude/loop/verify.sh`
- Scope to one module: `export LOOP_MODULE=AccountManagerUx752` (otherwise the
  gate targets modules with uncommitted git changes).

## Working agreement
- After any change, run the gate and fix failures until you see `VERIFY_OK`.
- Never weaken, skip, or delete tests to pass; never write fake tests; use
  `ensureSharedTestUser()`, not the admin user.
- Do not commit or push unless asked.
- When it looks done, hand off to the `verifier` subagent for an independent review.

## Autonomous mode (opt-in)
A `Stop` hook can force the loop automatically (blocks "done" until the gate is
green). It is **off by default**. Turn it on for a session only from a green
baseline: `export LOOP_VERIFY=on`.
