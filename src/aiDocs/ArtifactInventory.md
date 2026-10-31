# Artifact inventory — logs, test images, scratch files

The register required by `.claude/rules/artifacts.md` (the rule: where artifacts go, the five lifecycle
classes, when cleanup can and should happen). One row per location. Sizes are as measured on the date in
the column header; update the row and the History line in the same hand-back that adds or removes artifacts.
Nothing here is deleted by a session without the pending action being ruled by Stephen, except class 1–3
items inside `scratch/` and `target/` under the rule's lifecycle.

Classes: **1** evidence (run logs, classify output, probe JSON) · **2** looked-at images / screenshots ·
**3** scratch scripts and copies · **4** test inputs (promoted, committed) · **5** application logs.

## Locations

| Location | Written by | Git | Class | Size (2026-10-31) | State / pending action |
|---|---|---|---|---|---|
| `C:\tmp\` (outside the repo; also holds Stephen's own files and another project's session output) | every PictureBook V3 session 2026-10-09 → 2026-10-31 | — | 1, 2, 3 | **6,975 MB / 1,487 top-level entries.** Largest: `o7verify/` 5,315 MB (a full Objects7 module copy used for isolated verify runs — 3,905 MB of it rolled `logs/`, 895 MB `target/`); `p50-spike/` 125 MB; `pw-run5-gate-results/` 110 MB; `p414-e2e-results-run7/` 72 MB; `p44-autoapprove-renderjob.log` 94 MB; ~60 `PictureBookV3*.pre-*.md` ledger backups ≈ 60 MB; the rest are `p<slice>-*.log`, `o7-*/s7-*/pw-*` gate logs, `d1-*`/`doc*`/`ki*` doc-pass scripts, `transcript-line-*.txt` | **Pending — Stephen runs it.** Manifest `scratch/cleanup/c-tmp-am7-claude-2026-10-31.txt` (every attributed path, with sizes) + `…sh` (dry run by default, `--delete` to remove exactly those). Excluded and left alone: his 39 pre-2026-10-09 entries; the 2026-10-27/28 other-project set (`tl-*`, `gitleaks-8.30.1/`, `win-ca-bundle.pem`, `make_ca_bundle.py`, `ext-pkg.zip`, `baseline.txt`, `__pycache__/`). No session writes there from 2026-10-31 on. |
| `scratch/` (git root) | sessions from 2026-10-31 | ignored (`/scratch/`) | 1, 3 | 0.1 MB (the cleanup manifest + script) | the new home for helper scripts and captured run logs; per-slice subdirectories (`scratch/p9-0/…`) |
| `src/AccountManagerObjects7/target/` | Maven; tests that emit files (`target/p86/…`, surefire reports) | ignored | 1, 2 | 278 MB / 2,618 files | `mvn clean` at phase close |
| `src/AccountManagerObjects7/logs/` | log4j test config — daily rollover (`accountManagerObjects7-YYYY-MM-DD.log`), **no `<Delete>` policy** | ignored | 5 | 1,414 MB / 26 files (largest `…-2026-10-18.log` 280 MB; current `accountManagerObjects7.log` 249 MB) | Pending — Stephen's go: delete the rolled files dated before the Phase 9 start day, keep the current file. Open question (default no change): add `<Delete>` keep-7-days to `src/test/resources/log4j2-AccountManagerObjects7Logging.xml`. |
| `src/AccountManagerObjects7/export/` | `TestPictureBookCustom`, `TestPictureBookWorkflow` (`ki68_step20_*.png`, `ki68_step40_*.png`) | ignored | 2 | 97 MB / 65 files | pre-V3 (KI-68) output, Stephen's tests — propose delete; his call |
| `src/AccountManagerObjects7/media/flux/out/` | `TestFlux2Composite` (`flux2_composite_N.png`) — Stephen's visual-inspection convention | **untracked and not ignored** (appears in `git status`) | 2 | 2 MB / 1 file | propose `AccountManagerObjects7/media/flux/out/` in `src/.gitignore`; his call. Sessions follow this convention when he asks for an image to look at; otherwise `target/`. |
| `src/AccountManagerObjects7/media/flux/qa/` | fixtures (ledger §6.C 228 a) | tracked (2f109155) | 4 | 1.5 MB / 2 files | keep; remake of `p52-t01-direct.png` waits on 219 h (§6.C 230) |
| `src/AccountManagerService7/target/`, `src/AccountManagerISO42001/target/` | Maven | ignored | 1 | 16 MB / 218 files; 3 MB / 157 files | `mvn clean` |
| `src/AccountManagerUx752/test-results/` | Playwright specs writing their `OUT_DIR` / `RESULTS` constants directly | ignored | 2 | 50 MB / 126 files | Phase 8 is closed (§15 archived) → class 2 says delete; default: delete at the P9-0 hand-back unless Stephen says keep |
| `src/AccountManagerUx752/e2e/screenshots/` | Playwright specs | ignored | 2 | 18 MB / 274 files | same as `test-results/` |
| `src/AccountManagerUx752/dist/` | `vite build` | ignored | — | 22 MB | rebuilt every gate; not tracked here |
| Docker stack volumes (`am7-data`, `am7-certs`), `am7test` DB | the stack | — | — | not measured | runtime state, resettable test data — not file cleanup |

## Pending cleanup (ordered; nothing below has been done)

1. **`C:\tmp`** — Stephen: review `scratch/cleanup/c-tmp-am7-claude-2026-10-31.txt`, then
   `bash scratch/cleanup/c-tmp-am7-claude-2026-10-31.sh --delete`. Frees ≈ 6,975 MB. The dry run (no flag) lists 1,487 paths.
2. **Objects7 `logs/`** rolled files from Phases 0–8 (≈ 1.2 GB of the 1.4 GB) — Stephen's go; default: delete everything dated before the Phase 9 start day.
3. **Objects7 `export/`** (97 MB, KI-68 images) and the **`media/flux/out/` gitignore** — Stephen's call.
4. **Ux752 `test-results/` + `e2e/screenshots/`** (68 MB) — class 2 at phase close; default: delete at the P9-0 hand-back.

## History

- 2026-10-31 — register created at Stephen's request after he found the `C:\tmp` scratch; rule written
  (`.claude/rules/artifacts.md`); `/scratch/` added to the root `.gitignore`; `C:\tmp` manifest + dry-run
  script written. Nothing deleted.
