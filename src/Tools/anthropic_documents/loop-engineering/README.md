# Loop Engineering for Claude Code

A reusable, language-agnostic system that turns Claude Code into a self-correcting
build → test → fix loop. Drop it into any repo. The real definition of "done" is a
**passing verification gate**, not the model's own judgment.

It works two ways:

- **Interactive loop** — a `Stop` hook re-runs your gate whenever Claude tries to
  finish. If the gate fails, Claude is automatically sent back to keep fixing
  (capped at 8 continuations by Claude Code, plus a `stop_hook_active` guard).
- **Headless loop** — `scripts/claude-loop.sh` wraps `claude -p` for unattended /
  CI runs, feeding failures back across iterations with `--continue`.

## What's in the box

```
.claude/
  CLAUDE.md            # working agreement Claude reads automatically
  settings.json        # registers the Stop hook + permission allow/deny rules
  loop/
    detect.sh          # auto-detects build/test/lint/e2e commands per language
    verify.sh          # runs the gate; prints VERIFY_OK / VERIFY_FAILED
    loop.conf.example  # copy to loop.conf to override commands per repo
  hooks/
    stop_verify.sh     # Stop hook: blocks "done" until the gate is green
  commands/
    loop.md            # /loop <goal> — interactive autonomous task
  agents/
    verifier.md        # independent review/verification subagent
scripts/
  claude-loop.sh       # headless runner for unattended / CI loops
```

## Install into a repo (e.g. C:\Projects\GitHub\AccountManager7)

1. Copy the `.claude/` folder and `scripts/` into the repo root.
2. From a Git Bash / terminal at the repo root:
   ```bash
   chmod +x .claude/loop/*.sh .claude/hooks/*.sh scripts/*.sh
   ```
3. Tell it your commands. Auto-detection covers Maven, Gradle, npm, Playwright,
   pytest, Go, Rust, .NET, and Make. To be explicit, copy the example and edit:
   ```bash
   cp .claude/loop/loop.conf.example .claude/loop/loop.conf
   ```
   For AccountManager7 (Java + Postgres + JS, JUnit system tests + Playwright UX):
   ```bash
   BUILD_CMD="mvn -q -B -DskipTests compile"
   TEST_CMD="mvn -q -B test"        # JUnit / system tests
   LINT_CMD=""                       # or spotless:check, etc.
   E2E_CMD="npx playwright test"     # UX tests (full gate only)
   ```
4. Confirm the gate runs: `bash .claude/loop/verify.sh --quick`
5. Add `.claude/settings.local.json` and `.claude/loop/loop.local.conf` to
   `.gitignore` (personal overrides); commit the rest so the team shares it.

## Use it

**Interactive** — open Claude Code in the repo and either just work normally
(the Stop hook enforces the gate on every finish) or drive a task explicitly:
```
/loop add server-side validation to the account transfer endpoint
```
Claude implements, runs the gate, fixes failures, and only stops once it's green.
Ask it to hand off to the `verifier` subagent for an independent review.

**Headless / CI**:
```bash
scripts/claude-loop.sh "Fix the failing AccountTransferTest and keep tests green" 6
```

## How the loop actually works (the mechanism)

Claude Code fires a `Stop` hook when the model finishes a turn. A hook that
returns `{"decision":"block","reason":"..."}` prevents the stop and feeds the
reason back into the conversation, so Claude keeps going. `stop_verify.sh` runs
your gate and only blocks when it fails — so "green tests" becomes the exit
criterion. Two safety limits prevent runaway loops: Claude Code stops overriding
after 8 consecutive blocks, and the hook no-ops when `stop_hook_active` is true.

## Customizing per language / project

- Simplest: set `BUILD_CMD` / `TEST_CMD` / `LINT_CMD` / `E2E_CMD` in `loop.conf`.
  Set any to `""` to skip a phase; multi-step values like
  `TEST_CMD="mvn -q -B test && npm test --silent"` are fine.
- New language: add a detection block to `.claude/loop/detect.sh`.
- Turn the gate off for a session: `export LOOP_VERIFY=off`.
- Loosen/tighten what Claude may run unattended: edit `permissions.allow` /
  `permissions.deny` in `.claude/settings.json`.

## Notes & caveats

- Hooks run through Git Bash on Windows; the scripts are POSIX bash.
- `git push` is denied by default and commits are left to you — remove those
  deny rules only if you want the loop to push.
- Some flags evolve between Claude Code versions. If something is rejected, run
  `claude --help` to confirm the current flag name (`--permission-mode`,
  `--allowedTools`, `--output-format`, `--continue`, `--bare`).
- Verified against the Claude Code docs for hooks, slash commands, sub-agents,
  headless mode, and settings.
