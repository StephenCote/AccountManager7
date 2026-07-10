# PageIndex Integration Plan — REST, Chat, MCP, Ux752

**Status:** ✅ **all tiers complete, live-verified end to end (2026-07-09)** — prerequisite, Tier 1 (REST),
Tier 2 (MCP + chat RAG), and Tier 3 (Ux752) all landed and independently re-verified the same day ·
**Date:** 2026-07-08 (design), 2026-07-09 (full integration) · **Depends on:** `PageIndexDesign.md` (core
engine — implemented & verified)

The PageIndex **engine** is complete in Objects7 (build via `AccessPoint.pageIndex`, retrieve via
`PageIndexUtil.retrieve`, hierarchical `data.pageIndexNode` tree, LLM-TOC + summaries + cosine ranking).
This plan adds the four **surfaces above Objects7** that the engine currently lacks — REST, chat RAG,
MCP, and the Ux752 UI — each mirroring a proven **vector/RAG twin** that already exists at every layer.

Everything below stays within the architecture rules: business logic in Objects7, Service7 as pure
transport, MCP marshaled through the existing `IToolProvider` boundary, Ux752 consuming REST. The one
place the rules are currently bent (retrieval bypasses PBAC as a utility) is closed by the prerequisite
before any surface exposes it. **The prerequisite and Tier 1 (REST) are both done and live-verified**
(see below) — PageIndex is now reachable over HTTP with full PBAC enforcement. Tier 2 (chat/MCP) is next;
testing PageIndex from a chat conversation needs Tier 2's chat-RAG step (reusing the existing `contextRefs`
attach flow), and testing from the Ux UI directly needs Tier 3.

---

## Current state

| Layer | Vector/RAG (exists) | PageIndex |
|---|---|---|
| Objects7 build (PBAC) | `AccessPoint.vectorize` (`AccessPoint.java:677-727`) | ✅ `AccessPoint.pageIndex` (`:729-772`) |
| Objects7 retrieve (PBAC-gated) | `VectorUtil.findByEmbedding` (`VectorUtil.java:173-246`) | ✅ `AccessPoint.pageIndexRetrieve`/`pageIndexTree`/`pageIndexDelete` (prerequisite, 2026-07-09) → `PageIndexUtil.retrieve`/`getTree`/`deletePageIndex` |
| REST | `VectorService` (`/vector/vectorize`, `/vector/reference`) | ✅ `PageIndexService` (`/pageIndex/...`, 2026-07-09) |
| Chat RAG injection | `ChatUtil.getDataCitations` → prepended to user turn | ✅ opt-in via `usePageIndex` chatConfig flag (2026-07-09) |
| MCP tool | `am7_vector_search` (`Am7ToolProvider.java:113-162`) | ✅ `am7_pageindex_search`/`_structure`/`_section`/`_content` (2026-07-09) |
| Ux752 | `vectorize.js` workflow + `ContextPanel` attach | ✅ `pageIndex.js` workflow + `pageIndexTree.js` component + chat toggle/attach (2026-07-09) |

**PageIndex is now reachable end to end — REST, MCP, chat (opt-in), and the Ux752 UI — all live-verified**
with real PBAC enforcement (positive + cross-user-denied + unauthenticated cases at every layer). A user
can build/browse/query a document's PageIndex from the object page, and opt a chat conversation into
PageIndex-augmented citations via a config toggle.

## Vector-pattern seams to mirror (verified file:line)

- **REST build:** `AccountManagerService7/.../rest/services/VectorService.java:48-64` — `@RolesAllowed({"user"})
  @GET /vector/vectorize/{type}/{objectId}/{chunkType}/{chunkSize}` → `AccessPoint.vectorize`.
- **REST retrieve:** `VectorService.java:94-119` — `@POST /vector/reference/{type}/{objectId}/{count}/{dist}/{distinct}`
  → `VectorUtil.find` → `RecordSerializerConfig.getForeignUnfilteredModuleRecurse()`.
