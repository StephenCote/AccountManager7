# AccountManagerAgent7 — Agent Runtime

## MANDATORY rules — read first

Working-discipline rules (NO LYING, honesty, testing) live in **`../.claude/rules/llm-conduct.md`**.
Architecture and layering rules live in **`../.claude/rules/architecture.md`**.

## Role

This module provides the agent runtime layer that interfaces with the LLM services. It depends on
Objects7's prompt templates (`AccountManagerObjects7/src/main/resources/olio/llm/`) and consumes them
as-is when originating LLM calls.

## Design docs

- `CHAIN_PLAN.md` — agent chain design/build plan.
