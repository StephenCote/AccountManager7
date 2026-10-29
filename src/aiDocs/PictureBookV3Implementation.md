# PictureBook V3 — Implementation State

**As of:** 2026-10-29 local · **Phase 6 (Variants) IN PROGRESS** on Stephen's "implement this" (plan + architect verdict, defaults
standing): P6-1 schema, P6-3a selector, P6-2 variant loop, P6-3b retention util + routes and P6-4 Ux752 strip + dialog are built and
reported (§13.6, §6.C 212) — `am7db` 62 / 0 / 0 / 1, Service7 H2 23 / 0 / 0 / 0, Ux752 `VERIFY_OK`, Playwright on Docker 3 / 3;
Scenario F ran live on `.39` once it came back (`TestPbVariantsLive` 1 / 0 / 0 / 0, `p65-live.log`; §13.6 "Scenario F"); the
verifier's verdict is in §13.6. **Next: STOP for the P6 hand-back.** Phase 5
is reported through P5-3 (§13.5, §6.C 211; its "Yours" below is still Stephen's); every default through 209 accepted 2026-10-28
(§6.C 210); nothing is DONE by me (archive §6.C 159 b). Phases 0–4 are closed (Phase 3 exit 2026-10-18, §6.C 182; Phase 4 exit 2026-10-26, §6.C 207); their history, the
closed Phase 5 slices (§3 P5-2 probes, §4.11–§4.13, §13.1–§13.4) and §6.C entries 2–206 / 208–209 are in
`archive/PictureBookV3Implementation-P0-P4.md` with the original section numbers — a "§n" reference that is a one-line stub here
resolves there (the former header paragraphs are its §0). Still open and Stephen's, unchanged since Phase 0–1: archive §6.A, §6.D,
§6.E, §6.G, §6.L (roll-up in §6).

**Design of record:** `PictureBookV3Design.md` (ratified 2026-10-09). Where this doc and the design
disagree, the design wins; every deviation is recorded in §5 here **and** as a dated in-place edit in
the design. Format follows `PictureBook2ImplementationState.md`.

**Working rules for this doc (from the brief):** updated after every task; test results are pasted
verbatim (`Tests run: n, Failures: 0 … BUILD SUCCESS`, Playwright `n passed`, the `[LLM-GATE]` /
`[e2e-llm]` line, measured `durationMs` as a trend, never a threshold); services re-probed before every
test run; phase is DONE only on `VERIFY_OK` plus verifier / architect / security-reviewer verdicts.

**Terseness (Stephen, 2026-10-28 — "you are incredibly verbose"):** a results section is ≤ ~60 lines and a §6.C entry ≤ ~25 lines;
evidence is a log path plus line numbers (`p52-t11d.log:2873`), never a quoted paragraph; counts are `run / fail / err / skip`; a
closed section (slice reported, defaults accepted) moves verbatim to the archive with a one-line `> Moved …` stub in the doc pass
that follows its close, and the §1 row of a closed phase is one line.

**Open-item defaults in force (§34):** auto-approve default **off**; default preset **DRAFT**; verify
bar **two weeks + sign-off**; estimate source **(b) seeded by (a)**.

---

## 1. Status at a glance

| Phase | State | Tests |
|---|---|---|
| **0 — Hygiene + Kontext Deprecate** | **AT PHASE END** 2026-10-10 (gate red only on Stephen's §6.A / §6.B / §6.F / §6.H items; P0-1…P0-11 DONE with live tests; Phase 1 started 2026-10-10) — full row archive §1 | 136 green in the task runs — archive §1, §2 |
| 1 — Graph-first rendering + review | Row last written 2026-10-13 (**IN PROGRESS**, "NOT declared done" by me; the §9 follow-ups PF-1 / PF-9 / PF-15b DONE); Phase 2 started on it 2026-10-13 — full row archive §1 | archive §1, §8–§9 |
| 2 — Style, model resolution, compiler core; ChapBook goes V3; Kontext Disable | **GREEN PASS COMPLETE** 2026-10-15 19:45 (P2-10 gate; phase exit Stephen's, 159 b) — full row archive §1 | archive §1, §10 |
| 3 — Locations + POI link | **DONE 2026-10-18** — Stephen confirmed the §11.8 report and answered every open ask (§6.C 182) — full row archive §1 | archive §11 |
| 4 — Composition + wardrobe | **DONE 2026-10-26** — Stephen confirmed the Phase 4 exit (§6.C 207, "will catch any issues in next") — full row archive §1 | archive §12 |
| 5 — Multi-character strategies (spike first) | **IN PROGRESS** — P5-0 spike (archive §13.1, §6.C 204), P5-1 graph builder (archive §13.2 / §4.12, §6.C 205), P5-2 executor wiring (archive §13.4 / §4.13, §6.C 208–209) and P5-3 implementation cleanup (§13.5, §6.C 211) run and reported; 2026-10-25 went to the Part A doc consolidation (archive §13.3, §6.C 206); every default through 209 accepted 2026-10-28 (210); nothing DONE (159 b); **next: STOP for the P5-3 hand-back** — former row archive §1 | `TestP50SwarmSpike` 6 (gated); live on `.39`: `TestP51GraphLive` 5 / 5, `TestP52SequentialInpaintLive` 5 / 5 (`p52-t11d.log:2873`; P5-3 re-run 5 / 0 / 0 / 0, `p53-t11.log`), `TestPbRenderPlanExecutor` 8 / 8; offline `olio.illustration.*Test*` 187 / 187 (`p51-unit5.log`), P5-2 offline set 133 / 133 (`p52-run-final3.log`); P5-3 offline 28 + batches 49 + 108 + Service7 36, all 0 fail (`p53-offline2.log`, `p53-batch1.log`, `p53-batch2.log`, `p53-s7-tests.log`); 12-class regression set 116 / 0 / 0 / 1 (`p53-regression.log`; P5-2 116 / 0 / 0 / 1) |
| 6 — Variants | **IN PROGRESS** 2026-10-29 — P6-1 schema, P6-3a selector, P6-2 variant loop, P6-3b retention util + routes, P6-4 Ux752 strip + dialog built and reported (§13.6, §6.C 212); Scenario F run live on `.39` (§13.6); verifier verdict in §13.6; nothing DONE (159 b) | `am7db` one JVM **62 / 0 / 0 / 1** (`p6-regress1.log:154677`; the skip was the `.39` outage) after one red run of mine (`p62-regress1.log`, concurrent recompile) and one red sweep (`p63b-sweep.log`, fixture grants); **Scenario F live `TestPbVariantsLive` 1 / 0 / 0 / 0** (`p65-live.log:496`, STANDARD 2 + FINAL 4 variants on `.39`, approve one, retention K = 1 archives 3); Service7 H2 `TestPictureBookRestContract` 23 / 0 / 0 / 0 (`p63b-contract.log:3064`); Ux752 Vitest 1469 / 1469, quick gate `VERIFY_OK`; Playwright on Docker 3 / 3 after a 1 / 2 first run |
| 7 — PictureBook goes V3 | TODO | — |
| 8 — Visual QA | TODO | — |
| 9 — Cover & book art | TODO | — |
| 10 — Removal (PB1, old ChapBook path, Kontext Remove) | TODO | — |

---

## 2. Phase 0 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §2 (section numbering unchanged there).

## 3. Services checked

> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §3 (the 118 P0–P4 service probes, 2026-10-09 → 2026-10-23; new P5+ probes append here) (section numbering unchanged there).

**P5-3 probes, 2026-10-28 18:12** (host; `curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5`): SwarmUI `http://192.168.1.39:7801/` 302;
Ollama `http://192.168.1.42:11434/api/tags` 200; LiteLLM `http://127.0.0.1:4000` 000 (down — the test runs went direct to `.42`, §13.5);
`postgres3` `pg_isready` accepting. SwarmUI `.39` stopped answering at 19:21:43 (`000` ×5, host pinging) right after the regression set's last render,
reported to Stephen at once, restarted by him (".39 crashed ... it's back up now"), 302 again at 22:14:41 — the live leg ran after that (§13.5).
The P5-2 probes (2026-10-26) moved to the archive §3 (§6.C 211 j).

## 4. Verification per phase

| Phase | `verify.sh` | verifier | architect | security-reviewer |
|---|---|---|---|---|
| 0 | Two full gates `VERIFY_FAILED` on Stephen's items only (2026-10-10; archive §4.1–§4.3: Objects7 `902/5/0/15` = §6.A ×3 / §6.B / §6.F, Service7 `108/0/1` = §6.H, 75 e2e triaged) — former row archive §4 | Slices A/B CHANGES-NEEDED (resolved); P0-10d, P0-11 and the phase-level pass PASS-WITH-NOTES (2026-10-09/10) | plan reviews 2026-10-09 (A/B 7 APPROVED / 4 CHANGES-NEEDED, C/D CHANGES-NEEDED on P0-10a / P0-11 — all resolved, §6.C); code reviews A/B and C/D PASS | P0-10c PASS, Slices A/B PASS-WITH-NOTES, P0-10d PASS-WITH-NOTES, P0-11 PASS (2026-10-09) — former row archive §4 |

> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §4.1–§4.3 (Phase 0 gate runs + the 75-e2e triage) (section numbering unchanged there).

### 4.4 Phase 1 module-gate runs (2026-10-10 → in progress)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §4.4 (Objects7 module-gate runs 1–30, 2026-10-10 → 2026-10-23: the cross-phase run table and per-run notes) (section numbering unchanged there). Phase 5 has run no full Objects7 gate yet (P5-2 ran a 12-class regression set, §4.13); run 31+ append here in the same table.

> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §4.5–§4.8 (Phase 1–3 full Ux752 gates: runs 1–5); §4.9/§4.10 (Phase 4) moved 2026-10-26, next stub (section numbering unchanged there).

> Moved 2026-10-26 to `archive/PictureBookV3Implementation-P0-P4.md` §4.9–§4.10 (Phase 4 full Ux752 gates: runs 6–7, P4-11 / P4-14; Phase 4 exit confirmed by Stephen 2026-10-26, §6.C 207) (section numbering unchanged there).

### 4.11 P5-0 Phase 5 spike — `TestP50SwarmSpike` (Objects7, gated, no DB, no LLM) against SwarmUI `http://192.168.1.39:7801` — run 2 (2026-10-23 23:02:08 → 2026-10-24 00:12:00 local, `Total time: 01:09 h`): `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4192 s`, `BUILD SUCCESS` — 6 / 6 ran, 29 render requests: 26 images, 1 server-side error (FACEID), 1 unmeasured (client ceiling) → re-measured alone in run 3 (00:34:13 → 00:50:05), 1 WebSocket render (frames only); run 1 (22:55) stopped and archived; **27 PNGs opened and described** (26 from run 2 + the run-3 re-measure)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §4.11 (the `TestP50SwarmSpike` runs 1–3, 2026-10-23 → 2026-10-24; ratified 2026-10-24, §6.C 204) (section numbering unchanged there).

---

### 4.12 P5-1 graph builder — `TestP51GraphLive` (Objects7, gated, `am7db`, pure SD, no LLM call) against SwarmUI `http://192.168.1.39:7801` (`0.9.8.4.GIT-d74f8ce4`) — run 5 (2026-10-24 19:29:58 → 19:38:39 local, `Total time: 08:46 min`): `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 522.0 s`, `BUILD SUCCESS` — 5 / 5 ran, 9 render requests: 7 images (1 BASE FLUX.2 with book 58's three real portraits, 4 FLUX.2 ACTOR_REGION variants, 1 BASE SDXL, 1 ACTOR_REGION SDXL) + 2 deliberate rejections (t04a uninstalled checkpoint → `RENDERER_REJECTED`, t04b unknown node type → `GRAPH_INVALID` before any request); runs 1–4 red (below); **the 7 PNGs of run 5 opened and described** (and the 7 of each of runs 3 and 4 before them)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §4.12 (`TestP51GraphLive` runs 1–5 and the regression set after run 5, 2026-10-24; defaults accepted 2026-10-26, §6.C 207) (section numbering unchanged there).

### 4.13 P5-2 executor wiring — offline set, regression set, then the live leg (Objects7, `am7db`, one surefire JVM each; **first half with no SD reachable — `.39` unreachable until 16:44, §3 — live leg after that**) — 2026-10-26

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §4.13 (P5-2 offline set, regression set and live-leg runs 1–4, 2026-10-26; defaults accepted 2026-10-28, §6.C 210) (section numbering unchanged there). P5-3 ran no gate table — its runs are listed in §13.5.


---

## 5. Deviations from design

Each entry here is mirrored by a dated in-place edit in `PictureBookV3Design.md` (or the gap doc
`PictureBookImageGenerationDesign.md` where the error originated).

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §5.1–§5.7 (Phase 0–4 deviations and recorded facts, 2026-10-09 → 2026-10-22) (section numbering unchanged there). Phase 5 deviations are recorded in each slice's §6.C entry (204, 205, 208, 209) and as dated in-place edits in the design; a §5.8 is opened here only when one needs more than an entry.

## 6. Blocked / waiting on Stephen

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §6.A, §6.B, §6.D, §6.E, §6.F (Phase 0–1 blockers; §6.B and §6.F RESOLVED 2026-10-12) (section numbering unchanged there). Still open there and Stephen's: §6.A (three PB-series tests red — policy cache vs the series grant model), §6.D (`postgres3` PANIC 2026-10-09), §6.E (architect flags from the Slice A/B review).

### 6.C Decisions already made under auto mode

Entries 2–206 (2026-10-09 → 2026-10-25, Phase 0 through the Part A doc consolidation) and 208–209 (P5-2, 2026-10-26) moved to
`archive/PictureBookV3Implementation-P0-P4.md` §6.C on 2026-10-28 (numbering unchanged there; 159 b = "nothing is DONE by me" lives there).
Every entry is a ⚠ MY JUDGMENT call under auto mode with its default; Stephen accepted all open defaults through 209 on 2026-10-28 (210).

207. ⚠ **2026-10-26 (Stephen's rulings on §13.3 "Yours" and the §13.2 defaults; Phase 4 exit; the STEP 0 doc pass that records
     them, before P5-2).** Nothing here is DONE by me (159 b); Phase 4 is DONE by his word. His answers, verbatim, and what each closes:
     (1) "Phase 4 exit CONFIRMED — will catch any issues in next" → closes 206 (b): §12 and §4.9 / §4.10 moved to the archive this
     pass, file renamed `archive/PictureBookV3Implementation-P0-P4.md`; §1 Phase 4 row → DONE 2026-10-26 (Phase 3 row pattern).
     (2) "keep `PictureBookDesign.md` / `PictureBook2Plan.md` live" → closes 206 (f) at its default (reference banners stay).
     (3) "KI-91 stays home of the leftovers" → closes 206 (c) at its default (no design §34 rows for them).
     (4) "keep pre-images + P5-1 scratch" → closes 206 (k) at its default (nothing deleted; `C:/tmp/p51-*`, `C:/tmp/p51_*`,
     `C:/tmp/*.pre-split`, `AccountManagerObjects7/target/p51/` stay until he says).
     (5) "keep `## Project content policy` in root `CLAUDE.md` for now" → closes 206 (e) at its default.
     (6) "keep the three pre-existing re-points (206 l)" → closes 206 (l) at its default.
     (7) "proceed with P5-2" → the §13.2 plain questions (1)–(9) were not answered individually, so their defaults stand: (1)
     `capabilities.json` flux2 inpaint `4 / 1.0` → `8 / 0.6` in P5-2 together with the calibration record; (2) three real
     portraits (book 58) is the real cast; (3) `isGraphRejection` stays broad until the executor needs the split; (4)
     `TestP51GraphLive`'s portrait read → a planned `profile.portrait` on the `AccessPoint.find` query in P5-2 (live re-run);
     (5) `TestPictureBookWorkflow`'s mutation of book 58 is left; (6) the p50 fixtures are refreshed from a live capture after
     P5-2; (7) the 205 (w) / (x) one-liners are P5-2's; (8) the `gpt-oss:120b` key migration is its own task, not P5-2's; (9) the
     `TestModelResolver` update and the INPAINT inventory answer stand. 206 (a) / (d) / (g)–(j) were not raised and stay at their
     defaults.
     (8) NEW: "add implementation cleanup to remove/redact/deprecate all the older/replaced picturebook features" → new slice
     **P5-3** (scope paragraph in §13; results → §13.5; its rulings → 209), sequenced after P5-2 — see (a) / (b).
     Judgments taken in this pass:
     (a) ⚠ **(8) read as an inventory-first, stage-aware cleanup slice that honours design §28.1 (disable → verify → remove;
     Stage 4 = Phase 10) and `PbRenderPath.PICTUREBOOK_DEFAULT = V1` until Phase 7** — three verbs: REMOVE what has no remaining
     caller (verified by grep, not assumed), REDACT replaced surfaces where V3 parity exists (the PB1 entry points of §28.1 Stage 2
     stay reachable under `v1`, gated only behind `PbRenderPath`; Ux752 hides replaced menu / wizard surfaces behind
     `GET /rest/config/render-path`), DEPRECATE what Phase 7 / 10 still needs (`@Deprecated` + WARN on use + a §28.1
     removal-inventory row: file:line, replacement, removing phase). The alternative — read (8) as "pull Phase 10 forward: flip the
     default to V3 and delete PB1 now" — was not taken; it is the first plain question of the P5-3 plan. Default: keep §28.1
     staging; the default is not flipped and PB1 is not deleted unless he answers yes.
     (b) ⚠ **P5-3 sequenced after P5-2**, not before or interleaved: P5-2 is the slice (7) says to proceed with, and the cleanup
     inventory is more accurate once the executor wiring shows which PB1 / Kontext paths V3 actually replaces. The alternative —
     cleanup first so P5-2 lands on a smaller code base — was not taken. Default: P5-2, then P5-3.
     (c) ⚠ **§12 moved as `## 12. Phase 4 — tasks` through its closing `next §6.C number 204 (P5-0).` line (2,185 lines, §12.1–§12.15
     verbatim); the blank / `---` / blank that separate it from `## 13.` stay live as §13's separator**; the archive's §11 block
     already ended with one blank line, so `## 12.` was appended after it with nothing added. The live keeps the `## 12.`
     heading plus a one-line `> Moved 2026-10-26 …` stub, as §7–§11 do. The alternative — move the separator too and re-add one
     — was not taken. Default: stands.
     (d) ⚠ **§4.9 + §4.10 moved as one block (108 lines, `### 4.9 …` through the blank before `### 4.11`) into the archive between
     §4.8's last paragraph and the `---` before `## 7.`**, so the archive's §4 now runs §4.1–§4.3, §4.5–§4.10 in order; the
     2026-10-25 stub above them ("§4.9/§4.10 (Phase 4) stay below") re-worded to "moved 2026-10-26, next stub", and a second
     `> Moved 2026-10-26 …` stub placed where they stood. §4.11 / §4.12 (Phase 5) stay live. Default: stands.
     (e) ⚠ **Archive renamed with `os.rename` (Phases 0–3 suffix → `-P0-P4.md`; no `git mv`), banner rewritten for Phases 0–4** (it now
     says §4.9 / §4.10 / §12 arrived 2026-10-26 under 207 and lists §8–§12); the 12 old-suffix citations in this file (L3, the
     `> Moved` stubs, 206 (b), the §13.3 table) and the 2 in `README.md` re-pointed by filename substitution only — no
     ledger text rewritten except the README's own two row descriptions (archive row: "Phases 0–4 … §4.5–4.10 … §7–§12"; live
     row: "Phase 5 runs §4.11–§4.12 … Phases 0–4 history"). a `grep -rn` for the old suffix over `src/aiDocs`, `CLAUDE.md` and `.claude` is empty afterwards (this ledger avoids the literal too).
     The alternative — a fresh `-P4` archive file — was not taken (one file, original numbering, 206 b). Default: stands.
     (f) ⚠ **Counts and conservation (`wc -l` / `tr -cd '\r' | wc -c`, one script `C:/tmp/p52_step0.py`, same byte rules as
     206):** this file 11,370 → 9,081 after the moves (−108 −2,185 +4: two stubs, one blank, one re-used heading) → 9,139
     after this entry, the §13 P5-3 paragraph, the §1 / L3 / footer edits (LF, 0 CR); archive 12,151 → 14,444 (+108 +2,185;
     LF, 0 CR); 11,370 + 12,151 = 9,081 + 14,444 − 4 = 23,521. `README.md` 113 → 113 (CRLF). Nothing committed,
     staged or pushed. Default: stands.
     (g) ⚠ **The "As of" line (L3) got a 2026-10-26 lead-in** (the file's own convention for every pass) and the footer's 205 line a
     trailing "§13.2 (1)–(9) defaults stand (207)" so the open-items roll-up matches (7). Not asked for by name; the alternative —
     footer 206 line only — was not taken. Default: stands.

210. ⚠ **2026-10-28 (Stephen: "I'm okay with the defaults" + "thoroughly review picturebook documentation … completed sections can be
     moved off to archive with a pointer"; this doc pass).** Accepted at their defaults: §13.4 "Yours" (1)–(21), 208 (a)–(az), 209 (a)–(t);
     P5-3 therefore keeps design §28.1 staging (`PICTUREBOOK_DEFAULT` stays `V1`, PB1 stays), removes `CLASS_TEXT_ENCODE_ADVANCED`
     (209 m) and carries the `renderStages` seam (209 b) + `ActorTarget.baseArtifactObjectId` cleanup (208 ay). Moved verbatim to the
     archive (counts in the hand-back): the former "As of" header (→ §0), §1 rows 0–5 (→ §1), the §4 Phase 0 row, §4.4, §4.11, §4.12,
     §5.1–§5.7, §6.A / B / D / E / F, §6.C 2–206, §6.G–§6.N, §13.1–§13.3; live keeps every heading with a stub. Nothing DONE (159 b).
     (a) ⚠ §4.12 and §13.2 (P5-1) moved although §13.4 cites them — P5-1's defaults were accepted 2026-10-26 (207), so it is closed
     under the new terseness rule; alternative: keep them live one more pass. Default: moved.
     (b) ⚠ §4.4 (gate-run table, runs 1–30) moved, heading kept for runs 31+ — cross-phase table, but every row is Phase 1–4;
     alternative: keep live. Default: moved.
     (c) ⚠ Open-but-Stephen's §6.A / §6.D / §6.E / §6.G / §6.L / §6.N moved with a one-line roll-up left live — none changed since
     Phase 0–1, none blocks Phase 5; alternative: keep them live. Default: moved, roll-up stays.
     (d) ⚠ The "As of" paragraph, §1 rows 0–5 and the §4 Phase 0 row rewritten as one-liners (originals archived verbatim); each new
     row restates its own closing state and adds no status. Default: stands.
     (e) ⚠ Terseness rule added to "Working rules for this doc" (≤ ~60-line results, ≤ ~25-line entries, log path + line evidence,
     move on close). Default: stands.
     (f) ⚠ `PictureBookV3Design.md` untouched this pass; archive candidates reported in the hand-back only (§34 ratified decisions →
     a table; §36 / §38 are ground truth and stay). Default: no design edits.

211. ⚠ **2026-10-28 (P5-3 implementation cleanup — inventory first, REMOVE / REDACT / DEPRECATE under design §28.1 staging; Stephen's
     answers (1)–(5), "I'm good with your proposed defaults - continue": (4) `FixtureGraphClient` public overriding `generatedWorkflow` /
     `swarmVersion`, (5) the seam as a parameter overload — built as ruled; results §13.5).** Nothing DONE (159 b).
     (a) ⚠ `TestPbRenderStagesOffline` went 4 / 1 / 3 / 0 (all four red) on the p50 fixtures (captured with three reference loaders; the live generator
     emits one per image) → opt-in `FixtureGraphClient.pruneUnusedLoaders()` splices the unfilled loader + VAEEncode + ReferenceLatent
     chain; alternative: capture 1- / 2-reference fixtures from `.39`. Default: prune, off by default so the compiler's leftover-loader
     guard still sees the node.
     (b) ⚠ `testStripSDXLWeighting` relocated to a new pure-JUnit `TestSwUtilPromptText`, not `TestFlux2Composite` as design §37.4 says —
     that class extends `BaseTest` (DB); the helper needs neither; alternative: follow §37.4 literally. Default: new class; §37.4 corrected.
     (c) ⚠ `StageSequence.swarmVersion` asked of the `SwarmGraphClient` once per sequence (re-asked only while null) instead of
     `sdu.swarmVersion()` per sample — same source (`SwarmGraphClient.swarmVersion()` → `sdu.swarmVersion()`), and the fixture client
     can answer `fixture-p50` offline. Default: stands.
     (d) ⚠ `SDUtil.stitchSceneImages` javadoc retitled only (shared by `executeReferenceStrip`); not deprecated. Default: stands.
     (e) ✓ Stephen (2): PB1 `generateSceneImage` under `picturebook.renderPath=v3` WARNs (`PictureBookUtil.java:9755`), no refusal (Stage 2 = Phase 7).
     (f) ✓ Stephen (3): legacy `REFERENCE_STRIP` / `COMPOSITE` nodes → one WARN per dispatch with book / node oid (`PbNodeExecutor.warnLegacyNode` :501).
     (g) ⚠ Ux752 untouched — replaced surfaces already hide behind `GET /rest/config/render-path`; the only candidates were comment
     rewrites (design §37.4 Ux752 rows), left for Phase 10 with the rest of Kontext. Default: no Ux edits, no Playwright.
     (h) ✓ Stephen (1): Kontext dead code (design §37.4 #3–#8, `TestKontext` delete, the excluded-test rewrites) → Phase 10. (i) ⚠ So
     `TestKontext` is left pom-excluded with a one-line relocation comment, not deleted. Default: stands.
     (j) ⚠ Doc pass: the §3 P5-2 probes, §4.13, §6.C 208–209 and §13.4 moved verbatim to the archive with stubs (terseness rule, 210 e);
     design §28.1 gets a dated P5-3 bullet and §37.4 dated in-place corrections from the tree (`TestPictureBookUtilPersistenceE2E.java`
     does not exist; `kontext-test-output` writers already go to `./target/…` and the scratch dir is gone; `bad.composite.png` has no
     reader; `test.swarm.kontextModel` is `resource.properties:126`). Default: stands.
     (k) ⚠ `SceneCompositeUtil`'s four deprecated builders carry `@Deprecated` + javadoc but no WARN of their own — the PB2 path WARNs at
     dispatch (f); the chat `v1` scene path stays silent; alternative: a WARN per scene in `Chat` / `ChatService`. Default: none until Phase 7.
     (l) ⚠ The first Service7 test command omitted `-DskipTests=false` → "Tests are skipped" + `BUILD SUCCESS` having run nothing
     (`p53-s7-tests.log:37-39`); discarded and re-run (§13.5). Mine.
     (m) ⚠ `TestPbRenderStagesOffline` leaves `olio.sd.calibration` rows keyed to its closed-port server `127.0.0.1:9` in `am7db` after
     each run (verifier note; the test counts before / after, so it is stable); alternative: delete them in `@After`. Default: leave.

212. ⚠ **2026-10-29 (Phase 6 — Variants, P6-1 → P6-3a → P6-2 → P6-3b → P6-4 built in that order per Stephen's plan + architect verdict,
     "unanswered = defaults stand"; results §13.6).** Nothing DONE (159 b). Defaults in force: variant count from the preset only; K = 3 /
     D = 30 overridable per request, never persisted; manual dry run + apply, no scheduler; bytes deleted = `data.data` row removed + FK
     nulled, artifact row kept; no un-archive.
     (a) ⚠ The variant loop was proven offline first (`TestPbRenderStagesOffline`, fixture client); the live leg (Scenario F) ran the same
     day once `.39` was back — `TestPbVariantsLive` 1 / 0 / 0 / 0 (§13.6 "Scenario F"). Test-design choices there, mine: autoApprove OFF so
     "approve one" is a human act on FINAL v2 (not the headline); K = 1 so the older attempt is the one archived; the 60-min `@Test` timeout
     is a hang guard, never a threshold. Observed, not changed: the FINAL preset's UPSCALE stage is a reported degradation (Phase 2 renders
     BASE only) and so vetoes auto-approve; retention archived 3, not 2, because the older attempt's `renderPlan` JSON artifact is its own
     attempt under the role-scoped rule. Alternative: count attempts across roles. Default: as built.
     (b) ⚠ Variants = the plan's **whole stage sequence run N× sequentially** (BASE + inpaints + upscale per variant), not N images per
     stage — design §23 read that way; alternative: batch at the renderer. Default: sequential.
     (c) ⚠ `variants` comes from the preset only — no `olio.pictureBookRequest` override; `qualityPresets.json` says so. Default: stands.
     (d) ⚠ `VARIANT_FAILED` (v > 0 failed; vetoes auto-approve) and `VARIANT_SEED_UNKNOWN` (renderer reported no seed; accepted) added as
     plan degradations; the veto set is six codes (test `case08` updated 5 → 6). (e) ⚠ A failed middle variant leaves a gap in
     `variantIndex`; the attempt is kept. Retention applies to every artifact type; IN_REVIEW is always kept; ChapBook pages and
     LOCATION nodes render one variant (their presets). Default: stands.
     (f) ⚠ `keepAttempts` / `deleteBytesAfterDays` had to be declared on `olio.pictureBookRequest` and read via `hasField()` — an
     undeclared body field is dropped (KI-24) and an absent int reads 0 (KI-25), which for D would have deleted bytes immediately.
     (g) ⚠ `TestPbRetentionSweep` uses its own `pb63-retention` book on `am7db`, cleaned in `@After`. (h) ⚠ Its reader needs READ on the
     container's `/Workflow` and `/Artifacts` groups — a retention scan walks both; the sibling tests' readers never did (first run red, mine).
     (i) ⚠ `PbServiceFacade.ensureSchema` with `{}` yields null params — pre-existing, not touched. (j) ⚠ The Service7 POM also skips
     tests by default; `-DskipTests=false` there too (211 l, caught before the run was reported). (k) ⚠ Ux752: Retention is a toolbar
     button + `dialogCore` dialog, not a tab; null-`attempt` rows (pre-P6, TEXT, renderPlan) form one group each. (l) ⚠ Apply is enabled
     only after a dry run in this dialog that lists ≥ 1 ARCHIVE / DELETE_BYTES. (m) ⚠ The Docker DB got the three P6-1 columns by boot
     DDL on `docker restart` — no migration (objects7-reference Path 1).
     (n) ⚠ At 1280 px the node detail panel (`position:fixed; right:16px; top:80px; width:280px`) overlays the toolbar's right end, hiding
     the Retention button while a node is selected — pre-existing geometry; the spec uses 1600 × 900; alternative: move the button or the
     panel. Default: not fixed here.
     (o) ⚠ **Mine, found live:** `normalizeArtifact` showed `s0` for rows whose `seed` is the `long` column's DDL default 0; now a seed is
     known only when > 0 (the legacy result card's convention). (p) ⚠ **Pre-existing, not fixed:** `PUT /{book}/node/{node}/canvas` 500s —
     `olio.pictureBookRequest` has no `x` / `y` / `w` / `h`, so `getInt` throws (`PictureBookService.java:234-239`, `:2186-2203`; same in
     HEAD; KI-24 shape); fix = declare the four ints + `optionalInt`; its own task. Default: stands.

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §6.G, §6.L, §6.H–§6.N (Slice C findings, the nude-fixture question, Phase 0–1 infrastructure items; §6.H / §6.I / §6.J / §6.K RESOLVED, §6.M FIXED) (section numbering unchanged there). Still open there and Stephen's: §6.G (Slice C transport findings 1–12), §6.L (the `generateScene` e2e fixtures render nude), §6.N (`.39` outage log 2026-10-11 → 2026-10-14).

## 7. Phase 1 start prompt (drafted 2026-10-10 while the §4.2 gate ran; **valid as of 22:35** — §4.2 + §4.3 show no new blocker; Ux752 quick gate `VERIFY_OK`)
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §7 (section numbering unchanged there).

## 8. Phase 1 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §8 (section numbering unchanged there).

## 9. Phase 1 follow-ups — product defects (2026-10-12, from 14:40 local)
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §9 (section numbering unchanged there).

## 10. Phase 2 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §10 (section numbering unchanged there).

## 11. Phase 3 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §11 (section numbering unchanged there).

## 12. Phase 4 — tasks
> Moved 2026-10-26 to `archive/PictureBookV3Implementation-P0-P4.md` §12 (Phase 4 tasks, §12.1–§12.15; Phase 4 exit confirmed by Stephen 2026-10-26, §6.C 207) (section numbering unchanged there).

---

## 13. Phase 5 — tasks

Scope: design §30 row 5 and §38.4 / §38.5 as corrected (architect F3, 2026-10-23): **the spike measures the contract and captures
Swarm's generated graphs against the live backend; the graph compiler (`WorkflowCompiler` / `ComfyGraph`) is P5-1 and later, product
code in `olio/illustration/`, built from the captures.** Nothing in this section is DONE (159 b); P5-1 starts only after P5-0 is
ratified. **Ratified 2026-10-24 (Stephen, in answer to §13.1 "Yours"):** (9) P5-0 as measured; (7) design §34 Q14 → **(b)** a
per-server calibration record seeded by (a) the spike's numbers; (3) the FLUX.2 inpaint pass — **measure both** (more steps vs
creativity 1.0 inside the mask) in P5-1 before deciding; (1) **real cast if one exists** (an `am7db` book with ≥ 4 real portraits;
AIME-derived only if none, and say so). (2) FACEID out, (4) `useinpaintingencode` excluded, (5) 90-min spike ceiling, (6) no
re-measure of `4actor_inpaint_flux2`, (8) the `architecture.md` spike sentence in his next rules pass — all at their defaults.

**Backlog (found on the way, not in any task yet):** the `composition.book`-FK branch of `PbOrphanUtil` (`:558-560`) — the page /
element rows whose composition names a deleted book — is now exercised by the second case of `TestPbOrphanCompositions` (added in
P5-1 as the first Phase 5 test slice, §6.C 204 y / 205 y); its run evidence is §4.12 "Regression set after run 5". Nothing else
is parked here from P5-1 — the out-of-scope list is §6.C 205 (aa).
**P5-3 — implementation cleanup (added 2026-10-26; Stephen's item 8, §6.C 207; runs after P5-2; results → §13.5, rulings → §6.C 211):** "add implementation cleanup to remove/redact/deprecate all the older/replaced picturebook features." Planned against design §28.1 (disable → verify → remove; Stage 4 = Phase 10) and `PbRenderPath.PICTUREBOOK_DEFAULT = V1` until Phase 7, not around them — an inventory-first, stage-aware slice with three verbs. **REMOVE**: code / tests / resources with no remaining caller, each verified by grep, never assumed (candidates: `SceneCompositeUtil` and the Kontext composite path per design §37.4 — Kontext Disable landed in Phase 2; the pre-V3 scene-copy path retired in P4-10; dead PB2-plan artefacts; `imageComposite`-era `SWTxt2Img` paths; anything the archived docs describe that nothing references). **REDACT**: replaced surfaces that must stop being reachable where V3 parity exists — ChapBook is already v3-only at the switch; the PB1 entry points of §28.1 Stage 2 (`/scene/{id}/generate`, `prepare-images`, PB1 settings writes, wizard steps 4–5) stay reachable under `v1` until Phase 7 and are gated only behind `PbRenderPath` as designed; in Ux752, replaced menu / wizard surfaces hide behind `GET /rest/config/render-path`. **DEPRECATE**: what Phase 7 / 10 still needs — `@Deprecated` + WARN on use + a row in a §28.1 removal inventory (file:line, replacement, removing phase). Flow: planner (inventory with grep evidence per item: callers, tests, REST routes, Ux routes, docs) → architect (layering, PBAC untouched, no column drops except via the off-by-default logged property) → implement → Objects7 compile + affected tests, `mvn -o -pl AccountManagerService7 compile`, Ux752 `npx vite build` + `npx vitest run`, Playwright for any Ux surface changed (Docker stack, `ensureSharedTestUser()`) → the P5-2 regression set in one JVM → verifier → doc (§6.C 211, §13.5, design §28.1 / §37.4 inventory updates, LF). First plain question of its plan, with default: pull Phase 10 forward (flip `PICTUREBOOK_DEFAULT` to V3 and remove PB1 now) or keep §28.1 staging (remove only dead code, redact / deprecate the rest; PB1 removal waits for the Phase 7 verify)? Default: keep §28.1 staging — the default is not flipped and PB1 is not deleted unless Stephen answers yes. **Run 2026-10-28: §13.5 / §6.C 211; the default was kept (Stephen, 2026-10-28, answers (1)–(5)).**


### 13.1 P5-0 results — the Phase 5 Swarm spike: `TestP50SwarmSpike` (6 methods, gated, Objects7 test tree, no DB, no LLM) + `P50Swarm` against SwarmUI `http://192.168.1.39:7801` (`0.9.8.4.GIT-5dc94afa`) with `flux2Klein_9b` + `juggernautXL_ragnarokBy` — 2026-10-23 22:40 → 2026-10-24 00:50:05 local (run 1 22:55 stopped; run 2 23:02:08 → 00:12:00, 6 / 6 ran, 29 requests = 26 images + 1 FACEID error + 1 client-cut + 1 WebSocket frames-only; run 3 00:34 → 00:50 re-measure of the cut render: 937 s, 3 of 4 actors)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §13.1 (P5-0 results; ratified by Stephen 2026-10-24 — the answers are in the §13 scope paragraph above and §6.C 204) (section numbering unchanged there).

### 13.2 P5-1 results — the graph builder: `ComfyGraph` / `GraphInputs` / `WorkflowCompiler` / `NodeCatalogue` / `SwarmGraphClient` / `MaskImage` (Objects7 `olio/illustration/`, product), the raw-graph path of `SwarmRendererAdapter`, the `renderGraph` artifact (`PbRenderPlanUtil.persistGraphArtifact`), `SDUtil` session / version, and the gated live class `TestP51GraphLive` (5 methods, Objects7 test tree, `am7db`, pure SD, no LLM call) against SwarmUI `http://192.168.1.39:7801` (`0.9.8.4.GIT-d74f8ce4`) with `flux2Klein_9b` + `OfficialStableDiffusion/sd_xl_base_1.0` and book 58's three real portraits — 2026-10-24 ~15:30 → 20:46:48 local (live runs 1–4 red at 18:43 / 18:47 / 19:17 / 19:28; run 5 green 19:29:58 → 19:38:39, 5 / 5, 9 requests = 7 images + 2 deliberate rejections; closing regression set 19:41:47 → 20:43:37 **red**, 243 run / 2 failures in the P4 `TestModelResolver` — my regression — fixed and the illustration wildcard re-run green 187 / 187 at 20:46:48)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §13.2 (P5-1 results, "Yours" (1)–(9) at their defaults per §6.C 207; its out-of-scope items (5) / (8) — `TestPictureBookWorkflow`'s `gpt-oss:120b` key and its mutation of book 58 — are still open, their own tasks) (section numbering unchanged there).

### 13.3 Documentation consolidation (Part A, 2026-10-25) — results; rulings §6.C 206

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §13.3 (Part A documentation consolidation results; rulings §6.C 206 → 207) (section numbering unchanged there).

### 13.4 P5-2 results — executor wiring: `PbNodeExecutor.renderStages` (BASE, then one ACTOR_REGION inpaint pass per overflow actor), `StrategySelector` → `DIRECT_REFERENCE` / `SEQUENTIAL_INPAINT`, `InpaintRegionCompiler`, the `olio.sd.calibration` record + `PbCalibrationUtil`, node-level `GRAPH_INVALID` / `RENDERER_REJECTED` codes, flux2 inpaint `8 / 0.6` (Objects7 only) — 2026-10-26; rulings §6.C 208 + 209; runs §4.13. **Offline green after the verifier's CHANGES-NEEDED (4 of 5 findings fixed); live leg RUN the same evening once `.39` answered ("Live leg" below): T11 5 / 0 / 0 / 0 on run #3 after one product defect (209 a) and one test defect of mine (209 d), 5 / 0 / 0 / 0 on run #4 after the verifier's five LOW findings (209 r), `TestP51GraphLive` 5 / 5, executor 8 / 8 twice, T12 refreshed, the 12-class regression set re-run with `PB_SD_TESTS=1` **116 / 0 / 0 / 1** in 1 h 14 min (`p52-regression2.log`; the one skip is `TestModelResolverLive.case07`'s inventory gate, no Kontext checkpoint on `.39`; `TestPictureBookWorkflow` 5 / 5 where it was 4 red on `.39` timeouts; 26 more PNGs opened and described); nothing DONE.**

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §13.4 (P5-2 results and its "Yours" (1)–(21), answered at their defaults 2026-10-28, §6.C 210) (section numbering unchanged there).

### 13.5 P5-3 results — implementation cleanup: inventory-first REMOVE / REDACT / DEPRECATE under design §28.1 staging (`PbRenderPath.PICTUREBOOK_DEFAULT` stays `V1`, PB1 stays, Stage 4 = Phase 10), the `renderStages` offline seam (209 b), `ActorTarget.baseArtifactObjectId` (208 ay), `CLASS_TEXT_ENCODE_ADVANCED` (209 m); rulings §6.C 211

**Inventory and verbs** (paths under `AccountManagerObjects7/src/main/java/org/cote/accountmanager/` unless noted; line numbers as of this pass; every REMOVE grep-proven caller-free first):

| Verb | Item | Where / how | Replacement, removing phase |
|---|---|---|---|
| REMOVE | `WorkflowCompiler.CLASS_TEXT_ENCODE_ADVANCED` (209 m) | `olio/illustration/WorkflowCompiler.java` (`CLASS_TEXT_ENCODE` now :51) | nothing referenced it |
| REMOVE | `ActorTarget.baseArtifactObjectId` field / ctor arg / getter (208 ay) | `WorkflowCompiler.java:85`; callers `PbNodeExecutor.java:1280`, `TestWorkflowCompiler.java:69, :496`, `TestP51GraphLive.java:837` → 3-arg `ActorTarget(actor, baseImage, boxes)` | never read |
| REMOVE (relocate) | `TestKontext.testStripSDXLWeighting` — the only test of `SWUtil.stripSDXLWeighting` (live FLUX.2 builders), never run (`TestKontext` pom-excluded, `pom.xml:154`) | → new pure-JUnit `objects/tests/TestSwUtilPromptText.java` (original assertions unchanged + a nested / bare-weight case); `TestKontext` keeps a one-line pointer | `TestKontext` itself stays until Phase 10 (211 h, i) |
| REDACT (WARN, no refusal — 211 e) | PB1 `generateSceneImage` under `picturebook.renderPath=v3` | `olio/picturebook/PictureBookUtil.java:9755` — one WARN naming the scene, group path, `POST …/render-plan`, Stage 2 (Phase 7) / Stage 4 (Phase 10) | RENDER_PLAN; refused Phase 7, removed Phase 10 |
| REDACT (WARN — 211 f) | legacy PB2 `REFERENCE_STRIP` / `COMPOSITE` nodes | `PbNodeExecutor.java:483-487` → `warnLegacyNode` :501, one WARN per dispatch with node oid / handle / book oid | RENDER_PLAN; removed Phase 10 |
| DEPRECATE | Service7 `PictureBookService.generateSceneImage` :705, `prepareImages` :775 | `@Deprecated` + javadoc; routes, status codes and PBAC unchanged | `POST /{book}/render-plan`; Phase 7 refusal, Phase 10 removal |
| DEPRECATE | `SceneCompositeUtil.includesLandscapeReference` :187, `buildSceneRequest` :222, `applyPromptOverride` :333, `defaultCreativity` :349 | `@Deprecated` + javadoc (211 k); mode vocabulary / `resolveMode` / refusal not deprecated | `PbRenderPlanUtil`; Phase 10 |
| DEPRECATE | `PbNodeExecutor.executeReferenceStrip` :1676, `executeComposite` :1777 | `@Deprecated`; the WARN is the dispatch row above | Phase 10 |
| retitle | `SDUtil.stitchSceneImages` :1772 javadoc ("for FLUX Kontext" → shared stitch) | design §37.4 row (211 d) | keep |
| SEAM (209 b) | `PbNodeExecutor.RenderBackend` :950-966 (`adapter(SDUtil)`, `client(SDUtil)`, `SWARM` = `new SwarmRendererAdapter(sdu)` / `new SwarmGraphClient(sdu)`); 7-arg `renderPlanned` :938 → 8-arg :967 with `RenderBackend.SWARM`; `renderStages(…, backend)` :1146; `StageSequence.swarmVersion` :1119 from `swarmVersion(seq, client)` :1322 (211 c) | package-private parameter overload (Stephen (5)); product callers unchanged | — |
| test infra | `olio/illustration/FixtureGraphClient` public (Stephen (4)): overrides `generatedWorkflow` / `swarmVersion` (`fixture-p50`; `SDUtil` at closed port `127.0.0.1:9`, never called), `requests()`, opt-in `pruneUnusedLoaders()` (211 a) | test tree | — |
| new test | `olio/picturebook/TestPbRenderStagesOffline` — 4 cases drive `renderStages` through the fixture client with no server | test tree | — |

**Not done, by ruling:** Kontext dead code (design §37.4 #3–#8, `TestKontext` delete, the excluded-test rewrites) → Phase 10 (211 h); `PICTUREBOOK_DEFAULT` not flipped, PB1 not deleted (§28.1); Ux752 untouched (211 g); `TestPictureBookWorkflow`'s `gpt-oss:120b` key / book 58 mutation — not P5-3.

**Runs** (Objects7 against `am7db`, one surefire JVM each, `-DskipTests=false`; `[LLM-GATE]` in words: LiteLLM `:4000` and the local container `:11435` refused, so every LLM call went to the remote `.42` Ollama directly — not Azure, not the container):
- Offline, post-fix (`C:/tmp/p53-offline2.log`): `TestWorkflowCompiler` 24 (:67), `TestPbRenderStagesOffline` 4 (:828) → 28 / 0 / 0 / 0 (:833), `BUILD SUCCESS` (:836). The first run (`p53-offline.log`) had `TestPbRenderStagesOffline` 4 / 1 / 3 / 0 — the compiler's leftover-loader guard firing on the three-loader fixtures (case 4 then saw 500 for 503) → fixed fixture-side with `pruneUnusedLoaders()` (211 a); `baseFailure` (`PbNodeExecutor.java:1344-1350`) unchanged.
- Batch 1 (`p53-batch1.log`): 49 / 0 / 0 / 0. Batch 2 (`p53-batch2.log`): `TestFlux2Composite` 36 (:77), `TestKontextDeprecation` 8 (:86), `TestPbSecurity` 10 (:2448), `TestSwUtilPromptText` 2 (:2453), `TestModelResolver` 41 (:2631), `TestPbKontextDisable` 3 (:2729), `TestPbNodeExecutorTypes` 2 (:3019), `TestPbSceneGenerationGuards` 6 (:4412) → 108 / 0 / 0 / 0 (:4416), `BUILD SUCCESS` (:4419); the new WARNs fire at :2651 / :2682 / :2715 (COMPOSITE) and :3064 / :3328 (PB1 `generateSceneImage`).
- Service7 (`p53-s7-tests.log`): the first command omitted `-DskipTests=false` → "Tests are skipped" + `BUILD SUCCESS` (:37-39), a non-run, discarded (211 l); the real run: `TestChapBookServiceRenderPath` 7 (:1226), `TestChatSceneKontextDisable` 9 (:2423), `TestPictureBookRestContract` 20 (:4992) → 36 / 0 / 0 / 0 (:4996), `BUILD SUCCESS` (:4999). Objects7 jar installed first (`p53-o7-install.log` — a `-q` run: no `BUILD` line in the log, no `[ERROR]`, shell exit 0), Service7 compiled (`p53-s7-compile.log:27` `BUILD SUCCESS`); `PictureBookService.class` checked with `javap` (no "Unresolved compilation", `Deprecated` in the constant pool).
- Ux752, no source change: `npx vite build` exit 0, 7.09 s (`p53-vite.log`); `npx vitest run` 98 files / 1445 tests passed (`p53-vitest.log:5-6`).
- 12-class regression set, `PB_SD_TESTS=1`, one JVM (`p53-regression.log`; `[LLM-GATE] route=direct tier=remote … server=http://192.168.1.42:11434` at :57): **116 / 0 / 0 / 1** (:192243), `BUILD SUCCESS` (:192246), `Total time: 01:08 h` — `TestModelResolverLive` 16 / 0 / 0 / 1 (:193), `TestPbGraph` 20 (:96444), `TestPictureBookWorkflow` 5 (2478 s, :100132), `TestModelResolver` 41 (:100310), `TestPbHealthStaleGraph` 2 (:189782), `TestPbOrphanCompositions` 2 (:189857), `TestPbOrphanScan` 1 (:189922), `TestPbRenderPlanActors` 5 (:190853), `TestPbRenderPlanExecutor` 8 (:191818), `TestPbRenderPlanLocationText` 7 (:191820), `TestPbRenderPlanSubject` 4 (:191822), `TestRenderPlanLive` 5 (:192239; last `.39` render measured 19:20:06, :192144). SwarmUI on `.39` stopped answering (`000`, host pinging) from 19:21:43, right after the run, and was reported to Stephen before anything else ran (".39 crashed ... it's back up now", 22:14) — P5-2 baseline 116 / 0 / 0 / 1 (`p52-regression2.log`); the skip is the no-Kontext case, not a pass.
- Live leg `TestP52SequentialInpaintLive`, `PB_SD_TESTS=1`, SwarmUI `.39` (`p53-t11.log`): **5 / 0 / 0 / 0** (:2875 / :2879), `BUILD SUCCESS` (:2882), `Total time: 16:14 min` (:2884), run 22:14–22:31 after the `.39` restart; the class uses no LLM (`[p52] LLM tier: none - this class is pure SD against http://192.168.1.39:7801 (Swarm 0.9.8.4.GIT-d74f8ce4)`, :60). t01 DIRECT_REFERENCE BASE returned in 242994 ms (:608), t02 SEQUENTIAL_INPAINT BASE + ACTOR_REGION in 277815 ms (:1344; box px [696, 154, 287, 819], :1362), t03 BASE kept with the ACTOR_REGION `RENDERER_REJECTED` on the deliberately corrupt Touvier portrait (:2062 / :2067), t04 BASE refused in 3921 ms on the corrupt Duña portrait (:2633), t05 cleanup (:2872); calibration `flux2*`/BASE sampleCount 10 (:624), ACTOR_REGION sampleCount 4 (:1365) — timings are observations, never thresholds — P5-2 baseline 5 / 0 / 0 / 0. PNGs opened: `target/p52/p52-t01-direct.png` (1445835 B, 1024×1024, 22:19:29) — two figures in a narrow, wet, walled alley under a blown-out sky: a woman (dark curls, pink glasses, pale patterned shirt, underwear, barefoot) and a bearded man (maroon leather jacket, pale-blue jeans, pink shoes); oversaturated, painterly, soft faces. `p52-t02-base.png` (1497083 B) — the same alley with a third figure on the right in a shiny lilac coat and dark trousers. `p52-t02-final.png` (1513041 B) — to the eye the same frame; a stdlib pixel diff (`C:/tmp/p53_pngdiff.py`, no PIL) shows 84.4 % of the pixels inside the actor box changed (max channel delta 82) against 3.2 % outside it (max 63), i.e. the inpaint re-denoised the right-hand figure at low creativity and left the rest alone. `p52-t03-base-kept.png` (1383012 B) — four figures (the woman, a second figure in a maroon jacket with a smeared face, the bearded man, the lilac-coat man); the BASE the executor kept after the ACTOR_REGION rejection. Same look as the P5-2 run; no quality claim
- Verifier (read-only — no `mvn` while the DB slot was held): **PASS-WITH-NOTES** — seven checks PASS from the diff + logs (prune / splice is test-only and fails loudly on a dangling link; the new test drives the real `renderPlanned` 8-arg path against `am7db` and re-reads persisted state; `swarmVersion` source unchanged; dead symbols grep-clean; `PICTUREBOOK_DEFAULT = V1`, no test weakened or deleted; production callers still use the 7-arg overload; no mixed line endings). Notes: `verify.sh` not run (no `VERIFY_OK` claimed); the `-q` install log has no `BUILD` line; calibration residue rows (211 m); out-of-slice working-tree files (`Console7/tmp.bat`, `vectorEnv.bat`, `media/*`, `Agent7/plan.json`, `tmp.mp4`, Ux752 `test-results/*.png`) are not this slice's and were not touched.

**Not claimed.** Nothing is DONE (159 b); no render-path default moved; no PB1 route refuses; no column dropped; no Ux752 surface changed; `TestKontext` and the pom excludes are as they were. §4.13, §13.4, §6.C 208–209 and the §3 P5-2 probes moved to the archive in this pass (211 j).
Commit state: the twelve P5-3 code files are in Stephen's commit `54a80b2c` "PB3 P5 Mark" (2026-10-28 18:37 — his; nothing was `git add`ed or committed by me); this doc pass is uncommitted.

### 13.6 P6 results (Phase 6 — Variants, numbered here per Stephen's "ledger §13.6"; started 2026-10-29 on his "implement this" with the plan + architect verdict: P6-1 → P6-3a → P6-2 → P6-3b → P6-4, each with its tests on `am7db` / H2 / Docker, verifier at the end, Scenario F owed to `.39`) — `attempt` / `variantIndex` / `archivedAt` on `olio.pb.artifact` (P6-1), `PbRetentionSelector` (P6-3a), the `renderPlanned` variant loop (P6-2), `PbRetentionUtil` + four retention routes (P6-3b), the Ux752 variant strip + retention dialog (P6-4) — 2026-10-29 11:30 → 15:00 local, Scenario F 16:58 → 17:01; rulings §6.C 212

**Built** (paths under `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/picturebook/` unless noted; line numbers as of this pass):
- P6-1 `models/olio/pb/artifactModel.json`: `attempt` string 64, `variantIndex` int, `archivedAt` zonetime (DDL default `now()`, so it is trusted only when `reviewStatus` is ARCHIVED — objects7-reference Path 1); `OlioFieldNames` constants; `PbArtifactUtil.persistArtifact` 13-arg overload :157 (the 9-arg :127 delegates with `null, 0, null, true`); rule-1 auto-select only when `variantIndex == 0`; the three fields in `artifactRequest()`.
- P6-3a `PbRetentionSelector` (pure): per (node, role) the K newest attempts KEEP; selected / APPROVED / IN_REVIEW always KEEP; ARCHIVED bytes DELETE_BYTES once `archivedAt + D` has passed; K = 3, D = 30 defaults with MAX bounds.
- P6-2 `PbNodeExecutor.renderPlanned` :1169-1229 — `variantCount = max(1, preset.variants)` :1115 (`plan.getPreset().getVariants()` :1047), the plan's whole stage sequence run N× sequentially, `variantSeed(seed0, v)` = seed0 + v :1068 with seed0 from variant 0's reported seed :1215; a v > 0 failure → `VARIANT_FAILED` degradation + `stageErrors[variant]`, the loop continues; seed0 unknown → `VARIANT_SEED_UNKNOWN` (accepted); `PbArtifactUtil.AUTO_APPROVE_VETO_CODES` gains `VARIANT_FAILED` (5 → 6); `KEY_VARIANTS_PLANNED` on the result :934.
- P6-3b `PbRetentionUtil.scanBook / applyBook / scanOrg / applyOrg` as the caller via `AccessPoint` (`Policy.of(k, d)` :95 → 400 out of range, null → defaults); `PbArtifactUtil.archive` refuses selected / APPROVED (409 → `refused`); bytes deleted = the `data.data` row removed + the FK nulled, artifact row kept, no un-archive; `PbServiceFacade.scanRetention / applyRetention` :4883-4919 (`requireBook` → [`requireBookUpdate`] → `requireWorkflow`; org variants 401 / 403 unless AccountAdministrators); `Service7 PictureBookService` :3427-3498 `GET /{book}/retention`, `POST /{book}/retention/apply` (`admin,user`), `GET /retention/org`, `POST /retention/org/apply` (`admin`); `olio.pictureBookRequest` gains `keepAttempts` / `deleteBytesAfterDays`, read through `hasField()` (KI-24 / KI-25, 212 f).
- P6-4 Ux752 `workflows/pictureBookWorkflow.js` (`normalizeArtifact` → `groupByAttempt`; strip grouped by attempt with v / seed badges, status tint, archived toggle, compare, "bytes deleted" placeholder; toolbar Retention button → `dialogCore` dialog with K / D inputs, dry-run table, Apply enabled only after a dry run that lists ≥ 1 ARCHIVE / DELETE_BYTES) + `features/pictureBookWorkflow.js` (`scanRetention` / `applyRetention` / `retentionActionable`, node re-read after apply); tests `src/test/pictureBookVariants.test.js` (24) and `e2e/pictureBookVariants.spec.js` (3).

**Runs** (Objects7 on `am7db`, one surefire JVM each, `-DskipTests=false`; Service7 on H2; `[LLM-GATE]` in words: LiteLLM `:4000` and the container `:11435` refused, so every LLM call went to the remote `.42` Ollama directly — not Azure, not the container — `p6-regress1.log:54-57`):
- P6-1: `TestPbV3Schema` `case01` + the new `case06_variantColumnsDefaultAndRoundTrip` 2 / 0 / 0 / 0 (`p61-schema.log:129`, `BUILD SUCCESS` :136); `TestPbReview` 8 / 0 / 0 / 0 (`p61-review.log:16444`, :16451).
- P6-3a: `TestPbRetentionSelector` 19 / 0 / 0 / 0 (`p63a-selector.log:53`, :60), pure JUnit, no DB.
- P6-2: `TestPbRenderStagesOffline` 4 / 0 / 0 / 0 before the loop (`p62-stages-regress.log:812`, :819); 9 / 0 / 0 / 0 with five new cases after it (`p62-stages.log:2081`, 671.1 s, :2088).
- **Red, mine** (`p62-regress1.log`, 13:40, 49 / 1 / 9 / 0, `BUILD FAILURE` :144087): while that JVM ran in the background I ran `mvn -o -q -pl AccountManagerObjects7 compile` twice for the P6-3b edits and rewrote `target/classes` under it (`PbWatchedFields.class` 13:37:50; the failures start 13:38:14) → `ResourceUtil - Failed to load policies/systemReadObjectPolicy.json` (:126787), `PolicyUtil - Invalid policy resource name: systemReadObject` (:126843), `NoClassDefFoundError … PbWatchedFields` (:126849; the class is in `src/` and `target/classes/`), seven `TestPbNodeRun` cases + `TestPbReview.case07` NPEs on a null `getResourcePolicy` (:127287-127507, :144019). The one real failure: `TestPbAutoApprove.case08` `expected:<5> but was:<6>` (:126899) — the designed veto-set change; the test's count and javadoc updated. Everything re-run clean in the 14:26 set below; no product change for the nine errors.
- P6-3b: `TestPbRetentionSweep` first run 4 / 0 / 1 / 0 (`p63b-sweep.log:492`, `BUILD FAILURE` :547) — `case04` `requireWorkflow` "no workflow yet" (:494-497): my read-only reader lacked READ on the container's `/Workflow` and `/Artifacts` groups (212 h); granted in the fixture → 4 / 0 / 0 / 0 (`p63b-sweep2.log:548`, :551). Service7 `TestPictureBookRestContract` 23 / 0 / 0 / 0 (`p63b-contract.log:3064`, :3067; 20 → 23 with the three retention cases; `-DskipTests=false` present this time, 211 l).
- Regression set, one JVM, 14:26 (`p6-regress1.log`): `TestPbV3Schema` 6 (:178), `TestPbAutoApprove` 9 (:134837, 427.6 s), `TestPbNodeRun` 7 / 0 / 0 / 1 (:135343; SdTestGate "Swarm http://192.168.1.39:7801 not reachable" :135345), `TestPbRenderStagesOffline` 9 (:137369, 632.4 s), `TestPbRetentionSelector` 19 (:137371), `TestPbRetentionSweep` 4 (:137932), `TestPbReview` 8 (:154673) → **62 / 0 / 0 / 1** (:154677), `BUILD SUCCESS` (:154680), `Total time: 23:06 min` (:154682). The skip is the `.39` outage, not a pass.
- Ux752: `npx vitest run` 99 files / 1469 passed (24 in the new file); `npx vite build` exit 0; `LOOP_MODULE=AccountManagerUx752 bash .claude/loop/verify.sh --quick` → `VERIFY_OK` (`p6-ux752-gate.log:7`).
- Docker (`am7test`, `:9443`): the installed Objects7 jar (`~/.m2`) and `PictureBookService*.class` `docker cp`'d into the deployed WAR, `docker restart`, `/rest/schema` 200 after ~25 s; the three P6-1 columns arrived by boot DDL (212 m); `vite build` + `docker cp dist/` twice. Playwright `e2e/pictureBookVariants.spec.js` (`ensureSharedTestUser()`, a fresh ChapBook per run, WS stub, `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443`, `--workers=1`): **first run 1 passed / 2 failed** (3.5 min, foreground, no log file) — test 2 showed an `s0` badge on a pre-P6 archived row (the `seed` column is a `long` with DDL default 0 and my `normalizeArtifact` treated 0 as known; 212 o), test 3's click on `pbwf-retention-open` intercepted by the node detail panel's `READY` badge at 1280 px (212 n); fixed (seed > 0 rule + a Vitest case; the spec sets 1600 × 900) → **3 passed (22.5 s)**: dry run decides the fixture + rejects K = 0 (311 ms), strip grouping / badges / archived toggle / compare (3.5 s), dialog dry run → Apply archives the older attempt and the strip reloads (2.9 s). The failure screenshot was opened: strip, v / seed badges and the dialog table as described; a `saveCanvas` 500 toast is in it (212 p, pre-existing).
- Verifier: PENDING at the time of this pass (its own Objects7 run against `am7db` is in `p6-obj7-a.log`); verdict added here when it lands.

**Scenario F — RUN 2026-10-29 16:58 → 17:01 on `.39` (back, with `.42`), 1 / 0 / 0 / 0.** New `TestPbVariantsLive` (one `@Test`, `SdTestGate.requireRenderConfig` gate — there is no `PB_SD_TESTS` env var; the earlier placeholder command here named one and two classes that never exercised variants, both mine); one JVM: `cd src && mvn -o -pl AccountManagerObjects7 -Dtest='TestPbVariantsLive' -DskipTests=false test > /c/tmp/p65-live.log 2>&1` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` (192.1 s, `p65-live.log:496`), `BUILD SUCCESS` (:503), 3:15 min (:505). `[LLM-GATE]` in words: LiteLLM `:4000` and the container `:11435` refused, so route=direct tier=remote — the remote `.42` Ollama, not Azure, not the container (:54-57); no LLM call was made (user-edited MIGRATED composition → TEXT_ONLY). SD: Swarm `http://192.168.1.39:7801`, checkpoint `OfficialStableDiffusion/sd_xl_base_1.0.safetensors` (:60). The test drives the production `PbNodeExecutor.executeNodeRun` (SWARM backend) on one RENDER_PLAN node of a fresh book (`p65ffe644a7a`, owner `pbPlanOwner`, autoApprove OFF, deleted in `finally`):
- STANDARD run: COMPLETED, planned = rendered = 2, no degradations, wall 50.3 s (:205); seeds 1494118935 / 1494118936 (v0 + 1), one `attempt` = the run id, only v0 selected, 1024 × 1024, 1.65 / 1.67 MB (:211, :213).
- FINAL run: a second attempt, planned = rendered = 4, wall 87.9 s (:330); seeds 1513459083 … 086, distinct bytes per variant, ~21.4–21.6 s each (:340-:346); the STANDARD pair deselected; the one degradation is `upscale: UPSCALE stage planned by the preset but not rendered (Phase 2 renders BASE only)` — pre-existing Phase 2 limit, asserted, not fixed.
- Approve one: `PbArtifactUtil.approve` on FINAL v2 (not the headline) → APPROVED + selected + `approvedBy` owner, siblings GENERATED / deselected, node `pinned`.
- Retention K = 1: `scanBook` `counts={keep=5, archive=3, deleteBytes=0, none=0} total=8` (:390) — ARCHIVE = the STANDARD attempt's `base r1`, `base r2` **and its `renderPlan r1`** (the JSON artifact is its own attempt under the role-scoped rule, hence 3 not 2; :395, :398, :400), KEEP = the four FINAL finals (approved reason "approved") + the FINAL renderPlan; `applyBook` `archived=3 bytesDeleted=0 refused=0 denied=0 failed=0` (:402-403), `archivedAt` set, bytes still present (D = 30); the approved artifact re-read with its bytes (:412); a second apply archives 0 (:417).
- PNGs opened (`target/p65-*-p65ffe644a7a.png`): standard-v0 two figures in dark coats on black basalt slabs, orange / purple dusk, sea behind; standard-v1 four figures walking across basalt rubble toward a grassy cliff with surf (four, not two — SDXL-base fidelity, not a pipeline defect); final-v0 two tiny figures atop a basalt stack, pink dusk; final-v2 (the approved one) overcast grey, basalt cliff, a few figures, breaking surf. final-v1 / v3 and `approved-kept` (= final-v2's bytes) exist, not opened. Real, distinct, on-prompt images.

**Not claimed.** Nothing is DONE (159 b); no scheduler, no un-archive, no per-request variant count; `saveCanvas` not fixed; the panel / toolbar overlap not fixed; `TestPbNodeRun` was not re-run after `.39` came back (its skip in `p6-regress1.log` stands as an outage; Scenario F is the live proof). The FINAL preset's UPSCALE stage is still not rendered (Phase 2 limit). Commit state: nothing `git add`ed or committed by me — the P6 code, its tests (incl. `TestPbVariantsLive`), the two Ux752 test files, `qualityPresets.json`, design §23 / §27 and this pass are all uncommitted in the working tree.

Next: **STOP for the P6 hand-back** (one "Yours" block; unanswered defaults stand). Then, in order and only on Stephen's word: fix any
in-scope verifier defects and re-test; Phase 7 (PictureBook goes V3, §1 row 7) or the Phase 10 items parked by 211 h. Nothing is DONE
by me (159 b).

Open: the P6 "Yours" — (1) Scenario F: taken at its default (run as the first thing when `.39` was back, reported above) — nothing to
answer unless the test design (212 a) is wrong;
(2) 212 p — the `saveCanvas` 500 (`x` / `y` / `w` / `h` undeclared on `olio.pictureBookRequest`): fix as its own small task now? default:
not until you say; (3) 212 n — the node detail panel hiding the toolbar's right end at 1280 px: move the panel or the button? default:
leave; (4) 212 c — a per-request `variants` override on `olio.pictureBookRequest`? default: none; (5) 212 b — keep sequential whole-sequence
variants, or batch N images at the renderer when `.39` measures it? default: sequential; (6) the P5-3 "Yours" (1)–(4) above stay at their
defaults (scratch kept; calibration residue left; no chat-path WARN; `TestKontext` stays).
next §6.C number 213.
