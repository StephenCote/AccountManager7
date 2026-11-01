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
| `scratch/` (git root) | sessions from 2026-10-31 | ignored (`/scratch/`) | 1, 3 | 18 MB — `cleanup/` 0.1 MB; `p9-0/` 3 KB (`probe-picker-memo.mjs` only — cited by `src/test/tabularMemoPerInstance.test.js` and §6.C 233 d; class 3, delete at Phase 9 close); `p9-x/` ≈ 0.1 MB: `vitest-memo-red.log`, `vitest-memo-green.log`, `vitest-full.log`, `vite-build.log`, `probe-picker-memo-fixed.log` (class 1, quoted in ledger §18.2); `p9-1/` 17.6 MB / 11 Objects7 surefire logs, all quoted in ledger §18.3 (class 1): `o7-review.log` 12.1 MB + `o7-review-red.log` 2.4 MB are the bulk (the 221 a `TestPbReview` runs), then `o7-cover.log` / `o7-cover-2.log` / `o7-cover-clone.log` (`TestPbCoverUtil`), `o7-TestPbCompositionRows.log`, `o7-TestPbComposeFallback.log`, `o7-TestPbModelSchema.log`, `o7-TestPbV4Schema.log`, `o7-compile-cover.log`, `o7-test-compile.log`; `p9-2/` 0.6 MB / 7 files, all quoted in ledger §18.4 (class 1): `TestPbCoverPlanOffline.log` 0.3 MB, `TestPbCoverRenderLive.log` 0.2 MB, `TestPbCoverRenderLive-red.log` 0.1 MB, `compile1…4.log`; `p9-3/` 6.3 MB / 5 files, all quoted in ledger §18.5 (class 1): `s7-cover-routes.log` 2.1 MB (green run 3), `s7-cover-routes-red.log` + `-red2.log` 2.1 MB each (runs 1–2), `o7-install.log`, `s7-compile.log`; `p9-4/` 3.3 MB / 13 files, all quoted in ledger §18.6 (class 1): `s7-cover-routes.log` 3.2 MB (`TestPictureBookCoverRoutes` 14/14), `o7-install-p9-4.log`, `s7-compile-p9-4.log`, `o7-install.log`, `s7-compile.log`, `vite-build-p9-4.log` + `-p9-4b.log`, `vitest-cover-p9-4b.log` + `-p9-4c.log`, `vitest-full-p9-4.log` + `-p9-4b.log`, `pw-cover-p9-4-run3-red.log`, `pw-cover-p9-4.log` (run 4, red), `pw-cover-p9-4-run5.log` (green); `p9-5/` 0.9 MB / 1 file, quoted in ledger §18.7 (class 1): `o7-cover-exit.log` (`TestPbCoverExitCriterionLive` 2/2 on `.39` + `.42`); `p9-6/` 1.8 MB / 5 files + 1 dir, quoted in ledger §16.1 / §18.8 (class 1): `pw-usability.log` (run 15, 1/15), `pw-usability-run2.log` (run 16, 14/15), `pw-usability-run3.log` (run 17, 15/15 green), two compose-job JSON probes (`job-040fbe7b-….json`, `job-b1dcd9c5-….json`), `run16-shots/` (8 PNGs ≈ 1.6 MB — run 16's step-15 screenshots, class 2, the second Auto staging) | the home for helper scripts and captured run logs; per-slice subdirectories. `p9-0/` evidence deleted 2026-10-31 after Stephen's reply (pending item 5); `p9-x/`, `p9-1/`, `p9-2/`, `p9-3/`, `p9-4/`, `p9-5/` and `p9-6/` disposable after the reply to the Phase 9 hand-back that quotes them (default: delete then) |
| `src/AccountManagerObjects7/target/` | Maven; tests that emit files (`target/p86/…`, surefire reports); **`target/TestPbCoverRenderLive/`** — the P9-2 live cover test's two finals (`p92cov3e0f6d80-cover-v0.png`, `-v1.png`, 1024×1024, 2.2 MB; looked at, §18.4); **`target/TestPbCoverExitCriterionLive/`** — the P9-5 live exit-criterion test's six PNGs (`p95book031edbf1-cover-v0/-v1/-approved.png`, `p95chap759667b9-cover-v0/-v1/-approved.png`, 1024×1024, 7.2 MB; looked at, §18.7 — the ChapBook pair is the 239 c blown-out TEXT_ONLY-on-FLUX.2 evidence) | ignored | 1, 2 | 278 MB / 2,618 files (+ 2.2 MB `TestPbCoverRenderLive/` + 7.2 MB `TestPbCoverExitCriterionLive/`) | `mvn clean` at phase close; the cover PNGs are class 2 — keep until Phase 9 closes so Stephen can look |
| `src/AccountManagerObjects7/logs/` | log4j test config — daily rollover (`accountManagerObjects7-YYYY-MM-DD.log`), **no `<Delete>` policy** | ignored | 5 | 1,414 MB / 26 files (largest `…-2026-10-18.log` 280 MB; current `accountManagerObjects7.log` 249 MB) | Pending — Stephen's go: delete the rolled files dated before the Phase 9 start day, keep the current file. Open question (default no change): add `<Delete>` keep-7-days to `src/test/resources/log4j2-AccountManagerObjects7Logging.xml`. |
| `src/AccountManagerObjects7/export/` | `TestPictureBookCustom`, `TestPictureBookWorkflow` (`ki68_step20_*.png`, `ki68_step40_*.png`) | ignored | 2 | 97 MB / 65 files | pre-V3 (KI-68) output, Stephen's tests — propose delete; his call |
| `src/AccountManagerObjects7/media/flux/out/` | `TestFlux2Composite` (`flux2_composite_N.png`) — Stephen's visual-inspection convention | **untracked and not ignored** (appears in `git status`) | 2 | 2 MB / 1 file | propose `AccountManagerObjects7/media/flux/out/` in `src/.gitignore`; his call. Sessions follow this convention when he asks for an image to look at; otherwise `target/`. |
| `src/AccountManagerObjects7/media/flux/qa/` | fixtures (ledger §6.C 228 a) | tracked (2f109155) | 4 | 1.5 MB / 2 files | keep; remake of `p52-t01-direct.png` waits on 219 h (§6.C 230) |
| `src/AccountManagerService7/target/`, `src/AccountManagerISO42001/target/` | Maven | ignored | 1 | 16 MB / 218 files; 3 MB / 157 files | `mvn clean` |
| `src/AccountManagerUx752/test-results/` | Playwright specs writing their `OUT_DIR` / `RESULTS` constants directly | ignored | 2 | 18 MB / 60 files — **P9-6 `usability/`** (16 MB: run 17's 15-step set, 2026-11-01 04:09 → 04:23 — the spec overwrites by name, so this replaced P9-0's run-14 set; incl. `15a` … `15g` cover shots, `15-cover-and-art.png`, `results.json`, `14-export-chapbook.html` 4.8 MB, `14-export-picturebook.html`, `15-export-chapbook-cover.html` 4.8 MB; plus one run-15 leftover, `02b0-wizard-scenes-degenerate.png` 03:39 — the 240 b blank-scene evidence; all looked at, §18.8); **P9-4 `p94-cover/`** (2.2 MB: `01-empty` … `07-reader-cover.png` board/reader screenshots + `cover-latest.png`, the 1024×1024 `.39` render; all looked at, §18.6) | Phase 8 output deleted 2026-10-31 (pending item 4); `usability-run1…run13/` (55 MB) deleted 2026-10-31 after Stephen's reply (pending item 5). The run-17 set is what the §18.8 "looked at" list refers to (the §18.1 run-14 set it replaced is described in the ledger text only), `p94-cover/` the §18.6 one — keep both until Phase 9 closes |
| `src/AccountManagerUx752/e2e/results/` | Playwright `outputDir` (`retain-on-failure` trace) | ignored | 1 | 0 — empty; the P9-4 run-3/4 traces and the P9-6 run-15/16 traces were cleared by Playwright at the start of the green runs (`outputDir` is wiped per run) | — |
| `src/AccountManagerUx752/e2e/screenshots/` | Playwright specs | ignored | 2 | 0 (emptied 2026-10-31) | — |
| `src/AccountManagerUx752/dist/` | `vite build` | ignored | — | 22 MB | rebuilt every gate; not tracked here |
| Docker stack volumes (`am7-data`, `am7-certs`), `am7test` DB | the stack | — | — | not measured | runtime state, resettable test data — not file cleanup |

## Pending cleanup (ordered; nothing below has been done)

1. **`C:\tmp`** — Stephen: review `scratch/cleanup/c-tmp-am7-claude-2026-10-31.txt`, then
   `bash scratch/cleanup/c-tmp-am7-claude-2026-10-31.sh --delete`. Frees ≈ 6,975 MB. The dry run (no flag) lists 1,487 paths.
2. **Objects7 `logs/`** rolled files from Phases 0–8 (≈ 1.2 GB of the 1.4 GB) — Stephen's go; default: delete everything dated before the Phase 9 start day.
3. **Objects7 `export/`** (97 MB, KI-68 images) and the **`media/flux/out/` gitignore** — Stephen's call.
4. ~~**Ux752 `test-results/` + `e2e/screenshots/`** (68 MB) — class 2 at phase close; default: delete at the P9-0 hand-back.~~ Done 2026-10-31 (see History).
5. ~~**P9-0 evidence** after Stephen replies to the hand-back — default: delete `scratch/p9-0/pw-usability*.log`, the probes,
   `test-results/usability-run1…run13/` (55 MB) and `e2e/results/` (64 MB trace); keep `test-results/usability/` (run 14) until Phase 9 closes.~~ Done 2026-10-31 (Stephen: "2 yes"; see History).
6. **Phase 9 close** — delete `test-results/usability/` (run 17) and `test-results/p94-cover/`, `scratch/p9-0/probe-picker-memo.mjs`, `scratch/p9-6/run16-shots/`, every `scratch/p9-*/` log already quoted in the ledger, `target/TestPbCoverRenderLive/` and `target/TestPbCoverExitCriterionLive/`.

## History

- 2026-10-31 — register created at Stephen's request after he found the `C:\tmp` scratch; rule written
  (`.claude/rules/artifacts.md`); `/scratch/` added to the root `.gitignore`; `C:\tmp` manifest + dry-run
  script written. Nothing deleted.
- 2026-10-31 (P9-0) — pending item 4 executed: 80 Phase-8 `test-results/` entries + `e2e/screenshots/` (274 files, 68 MB) deleted
  (manifest `scratch/p9-0/ux752-class2-cleanup-manifest.txt`). P9-0 wrote `scratch/p9-0/` (0.85 MB), `test-results/usability*/` (64 MB),
  `e2e/results/` (64 MB, one failure trace). Nothing else deleted.
- 2026-10-31 (P9-x, after Stephen's reply to the P9-0 hand-back) — pending item 5 executed: `test-results/usability-run1…run13/` (13 dirs,
  55 MB), `e2e/results/` run-14 trace dir (64 MB), and 21 of 22 `scratch/p9-0/` files (14 gate logs, `s7-canvas.log`, `o7-install.log`,
  3 probes + 1 PNG, the class-2 manifest) deleted; `probe-picker-memo.mjs` kept (cited by the new Vitest). P9-x wrote `scratch/p9-x/` (5 logs).
- 2026-10-31 (P9-1) — wrote `scratch/p9-1/` (11 Objects7 surefire logs, 17.6 MB; class 1, quoted in §18.3). No test emitted files; no
  image output (Objects7 only — rendering is P9-2). Nothing deleted. Objects7 `logs/` measured 1,433 MB (the daily rollover happened; the
  current file is 17 MB) — pending item 2 unchanged.
- 2026-11-01 (P9-2) — wrote `scratch/p9-2/` (3 Objects7 surefire logs + 4 compile logs, 0.6 MB; class 1, quoted in §18.4) and
  `src/AccountManagerObjects7/target/TestPbCoverRenderLive/` (2 PNGs, 2.2 MB; class 2, looked at — keep until Phase 9 closes). Nothing
  deleted. Objects7 `logs/` 1.4 GB — pending item 2 unchanged.
- 2026-11-01 (P9-3) — wrote `scratch/p9-3/` (3 Service7 H2 surefire logs + 2 build logs, 6.3 MB; class 1, quoted in §18.5). H2 only — no test
  emitted files, no image output. Nothing deleted.
- 2026-11-01 (P9-4) — wrote `scratch/p9-4/` (13 logs, 3.3 MB; class 1, quoted in §18.6), `src/AccountManagerUx752/test-results/p94-cover/` (7
  screenshots + the `.39` cover render, 2.2 MB; class 2, looked at — keep until Phase 9 closes) and `e2e/results/` (3 failure-trace dirs from the
  red runs, 1.3 MB; class 1). Stack DB: books 1718–1723 `p94-cover-*` left as the runs' records (resettable test data). Nothing deleted.
- 2026-11-01 (P9-5) — wrote `scratch/p9-5/o7-cover-exit.log` (0.9 MB; class 1, quoted in §18.7) and
  `src/AccountManagerObjects7/target/TestPbCoverExitCriterionLive/` (6 PNGs, 7.2 MB; class 2, looked at — keep until Phase 9 closes). `am7db`:
  both fixtures deleted by the test (`deleted=true`), nothing left. Nothing deleted here.
- 2026-11-01 (P9-6) — wrote `scratch/p9-6/` (3 gate logs + 2 job JSON probes, 0.2 MB, class 1, quoted in §16.1 / §18.8; `run16-shots/` 8 PNGs
  1.6 MB, class 2) and `src/AccountManagerUx752/test-results/usability/` (run 17's set, 16 MB; class 2, looked at — the spec writes by name, so
  the P9-0 run-14 set was overwritten in place; keep until Phase 9 closes). `e2e/results/` emptied by Playwright at the green run's start.
  Stack DB: the run-15/16/17 fixtures (`p90-*` books, importedBooks, legacy groups, `p90-chap-*` ChapBooks, docs; run 17's ids in
  `-run3.log:47`) left as the runs' records (resettable test data). Nothing deleted here.