- **REST auto-scan:** `RestServiceConfig.java:35` (`packages("org.cote.rest.services")`) + `web.xml:275-283` —
  any new `*Service` in that package is auto-registered; no wiring.
- **Chat RAG:** `ChatUtil.getDataCitations` (`ChatUtil.java:1389-1502`) resolves knowledge sources from
  `creq.get("contextRefs")` (+ ephemeral `data`), runs `vu.find(frec, …, msg, 5, 0.6, false)` (`:1480`),
  turns chunks into citation strings. Injected by **prepending to the user turn**:
  `ChatService.java:439/472` (buffer REST) and `ChatListener.java:157/189` (streaming WS), wrapped by
  `PromptUtil.getUserCitationTemplate`.
- **Knowledge-source binding:** `chatRequestModel.json:79-84` — `contextRefs` (list of serialized
  `{schema,objectId}` JSON), persisted across turns. This is the "attach a doc as a knowledge source"
  mechanism; PageIndex rides the same list.
- **MCP:** `McpService.java` (`/mcp` JSON-RPC) → `McpServer.java:123-133` (`tools/list`,`tools/call`) →
  `CompositeToolProvider.java:24-40` (composes `Am7ToolProvider` + `ISO42001ToolProvider`, routes by name
  prefix). Template tool: `Am7ToolProvider.vectorSearch` (`:113-162`) + `buildVectorSearchSchema` (`:537-551`).
- **Ux build action:** `AccountManagerUx752/src/workflows/vectorize.js` (Dialog → `GET /vector/vectorize/…`),
  exported `workflows/index.js:8`, bound `views/object.js:912-915` (`objectPage.vectorize = vectorize`),
  model opt-in `"vectorize": true` `core/modelDef.js:4761` (consumed via `ModelSchema.isVectorize()`).
- **Ux chat knowledge-source manager:** `AccountManagerUx752/src/chat/ContextPanel.js` — attach/detach
  (`POST /rest/chat/context/attach|detach`, `:52-87`), attach-type menu (`:274-281`), renders `contextRefs`
  rows. Server: `ChatService.attachContext` (`:479+`), append/dedup (`:1028-1076`), **auto-vectorize on
  attach** `ChatService.autoVectorize` (`:1082-1104`, `vu.createVectorStore(obj, WORD, 500)`).
- **JUnit templates:** `TestVectorStore.java`, `TestContextRefs.java` (exercises `getDataCitations` with the
  shared test user), `TestPageIndex.java` (engine).

> **Gotcha to NOT replicate:** `vectorize.js:24` offers chunk types `PARAGRAPH/TOKEN/PAGE` that the backend
> `ChunkEnumType` (`VectorUtil.java:46-51`) doesn't support — a latent mismatch. PageIndex has no
> client-chosen chunk strategy (it picks header vs LLM-TOC itself), so the build UI takes **no chunk args**.

---

## PREREQUISITE (blocks Tier 1/2/3) — PBAC-gate retrieval — ✅ DONE (2026-07-09)

`PageIndexUtil.retrieve(model, query, limit)` (`PageIndexUtil.java:941`) took no `user` and read via
`IOSystem…getSearch()` directly (documented utility bypass). It must not reach REST/MCP/chat un-gated
(security + architect flagged this).

- **Module:** Objects7 only. Legal.
- **Added** `AccessPoint.pageIndexRetrieve(BaseRecord user, String model, String objectId, String query, int limit)`
  next to `pageIndex()`: resolves the source record with `find(user, q)` (same query shape as `pageIndex`),
  runs `getAuthorizationUtil().canRead(user, user, rec)` (mirrors the `canUpdate` gate), then calls
  `PageIndexUtil.retrieve(rec, query, limit)`. Audited via `ActionEnumType.READ`. Authorizing the source
  doc once is sufficient — nodes are grouped in the source's group, so their reads inherit that PBAC.
