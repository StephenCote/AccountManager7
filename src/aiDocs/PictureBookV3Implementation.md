# PictureBook V3 — Implementation State

**As of:** 2026-10-29 ~20:10 local · **Phase 7 (PictureBook goes V3) IN PROGRESS** on Stephen's "keep going with implementation until you
are stopped or have a question" — plan §14, rulings §6.C 214+, nothing DONE (159 b); the P6 report below stands as handed back (its
"Yours" (1)–(8) at their defaults, 213 e included). **Phase 6 (Variants)** ran on Stephen's "implement this" (plan + architect verdict, defaults
standing): P6-1 schema, P6-3a selector, P6-2 variant loop, P6-3b retention util + routes and P6-4 Ux752 strip + dialog are built and
reported (§13.6, §6.C 212) — `am7db` 62 / 0 / 0 / 1, Service7 H2 23 / 0 / 0 / 0, Ux752 `VERIFY_OK`, Playwright on Docker 3 / 3;
Scenario F ran live on `.39` once it came back (`TestPbVariantsLive` 1 / 0 / 0 / 0, `p65-live.log`; §13.6 "Scenario F"); the
verifier returned CHANGES-NEEDED (1 HIGH, 1 MEDIUM, 4 LOW) and all six are folded in and re-run green (§6.C 212 q–w; §13.6
"Post-verifier": `am7db` 27 / 0 / 0 / 0 + the stages regression, Service7 H2 24 / 0 / 0 / 0, Vitest 25, `VERIFY_OK`); the new
retention outcome is `detached` (a shared `data.data` row: this artifact's reference cleared, bytes kept); Yours (7), the Docker
Playwright re-run, ran 2026-10-29 on the post-verifier WAR — 26 / 2 skipped / 1 failed, the red being inspector test 4's reader-image
step, a latent P2-5 / P2-9 gap first executed today, not a P6 regression (§6.C 213 e; §13.6 "Yours (7)"). Phase 5
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
| 6 — Variants | **IN PROGRESS** 2026-10-29 — P6-1 schema, P6-3a selector, P6-2 variant loop, P6-3b retention util + routes, P6-4 Ux752 strip + dialog built and reported (§13.6, §6.C 212); Scenario F run live on `.39` (§13.6); verifier CHANGES-NEEDED → six findings folded in (212 q–w; `detached` retention outcome); nothing DONE (159 b) | `am7db` one JVM **62 / 0 / 0 / 1** (`p6-regress1.log:154677`; the skip was the `.39` outage) after one red run of mine (`p62-regress1.log`, concurrent recompile) and one red sweep (`p63b-sweep.log`, fixture grants); **Scenario F live `TestPbVariantsLive` 1 / 0 / 0 / 0** (`p65-live.log:496`, STANDARD 2 + FINAL 4 variants on `.39`, approve one, retention K = 1 archives 3); post-verifier `am7db` **27 / 0 / 0 / 0** (`p6f-sweep.log:1230`) + `TestPbRenderStagesOffline` (§13.6 "Post-verifier"); Service7 H2 `TestPictureBookRestContract` **24 / 0 / 0 / 0** (`p6f-contract.log:3322`); Ux752 Vitest 25 in the new file, quick gate `VERIFY_OK` (`p6f-ux752-gate.log:7`); Playwright on Docker 3 / 3 after a 1 / 2 first run; a 14-test adjacent-spec run is red on a host suspend (`p6-pw-regress.log`, 212 s) and not re-run |
| 7 — PictureBook goes V3 | **IN PROGRESS** 2026-10-29 ~20:10 on Stephen's "keep going with implementation until you are stopped or have a question" — plan §14 (P7-1 switch → P7-2 Stage 2 refusals → P7-3 wizard hand-off / open-in-V3 → P7-4 pre-V3 import → P7-5 Scenarios A–G), rulings §6.C 214+; nothing DONE (159 b) | — |
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
     **Verifier fold-in (17:10 → 17:45; verdict and re-runs §13.6):**
     (q) ⚠ **HIGH, mine, fixed:** `PbRetentionUtil.deleteBytes` deleted the `data.data` row without asking who else referenced it — a
     seeded LOCATION render (`PbServiceFacade.seedLocationNode` :4070) and a migrated PB1 image (`PbMigrationUtil` :537) hand an existing
     row to `persistArtifact`, so two `olio.pb.artifact` rows share one `data.data`, and archiving the first referrer would have taken the
     second's bytes. Now `otherReferencer()` (raw `findRecords` on `artifact.data` = the row + `organizationId`) runs first; a shared row
     yields the new outcome **`detached`** — this artifact's FK cleared, row kept, 0 freed, the sharer named in `outcomeReason`; the row
     goes when its last referrer is swept (`TestPbRetentionSweep.case05`). Alternative: refuse and keep the FK — rejected, both sharers
     would then never free (deadlock). Default: detached. Known gap, open: a PB1 scene's landscape ref is a string inside a JSON blob
     (`V1_LANDSCAPE_KEY`, `PbMigrationUtil:453-455`), not a model FK, so the probe cannot see it; Phase 10 removes PB1.
     (r) ⚠ Ux752 carries `detached` (`normalizeRetention`, outcome colour, summary span `pbwf-retention-detached`, toast suffix); Vitest 25.
     (s) ⚠ **MEDIUM, mine, recorded as-is:** a 14-test Playwright regression run of adjacent specs (`p6-pw-regress.log`, `EXIT=1` :96,
     1.4 h) was left out of this ledger: `pictureBookV3PlanInspector.spec.js` test 2 failed on its 2400 s timeout (:28) with
     `net::ERR_NETWORK_IO_SUSPENDED` on `…/render-plan` (:52) — the host suspended under the run, `SD_ENABLED:false` (:4) — tests 3–4 did
     not run (:94), 11 passed (:95; `pictureBookWorldSwitch` 6, `renderPath` 4, inspector test 1). Environmental, not a P6 regression,
     but unproven until re-run → "Yours" (7).
     (t) ⚠ LOW, fixed: `POST …/retention/apply` and `/retention/org/apply` ran with the defaults on any body `parseParams` could not read
     (WARN only). `parseRetentionBody`: absent / blank / bare `{}` (Ux752's both-blank body) → defaults; anything else unreadable → 400
     (`TestRetentionApplyBodyUnreadableIs400`). (i) above still describes bare `parseParams`.
     (u) ⚠ LOW, fixed: `PlannedRender.describe()` reported `variantsPlanned` from the preset even on the single-variant paths (LOCATION, a
     scene rendered outside a run) — now the count the render was asked for (`PlannedRender.variantsPlanned`).
     (v) ⚠ LOW, fixed: `variantSeed` folded the **sum** into range, so a base of −1 (renderer reported none) mapped variants 1 and 2 both
     to seed 1; now the base is folded once and the sum wraps at the top; `seed0 + v` unchanged for in-range seeds (`TestPbVariantSeed`).
     (w) ⚠ LOW, documented only: single-variant `stage` passes supersede the selection at write time (pass-by-pass chain); multi-variant
     passes all supersede the one pre-batch artifact, order carried by references / `variantIndex` / `attempt` — nothing walks
     `supersedes` today (`PbNodeExecutor` :1241-1251 comment). Default: as built.
213. ⚠ **2026-10-29 (P6 Yours (7) — the Playwright re-run, on Stephen's pick; results §13.6 "Yours (7)").** Nothing DONE (159 b).
     (a) ✓ Redeployed the post-verifier build first (jar + `PictureBookService*.class` + dist; stubs 0 by grep + `javap`), since the WAR
     in the container predated 212 q–w. ⚠ Added `pictureBookVariants.spec.js` to the two named specs for the same reason. Default: stands.
     (b) ⚠ Ran with `PB_SD_TESTS=1` so the `[SD]` cases executed rather than skipped (`.39` → 302 from the container; inventory 13).
     (c) ⚠ Kept the host awake with a process-scoped `SetThreadExecutionState(ES_CONTINUOUS|ES_SYSTEM_REQUIRED)` helper (`C:\tmp\p6g-keepawake.ps1`,
     released after the run); `powercfg /requests` needs elevation, so the request is unverified — the run simply completed (5.6 m).
     (d) ⚠ First launch aborted by me (background-timeout doubt) and relaunched under `nohup`; its seed book `p29-plan-mvu95nrv` (scene 5582,
     node `render_plan@2b55b096`) stays in the Docker DB like every other spec fixture. Trap recorded: `:9443/rest/schema` is the SPA
     fallback (`text/html` 200); the Tomcat check is `/AccountManagerService7/rest/schema`.
     (e) **Inspector test 4 `[SD]` red — not a P6 regression; a latent P2-5 / P2-9 contract gap, first ever seen.** The render succeeded
     (`p6g-pw-rerun.log:12`, 95 s, 2 `base` variants v0 selected / v1 not + `renderPlan` — exactly the STANDARD preset), but the reader's
     page view resolves an image only through `scene.sceneNode` + role `composite` / `landscape` or the ChapBook `imageObjectId` fallback
     (`PbServiceFacade.java:2294-2316`, `pageArtifactRole` :2357), while `ensureRenderPlanNode` leaves `scene.sceneNode` unlinked by
     design (:1031-1032) and records the image as `base`. So `GET …/pages` carries no `dataObjectId` / `imageGroupPath` / `imageName`
     (live as `e2etest_shared`: page keys `objectId,sceneIndex,title,pageBgOpacity,imageStale,pinned`), `pb2ImageUrl` is null and
     `pb2-scene-image` never renders (spec :359). Every earlier run skipped this case (`.39` DOWN, archive). Yours: (1) product — show the
     RENDER_PLAN node's selected `base` on the reader page (pages-view fallback, or link `sceneNode`), or (2) re-scope :359 to the review
     page / node detail. Default: neither until you say; test stays red and recorded.
     (f) The old red `p6-pw-regress.log` set was inspector + `pictureBookWorldSwitch` + `renderPath`, not the workflow spec; 212 s named
     inspector + workflow. This run is the first that executes the workflow `[SD]` cases (28, 29) — both green.
214. ⚠ **2026-10-29 ~20:10 (Phase 7 start — on Stephen's "keep going with implementation until you are stopped or have a question";
     plan §14).** Nothing DONE (159 b).
     (a) ✓ Phase 7 is the ledger's and design §30's next row; 213 e was asked and not answered → its default (neither) stands and
     P7-5's pass criteria avoid the reader-page image. The P6 "Yours" (1)–(8) stay at their defaults.
     (b) ⚠ Task order P7-1 → P7-5 as §14; the plan is mine (inventory read-only this evening) with the architect asked to review it in
     parallel with P7-1, which is mechanical under the P2-6 precedent (165 g / h: per-product defaults, every surface together, no flag).
     (c) ⚠ `PUT /{book}/settings` is refused under v3 (it is the "PB1 settings write" of design :1842: it rewrites `.pictureBookMeta`;
     its book-tier side effect has a V3 setter, `PUT /{book}/style` `sdConfig`), `GET` stays readable. Default; say if the dual write
     should stay for V3 books instead.
     (d) ⚠ Refusals are product-level (`PbRenderPath.pictureBook()`), not `isV3(book)`: `/scene/{id}/generate` and `/{book}/prepare-images`
     carry no `olio.pb.book`; `keyFor(book)` would fall to the picturebook key anyway. Default.
     (e) ⚠ Only the three named entry points refuse. Scene text / status / blurb / character writes stay — they are V3 data too, and the
     design's "…" is not read as a licence to retire more. Default; the list is Yours to extend.
     (f) ⚠ The six PB1 e2e specs that drive the retired routes directly are gated on the live render path, not deleted or rewritten: they
     remain the rollback (v1) proof and skip with the Stage 2 reason under v3. Their skips are reported as skips, never as passes.
215. ⚠ **2026-10-29 ~21:05 (architect review of §14: CHANGES NEEDED to the plan text, design and P7-1 diff sound; folded in before P7-2).**
     (a) ✓ H1 accepted — a product gate sits AFTER authorization and existence checks, never before (the `:9711` invariant, `requireV3`
     after `requireBook`, `requireEnabledCompositeMode` at `:9876`). So: `generateSceneImage` gates in the 4-arg body at the P5-3 WARN site
     (`:9751-9758`, after `authorizeSceneAccess`), not the 5-arg wrapper; `prepareSceneImagePrompts` gates per scene right after
     `authorizeSceneRecord` (`:10936`) and outside the swallowing try, so a denied caller still gets 403 and an authorized one 409 on the
     first book scene; `setBookSdConfig` gates after `resolveBookGroupEither`'s 404 (`:2104`). `TestPictureBookSceneAuthz:272-282` (403 for
     userB) stays green by construction. Recorded as a rule in `.claude/rules/architecture.md` ("gates after authorization").
     (b) ✓ H2 accepted — `BaseTest` re-pins v3 on every `@Before`, so every in-suite JUnit that drives the three entries meets the refusal:
     `TestPictureBookWorkflow` (:217, :425, :441, :677, :788), `TestPbBookSettingsResolveEither` (:108-276), `TestPictureBookSceneAuthz`
     (:272-282), `TestPbSceneGenerationGuards` (:114-538, 4-arg seam — gated by (a)), `TestPbSeriesChapterScenesResolve:184`; pom-excluded
     but runnable: `TestPictureBookFull` (15), `TestPictureBookCustom` (5), `TestPictureBookUtilE2E` (:627, :706). Each pins v1 around the
     PB1 call and restores (P2-6 precedent); no skip, no delete. `TestChapBook*`'s hits are `ChapBookUtil.setBookSdConfig` (not gated, (d)).
     Ux Vitest that asserts PB1 wizard wiring (`pictureBookWiring.test.js:73-158`, `pictureBook.test.js:13`, `pictureBookExtractState.test.js:1228`)
     is P7-3's, where the wiring changes.
     (c) ⚠ M1 — the `~/Chat` single-image fallback (`bookGroupPath == null`) stays exempt from the PB1 gate: it answers to `chat.renderPath`
     (default v1), exactly as the P5-3 WARN already treats it. Default.
     (d) ⚠ M2 / L3 — `PbBookUtil.setBookSdConfig` (ChapBook's `chap-book/{book}/settings` setter, which also patches `olio.pb.book.sdConfig`)
     is out of scope and not gated; `PUT picture-book/{chapbookOid}/settings` (reachable via `resolveBookGroupEither`) IS refused
     product-level — no Ux caller. `compositeSdConfig` has no style-tier equivalent (only the deprecated COMPOSITE node). Defaults.
     (e) ✓ L1 / L2 — inventory adds `docker/web.xml.template:81-82` (passthrough) and `docker-compose.test.yml:288`; `PbRenderPath.java:28`
     "checked only at facade entry" corrected now; `PictureBookService` :718-721 (and the two sibling javadocs) corrected in P7-2.
     (f) ✓ P7-1 Vitest caught a miss in my diff: `featureConfig.js:41` normalised an unrecognised picturebook value to `v1`; now `v3`
     (mirrors the chapbook line and the server's "unrecognised = product default"). 1 red → fixed → 1470 / 1470 (§14.1).
216. ⚠ **2026-10-29 ~22:20 (P7-2 close-out — the six-spec gate, the security review's hardening notes; results §14.2).** Nothing DONE (159 b).
     (a) ✓ 214 f realised: the PB1 specs are gated by `pb1Retired(request, opts)` / `PB1_RETIRED_SKIP` (`e2e/helpers/api.js`, beside
     `getOrgFeatures`; reads `GET /rest/config/render-path` as the test user and prints `[e2e-render-path] {…} -> PB1 entry points retired;
     skipping`) placed before the first PB1 call: `pictureBook.spec.js:246` (Steps 2-5), `pictureBookPagesAfterRender.spec.js:197 / :215 / :254`,
     `pictureBookSceneText.spec.js:169`, `pictureBookScenePromptUx.spec.js:324` (its `provisionBook` drives settings + prepare-images),
     `pictureBookWorkflowOverhaul.spec.js:186` (CORE + W2; W5 not gated), `pictureBookLive.spec.js:406` (B.4, after its three env skips; CRLF
     kept, `tr -cd '\r' | wc -c` = 1892 = line count). Load-safe cases in the same files keep running. `pictureBookLive` lists only under
     `--project=chromium-vite` (`playwright.config.js:36-40` `VITE_SOURCE_SPECS`) — run it with `PLAYWRIGHT_VITE_URL` at a Vite that proxies the stack.
     (b) ⚠ `TestPictureBookSceneAuthz` is deliberately NOT pinned to v1 (javadoc :257-261): its stranger 403 at :272-282 is the live proof of
     215 a's ordering — a 409 there would mean the gate had moved ahead of authorization. Default.
     (c) ⚠ `prepareSceneImagePrompts` keeps its pre-existing warn-and-skip for an id the caller cannot read; the gate fires on the first readable
     + updatable book scene, so a mixed list answers 403 / 409 by list order — disclosing only what the caller can already read. Default.
     (d) ⚠ Security-review hardening 1 (Yours): `setBookSdConfig` never checked `canUpdate` on the book group — under v1 a Read-only grantee gets
     200 with every write swallowed (`persistBookSdConfig` :2043, `saveMeta` :1984, `persistBookSdConfigFk` :2142 all catch); under v3 that
     reader now gets the 409, i.e. this gate sits after read-level, not write-level, authorization (no new disclosure — `GET /{book}/settings`
     already answers them 200). Not changed in P7-2: pre-existing, the route is retired, and a `canUpdate` → 403 before :2120 changes the
     rollback path too. Default: leave; say if you want the 403 added (one line + a case13 assertion).
     (e) ⚠ Hardening 2 (Yours): `POST /{book}/prepare-images` authorizes per scene and ignores `bookObjectId` (documented,
     `PictureBookService.java:785-787`), so a scene list spanning books is accepted; and the `~/Chat` exemption is a `groupPath` suffix test
     (`endsWith("/Scenes")`, `PictureBookUtil:9761-9765`) rather than the resolved group name `resolveSceneBookGroup` uses — both derive from
     `groupId`, so they cannot disagree today; a null `groupPath` would exempt via the `"~/Chat"` default. Pre-existing; not changed. Defaults.
     (f) ✓ Working-tree edits that are not mine and untouched: `Console7/tmp.bat`, `vectorEnv.bat`, `Objects7/media/speaker_0.mp3` (deleted),
     `Ux752/test-results/p44-*.png` — modified before this session; the reviewer flags `tmp.bat`'s dev credentials. Check before any commit
     (I commit nothing).
217. ⚠ **2026-10-29 ~23:30 (P7-3 close-out — the wizard hands off to the workspace; results §14.3).** Nothing DONE (159 b).
     (a) ⚠ The wizard and the PB1 viewer read `GET /rest/config/render-path` once per open (`pictureBookRenderPath()`); a failed / missing read
     counts as v3 (mirrors `featureConfig.js` after 215 f, "unrecognised = product default"), so a transport blip never resurrects steps 4–5. Default.
     (b) ⚠ Under v3 the progress bar shows three steps (`Source / Scenes / Characters`, `workflows/pictureBook.js:2435`); steps 4–5 and their
     code stay for v1 (removing PB1 code is out of scope). Default.
     (c) ⚠ Under v3 `pictureBook()` resolves resume BEFORE `loadDefaults()` / `ensureSdConfig()` (:2902-2924): an existing book never builds
     the step-4 SD config it cannot reach; a fresh source still loads defaults. Default.
     (d) ⚠ The PB2 reader header's Edit pencil is hidden under v3 (`features/pictureBook.js:2194`) — the wizard would only hand straight back to
     the reader; Characters stays. The list-row pencil and the canvas "Open in wizard" resolve to the reader (`resolvePb2BookObjectId`,
     `pictureBookWorkflow.js:22-54`). Yours: the canvas label "Open in wizard to render" (`pictureBookWorkflow.js:405-433`) is stale wording
     under v3 — not changed. Default: leave.
     (e) ⚠ `ReaderShell` gained an optional `renderBanner(nav)` slot (`readerShell.js:33, :339`), hidden in fullscreen; the PB1 viewer's notice
     uses it. Default.
     (f) ✓ Specs: `pictureBookNavigation.spec.js` contract updated (row Edit → reader under v3; 8b "wizard-launched manager" skipped under v3 —
     the wizard is not reachable from a book); `pictureBookWizardUx.spec.js` final step render-path aware (:259-269). New
     `pictureBookV3Handoff.spec.js` builds its fixtures over REST (a bridged book via `/chapter` + `/create-from-scenes`; a pure PB1 book via a
     directly POSTed `.pictureBookMeta` + `Scene 1` note) and skips as a whole on v1.
     (g) ⚠ Observation (Yours): the V3 reader header shows the record `name` = `"Book <slug>"` (`PbBookUtil.bookName` :1478, by design — the
     (name, groupId, org) unique index), not the typed "Picture Book Name", which becomes the slug + `title`. The v1 step-5 viewer showed the
     typed name. Pre-existing V3 contract (the nav spec asserts it the same way); the hand-off spec asserts via the `/books` DTO. Default: leave.
     (h) ⚠ Observation (Yours): the viewer's `{replace:true}` redirect is asserted in Vitest (`pictureBookV3Handoff.test.js:253, :261`) but is
     not Playwright-provable through hash navigation — `router.js:215-232`'s `hashchange` bridge re-pushes an externally set hash (address bar,
     `location.hash=`), so Back lands on the duplicate entry and redirects again. Pre-existing, every external hash set; not changed.
     (i) ✓ Observations: under v3 `create-from-scenes` queues a background `pb.compose` job (`p73-run3.log:16` `"jobStatus":"queued"`), so the
     hand-off lands on a reader whose scenes are composing; the live wizard run logs `detectBoundaries failed: 404` and extracts as a single
     document (`p73-wizardux-live.log:223`) — pre-existing, not P7-3's.
218. ⚠ **2026-10-30 ~00:45 (P7-4 close-out — Import on the legacy notice + verify check 2; results §14.4).** Nothing DONE (159 b).
     (a) ⚠ Import is a direct button on the notice (no confirm; the server refuses a second import with 409), routes to the V3 reader first and
     then opens the result dialog so a dialog dismissal never strands the user on the retired viewer; a non-2xx is a toast and the viewer stays;
     `locationPass` is not shown (its warnings are already in `warnings`). Default.
     (b) ⚠ Found inside P7-4 and fixed as part of it: `POST /migrate-v1` (`PictureBookService.java:2333`) read the body through `parseParams`
     → `olio.pictureBookRequest`, which has no `groupObjectId`, so every request was 400 — the route was never callable. Now a plain JSON map
     (`bodyMap` :2371; 400 on missing / malformed / absent key); `PictureBookException` → its status. Covered by Service7 H2
     `TestPictureBookMigrateRoute` (6). Default (not an Objects7 change).
     (c) ⚠ **Finding (Yours): cross-owner slug collision.** `importV1Book`'s pre-check (`PbBookUtil.findBookBySlug` :601) is PBAC-scoped, so
     another owner's same-slug book reads as "no conflict", and `writeBookRow` (:582-587) then 409s with the "concurrent creation" message —
     34 of 51 un-bridged Docker PB1 books (every second "AIME Test Book" / "AIME Chars Book" owner) cannot import at all (§14.4). Options:
     per-owner slug (e.g. suffix the owner), an admin-side existence check with an honest message, or leave (slug is org-unique by design).
     Default: leave, message unchanged.
     (d) ⚠ Observation (Yours): an imported book's `/health` immediately reports "Meta note pb2BookObjectId is missing" (WARN, repairable) because
     import never writes PB1 (§28 invariant); the PB1 group does not bridge via `/pb2` on the meta note but on the slug. Set it on import, or
     leave to Repair? Default: leave.
     (e) ⚠ Observation (Yours): the import-time location pass (step 12) hard-failed in all 27 sweep imports — Docker: the owner has no
     `generalChat`, the library fallback resolves the admin's config on LiteLLM `:4000` (down); `am7db`: `No chat config available` after a
     DENY-as-miss on `olio.llm.chatConfig` (`p74-am7db-sweep.log:329`). It never reached `.42`. Three warnings per import; not fixtured around.
     Default: leave; the reader's Locations tab can re-run it.
     (f) ⚠ Observation (Yours): `/reset` of an imported book deletes the owner's `~/Data/PictureBooks/<slug>` tree only when the PB1 group name
     equals the slug (`PbDeleteUtil.deleteBookComplete` step 4) — never for a spaced name, which is why the sweeps left PB1 intact; a PB1 book
     literally named after its slug would lose its PB1 data on reset. Default: leave.
     (g) ⚠ Sweep design: Docker enumerates owners from `~/Data/PictureBooks` children carrying a meta note (`steve` / `admin` excluded), imports
     back-to-back as each owner, resets every import at the end as its owner, fails only on import / media / reader / reset failures;
     `am7db` caps at 2 newest PB1-only books per owner (5 owners, one per PB1 owner class). Both are opt-in gated measurement tests
     (`PB_MIGRATE_SWEEP=1` / `-Dpb.sweep=true`), never part of the default suite; the Docker sweep's `EXIT=1` is (c), not a test defect. Default.
     (h) ✓ Spec fixtures: a `.pictureBookMeta` note must carry `"schema":"olio.pictureBookMeta"` in its text or `importV1Book` 400s "parsed as
     null"; the reader's cover backdrop is cached, so the image assertion is on the page `<img>` `src` + `naturalWidth` + a direct media GET.
     (i) ✓ Observations: `ki42b…`'s import took 123 s vs 20–33 s for the others (`p74-am7db-sweep.log:21012`); the Docker spec's reader column
     sometimes records the placeholder header "Picture Book" (read at first visibility) — screenshots show the real name; neither is a product
     finding.
219. ⚠ **2026-10-30 ~05:00 (P7-5 close-out — Scenarios A–G on Docker + verify checks 1 and 3; results §14.5).** Nothing DONE (159 b).
     (a) ⚠ Fixture shape, not product: RENDER_PLAN nodes bind only their location, so the portrait→scene bindings D needs are hand-seeded
     (`POST /node/{id}/bind`); `scene.sceneNode` is hand-linked and the RENDER_PLAN nodes pre-created by the handle `ensureRenderPlanNode`
     finds (213 e stands); there is no charPerson version field, so the watched `hairStyle` stands in for "character changed"; portrait nodes
     are unpinned after approval so D's propagation is not pre-blocked; `flux2Klein_9b` pinned per book via `PUT /style`. Default.
     (b) ⚠ Found by D and fixed inside P7-5: `requestSceneRender(force=true)` returned 202 for a pinned scene node whose upstream was STALE —
     the planner skips pinned candidates, so the run re-rendered the portrait, left the scene alone and reported success. The facade now
     refuses a pinned scene node 409 before planning (`PbServiceFacade.java:741-747`), force or not; `isPinnedRefusal` widened to the facade
     text; one ChapBook JUnit assertion and two exact-text e2e assertions updated. Default (a structured 409 body stays archive :7868 c, Yours).
     (c) ✓ Spec correction, not product: a RENDER_PLAN node in a run renders the preset's variants per attempt (STANDARD = 2, P6-2), so run 4
     failed on my "one artifact per node" assertion (`p75-scenarios-run4.log`); chains are now compared by attempt / variant 0 / selected.
     (d) ⚠ Observation (Yours): E has no background-only regenerate — it is unpin + forced location render + approve, after which every bound
     scene is STALE and G re-renders whole scenes; "character regions remain unchanged" is recorded as NOT met (§14.5 E). Default: leave.
     (e) ⚠ Observation (Yours): after the `hairStyle` change the scene text follows the charPerson (`d-scene4-v2/v3.png`, `g-scene2-v2.png`
     show short silver-white hair) while the portrait node's `promptText` is `narrative.sdPrompt` verbatim (+ "(professional photograph)."),
     so portrait v2 still has the copper braid and a scene's reference image and text disagree (G scene 2 drew a white-haired and a red-haired
     figure). Recompile the portrait prompt from the charPerson on drift, or keep `narrative.sdPrompt` as the source? Default: leave.
     (f) ⚠ Observation (Yours) = 213 e, now measured on the reader: the cover reads "0 rendered of 6 scenes" with scenes 1 / 2 / 4 rendered —
     `bookPageView` resolves a page image via `pageArtifactRole` (`composite` / `landscape`) or `scene.imageObjectId`, and a RENDER_PLAN scene
     render writes `base` and never `scene.imageObjectId` (all six scene rows NULL); check 3's ChapBook path does set it. Default: neither.
     (g) ⚠ Observations (Yours): render-all on an all-current book is 409 (G relies on E leaving scenes STALE); a regenerated scene references
     the still-APPROVED older portrait until the new one is approved (D v2 vs v3); the LOCATION node self-drifts on approve (E); after
     Regenerate the character manager's "N stale" badge clears although pinned scene 1 stays STALE (`d-after-regenerate.png`). Default: leave.
     (h) ⚠ Image observations (Yours): every `flux2Klein_9b` location / scene render on `.39` is smeared, over-saturated, with a dark circular
     vignette (`a-scene1`, `b-scene2`, `e-tavern-v2`, `g-scene2-v2`); portraits (same model) and check 3's juggernautXL landscape are crisp;
     referenced actors' faces in DIRECT_REFERENCE scenes are low-fidelity. 4-actor SEQUENTIAL_INPAINT first exercised here (B 8.5 min, three
     passes). Default: leave (flux2 sampler / step settings on `.39` are yours).
     (i) ✓ Check 3 is proven on a freshly built ChapBook shaped like a pre-V3 one (scene-row lock, no composition), not on a row persisted
     before V3 — the Docker DB has none that renders; `stagesRendered` is per-actor keyed (`ACTOR_REGION#<actor>`), spec corrected. Fixture
     books 1667–1671 `p75-scen-*` and 1672 `e2e-cb-prev3-lock-…` left on Docker `am72db` by design (nodes / artifacts reference them).
     (j) ✓ Independent verifier PASS (gate order / authz, real JUnit, matcher + Vitest, specs gated and non-admin, re-runs, ~40 ledger cites
     spot-checked, diff confined to P7-5 + ledger). LOW only: stale comment / title in the two specs I edited (fixed, LF); old planner text
     `(0 reused, 1 pinned)` still mocked in `src/test/pictureBookCharactersAffected.test.js:321-337` and `pictureBookV3Reader.test.js:345-386`
     (green, out of scope, left); `PbServiceFacade.java:742-743` a null (deleted / unreadable) node falls through to the planner — pre-existing,
     not covered by case12 (Yours, default leave).

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
- Verifier (independent; its own re-runs): **CHANGES-NEEDED → folded in 17:10 → 17:45** (rulings 212 q–w). Its re-runs were all green — `TestPbRetentionSelector` 19 (`p6v-selector.log:47`, :50), `TestPbV3Schema` 6 + `TestPbRetentionSweep` 4 → 10 (`p6v-sweep.log:878`, :881), Service7 `TestPictureBookRestContract` 23 (`p6v-contract.log:3064`, :3067), Vitest 24 (`p6v-vitest.log:6`) — and it found one HIGH (a `data.data` row shared by two artifacts was deleted under the first referrer, 212 q), one MEDIUM (a red Playwright run of mine left out of this ledger, 212 s) and four LOW (apply body silently defaulting, `variantsPlanned` from the preset on single-variant paths, `variantSeed` collisions for a non-positive base, the multi-variant `supersedes` shape undocumented; 212 t–w). All six addressed; nothing out of scope touched.
- **Post-verifier re-runs** (one JVM each): Objects7 `TestPbVariantSeed` 3 (new, pure) + `TestPbRetentionSweep` **5** (case05 = the shared-row detach: `detached=1 bytesFreed=0` `p6f-sweep.log:347`, then `bytesDeleted=1 bytesFreed=5000` once the sharer is swept :477) + `TestPbRetentionSelector` 19 → **27 / 0 / 0 / 0** (:1230), `BUILD SUCCESS` (:1233); Objects7 jar re-installed (`p6f-obj7-install.log`, `-q`, exit 0); Service7 `TestPictureBookRestContract` **24 / 0 / 0 / 0** (`p6f-contract.log:3322`, :3325; the new `TestRetentionApplyBodyUnreadableIs400`); Ux752 `pictureBookVariants.test.js` 25 (`p6f-vitest.log:6`), `verify.sh --quick` → `VERIFY_OK` (`p6f-ux752-gate.log:7`); `TestPbRenderStagesOffline` after the `PbNodeExecutor` edits: see the line below.
- `TestPbRenderStagesOffline` after the `PbNodeExecutor` edits (`variantsPlanned`, `variantSeed`), one JVM, 17:36 → 17:43: **9 / 0 / 0 / 0** (375.7 s, `p6f-stages.log:2073`, :2077), `BUILD SUCCESS` (:2080), `Total time: 06:19 min` (:2082), `EXIT=0` (:2085); `[LLM-GATE]` route=direct tier=remote — the remote `.42` Ollama, not Azure, not the container (:55-57). So the post-verifier Objects7 total is 27 + 9 = **36 / 0 / 0 / 0** across two JVMs.

**Scenario F — RUN 2026-10-29 16:58 → 17:01 on `.39` (back, with `.42`), 1 / 0 / 0 / 0.** New `TestPbVariantsLive` (one `@Test`, `SdTestGate.requireRenderConfig` gate — there is no `PB_SD_TESTS` env var; the earlier placeholder command here named one and two classes that never exercised variants, both mine); one JVM: `cd src && mvn -o -pl AccountManagerObjects7 -Dtest='TestPbVariantsLive' -DskipTests=false test > /c/tmp/p65-live.log 2>&1` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` (192.1 s, `p65-live.log:496`), `BUILD SUCCESS` (:503), 3:15 min (:505). `[LLM-GATE]` in words: LiteLLM `:4000` and the container `:11435` refused, so route=direct tier=remote — the remote `.42` Ollama, not Azure, not the container (:54-57); no LLM call was made (user-edited MIGRATED composition → TEXT_ONLY). SD: Swarm `http://192.168.1.39:7801`, checkpoint `OfficialStableDiffusion/sd_xl_base_1.0.safetensors` (:60). The test drives the production `PbNodeExecutor.executeNodeRun` (SWARM backend) on one RENDER_PLAN node of a fresh book (`p65ffe644a7a`, owner `pbPlanOwner`, autoApprove OFF, deleted in `finally`):
- STANDARD run: COMPLETED, planned = rendered = 2, no degradations, wall 50.3 s (:205); seeds 1494118935 / 1494118936 (v0 + 1), one `attempt` = the run id, only v0 selected, 1024 × 1024, 1.65 / 1.67 MB (:211, :213).
- FINAL run: a second attempt, planned = rendered = 4, wall 87.9 s (:330); seeds 1513459083 … 086, distinct bytes per variant, ~21.4–21.6 s each (:340-:346); the STANDARD pair deselected; the one degradation is `upscale: UPSCALE stage planned by the preset but not rendered (Phase 2 renders BASE only)` — pre-existing Phase 2 limit, asserted, not fixed.
- Approve one: `PbArtifactUtil.approve` on FINAL v2 (not the headline) → APPROVED + selected + `approvedBy` owner, siblings GENERATED / deselected, node `pinned`.
- Retention K = 1: `scanBook` `counts={keep=5, archive=3, deleteBytes=0, none=0} total=8` (:390) — ARCHIVE = the STANDARD attempt's `base r1`, `base r2` **and its `renderPlan r1`** (the JSON artifact is its own attempt under the role-scoped rule, hence 3 not 2; :395, :398, :400), KEEP = the four FINAL finals (approved reason "approved") + the FINAL renderPlan; `applyBook` `archived=3 bytesDeleted=0 refused=0 denied=0 failed=0` (:402-403), `archivedAt` set, bytes still present (D = 30); the approved artifact re-read with its bytes (:412); a second apply archives 0 (:417).
- PNGs opened (`target/p65-*-p65ffe644a7a.png`): standard-v0 two figures in dark coats on black basalt slabs, orange / purple dusk, sea behind; standard-v1 four figures walking across basalt rubble toward a grassy cliff with surf (four, not two — SDXL-base fidelity, not a pipeline defect); final-v0 two tiny figures atop a basalt stack, pink dusk; final-v2 (the approved one) overcast grey, basalt cliff, a few figures, breaking surf. final-v1 / v3 and `approved-kept` (= final-v2's bytes) exist, not opened. Real, distinct, on-prompt images.

**Not claimed.** Nothing is DONE (159 b); no scheduler, no un-archive, no per-request variant count; `saveCanvas` not fixed; the panel / toolbar overlap not fixed; `TestPbNodeRun` was not re-run after `.39` came back (its skip in `p6-regress1.log` stands as an outage; Scenario F is the live proof). The FINAL preset's UPSCALE stage is still not rendered (Phase 2 limit). The `detached` probe cannot see a PB1 scene's JSON-string landscape reference (212 q; Phase 10). The red 14-test Playwright run (`p6-pw-regress.log`, 212 s) is recorded, not re-run, and the `detached` outcome is Vitest- and JUnit-proven but has no Playwright case (a shared-row fixture needs a seeded LOCATION node through the UI). Scenario F was not re-run after the verifier fold-in (`renderPlanned` was touched only in `PlannedRender` plumbing + `variantSeed`; the stages regression covers it). Commit state: the P6 code and tests through Scenario F are in Stephen's commit `54c8e690` "Mark" (2026-10-29 17:20 — his; nothing was `git add`ed or committed by me). Uncommitted, from the verifier fold-in: `PbNodeExecutor`, `PbRetentionUtil`, `TestPbRetentionSweep`, new `TestPbVariantSeed`, `PictureBookService`, `TestPictureBookRestContract`, the two Ux752 `pictureBookWorkflow.js`, `pictureBookVariants.test.js`, and this ledger pass.

**Yours (7) — Playwright re-run, 2026-10-29 19:44 → 19:53:41 local (§6.C 213).** Stephen picked it over Phase 7. Post-verifier build
redeployed first: `~/.m2` Objects7 jar (17:35, `PbNodeExecutor.class` 17:33 / `PbRetentionUtil.class` 17:24) + `PictureBookService*.class`
+ `dist` (vite 6.22 s, `p6g-vite-build.log` EXIT=0) → `docker restart` 19:44:36 → `/AccountManagerService7/rest/schema` 200 JSON 19:45:58,
boot log 0 stub / NoSuchMethod lines; `.39` → 302, `.42` → 200 from the container. Run (`C:/tmp/p6g-pw-rerun.log`): `PB_SD_TESTS=1`,
`--workers=1`, `https://127.0.0.1:9443`, `e2etest_shared`, specs inspector + variants + workflow, 29 tests — **26 passed / 2 skipped /
1 failed (5.6 m), `EXIT=1`** (:113-117). Skips are the spec's own gates (:9 inventory available → test 2; :10 no node → test 3), not passes.
- Variants 3 / 3 on the post-verifier WAR (:15-17): retention dry run + invalid K, strip badges / archived toggle, dialog K=1 → Apply.
- Workflow 20 / 20 incl. both `[SD]` cases, first ever executed: 28 regenerate portrait 1024×1024 1 384 391 B on SWARM → STALE `refDrift`
  (:39-42); 29 P1-6 async render terminal 42 422 ms, `COMPLETED` 1 / 0 (:63, :67).
- Inspector 1 ok (:8); **4 `[SD]` red** (:14, :72-86): `[p29-plan-4] rendered in 95033 ms … artifact b4978987…` (:12) then `pb2-scene-image`
  not found (:359). Diagnosed live as `e2etest_shared` (`C:\tmp\p6g-diag4.mjs`): node `render_plan@d3978877` holds `base` v0 (selected,
  `data` 2bd7f013…, 1024×1024, 1 739 043 B) + v1 (not selected) + `renderPlan`; the pages view carries no image fields for the scene.
  Cause = the reader page never reads a RENDER_PLAN node (§6.C 213 e). Not a P6 regression; this assertion had never run. Yours.
- Artifacts: `e2e/results/pictureBookV3PlanInspector-c3f1e-nd-Plan-shows-its-inspector-chromium/` (png, trace, error-context).

Next: **STOP for the P6 hand-back** (one "Yours" block; unanswered defaults stand; Yours (7) is now answered by its run and adds 213 e).
Then, only on Stephen's word: Phase 7 (PictureBook goes V3, §1 row 7) or the Phase 10 items parked by 211 h. Nothing is DONE by me (159 b).

Open: the P6 "Yours" — (1) Scenario F: taken at its default (run as the first thing when `.39` was back, reported above) — nothing to
answer unless the test design (212 a) is wrong;
(2) 212 p — the `saveCanvas` 500 (`x` / `y` / `w` / `h` undeclared on `olio.pictureBookRequest`): fix as its own small task now? default:
not until you say; (3) 212 n — the node detail panel hiding the toolbar's right end at 1280 px: move the panel or the button? default:
leave; (4) 212 c — a per-request `variants` override on `olio.pictureBookRequest`? default: none; (5) 212 b — keep sequential whole-sequence
variants, or batch N images at the renderer when `.39` measures it? default: sequential; (6) the P5-3 "Yours" (1)–(4) above stay at their
defaults (scratch kept; calibration residue left; no chat-path WARN; `TestKontext` stays); (7) 212 s — **run 2026-10-29 (213): 26 / 2 / 1**;
the one red is inspector test 4's reader-image step — 213 e: show the RENDER_PLAN `base` on the reader page (product), or re-scope the
spec? default: neither, stays red and recorded; (8) 212 q — `detached` for a shared `data.data` row (vs refuse-and-keep, which would never
free either sharer)? default: detached as built.

## 14. Phase 7 — tasks (PictureBook goes V3; started 2026-10-29 ~20:10 local on Stephen's "keep going with implementation until you are stopped or have a question"; rulings §6.C 214+, results §14.x)

Scope: design §30 row 7 — "Book workspace Ux replaces wizard steps 4–5. PB1 disabled (§28.1 Stage 2). Exit: Scenarios A–G; PictureBook
verify checks start." Stage 2 text (design :1842-1853): V3 is the only path for new books and for opening existing books; the PB1 entry
points return a clear "replaced by V3" response and the Ux no longer calls them; PB1 stays in the build, unreachable unless re-enabled
(`picturebook.renderPath=v1` + restart = rollback); PB1 data is read by migration and never written, deleted or reshaped. Precedent is
P2-6 (ChapBook goes V3, archive §6.C 165): per-product defaults, every surface moves together, no new flag. Nothing here is DONE (159 b).

**Inventory (read-only, 2026-10-29 evening).** The picturebook switch is pinned at eight surfaces, all `v1` except the test stack:
`PbRenderPath.PICTUREBOOK_DEFAULT` (:53), Console7 `resource.properties:63`, Service7 `web.xml:83`, `docker/entrypoint.sh:49`,
`docker-compose.yml:60`, Ux752 `featureConfig.js:35` (+ :41 normaliser, :281 / :285 admin text), `am7client.js:743` (comment);
`docker/web.xml.template:81-82` is a pure `${PICTUREBOOK_RENDER_PATH}` passthrough; Objects7 test `resource.properties:51` and
`docker-compose.test.yml:288` already say `v3` (215 e). Tests that assert the v1 default: `TestPbRenderPath`
case01 / 03 / 10, `TestChapBookRestContract.TestChapBookDefaultsToV3AndV1IsRollback` (:276-312: constant, `configure(null,"graph")`,
shipped `web.xml`, entrypoint `:=v1`), `TestPbRenderPathRefusal` :135 / :187, Ux `renderPathConfig.test.js:85,97,101`,
`chapBookV3Render.test.js:93,182,496`, `pictureBookV3Reader.test.js:51-52`. PB1 entry points in `PictureBookService`: `POST
/scene/{id}/generate` (:723, `@Deprecated`), `POST /{book}/prepare-images` (:793, `@Deprecated`), `PUT /{book}/settings` (:1117 →
`PictureBookUtil.setBookSdConfig` :2101, which writes the `.pictureBookMeta` note AND patches `olio.pb.book.sdConfig` /
`compositeSdConfig` — the book tier `PbConfigUtil` :214-221 reads; V3's own setter for that tier is `PUT /{book}/style` with `sdConfig`).
Their only Ux752 callers are wizard step 4 (`workflows/pictureBook.js` :1305 settings, :1371 prepare-images, :1426 generate) and the
resume path (:2784 `getBookSdConfig`); ChapBook uses its own `chap-book/…` routes. The hand-off points into steps 4–5: step 3's
"Continue to Images" (:2557-2561), `tryResumeExistingBook` (:2763-2815, lands on 4 or 5), the pb2 edit pencil (`features/pictureBook.js`
:160-178 → wizard resume) and the PB1 viewer pencil (:1041-1048). The V3 reader `/picture-book/v2/{book}` already hosts Render all /
Review / Style / Locations / Compose / Characters / Canvas (:2055-2157) — it is the §26 workspace hub. `GET /{group}/pb2` (:1238) bridges a
PB1 group oid to its `olio.pb.book`. Pure-PB1 books (no `olio.pb.book`) are not listed by the selector (:75-76) and only `POST /migrate-v1`
(:2314, `PbMigrationUtil.importV1Book`, no Ux caller) imports them; no "first V3 open" trigger exists; §28 import steps 2 / 5 / 6 are not built.
Six PB1 e2e specs call the retired routes directly (`pictureBook.spec.js:314`, `pictureBookLive.spec.js:418`, `pictureBookPagesAfterRender.spec.js`
:117 / :155 / :285 / :290, `pictureBookScenePromptUx.spec.js:182 / :200`, `pictureBookSceneText.spec.js:176`, `pictureBookWorkflowOverhaul.spec.js:236`).

**Tasks, in order (each with its own real tests; verifier at the end; nothing DONE by me):**
- **P7-1 — the switch.** `PICTUREBOOK_DEFAULT → V3` and a `PICTUREBOOK_ROLLBACK_WARNING` WARN in `configure` (mirror of the ChapBook
  one); every surface above moves to `v3` in the same change, no new flag (165 g / h); the v1 assertions move (`TestPbRenderPath`,
  `TestChapBookRestContract` — which becomes "both products default to V3, v1 is rollback for both", `TestPbRenderPathRefusal`, the three
  Vitest files); Ux fallback `{picturebook:'v3', chapbook:'v3', chat:'v1'}`. Tests: Objects7 `TestPbRenderPath` on `am7db`, Service7 H2
  contract classes, Ux752 quick gate.
- **P7-2 — Stage 2 refusals.** `PbRenderPath.PB1_REPLACED_PREFIX` + `requirePb1Entry(label, replacement)` (409, message names the V3
  replacement, the rollback key / env var and "applied at startup only", like `requireV3`; built in P7-1), gated on `isPb1Retired()` =
  `pictureBook() == V3` (product, not book: these routes take a scene or group oid). **Gates sit after authorization and existence checks
  (215 a):** `generateSceneImage` in the 4-arg body at the P5-3 WARN site (`:9751`, after `authorizeSceneAccess`; the `~/Chat` fallback stays
  exempt, 215 c), `prepareSceneImagePrompts` per scene after `authorizeSceneRecord` (`:10936`, outside the per-scene try), `setBookSdConfig`
  after the 404 (`:2104`); `GET /{book}/settings` stays readable; `ChapBookUtil.setBookSdConfig` untouched (215 d). Tests: `TestPbRenderPath`
  case11 (unit shape, P7-1) + live cases on `am7db` (403 before 409 for a stranger; 409 for the owner under v3 before any SD/LLM work;
  admitted under v1 to the next failure), Service7 H2 `TestPbRenderPathRefusal` through the REST methods (409 body / `@Deprecated` stays),
  and every in-suite JUnit that drives the three entries pins v1 around the call and restores (215 b list; pom-excluded `TestPictureBookFull`
  / `Custom` / `UtilE2E` get the same pin). `PictureBookService` javadocs :718-721 and siblings say "refused since P7-2". The six PB1 specs:
  gated on the live render path (skip with the Stage 2 reason when v3; they stay the rollback proof) — list recorded in §6.C.
- **P7-3 — the wizard hands off to the workspace; existing books open in V3.** Under `renderPath.picturebook === 'v3'`: step 3 "Continue
  to Images" → `/picture-book/v2/{pb2WizardBookOid}` (steps 4–5 stay for v1); `tryResumeExistingBook` → the V3 reader (via `/pb2` when
  handed a group oid); the pb2 edit pencils stop opening steps 4–5; the PB1 viewer `/picture-book/:groupOid` redirects to the V3 reader
  when `/pb2` resolves, else shows its images read-only with a "replaced by V3 — import" notice (P7-4's entry). Ux752 stops calling the
  three routes under v3. Tests: Vitest (wiring), Playwright on Docker as `e2etest_shared` (create → step 3 → reader; resume; redirect).
- **P7-4 — opening a pre-V3 book.** The notice's Import runs `POST /migrate-v1` and opens the V3 reader; the result (`scenesImported`,
  `scenesFailed`, `landscapesImported`, `warnings`) is shown. Verify check 2 ("every existing book in the test DBs opens and migrates in
  V3, images still show") is run against the Docker DB's PB1 books and `am7db`'s; what `importV1Book` does not carry (§28 steps 2 / 5 / 6:
  portraits, composite renders, bindings) is measured there and reported as Yours, not built on my judgment.
- **P7-5 — Scenarios A–G on Docker** (archive `PictureBookV3Notes.md:1883-1955`), `PB_SD_TESTS=1`, `.39`, `--workers=1`: A two
  characters, B four, C repeated location, D character change, E background-only correction, F variant selection (P6, `TestPbVariantsLive`
  — re-run only), G browser navigation (Render all continues after leaving; progress retained on return). Then verify checks 1–3
  (design :1901-1907); 4 (two weeks) and 5 (sign-off) are Stephen's.

**Out of scope here (Phase 10 / Yours):** removing PB1 code; the Kontext Remove; 213 e (reader image for RENDER_PLAN nodes — default
"neither" stands, so Scenario A–E pass criteria read the review page / node detail, not the reader page, until you rule); the P6 Yours.

### 14.1 P7-1 results — the switch (2026-10-29 20:15 → 21:05 local; §6.C 214 b, 215)

Every picturebook surface moved to `v3` in one change, no new flag: `PbRenderPath.PICTUREBOOK_DEFAULT = V3` (:58) + `PICTUREBOOK_ROLLBACK_WARNING`
WARN in `configure` + `PB1_REPLACED_PREFIX` / `isPb1Retired()` / `requirePb1Entry(label, replacement)` (built here, wired in P7-2); Console7
`resource.properties:64`, `web.xml` comment (param-value stays empty = product default), `docker/entrypoint.sh:49` `:=v3`, `docker-compose.yml:61`
`:-v3`, `featureConfig.js:35` default + `:41` normaliser, `am7client.js:743` comment. CRLF files checked (`tr -cd '\r' | wc -c` = line count).
Tests moved with it: `TestPbRenderPath` case01 / 03 (v3 default, unrecognised → v3, no borrowing), case10 (explicit v1 = rollback), **new
case11** (default retires PB1; `requirePb1Entry` 409 carries prefix, label, "Use … instead", key=v3, key=v1, env var, not the `requireV3` prefix;
v1 re-admits without touching ChapBook; absent restores); `TestChapBookRestContract.TestChapBookDefaultsToV3AndV1IsRollback` (both products
default V3; shipped `web.xml` both v3; entrypoint `:=v3` both; both rollbacks honoured — ChapBook via `requireV3`, PictureBook via
`requirePb1Entry`); Ux `renderPathConfig.test.js` :82-102 / :112-118.
- Objects7 `TestPbRenderPath` on `am7db` (`C:/tmp/p7-1-objects7-renderpath.log`): `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` (:36599),
  `BUILD SUCCESS` (:36606), `EXIT=0`; the new WARN fires as `picturebook.renderPath=v1: legacy PictureBook (PB1) render path re-enabled (rollback)` (:62).
- Service7 H2 (`C:/tmp/p7-1-service7-renderpath.log`): `TestChapBookRestContract` `Tests run: 9, Failures: 0` (:1215), `TestPbRenderPathRefusal`
  `Tests run: 7, Failures: 0` (:2202), `BUILD SUCCESS` (:2209), `EXIT=0` (the `Unknown data type: "VECTOR"` DBUtil lines are the standing H2 noise).
- Ux752 Vitest (`C:/tmp/p7-1-vitest.log`): first run **1 failed / 1469 passed** — my `featureConfig.js:41` miss (215 f); fixed → `Test Files 99 passed
  (99)`, `Tests 1470 passed (1470)` (:5-6), `EXIT=0`. `npx vite build` `✓ built in 5.86s` (`p7-1-vite.log:146`), `EXIT=0`.
- Not run: the Docker stack — no server behaviour changes until P7-2, and `am7test-am7-1` already runs `PICTUREBOOK_RENDER_PATH=v3`
  (`docker-compose.test.yml:288`, `docker inspect` env), so the P7-1 default flip changes nothing there. Nothing DONE (159 b).

### 14.2 P7-2 results — Stage 2 refusals (2026-10-29 21:05 → 22:20 local; §6.C 215, 216)

`PbRenderPath.requirePb1Entry` (:220, built in P7-1) is wired at three `PictureBookUtil` sites, each AFTER its authorization / existence check
(215 a): `setBookSdConfig` :2120 (after `resolveBookGroupEither`'s 404 at :2115), `generateSceneImage` 4-arg body :9772 (after
`authorizeSceneAccess(WRITE)`, only when the scene's `groupPath` ends in `/Scenes` — the `~/Chat` single-image fallback stays exempt, 215 c),
`prepareSceneImagePrompts` :10957 (per scene, after `authorizeSceneRecord(WRITE)` at :10951, outside the swallowing try; an unreadable id stays
warn-and-skip). Labels / replacements are `PB1_ENTRY_*` / `PB1_REPLACEMENT_*` (:237-244: generate → `/{book}/scene/{id}/render-plan` or
`/render-all`, prepare → `/render-all`, settings → `PUT /{book}/style (sdConfig)`). `GET /{book}/settings` stays readable. `PictureBookService`
:718 / :790 / :1118 javadocs say "design §28.1 Stage 2 since P7-2"; `@Deprecated` / `@RolesAllowed` unchanged. The ordering rule is in
`.claude/rules/architecture.md` ("Feature / product gates sit after authorization and existence checks, never before").
Tests — all on the default v3; every PB1-driving JUnit pins `PbRenderPath.configure(V1, V3)` around the call and restores (215 b):
- Objects7 `TestPbRenderPath` on `am7db`, `[LLM-GATE] route=direct tier=remote server=http://192.168.1.42:11434` (:56 — the remote `.42`; the
  container `:11435` and LiteLLM `:4000` did not answer) — `C:/tmp/p7-2-TestPbRenderPath.log`: **new case12** (owner under v3: generate / prepare /
  settings 409 carrying prefix, label, replacement, `=v3`, `=v1`, env var, BEFORE any SD / LLM / persist — SD host `http://127.0.0.1:1`, meta
  stays null; unknown oids 404; v1 admits each to its next failure) and **case13** (second user in the org: no grant → 404 generate, silent skip
  prepare, 404 settings; Read + Update on `/Scenes` → 403 generate / prepare (book-level `canUpdate`), 404 settings; never 409; nothing
  persisted). `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` (:36749), `BUILD SUCCESS` (:36756).
- Pinned JUnits, same DB / tier, `BUILD SUCCESS` each: `TestPbBookSettingsResolveEither` 2/2 (`C:/tmp/p7-2-…ResolveEither.log:301`, :308),
  `TestPbSeriesChapterScenesResolve` 1/1 (:162, :169), `TestPbSceneGenerationGuards` 6/6 (:1462, :1469), `TestPictureBookWorkflow` 5/5
  (`…Workflow.log:3595`, :3602, `EXIT=0` :3607; 2500 s wall — five FLUX.2 composites on `.39`, one of them ~8 min: observations), the v1 pin
  WARN visible (`…Workflow.log:60`), zero `replaced by V3` inside a pinned call. `TestPictureBookSceneAuthz` 7/7 (:258, :265) is deliberately
  NOT pinned (216 b). Pom-excluded `TestPictureBookFull` / `Custom` / `UtilE2E` got the same pin and compile; not run. Only the Workflow launch
  appended `EXIT=`; for the others `BUILD SUCCESS` + `Finished at` are the exit evidence.
- Service7 H2 (`C:/tmp/p7-2-svc-refusal-chapcontract.log`): `TestPbRenderPathRefusal` **new `TestPb1EntriesRefusedUnderV3`** through the REST
  methods (409 body = the `requirePb1Entry` text for all three, `@Deprecated` stays) `Tests run: 8, Failures: 0` (:2398); `TestChapBookRestContract`
  9/9 (:1215); `BUILD SUCCESS` (:2405).
- Redeploy: Objects7 jar installed (`p7-2-obj-install.log`, `-q`; `~/.m2` jar 21:28:58, `javap` finds `requirePb1Entry`); Service7 `target/classes`
  Eclipse-stub check (`Unresolved compilation` grep empty, `javap` 0) → `docker cp` jar + `PictureBookService*.class` into `am7test-am7-1`,
  restart, `/rest/schema` 200, boot `Server startup in [10267] ms`; `GET /rest/config/render-path` → `{"picturebook":"v3","chapbook":"v3","chat":"v3"}`.
- Docker live check as `e2etest_shared` (`C:/tmp/p7-2-live-check.mjs` → `p7-2-live-check.log`): generate / prepare-images / PUT settings 409
  `replaced by V3` naming `render-plan` / `render-all` / `/style` / `PICTUREBOOK_RENDER_PATH` (:7-12); GET settings `200 {}` (:13); nil-UUID
  generate `404 Scene not found` (:15); nil settings `404 Book not found` (:17); `LIVE-CHECK OK` (:20), EXIT=0; fixture deleted (:19).
- Six PB1 specs gated (214 f, list in 216 a) via `pb1Retired` / `PB1_RETIRED_SKIP` — skips report as skips, never passes, zero SD calls.
  Stack runs (`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 --workers=1`, `[e2e-llm] route=direct server=http://192.168.1.42:11434` :13):
  `p7-2-e2e-gate-skips.log` `5 skipped` / `5 passed` (:31-32; `[e2e-render-path] … retired; skipping` at :16 / :18 / :20 / :22 / :28; the
  load-safe cases still pass), `p7-2-e2e-gate-skips2.log` `2 skipped` (:11; :4, :8), `p7-2-e2e-gate-live-b4.log` `1 skipped` (:17; :14 — B.4 on
  `--project=chromium-vite` through the Vite on `:8898`, `PB_LLM_TESTS=1 E2E_SD_LIVE=1` so the gate is reached after its env skips; its setup
  extracted 3 scenes on `.42` first, :10).
- Security review (security-reviewer, ~22:03): PASS, no blocking findings — no existence leak at any site, the 409 body discloses only what
  `render-path` already tells a `user`, the `~/Chat` exemption keys off the `groupId`-derived `groupPath` (not client-settable), `@RolesAllowed`
  on all four routes, no admin auth in the tests. Its two hardening notes → 216 d / e.
Noted, not fixed (out of scope): `PictureBookService.ensureSchema("{}")` (:193-202) yields `{"schema":…,}` — a trailing comma, so an empty body
parses to null with a WARN; the REST `setBookSdConfig` (:1132) has no null-user guard (same as its siblings; container-enforced); the 15
`[Thread-N] RecordUtil WriterException: Record failed validation` lines in the Workflow log are `system.audit` rows whose `description` (the
audit line of a wide charPerson read) fails validation — pre-existing audit-writer noise. Employer-identifier grep over every added diff
line: no matches. Nothing DONE (159 b).

### 14.3 P7-3 results — the wizard hands off to the workspace (2026-10-29 22:25 → 23:30 local; §6.C 217)

Ux752 only; no server change, the stack still carries P7-2's jar. `dist` rebuilt (`npx vite build`) and `docker cp`'d into
`am7test-am7-1:/opt/ux752/dist/` before the Playwright runs.
- Wizard (`src/workflows/pictureBook.js`): `pbIsV3()` / `readerRoute()` / `handOffToReader()` (:65-91). `pictureBook()` reads the render path
  and, under v3, resolves resume first — an existing book (pb2 oid or PB1 group oid, through `resolvePb2BookObjectId`) routes to
  `/picture-book/v2/{oid}` and the wizard never opens (:2902-2924); step 3 "Continue to Images" → `handOffToReader()` (:2610-2613); the
  progress bar shows three steps (:2435). v1 keeps steps 4–5 untouched.
- Id resolution (`src/workflows/pictureBookWorkflow.js:22-54` `resolvePb2BookObjectId`): by-id `olio.pb.book` → `GET /{id}/pb2` → `/books`
  list; null = no V3 record (a source document, a pure PB1 book, an unknown id). Every miss falls through; nothing throws.
- PB1 viewer (`src/features/pictureBook.js:804-870`): under v3 the route is a bridge — a resolvable id replace-redirects to the V3 reader
  (:823-829; `viewerLoading` stays true so the empty state never flashes); otherwise `viewerLegacy = v3 && scenes.length > 0` (:870) shows the
  book read-only with the amber `[data-pb1-legacy-notice]` banner (:1063-1076, "replaced by V3. Import the book…" — P7-4's entry, no action
  yet) through the new `ReaderShell.renderBanner` slot (`readerShell.js:33, :339`); the Edit pencil is hidden (:1083), as is the PB2 reader
  header's under v3 (:2194). Under v3 nothing in Ux752 calls `scene/{id}/generate`, `prepare-images` or `PUT settings` — the hand-off spec
  records every POST / PUT on those routes and asserts the list empty on all four paths.
- Vitest: new `src/test/pictureBookV3Handoff.test.js` (25, jsdom — hand-off route, resume → `{route}`, v1 unchanged, viewer redirect with
  `{replace:true}` (:253, :261), legacy notice + no pencil, `resolvePb2BookObjectId` fall-through). Full suite `C:/tmp/p7-3-vitest.log`:
  `Test Files 100 passed (100)` (:5), `Tests 1495 passed (1495)` (:6), `EXIT=0` (:10).
- Playwright on the stack as `e2etest_shared` (`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 --workers=1 --project=chromium`;
  `[e2e-render-path] {"picturebook":"v3",…} -> PB1 entry points retired; skipping` `p73-run3.log:4, :15`): new `pictureBookV3Handoff.spec.js`
  4/4 (:19-21, :36 — 17.6 s / 4.6 s / 2.3 s / 2.9 s, observations): (1) wizard on a fresh note → three-step bar, no Images / View, manual
  scene → step 2 → step 3 "Continue to Images" → hash `/picture-book/v2/<oid>`, `/books` DTO `slug = e2e-p73-wizard-<tag>` + `title` = the
  typed name, header = `name`, no dialog (`[p73] wizard book pb2=…` :18); (2) list-row Edit on a bridged book → reader, no wizard;
  (3) `/picture-book/<groupOid>` with `/pb2` 200 → replace-redirect to the reader, no notice; (4) a pure PB1 book (`/pb2` 404) → notice
  visible, hash unchanged, `h1` = workName, "1 Scene", no Edit pencil, Begin → scene page, notice stays. Zero retired-route calls on every
  path. Fixtures: bridged book (`/chapter` + `/create-from-scenes`, `[p73] bridged … "jobStatus":"queued"` :16), pure PB1 book
  (`[p73] legacy …` :17); cleanup `DELETE /{oid}/reset` → 200 (:22, :29), notes / groups deleted. `pictureBookNavigation.spec.js` in the same
  run: 1, 2, 4, 7, 9 ok; 3 / 5 / 6 skipped (data-dependent, `rendered=null sibling=null` :3); 8 skipped (8b under v3). Totals `5 skipped`
  (:39), `9 passed (1.2m)` (:40); this launch did not append `EXIT=` — the reporter's totals and the absence of a `failed` line are the exit
  evidence. Earlier runs (`p73-handoff-run1.log` 2 failed, `run2` 1 failed) were my spec's assertions, not the product — header text (217 g)
  and Back after the redirect (217 h) — fixed in the spec, no test weakened; run 3 is the first clean run.
- Live LLM, same stack (`PB_ASYNC_TESTS=1`, `p73-wizardux-live.log`): `[e2e-llm] route=direct server=http://192.168.1.42:11434
  analysis=qwen3:8b pb=goekdenizguelmez/JOSIEFIED-Qwen3:8b` (:5 — the remote `.42` Ollama, direct; the container `:11435` and LiteLLM `:4000`
  did not answer). `pictureBookWizardUx`: extract → continue → real characters → Manage Characters → the render-path-aware final step lands on
  the V3 reader: `ok 1 … (2.1m)` (:229), `1 passed (2.2m)` (:231), `EXIT=0` (:232). `[PAGE-WARNING] … detectBoundaries failed: 404` (:223) →
  single-document extraction (217 i).
- Line endings: every touched file uniform (LF files LF; CRLF files `i/lf w/crlf`). Employer-identifier grep over the touched files: no
  matches. Nothing DONE (159 b).

### 14.4 P7-4 results — opening a pre-V3 book + verify check 2 (2026-10-29 23:30 → 2026-10-30 00:45 local; §6.C 218)

**Built.** The P7-3 notice's Import (`features/pictureBook.js:1083-1088` `[data-pb1-import]`, `importLegacyBook` :1124-1150 guarded by
`viewerImporting`) calls `POST /migrate-v1` (`workflows/pictureBookWorkflow.js:438` `migrateV1Book`, `:446` `normalizeMigration`), routes to
`/picture-book/v2/{bookObjectId}` and then opens a dialog with `scenesImported` / `scenesFailed` / `landscapesImported` / `warnings`
(`[data-pb1-import-result]` :1107); a non-2xx is a toast, the viewer stays. Server: the route (`PictureBookService.java:2333`) was unusable —
`parseParams` → `olio.pictureBookRequest` has no `groupObjectId`, so every body was 400 "groupObjectId is required" (218 b); it now reads the body
as a plain map (`bodyMap` :2371 / `bodyString` :2376) and maps `PictureBookException` → its status. No Objects7 change.
- Service7 H2 **new `TestPictureBookMigrateRoute`** (401 anonymous; 400 missing / malformed / no-`groupObjectId`; 404 unknown group; owned group
  without meta 404 and a stranger's 404 — no existence leak): `Tests run: 6, Failures: 0` (`C:/tmp/p74-migrate-route.log:964`), `BUILD SUCCESS`
  (:967), `EXIT=0` (:972).
- Vitest **new `pictureBookV3Import.test.js`** (19 — button, guard, route-then-dialog, result text, error toast, `normalizeMigration`): full suite
  `Test Files 101 passed (101)` / `Tests 1514 passed (1514)` (`p74-vitest-full.log:5-6`), `EXIT=0` (:10). `dist` rebuilt and `docker cp`'d;
  `PictureBookService*.class` `docker cp`'d + restart.
- Playwright on the stack as `e2etest_shared` (`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 --workers=1 --project=chromium`) **new
  `pictureBookV3Import.spec.js`** 2/2 (`p74-import-run4.log:40`, `EXIT=0` :41): a REST-built PB1 book (meta + scene note + a real PNG) → notice →
  Import → reader + result dialog (`scenesImported 1, landscapesImported 1`, 3 warnings, :6-26) → the page `<img>` is the PB1 PNG
  (`src=…/Images/scene-1-….png` :27, direct GET `200 image/png` :28); PB1 untouched, `/pb2` now bridges, re-import 409 (:30); reset 200 (:31).
  `pictureBookV3Handoff.spec.js` re-run 4/4 (`p74-handoff-rerun.log:27`, `EXIT=0`).

**Verify check 2 — "every existing book in the test DBs opens and migrates in V3, images still show".** Two sweeps, each as every owner
(never admin), import → `/pages` → every imaged page's bytes / media → `/health` → (Docker) the reader, then `reset` as the owner so both DBs
are left as found (verified: PB1 groups, `Scenes` notes and the three image rows ~1.4 MB each still present; 0 `olio.pb.book` rows for the
imported slugs). Timings are observations.
- **Docker `am72db`, new gated `e2e/pictureBookV3ImportSweep.spec.js`** (`PB_MIGRATE_SWEEP=1`, owners from the DB, `steve` / `admin` excluded;
  `C:/tmp/p74-sweep.log`): **288 PB1 book groups across 38 owners — 237 already V3, 17 imported, 34 failed** (:347; table :57-346; the
  Playwright retry reproduced it exactly, :693). Imported (7–42 s each): `e2etest_shared` ×15 "P4-4 Unbound Import …" (2/0 scenes, 2 pages),
  one `e2etest_pbchars*` "AIME Chars Book" (3/0), one `e2etest_pbmv*` "AIME Test Book" (3/0) — pages = scenes, health errors `[]`, reader opens
  ("Book <slug>", "0 rendered of N scenes", screenshots `e2e/screenshots/p74-sweep-<owner>.png`), every reset 200 (:348). **All 34 failures are
  one defect** (218 c): 6 more "AIME Chars Book" and 28 more "AIME Test Book" owners got **409 "Failed to create book 'aime-test-book'. The unique
  (slug, organizationId) index rejects a duplicate, so a concurrent creation … is the expected cause"** (:7-12, :14-41) — the slug is org-unique,
  `findBookBySlug` is PBAC-scoped so another owner's book reads as "no conflict", and the DB index then refuses. The spec's verdict fails on it:
  `1 failed`, `EXIT=1` (:826, :828) — a real finding, not a test defect; no cleanup needed (the collision throws before any world / group).
  The three PB1 books that carry an image were among the collided, so re-run one owner per launch (`p74-sweep-imaged.log`): `e2etest_pbmv2vs25b`
  3/0 scenes, **1 landscape, 3 pages / 1 imaged / media 200 `image/*`**, reader "1 rendered of 3 scenes" (:7-12, `1 passed` :15, `EXIT=0` :16);
  `e2etest_pbmv2y0azy` 1/0, 1/1/1 (:23-32); `e2etest_pbmv4mgsud` 3/0, 3/1/1 (:39-48) — the PB1 render is the reader's cover backdrop
  (`p74-sweep-e2etest_pbmv4mgsud.png`). Images still show.
- **`am7db`, new gated Objects7 `TestPbMigrationSweep`** (`-Dpb.sweep=true`, default 5 owners × 2 newest PB1-only books, `-Dpb.sweep.perOwner=0`
  for all; `C:/tmp/p74-am7db-sweep.log`, `[LLM-GATE] route=direct tier=remote server=http://192.168.1.42:11434` :61 — the remote `.42`, direct;
  container `:11435` and LiteLLM `:4000` did not answer): **78 PB1 book groups with a meta note listed — 71 already V3, 7 imported, 0 failed**
  (:21014; table :20934-21013): `pbGuardUser` ×2 "Guard p01ng-…" (1/0, 23–25 s), `pbCustomTestUser` "Extract Legacy Book …" (3/0, 27 s),
  `pbTestUser` "Default Age Stats Book …" (1/0, 31 s) + "E2E Apparel Scene Test Book …" (3/0, 33 s), `ki42b…` "KI42 Book …" (1/0, **123 s**),
  `ki42c…` "KI42C Book …" (1/0, 20 s); pages = scenes, health errors `[]`, every reset ok. `Tests run: 1, Failures: 0, Errors: 0` (:21020),
  `BUILD SUCCESS` (:21023), `EXIT=0` (:21028). **No PB1-only book on `am7db` carries an image** — all 38 scene notes naming an `imageObjectId`
  sit in V3 `…/Book/Scenes` groups (psql, `unbridged=0`), so "images still show" is measured on Docker only.
- **Not carried by `importV1Book` (§28 steps 2 / 5 / 6), measured:** no character / portrait (PB1 `Characters` groups are empty in every swept book,
  so nothing to measure here), no composite render, no binding; each PB1 image becomes one unapproved LANDSCAPE artifact (`landscapesImported`)
  and the page shows it only through the scene note's `imageObjectId` fallback (`PbServiceFacade.bookPageView` :2315). `scenesFailed` was 0 in
  all 27 imports. Yours: 218 c–f.
- Line endings: touched files uniform (LF files LF; `web.xml` / `PictureBookService.java` keep their CRLF). Employer-identifier grep: no matches.
  Nothing DONE (159 b).

### 14.5 P7-5 results — Scenarios A–G on Docker + verify checks 1 and 3 (2026-10-30 01:50 → 05:00 local; §6.C 219)

**Setup.** Docker stack `am7test` (`https://127.0.0.1:9443`, `PICTUREBOOK_RENDER_PATH=v3`), SD `.39` (`SD_SERVER=http://192.168.1.39:7801`),
as `e2etest_shared` (never admin), `PB_SD_TESTS=1 --workers=1 --project=chromium --retries=0`. **New gated `e2e/pictureBookV3Scenarios.spec.js`**
(one serial describe; the fixture builds a fresh book per run: world + workflow, tavern LOCATION node rendered + approved, four charPersons with
PORTRAIT nodes rendered (174–178 s each) + approved + unpinned, six scenes with compositions / actor boxes for 1, 2, 4, RENDER_PLAN nodes by handle,
bindings 1: Alice+Bob, 4: Alice, `scene.sceneNode` linked; `PUT /style` pins `flux2Klein_9b` for portrait / location / sceneDirect / inpaint :4).
Timings are observations. Run 5 is the green run (`C:/tmp/p75-scenarios.log`; book 1671 `p75-scen-mvuq84sg` :29): **`6 passed (48.4m)` :190,
`EXIT=0` :191**; runs 1–4 (`p75-scenarios-run2..4.log`) were fixture / spec defects and the D product defect (219 b, c).
- **A two characters** — `render-plan` scene 1 200 in 221 s (:30); `DIRECT_REFERENCE: 2 referenced actors within 3 slots`, the approved tavern
  reused (`renderArtifactId c0841fac…` :49); `a-scene1.png` seed 1689345618, 114 s (:50); ok (:51). PNG: both figures at the bar.
- **B four characters** — 511 s (:52); `SEQUENTIAL_INPAINT: 4 referenced actors, 3 slots, 2 ACTOR_REGION passes` (:71); BASE=[Alice, Bob],
  ACTOR_REGION=[Cara, Dev], `stagesRendered` = BASE + one `ACTOR_REGION#<actor>` each (:72); `b-scene2.png`; ok 8.5 m (:76). PNG: four round a chart.
- **C repeated location** — scenes 3 / 7 / 12 plans share one `locationId` / `revisionHash 7fbc70dd…` / `renderArtifactId c0841fac…` (:77-79); ok,
  no SD call (:80).
- **D character change** — scene 4 v1 163 s (:81; `d-scene4-v1.png` :100); approve + pin scene 1 (chain `[r1 v0 sel, r2 v1]` :101); `PATCH
  olio.charPerson.hairStyle` → affected-by drifted false → true, `staleSceneIndexes [1,4]` (:102-103); UI Regenerate: facade 409 "is pinned" →
  "Scene 1 is pinned" confirm → Cancel → scene 4 `render?force=true` 202 (:104); job 345 s, executed 2 = portrait re-render 178 s + scene
  (:118-121); toast "Regenerated 1 of 2 scenes, 1 skipped (pinned)" (:122); scene 4 chain = two attempts, the regenerate's variant 0 selected
  (:123); the v2 plan still references the APPROVED portrait v1 (:124); `d-alice-portrait-v2.png` rev 2 GENERATED, unselected (:125); approve it →
  scene 4 v3 158 s, rev 5 (:127, :146-147); scene 1's chain and pin unchanged; ok (:148).
- **E background-only correction** — unpin the location (it self-drifted on approve :150), forced location render 39 s → `e-tavern-v2.png` rev 2
  APPROVED (:151-154); affected-by = location + 3 render_plan (:155); scene 2 / 4 plans carry the new `revisionHash 09e75e09…` (:156-157);
  "character regions remain unchanged" NOT met — no background-only regenerate exists, G re-renders whole scenes (:158); ok (:159).
- **F variant selection** — `TestPbVariantsLive` re-run only (`C:/tmp/p75-scenF-live.log`; `[LLM-GATE] route=direct tier=remote
  server=http://192.168.1.42:11434` — the remote `.42` Ollama, direct :56): `Tests run: 1, Failures: 0` (:495, :499), `BUILD SUCCESS` (:502),
  `EXIT=0` (:507).
- **G browser navigation** — cover "0 rendered of 6 scenes" (:160); Render all 202 (:161) → leave the page → first poll +648 ms `running`,
  `terminal=false` (:175) → return: "Render (resumed watching) 0 of 2 · render_plan@3e0e867d" + Cancel (`g-reattached.png`, :176); job 685 s,
  executed 2 (:180); "Render complete: 2 rendered" (:181, `g-terminal.png`); `g-scene2-v2.png` rev 3 / `g-scene4-v4.png` rev 7 (:182-183);
  cover still "0 rendered of 6 scenes" (:184; 219 f); ok (:186). Book left in place (:185).

**Verify check 1** (A–G pass on Docker): the run above, with the gaps in 219 a–h. **Check 2**: §14.4. **Verify check 3** ("a ChapBook created
before V3 renders a page in V3 with its locked prompt intact"): **new gated `e2e/chapBookV3PreV3Lock.spec.js`** (`C:/tmp/p75-check3.log`): a fresh
ChapBook (`createChapBook`, 2 pages :4) whose scene row alone is locked through generic `PATCH /rest/model` (`sdPrompt` = a lighthouse prompt,
`promptLocked: true` — the pre-V3 shape, no composition :5); `POST /olio/chap-book/scene/{id}/generate` 200 in 30 s, `renderPath v3`, job + run +
LANDSCAPE node + artifact (:6); node `promptText` = the locked prompt verbatim (:7); plan `location.description` = it, `TEXT_ONLY`, juggernautXL
deployment default for `location` (:8); compiled BASE prompt = it (:9); generator request `prompt` = it (:10); scene row after: `sdPrompt` /
`promptLocked` intact, `imageObjectId` set, `imageStale false` (:11); `/pages` carries `imageName` + `sceneNodeObjectId` (:12);
`check3-prev3-locked-page.png` 1024² (:13) — a lighthouse on black sand under a green aurora, as prompted. **`1 passed (40.4s)` :17, `EXIT=0` :18.**
Checks 4 (two weeks) and 5 (sign-off) are yours.

**D product fix (219 b).** `PbServiceFacade.requestSceneRender` refuses a pinned scene node 409 "Node <handle> is pinned. Unpin it first - …"
before planning (`PbServiceFacade.java:741-747`, force or not). Objects7 **new `TestPbRenderJob#case12_facadeRefusesPinnedSceneWithStaleUpstream
BeforePlanning`**: `Tests run: 1, Failures: 0` (`C:/tmp/p75-case12.log:33796`), `BUILD SUCCESS` (:33803), `EXIT=0` (:33808);
`TestChapBookV3Render#facadeForceRerendersACurrentPageAndPinStillWins` assertion updated, re-run live (`p75-cbforce.log:56` `[LLM-GATE]
route=direct tier=remote` — `.42`; :428 the 409; :434 `Tests run: 1, Failures: 0`; :441 `BUILD SUCCESS`; :446 `EXIT=0`). UI `isPinnedRefusal`
(`workflows/pictureBookWorkflow.js:413-416`) also matches the facade text (+1 Vitest case); full Vitest `Test Files 101 passed` / `Tests 1515 passed`
(`p75-vitest-full.log:5-6`), `EXIT=0` (:10); `vite build` ok (`p75-vite-build.log:147`); jar + `dist` `docker cp`'d + restart. The exact-text
pinned cases in `pictureBookV3PlainPb.spec.js` (:397-406) and `pictureBookV3Ux.spec.js` (~:607) were edited and both specs re-run on the stack
(`PB_SD_TESTS=1`): PlainPb `4 passed (52.4s)` (`C:/tmp/p75-plainpb-rerun.log:61`), `EXIT=0` (:62); Ux `4 passed (2.3m)` (`p75-ux-rerun.log:114`),
`EXIT=0` (:115).
- Line endings: touched files uniform (LF files LF; `pictureBookWorkflow.js` keeps its CRLF). Employer-identifier grep over the touched files: no
  matches. Nothing DONE (159 b).

next §6.C number 220 (218 = P7-4 close-out; 219 = P7-5 close-out).
