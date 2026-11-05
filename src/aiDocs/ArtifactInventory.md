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
| `scratch/` (git root) | sessions from 2026-10-31 | ignored (`/scratch/`) | 1, 2, 3 | **2026-11-05: `pb-style-portraits/` 0.1 MB — `case06.log` (the blocked TestPbRenderPlanActors run: am7db `a7_olio_pb_book_0_1.sdconfig`/`compositesdconfig` still `text`), `vite-build.log`, `vitest.log`, `playwright-preset.log`, `playwright-styleeditor.log`; class 1, delete after Stephen replies to that hand-back.** **9 MB (2026-11-04 20:35) — `cleanup/` 0.06 MB (the `C:	mp` manifest pair + `p10-c-kruft-2026-11-04.txt`) and `p10-c/` 8.8 MB: `19`–`23-cleanup-*.log` (0.76 MB, class 1, quoted in §6.C 263 c / d), `24`–`36-*.log` (P10-C 2: the edit-pane spec red / green runs, vite builds, vitest, the two live regression runs, the fixPageHeight alone / reload runs, the quick gate — 0.1 MB, class 1, quoted in §6.C 264 / §20.11), `37`–`42-*.log` (P10-C 3, 0.12 MB, class 1, §6.C 265 / §20.12), `43`–`59-*.log` (P10-C 4, 0.35 MB, class 1, §6.C 266 / §20.13), `60`–`63-*.log` (the PB walk: gate runs 21 / 22, the Locations Vitest, the full build + Vitest — 0.08 MB, class 1, §6.C 267 / §20.14) and `34-trace/` (7 MB — the failed fixPageHeight test's Playwright trace unzipped for the frame timeline quoted in 264 f; class 1). `tmpl.json` / `split-create.json` / `cj.txt` (class 3; `cj.txt` was a session cookie jar) deleted 2026-11-04 21:30 at the P10-C 3 + 4 + walk hand-back.** Everything else listed before 2026-11-04 18:05 — `p9-0/`, `p9-6/`, `p9-7/`, `p9-8/`, `p9-9/`, `p9-close/`, `verify-p9-789/`, `p10-0/`, `p10-a/` and `p10-c/` logs 1–18 — was deleted on Stephen's "cleanup your test kruft and logs" (§6.C 263 b; sizes in `scratch/cleanup/p10-c-kruft-2026-11-04.txt`) | the five cleanup logs go after Stephen replies to the 263 hand-back; later slices write `p10-c/<n>-<name>.log` from 24 |
| `src/AccountManagerObjects7/target/` | Maven; tests that emit files (`target/p86/…`, surefire reports); **`target/TestPbCoverRenderLive/`** — the P9-2 live cover test's two finals (`p92cov3e0f6d80-cover-v0.png`, `-v1.png`, 1024×1024, 2.2 MB; looked at, §18.4); **`target/TestPbCoverExitCriterionLive/`** — the live exit-criterion test's PNGs, 18 as of 2026-11-01 16:00, 22 MB, 1024×1024, all looked at: P9-5's six (`p95book031edbf1-*`, `p95chap759667b9-*`, §18.7 — the ChapBook pair is the 239 c blown-out TEXT_ONLY-on-FLUX.2 evidence, the book pair the SDXL-knobbed graph look), P9-7 run 2's six (`p95bookd6dada0d-*` the dark grainy halo = the graph path still SDXL-knobbed, `p95chap4ba87239-*` the first clean TEXT_ONLY), P9-7 run 4's six (`p95book87ccc4cf-*` the clean graph-path cover after the fix, `p95chap1973b3c9-*`; §18.9). The test deletes its fixture books, so these PNGs are the only record of the renders; **`target/p25-render-plan-*.png`** — `TestPbRenderPlanExecutor`'s finals written flat into `target/` (first registered 2026-11-02: 33 files, 52 MB — 25 from 2026-10-24 → 10-28 P6/P7-era runs, 8 from the P10-A 5 regress runs, the run-2 five looked at) | ignored | 1, 2 | **2026-11-04 18:05: `TestPbCoverRenderLive/`, `TestPbCoverExitCriterionLive/`, `TestPbNewBookRender/`, `p86/` and the 62 loose `*.png` (≈141 MB) deleted (§6.C 263 b)**; before that: 278 MB / 2,618 files (+ 2.2 MB `TestPbCoverRenderLive/` + 22 MB `TestPbCoverExitCriterionLive/`; 2026-11-02: 35 MB `TestPbCoverExitCriterionLive/`, 52 MB `p25-render-plan-*.png`) | `mvn clean` at phase close; the class-2 PNG sets described here are gone (the 219 h before/after pair with them — not promoted) |
| `src/AccountManagerObjects7/logs/` | log4j test config — daily rollover (`accountManagerObjects7-YYYY-MM-DD.log`), **no `<Delete>` policy** | ignored | 5 | **Cleaned by Stephen 2026-11-02 ~23:00 — 12 KB / 0 files** (was 1,439 MB / 30 files on 2026-11-01 16:00, over the rule's 1 GB limit); the next Objects7 test run recreates the current file | Done (Stephen, "cleaned up logs", 2026-11-02). Open question (default no change): add `<Delete>` keep-7-days to `src/test/resources/log4j2-AccountManagerObjects7Logging.xml`. |
| `src/AccountManagerObjects7/export/` | `TestPictureBookCustom`, `TestPictureBookWorkflow` (`ki68_step20_*.png`, `ki68_step40_*.png`) | ignored | 2 | 97 MB / 65 files | pre-V3 (KI-68) output, Stephen's tests — propose delete; his call |
| `src/AccountManagerObjects7/media/flux/out/` | `TestFlux2Composite` (`flux2_composite_N.png`) — Stephen's visual-inspection convention | **untracked and not ignored** (appears in `git status`) | 2 | 2 MB / 1 file | propose `AccountManagerObjects7/media/flux/out/` in `src/.gitignore`; his call. Sessions follow this convention when he asks for an image to look at; otherwise `target/`. |
| `src/AccountManagerObjects7/media/flux/qa/` | fixtures (ledger §6.C 228 a) | tracked (2f109155) | 4 | 1.5 MB / 2 files | keep; remake of `p52-t01-direct.png` waits on 219 h (§6.C 230) |
| `src/AccountManagerService7/target/`, `src/AccountManagerISO42001/target/` | Maven | ignored | 1 | 16 MB / 218 files; 3 MB / 157 files | `mvn clean` |
| `src/AccountManagerUx752/test-results/` | Playwright specs writing their `OUT_DIR` / `RESULTS` constants directly | ignored | 2 | **0 (deleted 2026-11-04 18:05 — 154 files, 81 MB: `usability/`, `p94-cover/`, `p10a7-series-art/`, `newbook/`, `p75-scenarios/`, `p10-c-1/`; §6.C 263 b)**; before that: 21 MB (2026-11-01 16:00) — **`usability/`** (17 MB: the 15-step set, overwritten by name on every gate run — run 18 at 14:12, run 19 from 15:50 replacing run 17's; incl. `15a` … `15g` cover shots, `15-cover-and-art.png`, `results.json`, the three export HTMLs ≈ 10 MB; plus one run-15 leftover, `02b0-wizard-scenes-degenerate.png` 03:39 — the 240 b blank-scene evidence; all looked at, §18.8 / §16.1); **`p94-cover/`** (4.4 MB / 14 files: P9-4's `01-empty` … `07-reader-cover.png` + `cover-latest.png` (§18.6) and P9-9's `08-title-page-dialog` … `12-reader-still-cover.png` + `title-page-latest.png`, the From-scene title page's 1024×1024 `.39` render (§18.11); all looked at); **`p10a7-series-art/`** (2.8 MB / 11 files, 2026-11-03 21:53, Playwright run 4's set overwriting runs 1–3 by name: `01-standalone-three-tabs` … `10-chapter-b-board-approved.png` + `series-art-approved.png`, the 1024×1024 `.39` SERIES_ART render, 1.4 MB; all looked at, §20.8) | Phase 8 output deleted 2026-10-31 (pending item 4); `usability-run1…run13/` (55 MB) deleted 2026-10-31 after Stephen's reply (pending item 5). The current `usability/` set is the latest gate run's (the earlier sets it replaced are described in the ledger text only), `p94-cover/` the §18.6 + §18.11 one — keep both until Phase 9 closes; `p10a7-series-art/` until Phase 10 closes; **`newbook/`** (2026-11-04, P10-B — the user-walk spec's 20 PNGs + `results.json`, 3.4 MB, run 2's set replacing run 1's by name; every one looked at, §20.9) until Phase 10 closes; `usability/` overwritten by the P10-B gate run 20 (2026-11-04 11:00, 19 MB); **`p75-scenarios/`** (28 MB, 2026-11-04 — the Scenarios A–G spec's `OUT_DIR`, overwritten by name on each run: run 1 11:19 → 12:01, run 2 12:05 → 14:02, G's six PNGs looked at; class 2, keep until Phase 10 closes); **`p10-c-1/`** (8.1 MB, 2026-11-04 16:28 → 17:12 — the P10-C 1 spec's `OUT_DIR`, overwritten by name per run: `default-page0.png` / `named-page0.png` / `dead-page0.png` / `ui-page0.png` (the four 1024×1024 `.39` page renders, every one looked at — only `default-` is NOT the poem), `dead-1-review-before` / `dead-2-review-after.png`, `ui-1-create-dialog` / `ui-2-after-create` / `ui-3-rendered.png` (looked at), `chain.json` / `dead-chain.json` (the REST probe chains); class 2, keep until Phase 10 closes); **`p10-c-2/`** (0.9 MB, 2026-11-04 19:05 — the P10-C 2 edit-pane spec's `OUT_DIR`, overwritten by name per run: `01-create-view` / `02-create-dialog` / `03-after-create-read` / `04-review-view.png` + `results.json` (the bounding boxes quoted in §20.11); all looked at; class 2, keep until Phase 10 closes); plus four loose PNGs from the run-33 regression (`gap6-chapbook-perscene-reader.png`, `llmUnavail-*.png` ×3, 0.9 MB, 2026-11-04 19:20 — those specs' own fixed names, class 2); **`usability/`** recreated by the PB walk's gate runs 21 (20:36 → 20:52) and 22 (21:08 → 21:25, overwriting 21's by name; 19 MB, the 15-step set + `results.json` + the three export HTMLs) — the run-22 set is the one on disk, the 14 named in §20.14 looked at; class 2, keep until Phase 10 closes |
| `src/AccountManagerUx752/e2e/results/` | Playwright `outputDir` (`retain-on-failure` trace) | ignored | 1 | **0 (2026-11-04 21:25 — gate run 22 wiped run 21's failure dir, 45 MB of trace + the step-1 / 14 `test-failed` shots; and run 43's step-4 dir before it)**; **0 (2026-11-04 19:40)** — the P10-C 2 run-33 failure traces (fixPageHeight + the expected-red `chapBookPromptNoOverride` DEFAULT case) were wiped by runs 34–35; the fixPageHeight `trace.zip` was unzipped into `scratch/p10-c/34-trace/` (7 MB) for the frame timeline in §6.C 264 f before that; **0 (deleted 2026-11-04 18:05, §6.C 263 b)**; before that: 27 MB (2026-11-04 14:02) — five `pictureBookV3Scenarios-…-chromium/attachments/` dirs from P10-B Scenarios run 2 (the spec's `testInfo.attach` copies of the renders already in `test-results/p75-scenarios/`; wiped by the next Playwright run); before that the two `pictureBookV3SeriesArt-…-chromium/` run-4 dirs (one empty, one `attachments/`); the P9-4 run-3/4, P9-6 run-15/16, P9-9 run-1/2 and P10-A 7 run-1/2 (`test-failed-1.png`) traces, and the P10-B Scenarios run-1 G failure trace (4.5 MB, 2026-11-04 12:01), were each cleared by Playwright at the start of the next run (`outputDir` is wiped per run) | — |
| `src/AccountManagerService7/logs/` | log4j test config — daily rollover (`accountManagerService7[-error]-YYYY-MM-DD.log`), **no `<Delete>` policy** (first registered 2026-11-03) | ignored (`Service7/.gitignore:3`) | 5 | **rolled files deleted 2026-11-04 18:05 (28 files, 246 MB; the current `error.log` kept; §6.C 263 b)**; before that: 246 MB / 28 files (2026-11-03) — rolled files from 2026-10-19 on (the 2026-10-23 pair 98 MB); today's current pair 29 MB from the four P10-A 7 H2 route runs | same open question as the Objects7 row (a `<Delete>` keep-7-days); default: delete rolled files older than the current phase at phase close |
| `src/AccountManagerService7/am7/` | the Service7 JUnit H2 fixture (`jdbc:h2:./am7/h2`; `h2.mv.db` + `h2.trace.db`, `.jks/`, `.streams/`, `.vault/`) (first registered 2026-11-03) | ignored (`Service7/.gitignore:5`) | — | **deleted 2026-11-04 18:05 (813 MB; §6.C 263 b) — the next Service7 test run recreates it**; before that: 818 MB (2026-11-03) — `h2.mv.db` 783 MB (was 44 MB before P10-A 7: each `POST /series` in `TestPictureBookCoverRoutes` initializes an Olio series world with the corpus, four runs), `h2.trace.db` 61 MB | resettable test data; default: delete the directory at phase close (the next Service7 test run recreates it; the first `POST /series` then costs ~105 s again) |
| `src/AccountManagerUx752/e2e/screenshots/` | Playwright specs | ignored | 2 | 0 (emptied 2026-10-31) | — |
| `src/AccountManagerUx752/dist/` | `vite build` | ignored | — | 22 MB | rebuilt every gate; not tracked here |
| Docker stack volumes (`am7-data`, `am7-certs`), `am7test` DB | the stack | — | — | not measured | runtime state, resettable test data — not file cleanup |

## Pending cleanup (ordered; nothing below has been done)

1. **`C:\tmp`** — Stephen: review `scratch/cleanup/c-tmp-am7-claude-2026-10-31.txt`, then
   `bash scratch/cleanup/c-tmp-am7-claude-2026-10-31.sh --delete`. Frees ≈ 6,975 MB. The dry run (no flag) lists 1,487 paths.
2. ~~**Objects7 `logs/`** rolled files from Phases 0–8 (≈ 1.2 GB of the 1.4 GB) — Stephen's go; default: delete everything dated before the Phase 9 start day.~~ Done 2026-11-02 by Stephen (the whole directory, see the row).
3. **Objects7 `export/`** (97 MB, KI-68 images) and the **`media/flux/out/` gitignore** — Stephen's call.
4. ~~**Ux752 `test-results/` + `e2e/screenshots/`** (68 MB) — class 2 at phase close; default: delete at the P9-0 hand-back.~~ Done 2026-10-31 (see History).
5. ~~**P9-0 evidence** after Stephen replies to the hand-back — default: delete `scratch/p9-0/pw-usability*.log`, the probes,
   `test-results/usability-run1…run13/` (55 MB) and `e2e/results/` (64 MB trace); keep `test-results/usability/` (run 14) until Phase 9 closes.~~ Done 2026-10-31 (Stephen: "2 yes"; see History).
6. ~~**Phase 9 close** — delete `test-results/usability/` (the latest gate run's set) and `test-results/p94-cover/`, `scratch/p9-0/probe-picker-memo.mjs`, `scratch/p9-6/run16-shots/`, every `scratch/p9-*/` log already quoted in the ledger (`p9-7/`, `p9-8/`, `p9-9/`, `p9-close/pw-usability-run18/19.log`, `verify-p9-789/`), `target/TestPbCoverRenderLive/` and `target/TestPbCoverExitCriterionLive/` (18 PNGs, 22 MB — the 219 h before/after pair is in there; say if you want it promoted to `media/flux/qa/` before deletion).~~ Done 2026-11-04 (Stephen: "cleanup your test kruft and logs"; the 219 h pair was not promoted — gone).
7. ~~**Objects7 `logs/` — overdue (1,439 MB > 1 GB)** — default: run `bash scratch/p9-close/o7-logs-rolled-before-p9-2026-11-01.sh --delete` (26 rolled files dated 2026-10-16 → 10-30, 1,413 MB; the current files and the 10-31 / 11-01 rolls are kept). Waiting on Stephen's go per the row.~~ Done 2026-11-02 by Stephen (item 2); the script and listing under `scratch/p9-close/` are now class 3, delete at Phase 9 close (item 6).

## History

- 2026-11-04 (P10-C kruft cleanup, ~17:30 → 18:05) — on Stephen's "cleanup your test kruft and logs, and keep going" (§6.C 263):
  **deleted ≈1.28 GB** per `scratch/cleanup/p10-c-kruft-2026-11-04.txt` — `scratch/p9-*`, `p10-0/`, `p10-a/`, `verify-p9-789/`, `p10-c/`
  logs 1–18; Ux752 `test-results/` (154 files, 81 MB) + `e2e/results/`; Objects7 `target/TestPb*`, `p86/`, 62 loose PNGs (141 MB); Service7
  rolled logs (246 MB) and the `am7/` H2 fixture (813 MB). **`am7test` stack fixtures deleted through the app routes as the e2e users** (never
  admin): 176 worlds, 20 series, 249 ChapBooks; 73 Sept-era series + 265 chapter books remain (no non-admin can delete them, §6.C 263 d,
  default leave). Wrote `scratch/p10-c/19`–`23-cleanup-*.log` (0.76 MB, class 1); the cleanup scripts (class 3) deleted. Pending item 6
  (Phase 9 close) is thereby done for everything but `scratch/p9-0/probe-picker-memo.mjs` — also deleted; `src/test/tabularMemoPerInstance.test.js`
  cites it as provenance only.
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
- 2026-11-01 (Phase 9 hand-back reply — Stephen: "commit made, keep your defaults") — the Phase 9 class-1 evidence deleted: `scratch/p9-x/`,
  `p9-1/` … `p9-5/` whole and `scratch/p9-6/`'s 5 files (48 files, 28.5 MB; manifest `scratch/p9-close/cleanup-manifest-2026-11-01.txt`).
  Kept: `p9-0/probe-picker-memo.mjs`, `p9-6/run16-shots/`, every class-2 image set (`target/TestPbCover*Live/`, `test-results/usability/`,
  `test-results/p94-cover/`) until Phase 9 closes. Stack DB fixtures left (default 2). Phase 9 committed by Stephen as `f4482f2d`.
  Wrote `scratch/p9-close/pw-usability-run18.log` (gate run 18, the 240 e verification).
- 2026-11-01 (P9-7 / P9-8 / P9-9 + close pass, 15:00 → 16:10) — wrote `scratch/p9-7/` (14 logs, 3.2 MB, class 1, quoted in §18.9),
  `scratch/p9-8/` (7 logs, 80 KB, §18.10), `scratch/p9-9/` (3 Playwright logs, 44 KB, §18.11), `scratch/p9-close/pw-usability-run19.log`
  (gate run 19), `scratch/p9-close/o7-logs-listing-2026-11-01.txt` + `o7-logs-rolled-before-p9-2026-11-01.sh` (the overdue Objects7 rolled-log
  manifest, class 3), `scratch/verify-p9-789/` (the verifier agent's 5 logs, 245 KB, §6.C 246), `scratch/p9-7/o7-lasterrordto-2.log` (the
  verifier's MED finding closed by extending `TestPbLastErrorDtoOffline` to all three DTOs); 12 more PNGs under `target/TestPbCoverExitCriterionLive/` (P9-7 live
  runs 2 and 4, 14.8 MB, class 2, all looked at — run 3 rendered nothing); 7 more files under `test-results/p94-cover/` (P9-9's title-page
  screenshots + render, 2.2 MB, class 2, looked at); `test-results/usability/` overwritten in place by runs 18 and 19. Objects7 `logs/` measured
  1,439 MB — over the 1 GB limit (pending item 7). `am7db`: the live test deletes its own fixture books. Nothing deleted.
- 2026-11-02 (P10-0, 09:15 → ) — wrote `scratch/p10-0/inventory-grep.sh` (12 KB, class 3) + `inventory-grep.log` (213 KB, class 1; the
  grep-proven Phase 10 inventory, cited from ledger §19's table and §6.C 248). No build, no test, no image output, no DB write. Nothing
  deleted; pending items 1–3, 6, 7 unchanged (Phase 9 is not closed — its class-2 sets stay).
- 2026-11-02 (P10-A 0, ~15:00 → 15:15) — deleted `scratch/p10-0/inventory-grep.log` (213 KB, class 1; Stephen replied to the P10-0 hand-back).
  Wrote `scratch/p10-a/0-*.log` (6 files, 11.1 MB, class 1 — the P10-A 0 compile / `TestPbRenderPath` / install / `TestChatSceneRenderPath` /
  Vitest / `vite build` runs, quoted in §6.C 249 / §20.1). No image output; `am7db` untouched beyond the test's own boot; no deploy.
- 2026-11-02 (P10-A 1, ~15:15 → 15:35) — wrote `scratch/p10-a/1-*.log` (3 files, 112 KB, class 1 — compile, the salvage + emulator test
  run, install; quoted in §6.C 250 / §20.2). No image output; `am7db` untouched beyond the test's own boot; no deploy.
- 2026-11-02 (P10-A 2, ~15:40 → 16:10) — wrote `scratch/p10-a/2-*.log` (4 files, 0.6 MB, class 1 — the three page-view test classes,
  install, Ux752 `vite build`, Vitest; quoted in §6.C 251 / §20.3). The new `TestPbPageViewRenderPlanBase` writes its 2×2 PNGs into
  `am7db` as the fixture books' artifacts and deletes the books in `@After` (`deleteBookComplete`); no files emitted; no deploy.
- 2026-11-02 (P10-A 3, ~16:40 → 17:05) — wrote `scratch/p10-a/3-pw-cover-run1.log` (12 KB, class 1 — the cover spec 4/4 on `am7test` + `.39`,
  quoted in §6.C 252 / §20.4). `test-results/p94-cover/` overwritten by the run (14 files, 4.7 MB, class 2, gitignored; five looked at).
  Fixture book `p94-cover-mvzv03kk` left in the `am7test` stack DB (resettable). No deploy; `scratch/p10-a/` 12 MB total.
- 2026-11-02 (P10-A 4, ~17:05 → 18:40) — wrote `scratch/p10-a/4-*.log` (13 files, 162 KB, class 1 — `vite build` ×2, Vitest, Service7
  compile, the deploy, the two measurement runs, RenderPlan runs 1–4, Cover, Ux, PlainPb; quoted in §6.C 253 / §20.5). Screenshot sets
  overwritten or created by the runs (class 2, gitignored, all looked at): `test-results/p10a4-measure/` (3, 264 KB), `p25-render-plan/`
  (3, 3.2 MB), `p94-cover/` (14, 4.6 MB), `p110/` (13, 8.2 MB), `pf9-plain/` (3, 2.9 MB). Deleted (class 3): the temporary
  `e2e/p10a4-reader-measure.spec.js`, `scratch/p10-a/jarcheck/` (3.3 MB) and `scratch/p10-a/plandiff/` (two plan JSONs + a cookie jar
  holding a live session cookie). **Deploy:** the P10-A 0–2 Objects7 jar + the P10-A 4 `dist` into `am7test-am7-1` (no WAR rebuild —
  `51bf42e1`'s Service7 lines are comments only), container restarted. Fixture books left in the `am7test` stack DB (resettable):
  `p10a4-measure-mvzvgh43` / `-mvzvidwj`, `p25-plan-mvzvqw3s` (1743) / `-mvzvuigp` (1744) / `-mvzw4d15` (1745) / `-mvzwg61p` (1746),
  `p94-cover-mvzwnwql` (1747), `p110-ux-mvzwr4as`, `pf9-plain-mvzwzqbx` (1750). `scratch/p10-a/` 13 MB total.
- 2026-11-02 (P10-A 5, ~18:10 → 20:05) — wrote `scratch/p10-a/5-*.log` (16 files, 4.7 MB, class 1 — compile ×2 + testcompile, JUnit
  runs 1–3, `vite build`, Vitest, the parity spec, install, deploy, live cover runs 1–2, live regress runs 1–2 (`5-live-regress-run1.log`
  2.0 MB red 8/2, `-run2.log` 0.6 MB green 8/8); quoted in §6.C 254 / §20.6). The live cover runs added 8 PNGs under
  `AccountManagerObjects7/target/TestPbCoverExitCriterionLive/` (books `p95bookb1530fe5` / `p95chape4491649` run 1, `p95book242ee7b6` /
  `p95chap6e531c54` run 2; now 28 PNGs, 35 MB there, class 2, all looked at; the fixture books are deleted by the test, so the PNGs are the
  record). The live regress runs wrote 8 `AccountManagerObjects7/target/p25-render-plan-*.png` (`TestPbRenderPlanExecutor`'s finals,
  1024×1024, ~1.6 MB each; run 1 19:44–19:48 `p26cf0a8743b-p26-defaults`, `p25hd58053d9-first`, `p25d10a31ff6-degraded` — not looked at,
  the run was red on a stale assertion; run 2 19:58–20:03 `p25h2fc7aeb2-first` / `-second`, `p25dea76210c-degraded`, `p26c9a769116-p26-defaults` /
  `-p26-override` — all five looked at, class 2). That `target/` set was not registered before: 25 older PNGs from the same test dated
  2026-10-24 → 10-28 (P6/P7-era runs) sit beside them — 33 files, 52 MB in all; `mvn clean` at phase close. `TestChatSceneRenderUtil` still
  writes its `p27-chat-scene-v3-*.png` into `%TEMP%` (pre-existing, outside the repo — noted, not changed; **since P10-C 3, 2026-11-04,
  the test writes `target/TestChatSceneRenderUtil/` instead** — one `p27-chat-scene-v3-91e44013-….png` of 2026-11-02, 474 KB, remains in
  `%TEMP%` for Stephen's manifest, never my `rm`). **Deploy:** the P10-A 5 jar + dist
  into `am7test-am7-1` at 18:52 (`5-deploy.log`), the jar superseded by the P10-A 6 deploy below. `am7db` gains the fixture users / audit rows
  the tests write (resettable).
- 2026-11-02 (P10-A 6, ~19:05 → 20:05) — wrote `scratch/p10-a/56-*.log` (3 files, 0.4 MB, class 1 — the shared offline JUnit run for 254 h +
  255 c, install, deploy; quoted in §6.C 255 / §20.7). `TestAuditDescriptionBound` leaves user `p10a6AuditUser` and its audit rows in `am7db`
  (resettable). **Deploy:** the Objects7 jar carrying 254 h + 255 b (3,717,152 B) into `am7test-am7-1` at 20:05, container restarted,
  `/rest/schema` 200 after ~20 s (`56-install.log`, `56-deploy.log`). `scratch/p10-a/` 17 MB / 46 files total.
- 2026-11-02 (after the P10-A 3–6 hand-back, ~23:00 → 23:10) — Stephen committed the P10-A 3–6 set as `540f3e8e PB3 P10 Mark` and emptied
  `src/AccountManagerObjects7/logs/` himself (12 KB / 0 files; pending items 2 and 7 closed). On his reply I **deleted `scratch/p10-a/`**
  whole (46 `.log` files, 17 MB, class 1 — every one quoted in §6.C 249–255 / §20.1–§20.7; the pointers dangle, per the rule); `scratch/`
  is 5.8 MB. Kept (class 2, until phase close): `target/p25-render-plan-*.png` 33 / 52 MB, `target/TestPbCoverExitCriterionLive/` 35 MB,
  `test-results/usability/` + `p94-cover/`. No code, no tests, no deploy; the P10-A 7 start prompt went into ledger §19.
- 2026-11-03 (P10-A 7, ~19:20 → 21:55) — wrote `scratch/p10-a/7-*.log` (25 files, 16 MB, class 1; quoted in §6.C 257 / §20.8),
  `test-results/p10a7-series-art/` (11 files, 2.8 MB, class 2 — run 4's set, every one looked at) and two `e2e/results/` run dirs (1.4 MB).
  Seed data left in `am7test` (resettable): four series `p10a7-series-mw1kcwqa` / `-mw1kfb2v` / `-mw1krrg0` / `-mw1kzvf0`, each with
  two chapters, a standalone `p10a7-standalone-*`, a world, and (runs 3–4) a rendered + approved SERIES_ART composition with two
  artifacts under `/Olio/Universes/Books/Worlds/<ch1-slug>/Artifacts`. The Service7 H2 fixture grew 44 MB → 783 MB (four
  `TestPictureBookCoverRoutes` runs, each creating series worlds) and `Service7/logs/` gained today's 29 MB pair — both newly
  registered above. **Slip:** jar classes were unzipped to Git Bash `/tmp/p10a7jar` (outside the repo) to `javap` them — deleted
  immediately; against the rule. **Deploy:** the Objects7 jar after `7-objects7-install-3.log` and the Ux752 dist (`7-deploy-dist-3.log`,
  `index.html` 2026-11-04 03:52:42 UTC) into `am7test-am7-1`.
- 2026-11-03 (Phase 10 first report, ~22:40 → 23:10) — wrote `scratch/p10-report/regrep.sh` (9 KB, class 3) + `regrep.log` (55 KB,
  class 1; the §19 / §20.8 line re-grep at `87a089dd` and the P10-7 exit-grep baseline, 299 hits / 48 files). No code, no tests, no
  deploy, nothing deleted; `postgres3` not started. `scratch/p10-a/7-*.log` (25 files, 16 MB) still pending Stephen's reply to 257.
- 2026-11-04 (P10-B, ~09:00 → 14:30) — on Stephen's reply to 257 / the first report, **deleted `scratch/p10-a/7-*.log`** (25 files, 16 MB,
  class 1; quoted in §6.C 257 / §20.8). Wrote `scratch/p10-b/` (16 logs ≈ 3.4 MB class 1; `open-items-from-stephen.md` class 3 deleted at this hand-back, its three items are in §6.C 260 j; quoted
  in §6.C 260 / §20.9), `test-results/newbook/` (20 PNGs + `results.json`, 3.4 MB, class 2, every one looked at), the gate run's
  `test-results/usability/` set (replacing run 19's by name) and `src/AccountManagerObjects7/target/TestPbNewBookRender/` (one PNG, 1.6 MB,
  class 2, looked at). Seed data left in `am7test` (resettable): the user-walk's two books per run (`p10b-…` / `Book p10b-…`, two runs), the
  nav spec's, the gate run's `p90-…` pair and the Scenarios run's `p75-scen-…` book, all as the shared test user; Objects7's `TestPbNewBookRender`
  books in `am7db` (`p10b1`…`p10b5`, deleted by each case's `cleanup` → `PbDeleteUtil.deleteBookComplete`; run 1's case05 ran its
  cleanup after the failed assertion too); `TestPbCoverUtil` run 1's series `p91sd17045be` world under `/Olio/Universes/Books/Worlds/` in
  `am7db` (its `deleteSeries` cleanup hit the same policy-null NPE and "left the series in place for retry", §6.C 260 l — not re-tried by me;
  `am7db` is resettable); the H2 fixture `Service7/am7/` 813 MB and
  `Service7/logs/` 250 MB after today's three H2 runs (`TestChapBookRestContract`, `TestPictureBookNewBookRoutes`). **Deploy:** the Objects7
  jar + Service7 classes and the Ux752 dist into `am7test-am7-1` (container restarted 10:28). Nothing else deleted.
- 2026-11-04 (P10-C 1, ~15:45 → 17:30) — on Stephen's reply to the 260 hand-back (`c7cd4c31`), **deleted `scratch/p10-b/`** (16 logs, 3.4 MB,
  class 1) and **`scratch/p10-report/`** (2 files, 64 KB) — every quote stands in §6.C 260 / §20.9 and the first-report message. Wrote
  `scratch/p10-c/` (24 logs, 0.55 MB, class 1; quoted in §6.C 262 / §20.10) and `test-results/p10-c-1/` (11 files, 8.1 MB, class 2 — the four
  page renders and the five screenshots all looked at). Seed data left in `am7test` (resettable), all as the shared test user: the repro
  books of 16:05 → 16:28 and the proof books `5112f152-…` (DEFAULT, placeholder), `526c9e99-…` (named `contentAnalysis`), `91f06a08-…` /
  `0797f042-…` (UI create, runs 1–2), `8dd98222-…` / `91d816ba-…` (dead-LLM re-render, runs 1–2), plus the `chapBookRenderPathV3` and
  `chapBookLlmUnavailable` fixtures of the 17:12 → 17:25 re-runs; `am7db`: `TestChapBookRerenderEcho`'s fixture books ×2 runs under
  `/Development/ChapBook Tests` (user `chapbookTestUser`; not deleted by the test). **Deploy:** the Objects7 jar carrying F2
  (3,719,945 B, `9-o7-install.log`) into `am7test-am7-1` + restart at 16:56; the Ux752 dist at 16:54 (F1/F4) and 17:08 (F5). Nothing else deleted.
- 2026-11-04 (P10-C 2, ~18:05 → 19:40) — wrote `scratch/p10-c/24`–`36-*.log` (13 logs, 0.1 MB, class 1; quoted in §6.C 264 / §20.11) and
  `scratch/p10-c/34-trace/` (7 MB — run 33's fixPageHeight failure `trace.zip` unzipped for the screencast/frame timeline in 264 f; class 1,
  delete with the logs); `test-results/p10-c-2/` (4 PNG + `results.json`, 0.9 MB, class 2 — all looked at) and run 33's four loose spec PNGs
  (0.9 MB, class 2). Seed data left in `am7test` (resettable), all as the shared test user: the edit-pane spec's books (red run 18:20, green
  runs 18:40 / 18:50 / 19:05) and the regression runs' `chapBookConfigPanel` / `chapBook.spec` fixtures (19:10 → 19:25). **Deploy:** the Ux752
  dist at 19:05 (`cb-config-panel` restructure); no jar. Nothing deleted this slice (`scratch/p10-c/` is now 8.0 MB, under the limits).
- 2026-11-04 (P10-C 3, ~19:15 → 20:00) — wrote `scratch/p10-c/37`–`42-*.log` (6 logs, 0.12 MB, class 1; quoted in §6.C 265 / §20.12) and
  `scratch/p10-c/tmpl.json` (1 KB, class 3 — the `/olio/randomImageConfig` template fetched by curl for 265 j; delete at hand-back).
  `src/AccountManagerObjects7/target/TestChatSceneRenderUtil/` (new location, 3 PNG, 1.3 MB, class 2 — `direct-reference-e9423457-….png`
  and two `text-only-*.png`, all looked at) and two `e2e/results/chatChainSceneReimageRoute-*/` run dirs (1.7 MB, class 2 — run 42's
  `scene-v3.png` 421 KB looked at; run 41's overwritten by name). Seed data left (resettable): `am7db` — `TestChatSceneRenderUtil`'s fixture
  users / characters / portraits / scenes under the test user's home, ×3 runs; `am7test` — the item-14-live spec's `Mira and Theo` chat,
  characters, portraits and two scenes under `/home/e2etest_shared/Gallery/Scenes/Mira and Theo` (runs 41 / 42) as the shared test user.
  **Deploy:** the Objects7 jar after `39-o7-install.log` + the `ChatService` classes into `am7test-am7-1`, container restarted ~19:35.
  Nothing deleted this slice (`scratch/p10-c/` 8.4 MB).
- 2026-11-04 (P10-C 4, ~20:00 → 20:35) — wrote `scratch/p10-c/43`–`59-*.log` (17 logs, 0.35 MB, class 1; quoted in §6.C 266 / §20.13),
  `scratch/p10-c/split-create.json` (0.4 KB, class 3 — the pre-fix `POST /rest/model/olio.pb.scene` 404 probe body) and
  `scratch/p10-c/cj.txt` (0.2 KB, class 3 — a curl cookie jar holding an `e2etest_shared` session cookie for that probe; **never commit;
  delete at hand-back**). `e2e/screenshots/cb-review-masterdetail-{before,after}-split.png` (0.16 MB, class 2, the spec's fixed names,
  overwritten per run; both looked at — keep until Phase 10 closes). `e2e/results/` holds only run 43's step-4 failure dir
  (`chapBookReviewMasterDetail-9bf58-…/test-failed-1.png`, 1 MB, class 1 — the pre-fix Split evidence; the next Playwright run wipes it).
  Seed data left (resettable): `am7db` — `cb-scenesplit-{split,short,authz}-<ts>` ChapBooks + `~/Data/CbSceneSplit-*` poems for the
  `cbSceneSplitOwner` / `cbSceneSplitDenied` users (×2 JUnit runs) and the merge test's ×1; `am7test` — the `e2e-cb-md-<ts>` books (×3 spec
  runs, the first left with the pre-fix half-stanza page `664c88ff-…`), the merge / save / SCENEDEL / regression specs' own books, all as
  `e2etest_shared`. **Deploy:** the Objects7 jar after `44-o7-install.log` + `ChapBookService.class` + the dist into `am7test-am7-1`,
  container restarted ~20:20. Nothing deleted this slice (`scratch/p10-c/` 8.8 MB, `scratch/` 9 MB — under the limits).
- 2026-11-04 (P10-C PB walk, ~20:35 → 21:30) — wrote `scratch/p10-c/60`–`63-*.log` (4 logs, 0.08 MB, class 1; quoted in §6.C 267 /
  §20.14 / §16.1 rows 21–22) and `src/AccountManagerUx752/test-results/usability/` (gate runs 21 then 22, 19 MB, class 2 — the run-22
  set is on disk, the 14 shots named in §20.14 looked at; keep until Phase 10 closes). `e2e/results/` held run 21's two-step failure dir
  (45 MB) until run 22 wiped it. **Deleted:** `scratch/p10-c/tmpl.json`, `split-create.json`, `cj.txt` (class 3, 1.6 KB — the cookie jar
  is gone). Seed data left (resettable, `am7test` as `e2etest_shared`): the two gate runs' books (`p90-mw2xp6wj-…` / `p90-mw2ywi60-…` wizard
  books with their `.39` renders, covers and location variants, the `p90-legacy-mw2…` / `p90-legacy2-mw2…` PB1 groups and imports, the
  `p90-chap-mw2…` ChapBooks, the `000-p90-gate-llm` chat configs) — the spec leaves them in place by design (`[p90-gate] left in place`).
  **Deploy:** the dist into `am7test-am7-1` ~21:05; no jar. `scratch/p10-c/` 8.8 MB, `scratch/` 9 MB — under the limits.