- **Also added** the PBAC wrappers used by the tree-read/rebuild surfaces: `AccessPoint.pageIndexTree(user, type, objectId)`
  (`find`→`canRead`→ `PageIndexUtil.getTree(model)`) and `AccessPoint.pageIndexDelete(user, type, objectId)`
  (`find`→`canUpdate`→`PageIndexUtil.deletePageIndex`). `canUpdate` is deliberate (not `canDelete`): the
  index is **derived data on the source doc**, so delete is gated on modify-rights to the source — consistent
  with build (`pageIndex` also uses `canUpdate`).
- **`PageIndexUtil.getTree(model)`** (new): returns every node for the source as a flat, safely-projected
  list (reuses `loadRoots`/`loadChildren`'s field projection so the un-projectable VECTOR embedding is
  never requested), sorted by `level` then `ordinal` so a caller rebuilds the tree by walking `parentId`.
- **Verified:** new `TestPageIndexAccessPointGate` in `TestPageIndex.java` — positive (owning `testUser1`
  reads tree/retrieves/deletes) + negative (`testUser2`, same org, no group membership → `canRead`/
  `canUpdate` both deny, returns empty list/`false`, not an exception) — passed live against the real
  backend. Retrieve/tree/delete internals in `PageIndexUtil` unchanged.

### Environment/config fixes discovered while verifying (workstation switch, not code)

- Test DB port `15430`→`15432` and embedding server `localhost:8123`→`192.168.1.42:8123` (both moved with
  the DGX Spark) in `AccountManagerObjects7/src/test/resources/resource.properties`.
- `TestPageIndex`'s shared `contentAnalysis` chat config now points at **Ollama on the DGX Spark
  (`qwen3:8b`)** instead of Azure/OpenAI (those credentials are blank in the committed properties file).
  Renamed `ensureAzureContentAnalysis`→`ensureOllamaContentAnalysis` accordingly.

### Real bug found + fixed: hybrid-reasoning Ollama models need `think:false`, not just tag-stripping

`qwen3:8b` (and other Qwen3-family hybrid-reasoning models) defaults to **thinking-on** and returns
chain-of-thought prose inline with the answer — sometimes wrapped in literal `<think>...</think>`
markers, sometimes **not wrapped at all** (plain reasoning prose followed directly by the real answer).
This broke `PageIndexUtil`'s strict-JSON LLM-TOC parsing (`could not parse JSON from chat output`) and
polluted summaries. Two fixes, both worth knowing for Tier 2 (chat RAG reuses the same `Chat`/`callChat`
path):

1. **`Chat.chatInternal` (`Chat.java`) — the `think`-field wire gate was one-way.** It only ever forwarded
   `think:true` to Ollama and silently pruned anything else (`Boolean.TRUE.equals(req.get("think"))`),
   because some non-thinking local Ollama models reject the `think` parameter in *any* form — including
   `false` (`"<model> does not support thinking"`). Fixed to check `req.hasField("think")` instead (was
   the field **explicitly populated** by the caller, vs left at its schema default) — a caller that never
   touches `think` still gets the old safe-omit behavior; a caller that explicitly sets `true` **or**
   `false` now gets that value forwarded on the wire. This is what makes fix #2 possible.
