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
| `scratch/` (git root) | sessions from 2026-10-31 | ignored (`/scratch/`) | 1, 2, 3 | 5.4 MB (2026-11-01 16:00) — `cleanup/` 0.1 MB; `p9-0/probe-picker-memo.mjs` 3 KB (cited by `src/test/tabularMemoPerInstance.test.js` and §6.C 233 d; class 3, delete at Phase 9 close); `p9-6/run16-shots/` 8 PNGs ≈ 1.6 MB (run 16's step-15 screenshots, class 2, the second Auto staging; delete at Phase 9 close); **`p9-7/`** 15 logs 3.3 MB (class 1, quoted in §18.9 — offline `o7-flux2-graph-1`, `o7-flux2textonly-1..3`, `o7-lasterrorscrub-1`, `o7-lasterrordto-1` + `-2` (the three-DTO extension, §6.C 246 1), builds; live `o7-cover-exit.log` (skipped), `-2` (green, T2I cut), `-3` (red, `.42` connect timeout), `-4` (green, graph cut)); **`p9-8/`** 7 logs 80 KB (class 1, §18.10 — Vitest, `vite build`, the deploy); **`p9-9/`** `pw-cover-run1..3.log` 44 KB (class 1, §18.11); `p9-close/` — `cleanup-manifest-2026-11-01.txt`, `pw-usability-run18.log` + `pw-usability-run19.log` (gate runs 18 / 19, class 1), `o7-logs-listing-2026-11-01.txt` + `o7-logs-rolled-before-p9-2026-11-01.sh` (the Objects7 rolled-log manifest + dry-run script, class 3); `verify-p9-789/` the verifier agent's captured runs — 5 logs, 245 KB (class 1, quoted in §6.C 246); **`p10-0/`** (2026-11-02) `inventory-grep.sh` 12 KB (class 3, the P10-0 inventory grep; delete at phase close) — its `inventory-grep.log` (213 KB, class 1) **deleted 2026-11-02 15:15** after Stephen replied to the P10-0 hand-back (the §19 table carries the numbers; its `:line` pointers dangle, per the rule); **`p10-a/`** (2026-11-02, P10-A slices) `0-*.log` — six P10-A 0 logs, 11.1 MB (class 1, quoted in §6.C 249 / §20.1; `0-TestPbRenderPath.log` is 11.1 MB of it — the full Objects7 boot log around 14 tests) + `1-*.log` — three P10-A 1 logs, 112 KB (class 1, quoted in §6.C 250 / §20.2: compile, `TestLlmJsonArraySalvage`+`TestLlmEmulator`+`TestLlmJsonSalvage`, install) + `2-*.log` — four P10-A 2 logs, 0.6 MB (class 1, quoted in §6.C 251 / §20.3: `2-TestPbPageView.log` the three page-view classes 15/15, install, Ux752 `vite build` + Vitest); delete after Stephen replies to the P10-A hand-back. **Deleted 2026-11-01** on Stephen's "keep your defaults": `p9-x/`, `p9-1/`, `p9-2/`, `p9-3/`, `p9-4/`, `p9-5/` whole and `p9-6/`'s 3 gate logs + 2 job JSONs — 48 files, 28.5 MB, every one quoted by `log:line` in ledger §18.2–§18.8 / §16.1 (the quotes stand; the pointers dangle, per the rule) | the home for helper scripts and captured run logs; per-slice subdirectories |
| `src/AccountManagerObjects7/target/` | Maven; tests that emit files (`target/p86/…`, surefire reports); **`target/TestPbCoverRenderLive/`** — the P9-2 live cover test's two finals (`p92cov3e0f6d80-cover-v0.png`, `-v1.png`, 1024×1024, 2.2 MB; looked at, §18.4); **`target/TestPbCoverExitCriterionLive/`** — the live exit-criterion test's PNGs, 18 as of 2026-11-01 16:00, 22 MB, 1024×1024, all looked at: P9-5's six (`p95book031edbf1-*`, `p95chap759667b9-*`, §18.7 — the ChapBook pair is the 239 c blown-out TEXT_ONLY-on-FLUX.2 evidence, the book pair the SDXL-knobbed graph look), P9-7 run 2's six (`p95bookd6dada0d-*` the dark grainy halo = the graph path still SDXL-knobbed, `p95chap4ba87239-*` the first clean TEXT_ONLY), P9-7 run 4's six (`p95book87ccc4cf-*` the clean graph-path cover after the fix, `p95chap1973b3c9-*`; §18.9). The test deletes its fixture books, so these PNGs are the only record of the renders; **`target/p25-render-plan-*.png`** — `TestPbRenderPlanExecutor`'s finals written flat into `target/` (first registered 2026-11-02: 33 files, 52 MB — 25 from 2026-10-24 → 10-28 P6/P7-era runs, 8 from the P10-A 5 regress runs, the run-2 five looked at) | ignored | 1, 2 | 278 MB / 2,618 files (+ 2.2 MB `TestPbCoverRenderLive/` + 22 MB `TestPbCoverExitCriterionLive/`; 2026-11-02: 35 MB `TestPbCoverExitCriterionLive/`, 52 MB `p25-render-plan-*.png`) | `mvn clean` at phase close; the cover PNGs are class 2 — keep until Phase 9 closes so Stephen can look (the 219 h before/after pair `p95bookd6dada0d-approved.png` / `p95book87ccc4cf-cover-v0.png` is the one to look at) |
| `src/AccountManagerObjects7/logs/` | log4j test config — daily rollover (`accountManagerObjects7-YYYY-MM-DD.log`), **no `<Delete>` policy** | ignored | 5 | **1,439 MB / 30 files (2026-11-01 16:00) — over the rule's 1 GB limit, cleanup overdue**; 26 rolled files dated before the Phase 9 start day = 1,413 MB (largest `…-2026-10-18.log` 267 MB, `…-10-30.log` 237 MB); the 10-31 / 11-01 rolls + the two current files 26 MB | Pending — Stephen's go (asked again in the P9-7/8/9 hand-back, default = delete): `scratch/p9-close/o7-logs-rolled-before-p9-2026-11-01.sh` (dry run by default, `--delete` removes exactly the 26 listed files; listing `o7-logs-listing-2026-11-01.txt`). Open question (default no change): add `<Delete>` keep-7-days to `src/test/resources/log4j2-AccountManagerObjects7Logging.xml`. |
| `src/AccountManagerObjects7/export/` | `TestPictureBookCustom`, `TestPictureBookWorkflow` (`ki68_step20_*.png`, `ki68_step40_*.png`) | ignored | 2 | 97 MB / 65 files | pre-V3 (KI-68) output, Stephen's tests — propose delete; his call |
| `src/AccountManagerObjects7/media/flux/out/` | `TestFlux2Composite` (`flux2_composite_N.png`) — Stephen's visual-inspection convention | **untracked and not ignored** (appears in `git status`) | 2 | 2 MB / 1 file | propose `AccountManagerObjects7/media/flux/out/` in `src/.gitignore`; his call. Sessions follow this convention when he asks for an image to look at; otherwise `target/`. |
| `src/AccountManagerObjects7/media/flux/qa/` | fixtures (ledger §6.C 228 a) | tracked (2f109155) | 4 | 1.5 MB / 2 files | keep; remake of `p52-t01-direct.png` waits on 219 h (§6.C 230) |
| `src/AccountManagerService7/target/`, `src/AccountManagerISO42001/target/` | Maven | ignored | 1 | 16 MB / 218 files; 3 MB / 157 files | `mvn clean` |
| `src/AccountManagerUx752/test-results/` | Playwright specs writing their `OUT_DIR` / `RESULTS` constants directly | ignored | 2 | 21 MB (2026-11-01 16:00) — **`usability/`** (17 MB: the 15-step set, overwritten by name on every gate run — run 18 at 14:12, run 19 from 15:50 replacing run 17's; incl. `15a` … `15g` cover shots, `15-cover-and-art.png`, `results.json`, the three export HTMLs ≈ 10 MB; plus one run-15 leftover, `02b0-wizard-scenes-degenerate.png` 03:39 — the 240 b blank-scene evidence; all looked at, §18.8 / §16.1); **`p94-cover/`** (4.4 MB / 14 files: P9-4's `01-empty` … `07-reader-cover.png` + `cover-latest.png` (§18.6) and P9-9's `08-title-page-dialog` … `12-reader-still-cover.png` + `title-page-latest.png`, the From-scene title page's 1024×1024 `.39` render (§18.11); all looked at) | Phase 8 output deleted 2026-10-31 (pending item 4); `usability-run1…run13/` (55 MB) deleted 2026-10-31 after Stephen's reply (pending item 5). The current `usability/` set is the latest gate run's (the earlier sets it replaced are described in the ledger text only), `p94-cover/` the §18.6 + §18.11 one — keep both until Phase 9 closes |
| `src/AccountManagerUx752/e2e/results/` | Playwright `outputDir` (`retain-on-failure` trace) | ignored | 1 | 0 — empty (only `.last-run.json`); the P9-4 run-3/4, P9-6 run-15/16 and P9-9 run-1/2 (`pictureBookV3Cover-…/test-failed-1.png`) traces were each cleared by Playwright at the start of the next run (`outputDir` is wiped per run) | — |
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
6. **Phase 9 close** — delete `test-results/usability/` (the latest gate run's set) and `test-results/p94-cover/`, `scratch/p9-0/probe-picker-memo.mjs`, `scratch/p9-6/run16-shots/`, every `scratch/p9-*/` log already quoted in the ledger (`p9-7/`, `p9-8/`, `p9-9/`, `p9-close/pw-usability-run18/19.log`, `verify-p9-789/`), `target/TestPbCoverRenderLive/` and `target/TestPbCoverExitCriterionLive/` (18 PNGs, 22 MB — the 219 h before/after pair is in there; say if you want it promoted to `media/flux/qa/` before deletion).
7. **Objects7 `logs/` — overdue (1,439 MB > 1 GB)** — default: run `bash scratch/p9-close/o7-logs-rolled-before-p9-2026-11-01.sh --delete` (26 rolled files dated 2026-10-16 → 10-30, 1,413 MB; the current files and the 10-31 / 11-01 rolls are kept). Waiting on Stephen's go per the row.

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
  writes its `p27-chat-scene-v3-*.png` into `%TEMP%` (pre-existing, outside the repo — noted, not changed). **Deploy:** the P10-A 5 jar + dist
  into `am7test-am7-1` at 18:52 (`5-deploy.log`), the jar superseded by the P10-A 6 deploy below. `am7db` gains the fixture users / audit rows
  the tests write (resettable).
- 2026-11-02 (P10-A 6, ~19:05 → 20:05) — wrote `scratch/p10-a/56-*.log` (3 files, 0.4 MB, class 1 — the shared offline JUnit run for 254 h +
  255 c, install, deploy; quoted in §6.C 255 / §20.7). `TestAuditDescriptionBound` leaves user `p10a6AuditUser` and its audit rows in `am7db`
  (resettable). **Deploy:** the Objects7 jar carrying 254 h + 255 b (3,717,152 B) into `am7test-am7-1` at 20:05, container restarted,
  `/rest/schema` 200 after ~20 s (`56-install.log`, `56-deploy.log`). `scratch/p10-a/` 17 MB / 46 files total.
