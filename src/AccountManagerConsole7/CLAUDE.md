# AccountManagerConsole7 — CLI / Console Entry Points

## MANDATORY rules — read first

Working-discipline rules (NO LYING, honesty, testing) live in **`../.claude/rules/llm-conduct.md`**.
Architecture and layering rules live in **`../.claude/rules/architecture.md`**.

## Role

`AccountManagerConsole7` (jar) provides the command-line / console entry points into the
AccountManager7 platform. It sits above `AccountManagerObjects7` (and, where relevant, the LLM/agent
services) and must respect the same layering: no business logic that belongs in Objects7/ISO42001, and
all data access goes through `AccessPoint` (PBAC is never bypassed).

## Notes

- Console paths that originate LLM calls consume the Objects7 prompt templates
  (`AccountManagerObjects7/src/main/resources/olio/llm/`) as-is.
- Follow the repo verification standard in `../.claude/rules/architecture.md` before claiming done.