2. **`PageIndexUtil.callChat`** now explicitly sets `req.set("think", false)` for `OLLAMA`-serviced chat
   configs, before every TOC/summarization call — the model no longer reasons at all, so there's no
   chain-of-thought prose to strip, parsing succeeds, and calls run noticeably faster/cheaper. Kept
   `stripThinking()` (regex-stripping `<think>…</think>`/`<thought>…</thought>`) as defense-in-depth for
   any config that still has thinking on, and added a `LLM-TOC: could not parse JSON…` diagnostic that
   logs a head/tail preview of the raw model output (beyond `snippet()`'s 60 chars) so a future failure of
   this class is diagnosable from logs alone.

**Net result:** `TestPageIndex` suite fully green (`Tests run: 6, Failures: 0, Skipped: 1` — only the
`PAGEINDEX_HEAVY`-gated Verse.docx case) and ~6x faster (no wasted reasoning tokens).

---

## Tier 1 — REST surface (Service7, transport only) — ✅ DONE (2026-07-09)

**Goal.** Expose build, gated retrieve, tree/node reads, count/delete so Tier 2/3 can consume them.

**Modules.** New `PageIndexService` (Service7); the Objects7 `AccessPoint`/`PageIndexUtil` wrappers from the
prerequisite (plus one addition — see below). Service7 stays transport (`AccessPoint` + `ServiceUtil` only).

**Built.** `AccountManagerService7/src/main/java/org/cote/rest/services/PageIndexService.java`, `@Path("/pageIndex")`,
`@DeclareRoles({"admin","user"})`, `@RolesAllowed({"user"})` on every endpoint — mirrors `VectorService`:

| Endpoint | Method | → |
|---|---|---|
| `/pageIndex/build/{type}/{objectId}` | GET | `AccessPoint.pageIndex` → boolean (no chunk args) |
| `/pageIndex/retrieve/{type}/{objectId}/{count}` (query = POST body) | POST | `AccessPoint.pageIndexRetrieve` |
| `/pageIndex/tree/{type}/{objectId}` | GET | `AccessPoint.pageIndexTree` |
| `/pageIndex/node/{objectId}` | GET | safe-projected single node via `AccessPoint.find` |
| `/pageIndex/node/{objectId}/children` | GET | safe-projected children via `AccessPoint.list` |
| `/pageIndex/count/{type}/{objectId}` | GET | `AccessPoint.pageIndexCount` (new, see below) |
| `/pageIndex/{type}/{objectId}` | DELETE | `AccessPoint.pageIndexDelete` |

**One addition beyond the prerequisite's original three wrappers:** `AccessPoint.pageIndexCount(user, model,
objectId)` — the plan didn't call out a dedicated wrapper for count, but exposing
`PageIndexUtil.countPageIndex` un-gated from REST would have violated "REST never bypasses PBAC." Added,
mirroring `pageIndexTree`'s `canRead` gate. Also made `PageIndexUtil.safeNodeRequestFields()` public (was
`requestNodeFields`, private) so the node/children endpoints request the identical non-embedding projection
used internally by `getTree`/`retrieve` — the node endpoints stay bespoke (NOT the generic
`/rest/model/data.pageIndexNode/{objectId}` route) exactly per the original plan: that generic route would
project the pgvector `VECTOR` field and throw `ReaderException`.

**Verified live** against the deployed WAR (`https://localhost:8443/AccountManagerService7/rest`), using
two real non-admin `system.user` accounts created via one admin-authenticated bootstrap call each (never
admin for the actual PageIndex calls — same discipline as the JUnit tests), session-cookie auth (JAAS
login, matching `e2e/helpers/api.js`'s `loginCtx`, not JWT — both are supported, this is what the existing
Playwright tests use):
- Owner (`test1`): build → `true`; retrieve → 2 ranked chunks with real descending cosine scores; tree →
  3 nodes (1 ROOT + 2 CHUNK), no `embedding` field anywhere in any response; node-by-objectId and
  node/children both correct; count → `3` (matches tree size).
- Cross-user (`test2`, same org, no access to `test1`'s doc): tree/retrieve → `[ ]` (denied, not an
  exception); delete → `false`; count afterward still `3` (untouched).
- Unauthenticated (no session): `403` (container-level `@RolesAllowed` enforcement).
- Owner delete → `true`; count afterward → `0`.

**Bug found during verification, unrelated to PageIndex (logged in `KnownIssues.md`):**
`CredentialService.newPrimaryCredential` (`CredentialService.java:82`) hardcodes the new credential's
password to the literal string `"password"` — `authReq.get(FieldNames.FIELD_CREDENTIAL)` (the password
actually sent in the request) is never read. Worked around for this verification by using `"password"` as
the effective test-user password; not fixed as part of this work (out of scope, security-sensitive, left
for a deliberate fix).

