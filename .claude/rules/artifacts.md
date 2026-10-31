# Artifacts — logs, test images, scratch files: where they go, when they are cleaned up

Recorded 2026-10-31 after Stephen found ~7 GB / 1,487 entries of session scratch in `C:\tmp` — a
directory he also uses for his own files — and asked for a rule. This file is the rule; the live
register of every artifact location and its current state is `src/aiDocs/ArtifactInventory.md`.

## Where things go

| Kind | Home | Why |
|---|---|---|
| Helper scripts, captured run output (`mvn … > x.log`, Playwright stdout, probe JSON), doc-pass scripts, cleanup manifests | `<git root>/scratch/<slice>/` — gitignored (`/scratch/`) | one place, inside the repo, named by slice, never mixed with Stephen's files or other projects' sessions |
| Files a test emits (images, masks, dumps) | the module's `target/<TestName>/…` | wiped by `mvn clean`; **never the source tree** (the `media/flux/qa/` lesson, ledger §6.C 228 a) |
| Visual-inspection output Stephen looks at himself | `media/flux/out/` (his `TestFlux2Composite` convention; untracked, not yet gitignored) | existing convention — follow it, do not invent a sibling |
| Playwright output | the configured `outputDir` (`e2e/results/`), `test-results/`, `e2e/screenshots/` — all gitignored | — |
| Application logs | the module's `logs/` (log4j daily rollover, **no delete policy**) | — |
| Inputs a test reads | `media/` or `src/test/resources/`, committed (`media/flux/qa/p52-t01-direct.png` is the pattern) | a fixture left in scratch or `target/` fails on a clean checkout |
| Anything holding a credential | `volatile*` only | root `.gitignore` |

Never:
- `C:\tmp` or any path outside the repo. Nothing ties it to a slice, and it is shared.
- A copy of a module to get an isolated build (`C:\tmp\o7verify\` reached 5.3 GB, 3.9 GB of it rolled
  logs). Use `git worktree` (auto-cleaned) or wait for the single Objects7 surefire JVM.
- Pre-edit backups of tracked files (`*.pre-*.md` — ~60 MB of ledger copies). Git is the backup. If an
  edit script wants a safety copy, write it under `scratch/<slice>/` and delete it once the edit is verified.
- Writing a test's output next to its input under `media/` (what `p86-t01-broken.png` did).

## Lifecycle — when cleanup can and should happen

An artifact exists to support a claim in the ledger. Its class decides its life:

1. **Evidence** — run logs, classify outputs, probe JSON. Disposable the moment the ledger entry quoting it
   (`Tests run:` / `BUILD` / `EXIT=`, as `log:line`) is written **and Stephen has replied to that hand-back**.
   The ledger carries the quoted lines, so the pointer may dangle afterwards. Delete in the next hand-back
   after his reply; at phase close at the latest.
2. **Looked-at images and screenshots** — keep until the phase closes (its §6.C entries move to the archive),
   because Stephen may want to look himself; then delete. Anything he asks to keep is promoted to class 4.
3. **Scratch scripts and scratch copies** — delete when the slice's hand-back is written.
4. **Test inputs** — promote into `media/` / `src/test/resources/` and commit. Never left behind.
5. **Application logs** — `logs/` rotates daily and never deletes. Whether to add a `<Delete>` (keep 7 days)
   to the test log4j config is Stephen's call; until then, delete rolled files older than the current phase
   at phase close. `accountManagerObjects7.log` (current) is never deleted mid-run.

Hard limits: `scratch/` over 1 GB, one slice over 200 MB, or a module `logs/` over 1 GB ⇒ cleanup is
overdue and the next hand-back must say so, with the manifest.

## Tracking

- Every hand-back ends with an `ARTIFACTS:` block — path, size, class, disposition (kept / deleted / promoted).
- `src/aiDocs/ArtifactInventory.md` is the register: one row per location (what writes it, gitignored?,
  class, size as of a date, pending action) plus a "Pending cleanup" list pointing at manifests. Update it in
  the same hand-back that created or removed artifacts.
- Never delete what the register does not attribute to a session: Stephen's files, tracked `media/`, the
  stack's data volumes. Cleanup of anything **outside the repo** is a manifest plus a dry-run-by-default
  script for Stephen to run (`scratch/cleanup/c-tmp-am7-claude-2026-10-31.{txt,sh}` is the shape), never an
  `rm` by the session.
