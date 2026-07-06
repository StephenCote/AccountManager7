# Loop-engineering conventions

This repo uses an autonomous build→test→fix loop. The real definition of "done"
is a passing verification gate, not the model's own judgment.

## Commands
- Verification gate (build + tests + lint, skip slow E2E): `bash .claude/loop/verify.sh --quick`
- Full gate (adds Playwright / E2E): `bash .claude/loop/verify.sh`
- Per-repo command overrides live in `.claude/loop/loop.conf` (auto-detected otherwise).

## Working agreement for Claude
- Explore and read before editing. Prefer the smallest change that satisfies the goal.
- After any change, run the gate yourself and fix failures until you see `VERIFY_OK`.
- Never weaken, skip, or delete tests to make the gate pass. If a test is wrong,
  explain why before changing it.
- Do not commit unless asked; never `git push`.
- When the change looks complete, hand off to the `verifier` subagent for an
  independent review before declaring success.

## Notes
- A `Stop` hook re-runs the quick gate automatically when you try to finish and
  sends you back if it fails, so iterate until it's genuinely green.