**Risks (carried forward, still true).** Retrieve re-embeds per node (known v1 inefficiency,
`PageIndexUtil.java:1038`) → latency-heavy; keep future automated tests single-threaded. Tree/node
endpoints must never project the VECTOR field — enforced via the shared `safeNodeRequestFields()`.

---

## Tier 2 — Chat + MCP bridge — ✅ DONE (2026-07-09)

**Goal.** Let a PageIndex-indexed doc be (a) navigated agentically via MCP and (b) injected as RAG into a
chat turn.

**Modules.** Objects7 `Am7ToolProvider` + `ChatUtil` (both already home to the vector equivalents). MCP stays
pure transport over `AccessPoint`/`PageIndexUtil`. No Service7 logic.

**Built — MCP (landed first, per the recommendation).** Four tools added to `Am7ToolProvider`
(`listTools`/`callTool`), all going through `AccessPoint` (never raw `PageIndexUtil`):
- `am7_pageindex_search` (`type`, `objectId`, `query`, `limit`) → `AccessPoint.pageIndexRetrieve` — ranked leaves.
- `am7_pageindex_structure` (`type`, `objectId`) → `AccessPoint.pageIndexTree` — full ROOT/SECTION/CHUNK outline
  with titles+summaries, each node tagged `(nodeId=<objectId>)` for follow-up calls.
- `am7_pageindex_section` (`nodeId`) → children of a node (`AccessPoint.list` with
  `PageIndexUtil.safeNodeRequestFields()` — the field-safety helper is shared with Service7's node endpoints).
- `am7_pageindex_content` (`nodeId`) → a leaf `CHUNK`'s text (errors if the node isn't a CHUNK, directing the
  caller to `am7_pageindex_section` first).

Both the search tool AND the structure/section/content trio were built, per the plan's recommendation.

**Built — Chat RAG (second, flag-gated).** `ChatUtil.getDataCitations`: after the per-`frec` vector-store
lookup, if the new `usePageIndex` chatConfig boolean is true (default false) and
`PageIndexUtil.countPageIndex(frec) > 0`, calls `PageIndexUtil.retrieve(frec, msg, 5)` and adds the leaves to
the same `vects` list that already flows through `getFilteredCitationText` — no new citation-formatting path.
`frec` already came from `AccessPoint.find` with the authorized user, so calling `PageIndexUtil.retrieve`
(the unauthenticated utility) directly at that point is correct, matching the plan's "do NOT double-authorize"
instruction. **Binding:** reuses `contextRefs` exactly as planned — no new binding model.

**Data/model impact.** Added `usePageIndex` (boolean) to `chatConfigModel.json`, next to `useJailBreak`.

**Bug fixed as a side effect (not in the original plan, found while wiring this up):**
`ChatUtil.getCitationText` unconditionally read `storeChunk.get(FieldNames.FIELD_VECTOR_REFERENCE_TYPE)` and
the embedded `vectorReference.id` — fields that exist on `data.vectorModelStore` but NOT on
`data.pageIndexNode` (which has `sourceReference`/`sourceReferenceType` instead). Reusing
`getFilteredCitationText` for PageIndex leaves therefore hit `BaseRecord.getEmbedded`'s "field absent from
schema" path, which logs an ERROR + a full stack trace (not a crash, but log pollution) on every PageIndex
citation. Fixed by guarding both reads with `hasField()` first — safe for any non-`vectorModelStore` record,
and behavior-identical for existing `vectorModelStore` callers (they still have both fields).

