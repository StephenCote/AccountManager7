---
description: Autonomously implement a change and keep working until the verification gate (build + tests + lint) passes.
argument-hint: [what to build or fix]
allowed-tools: Read, Edit, Write, Grep, Glob, Bash
---

# Task

$ARGUMENTS

# How to work

Operate as an autonomous build→test→fix loop. Do not stop until the project's
verification gate passes.

1. **Explore first.** Read the relevant files and, if present, `CLAUDE.md` and
   `.claude/loop/loop.conf` to learn the build/test commands. Do not guess.
2. **Plan** a short list of concrete steps before editing.
3. **Implement** the smallest change that moves toward the goal.
4. **Verify** by running the gate yourself:
   `bash .claude/loop/verify.sh --quick`
   (add a full `bash .claude/loop/verify.sh` run, including E2E, before you finish).
5. **Read the failures**, fix them, and re-run. Repeat until you see `VERIFY_OK`.
6. When green, summarize what changed and why in a few sentences. Do not commit
   or push unless explicitly asked.

The Stop hook will re-run the gate when you try to finish; if it still fails you
will be sent back automatically, so treat a passing gate as the real exit
criterion, not your own sense of "done".
