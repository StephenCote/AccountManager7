---
description: Orchestrate a change end-to-end across the specialist roster — plan, design review, implement, test, and verify — routing to the right agent at each step.
argument-hint: [what to build or fix]
allowed-tools: Read, Grep, Glob, Bash, Edit, Write, Agent
model: inherit
---

# Feature / change request

$ARGUMENTS

# You are the orchestrator (hub)

Subagents cannot call each other, so YOU route the work. Delegate each step to the right
teammate via the Agent tool, read what comes back, and decide the next hop using
`.claude/rules/troubleshooting.md` (the routing map) and `.claude/rules/architecture.md`.
Keep the main thread lean — push heavy work into subagents and act on their summaries.

First, determine the target module. If it's not obvious, ask or set `LOOP_MODULE=<ModuleDir>`
so the gate and specialists stay scoped.

## Pipeline (skip steps that clearly don't apply, but don't skip review or verification)

1. **Plan** — delegate to `planner`. Get the goal, affected modules, ordered steps, data/model
   impact, verification plan, and risks. Confirm the dependency direction stays legal.
2. **Design review (before coding)** — delegate to `architect` to check the plan against the
   architecture rules. If it returns CHANGES-NEEDED, revise the plan and re-check before proceeding.
3. **Implement** — route by what the change touches:
   - Java backend / schema / model / PBAC / persistence → `backend-specialist`
   - data appears missing/wrong, a query, or "the backend seems broken" → `query-specialist`
     (it runs the layer-isolation gate: reproduce against the raw API before blaming the backend)
   - Mithril UI (Ux752/Ux7) → `ux-specialist`
   - trivial/cross-cutting glue → do it yourself
   Give each specialist the plan and the specific slice it owns.
4. **Tests** — delegate to `test-author` to write and RUN real tests (no fake tests,
   `ensureSharedTestUser()` not admin, never reset the schema). Require actual pass/fail output.
5. **Verify & review** — delegate to `verifier` to run the full gate (`bash .claude/loop/verify.sh`)
   and review the diff. If the change touches auth, endpoints, secrets, or LLM prompt paths, also
   delegate to `security-reviewer`.
6. **Sign-off** — have `architect` do a final pass on the implemented diff. Resolve any
   CHANGES-NEEDED by looping back to the relevant specialist (step 3).

## Rules
- A reported failure is presumed a client/query error until the raw REST API is shown to
  misbehave — always run the query-specialist gate before changing backend code.
- Do not weaken or fake tests to pass. Do not commit or push unless explicitly asked.
- End with a concise summary: what changed, per-step verdicts, and the verification result.