**Verified live** (all four tests below, `Tests run: 10, Failures: 0, Skipped: 1` combined with Tier 0/1's
`TestPageIndex`):
- `TestPageIndexCitations` (new, modeled on `TestContextRefs.java`) — two tests, deliberately differential:
  a doc that was **never vectorized**, only PageIndex-built. `usePageIndex=false` → 0 citations (proves the
  flag actually gates the branch, not just "some citation path happens to work"). `usePageIndex=true` → 2
  real PageIndex-derived citations, content-matched against the source text, well-formed
  `am7://.../data.pageIndexNode/{id}/citations/chunk/0` URIs, **zero error-log/stack-trace pollution**
  (confirms the `hasField` fix).
- `TestPageIndexMcp` (new, `objects/tests/mcp/` package — a dedicated file rather than extending
  `TestMcpMemory.java`, which is CardFox/Verse-vector-specific; same real-`McpSession`-plus-`callTool`
  pattern) — one test lists the four tools; one drives the full navigation chain (structure → section →
  content → search) against a real built index AND a cross-user PBAC-negative case (`testUser2` gets no
  structure, `"Found 0 PageIndex result"` from search) through the MCP dispatch layer itself, not just the
  underlying `AccessPoint` calls.

**Risks (carried forward, still true).** Per-turn RAG cost → default the flag off (done). `nodeType`
compares via `getEnum()` (already done, unchanged). Deferred: a `pageindex` `summaryPhase` vocabulary entry
for build-progress UI — not needed until Tier 3 has a build-progress affordance to drive it.

---

## Tier 3 — Ux752 — ✅ DONE (2026-07-09)

**Goal.** Build-index action, tree viewer, ranked query UI, and PageIndex as a chat knowledge source.

**Built.**
1. `src/workflows/pageIndex.js` — mirrors `vectorize.js`'s Dialog→REST→toast shape, but a plain
   `Dialog.confirm` (no chunk-type options form, since build takes none) → `GET /pageIndex/build/{type}/{objectId}`.
   Exports `pageIndex` (bound as `objectPage.pageIndex`, mirroring `objectPage.vectorize`) plus raw
   `buildPageIndex`/`deletePageIndex` helpers reused by the tree component's build/rebuild buttons.
2. `src/components/pageIndexTree.js` — a pure, directly-unit-tested `buildPageIndexTree(nodes)` function
   (flat `GET /pageIndex/tree/...` array → nested outline, matching `parentId`↔`id` by number not
   `objectId`, case-insensitive `nodeType`, orphans surfaced not dropped, sorted by level/ordinal) plus a
   `PageIndexTree` Mithril component: empty-state "Build Index", populated tree with expand/collapse +
   "Rebuild Index", and an inline ranked-query box (`POST /pageIndex/retrieve/...`, raw-text body via
   `serialize: v => v`, not JSON).
3. Mounted as a **custom form tab** (`forms.pageindex` in `formDef.js`, added to `data.data`/`data.note`'s
   tab lists) rather than a form command button — see deviation note below.
4. Chat integration: `usePageIndex` toggle added to `forms.chatConfig.fields` in `formDef.js` (mirrors how
   `useNLP`/`useJailBreak`/`prune` are exposed); `ContextPanel._attachTypes` gained a "Note" entry (`data.note`,
   the other PageIndex/vectorize-eligible model — Document was already present, Note was the gap). No new
   attach mechanism — both ride the existing `contextRefs` flow.
5. Model opt-in: `"pageIndex": true` added to both `data.data` and `data.note` in `modelDef.js` (hand-patched
   directly per Stephen — `modelDef.js` originates from `/rest/schema` but carries manual tweaks on top, so
   it's edited in place, never regenerated/refetched wholesale). `usePageIndex` boolean field also added to
   `olio.llm.chatConfig` in `modelDef.js`.

**Deviation from the plan (flagged, reasoned): a tab, not a command button.** The plan assumed a
`vectorize`-style one-shot action button. Investigation found `vectorize`'s own command button was already
removed from `formDef.js` in an earlier "Phase 13f" pass ("MCP + memory handles automatically") — and
PageIndex needs a genuine viewer + query surface, not a one-shot action, which doesn't fit a command button
regardless. Gave it a dedicated tab (`forms.pageindex`) instead. `objectPage.pageIndex` binding was kept
for parity/future use even though the tab is the primary entry point.

**Verified (independently re-run and confirmed, not just trusted from the report):**
- `npx vite build` → clean, `✓ built in ~9s`.
- `npx vitest run` → new `pageIndexTree.test.js` (7 tests: nested build, case-insensitive `nodeType`, orphan
  surfacing, level/ordinal sort, empty input, id-vs-objectId disambiguation) + updated `workflows.test.js`
  (16 tests, +1 for the `pageIndex` export) all pass. One pre-existing unrelated failure (`dialog.test.js`)
  confirmed via `git stash` to already fail on unmodified `main` — not a regression from this work.
- `npx playwright test e2e/pageIndex.spec.js` against the live stack (Tomcat :8443 + Vite :8899), using
  `ensureSharedTestUser()` (never admin), `--workers=1 --project=chromium`:
  - Default (non-LLM) describe — empty-state + Build-Index affordance + confirm-dialog-cancel wiring, and
    the `usePageIndex` chat-config toggle visibility — **2 passed**, re-run independently and confirmed.
  - `PAGEINDEX_E2E=1`-gated describe — real build (live LLM summarization) → tree renders → ranked query
    returns a scored leaf — **1 passed**; cross-checked against the live backend (`GET
    /pageIndex/count/data.note/{objectId}` → 3, matching the documented 1 ROOT + 2 CHUNK shape) to confirm
    the UI round-tripped a genuine build, not a false-positive toast.

**Risks (carried forward).** Enums lowercase on wire / UPPERCASE in Java — `nodeType` compared
case-insensitively throughout. `groupId` = numeric `.id`, never `.objectId`. The tree component fetches the
full flat `/pageIndex/tree/...` result rather than lazy-loading via `/pageIndex/node/{objectId}/children` —
fine for small/medium docs; revisit if a very large document's tree proves slow to render.

---

## Cross-cutting

- **Rebuild/staleness.** No auto-invalidation on source edit (same as vectors). `AccessPoint.pageIndex`
  already replaces an existing index (`PageIndexUtil.createPageIndex:115-120`), so "build" is idempotent;
  expose explicit `count`/`delete` (Tier 1) + a UX "Rebuild index" action (delete → build).
- **Retrieval quality (defer; note).** (a) LLM tree-search variant (reference reasoning retrieval) vs cosine —
  security-reviewer sign-off only if it adds policy/bias content. (b) Fix per-node live re-embed by reading
  stored vectors via VectorUtil's raw-SQL path (`PageIndexUtil.java:1038`). (c) Stronger embedding model note
  (local 768-dim clustered tightly on abstract prose).
- **OCR extraction gap (KI-§12 / design §12).** Scanned/image PDFs + custom-font encodings → empty/garbage
  extraction → empty tree. Surface a clear "no content extracted" error in build (endpoint + UX) rather than
  persisting an empty index. Future fix: an extraction-preprocessing service (Tesseract / Azure Document
  Intelligence) feeding clean text into `DocumentUtil` unchanged — NOT part of this plan.

---

## Dependency sequencing

1. ✅ **PBAC-gate retrieval** (`AccessPoint.pageIndexRetrieve` + `pageIndexTree`/`pageIndexDelete` +
   `PageIndexUtil.getTree`) — hard prerequisite, blocks everything. **Done 2026-07-09.**
2. ✅ **Tier 1 REST** — depends on #1 (satisfied); prerequisite for Tier 3. **Done 2026-07-09**, live-verified.
3. ✅ **Tier 2** — MCP + chat depend on the Objects7 wrappers from #1 (MCP is in Objects7, doesn't need REST).
   MCP navigation landed before flag-gated RAG injection, per the recommendation. **Done 2026-07-09**,
   live-verified. PageIndex can now be tested end-to-end from a chat conversation (attach a doc via
   `contextRefs`, flip `usePageIndex` on the chat's chatConfig) with no new UI.
4. ✅ **Tier 3 Ux752** — depends on Tier 1 REST being deployed (satisfied). **Done 2026-07-09**, live-verified
   (vite build, vitest, and Playwright e2e against the live stack, both the default and `PAGEINDEX_E2E`-gated
   describes). Build/tree/query are now reachable directly from the Ux, and the `usePageIndex` toggle +
   PageIndex-eligible attach types are exposed in the chat UI.

**All four items are done. The PageIndex feature is fully integrated end to end as of 2026-07-09.**

## Layering confirmation

Business logic stays in Objects7 (`PageIndexUtil`, `AccessPoint`, `ChatUtil`, `Am7ToolProvider`). Service7
`PageIndexService` is pure transport (`AccessPoint` + `ServiceUtil`). MCP is transport over `IToolProvider`,
touching only `AccessPoint`/`PageIndexUtil`. Ux752 consumes REST at `https://localhost:8443`. No ISO in
Objects7; no upward dependency; REST never bypasses PBAC — the prerequisite closes the one bypass.

## Key files to change / add

- **Objects7 (prerequisite + Tier 1 + Tier 2, done):** `client/AccessPoint.java` (+`pageIndexRetrieve`/
  `pageIndexTree`/`pageIndexDelete`/`pageIndexCount`), `util/PageIndexUtil.java` (+`getTree`, +public
  `safeNodeRequestFields`, `stripThinking`, `think:false` on Ollama calls, wider parse-failure diagnostics),
  `olio/llm/Chat.java` (`think`-field wire-gate fix — `hasField` tri-state, not `==true`),
  `olio/llm/ChatUtil.java` (PageIndex branch in `getDataCitations` gated by `usePageIndex`; `hasField` guard
  in `getCitationText` for non-`vectorModelStore` records), `mcp/server/Am7ToolProvider.java` (+4 tools:
  `am7_pageindex_search`/`_structure`/`_section`/`_content`), `resources/models/olio/llm/chatConfigModel.json`
  (+`usePageIndex` boolean).
- **Service7 (done):** `rest/services/PageIndexService.java` — build/retrieve/tree/node/node-children/count/delete,
  all `@RolesAllowed({"user"})`, live-verified.
- **Ux752 (done):** `src/workflows/pageIndex.js` (new), `src/components/pageIndexTree.js` (new),
  `src/workflows/index.js`, `src/views/object.js`, `src/chat/ContextPanel.js` (+"Note" attach type),
  `src/core/modelDef.js` (+`pageIndex: true` on `data.data`/`data.note`, +`usePageIndex` on `chatConfig`,
  hand-patched not regenerated), `src/core/formDef.js` (+`forms.pageindex` tab, +`usePageIndex` toggle),
  `src/test/pageIndexTree.test.js` (new), `src/test/workflows.test.js`, `e2e/pageIndex.spec.js` (new),
  `e2e/helpers/api.js` (+`createObject` export).
- **Tests (done):** `TestPageIndex` (`TestPageIndexAccessPointGate`), `TestPageIndexCitations` (new — flag
  off/on differential), `objects/tests/mcp/TestPageIndexMcp` (new — dedicated file, not an extension of
  `TestMcpMemory.java`, which is CardFox/Verse-vector-specific; real `McpSession` + `callTool` dispatch,
  navigation chain + cross-user PBAC negative), `pageIndexTree.test.js` (new, tree-reconstruction unit
  tests), `e2e/pageIndex.spec.js` (new, live against Tomcat+Vite). REST (Tier 1) verified live via curl
  rather than a JUnit `RestIntegrationTest` — no existing Java harness for session/JWT login exists in this
  repo yet; worth adding if REST verification becomes routine.
