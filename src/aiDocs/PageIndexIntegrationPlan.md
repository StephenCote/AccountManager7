# PageIndex Integration Plan — REST, Chat, MCP, Ux752

**Status:** plan (not started) · **Date:** 2026-07-08 · **Depends on:** `PageIndexDesign.md` (core engine — implemented & verified)

The PageIndex **engine** is complete in Objects7 (build via `AccessPoint.pageIndex`, retrieve via
`PageIndexUtil.retrieve`, hierarchical `data.pageIndexNode` tree, LLM-TOC + summaries + cosine ranking).
This plan adds the four **surfaces above Objects7** that the engine currently lacks — REST, chat RAG,
MCP, and the Ux752 UI — each mirroring a proven **vector/RAG twin** that already exists at every layer.

Everything below stays within the architecture rules: business logic in Objects7, Service7 as pure
transport, MCP marshaled through the existing `IToolProvider` boundary, Ux752 consuming REST. The one
place the rules are currently bent (retrieval bypasses PBAC as a utility) is closed by the prerequisite
before any surface exposes it.

---

## Current state

| Layer | Vector/RAG (exists) | PageIndex |
|---|---|---|
| Objects7 build (PBAC) | `AccessPoint.vectorize` (`AccessPoint.java:677-727`) | ✅ `AccessPoint.pageIndex` (`:729-772`) |
| Objects7 retrieve | `VectorUtil.findByEmbedding` (`VectorUtil.java:173-246`) | ✅ `PageIndexUtil.retrieve` (`:941-964`, cosine tree-walk) |
| REST | `VectorService` (`/vector/vectorize`, `/vector/reference`) | ❌ none |
| Chat RAG injection | `ChatUtil.getDataCitations` → prepended to user turn | ❌ none |
| MCP tool | `am7_vector_search` (`Am7ToolProvider.java:113-162`) | ❌ none |
| Ux752 | `vectorize.js` workflow + `ContextPanel` attach | ❌ none |

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

## PREREQUISITE (blocks Tier 1/2/3) — PBAC-gate retrieval

`PageIndexUtil.retrieve(model, query, limit)` (`PageIndexUtil.java:941`) takes no `user` and reads via
`IOSystem…getSearch()` directly (documented utility bypass). It must not reach REST/MCP/chat un-gated
(security + architect flagged this).

- **Module:** Objects7 only. Legal.
- **Add** `AccessPoint.pageIndexRetrieve(BaseRecord user, String model, String objectId, String query, int limit)`
  next to `pageIndex()`: resolve the source record with `find(user, q)` (same query shape as `pageIndex`
  `:732-736`), run `getAuthorizationUtil().canRead(user, user, rec)` (mirror the `canUpdate` gate `:747`),
  then call `PageIndexUtil.retrieve(rec, query, limit)`. Audit via `ActionEnumType.READ`. Authorizing the
  source doc once is sufficient — nodes are grouped in the source's group, so their reads inherit that PBAC.
- **Also add** PBAC wrappers used by the tree-read/rebuild surfaces: `AccessPoint.pageIndexTree(user, type, objectId)`
  (`find`→`canRead`→ new `PageIndexUtil.getTree(model)`) and `AccessPoint.pageIndexDelete(user, type, objectId)`
  (`find`→`canUpdate`→`PageIndexUtil.deletePageIndex`). `canUpdate` is deliberate (not `canDelete`): the
  index is **derived data on the source doc**, so delete is gated on modify-rights to the source — consistent
  with build (`pageIndex` also uses `canUpdate`).
- **`PageIndexUtil.getTree(model)`** (new): returns the nested node tree, reusing `loadRoots`/`loadChildren`
  (`:971,981`) which already project safe fields and omit the un-projectable VECTOR embedding (`:989-1001`).
- **Verify:** extend `TestPageIndex` — positive (shared test user) + negative (second user → `canRead` denies)
  around `pageIndexRetrieve`. Retrieve internals unchanged.

---

## Tier 1 — REST surface (Service7, transport only)

**Goal.** Expose build, gated retrieve, tree/node reads, count/delete so Tier 2/3 can consume them.

**Modules.** New `PageIndexService` (Service7); the Objects7 `AccessPoint`/`PageIndexUtil` wrappers from the
prerequisite. Service7 stays transport (`AccessPoint` + `ServiceUtil` only).

**Steps.**
1. New `AccountManagerService7/src/main/java/org/cote/rest/services/PageIndexService.java`, `@Path("/pageIndex")`,
   `@DeclareRoles({"admin","user"})` — auto-scanned. Mirror `VectorService` structure.
2. **Build** (mirror `VectorService.java:48-64`): `@RolesAllowed({"user"}) @GET /build/{type}/{objectId}` →
   `ServiceUtil.getPrincipalUser` → `AccessPoint.pageIndex(user, type, objectId)` → boolean. **No chunk args.**
   Return a clear "no content extracted" signal when extraction yields nothing (OCR gap, see cross-cutting).
3. **Retrieve** (mirror `:94-119`): `@RolesAllowed({"user"}) @POST /retrieve/{type}/{objectId}/{count}` with the
   query as the POST body → `AccessPoint.pageIndexRetrieve(user, type, objectId, query, count)` →
   `JSONUtil.exportObject(results, RecordSerializerConfig.getForeignUnfilteredModuleRecurse())`.
4. **Tree/node reads** (also MCP primitives): `@GET /tree/{type}/{objectId}` → `AccessPoint.pageIndexTree`;
   `@GET /node/{objectId}` and `@GET /node/{objectId}/children` (resolve `data.pageIndexNode` via
   `AccessPoint.find`/`findByObjectId` — node carries `groupId`, group-shortcut PBAC applies). Must reuse
   `requestNodeFields` (never project `FIELD_EMBEDDING`). **These stay bespoke (NOT the generic
   `/rest/model/data.pageIndexNode/{objectId}` route) on purpose:** the generic route would project the
   pgvector `VECTOR` field and throw `ReaderException` (`PageIndexUtil.java:989-1001`); the bespoke endpoints
   guarantee `requestNodeFields` with the embedding omitted. Do not later "simplify" them onto `ModelService`.
5. **Count/delete** (rebuild flow): `@GET /count/{type}/{objectId}`, `@DELETE /{type}/{objectId}` →
   `AccessPoint.pageIndexDelete`.
6. `@RolesAllowed` on every endpoint.

**Verify.** Live REST integration test per `service7-reference.md`: JWT via `/rest/login/token` as
`ensureSharedTestUser()`; `GET /pageIndex/build` then `POST /pageIndex/retrieve` → 200 + non-empty; 403 for a
second user; 401 unauthenticated. Backend `AccessPoint` wrappers covered in `TestPageIndex`. Build:
`mvn -o -pl AccountManagerObjects7 install -DskipTests` → `mvn -o -pl AccountManagerService7 compile`; Stephen
redeploys the WAR. Never reset schema.

**Risks.** Retrieve re-embeds per node (known v1 inefficiency, `PageIndexUtil.java:1038`) → latency-heavy;
document it, keep tests single-threaded. Tree endpoints must never project the VECTOR field.

---

## Tier 2 — Chat + MCP bridge

**Goal.** Let a PageIndex-indexed doc be (a) navigated agentically via MCP and (b) injected as RAG into a
chat turn.

**Modules.** Objects7 `Am7ToolProvider` + `ChatUtil` (both already home to the vector equivalents). MCP stays
pure transport over `AccessPoint`/`PageIndexUtil`. No Service7 logic.

**Recommendation.** Land **MCP navigation first**, treat **RAG injection as opt-in** (chatConfig flag).
Rationale: `getDataCitations` runs every turn and PageIndex retrieve is expensive (per-node re-embed);
MCP navigation is pull-based (LLM calls only when needed) and matches the reference's "reason over
structure" design.

**Steps — MCP (first).**
1. In `Am7ToolProvider.listTools` (`:36-78`) add three tools (schemas via a new `buildPageIndex*Schema`
   like `:537-551`):
   - `am7_pageindex_structure` (`type`,`objectId`) → `AccessPoint.pageIndexTree` — titles+summaries tree.
   - `am7_pageindex_section` (`nodeId`) → children of a node.
   - `am7_pageindex_content` (`nodeId`) → a leaf CHUNK's `content`.
   (Or a single `am7_pageindex_search` mirroring `vectorSearch` `:113-162` → `AccessPoint.pageIndexRetrieve`;
   recommend BOTH the search tool and the structure/section/content navigation trio.)
2. Add `case` handlers in `callTool` (`:82-107`), each using `session.getUser()` through the `AccessPoint`
   wrappers (PBAC), never raw `PageIndexUtil`. (No `CompositeToolProvider` change — tools live on the
   existing `Am7ToolProvider`.)

**Steps — Chat RAG (second, flag-gated).**
3. Extend `ChatUtil.getDataCitations` (`:1454-1494`): after the `vu.find(...)` per-ref loop (`:1480`), for each
   resolved `frec` with `PageIndexUtil.countPageIndex(frec) > 0`, call `PageIndexUtil.retrieve(frec, msg, N)`
   and append leaves through the same `getFilteredCitationText` path (`:1495-1499`). `frec` already came from
   `AccessPoint.find` with the authorized `user` (`:1434`), so call `PageIndexUtil.retrieve` directly here — do
   NOT double-authorize. Gate the whole block behind a new chatConfig boolean (infer `usePageIndex`).
4. **Binding:** reuse `contextRefs` (`chatRequestModel.json:79-84`) — a doc already in `contextRefs` flows into
   `getDataCitations`; PageIndex piggybacks on the same resolved `frec`. No new binding model.

**Data/model impact.** Infer one boolean `usePageIndex` on the chat config model (mirror existing RAG flags)
if RAG injection is opt-in. No new models for MCP.

**Verify.** `TestPageIndexCitations` modeled on `TestContextRefs.java`: build a doc's index, add it as a
contextRef, call `getDataCitations(testUser, oreq, creq)`, assert PageIndex-derived citations appear. MCP: extend
`objects/tests/mcp/TestMcpMemory.java` to call the new tools and assert structure/section/content. Shared test
user, single-threaded, LLM-flag-gated.

**Risks.** Per-turn RAG cost → default the flag off. `nodeType` compares via `getEnum()` (already done). If the
UI shows build progress, the contextRef `summaryPhase` vocabulary (`contextRefModel.json`) may need a
`pageindex` phase.

---

## Tier 3 — Ux752

**Goal.** Build-index action, tree viewer, ranked query UI, and PageIndex as a chat knowledge source.

**MANDATORY first step (project rule).** Read the vector/RAG UI before writing: `src/workflows/vectorize.js`,
`src/views/object.js:912-915`, `src/chat/ContextPanel.js` (attach flow, `_attachTypes:274-281`, `summaryPhase`),
`src/core/modelDef.js:4761` (`"vectorize": true`).

**Modules.** Ux752 only; consumes REST.

**Steps.**
1. New `src/workflows/pageIndex.js` mirroring `vectorize.js` (Dialog → `GET /pageIndex/build/{type}/{objectId}`
   with `withCredentials`, toast on result) — **no chunk-type args**. Export from `workflows/index.js`; bind in
   `object.js` as `objectPage.pageIndex` (mirror `:915`).
2. New `src/components/pageIndexTree.js`: fetch `GET /pageIndex/tree/{type}/{objectId}`, render
   ROOT→SECTION→CHUNK (title + summary, expandable, drill to leaf `content`; lazy-load big trees via
   `/pageIndex/node/{objectId}/children`).
3. Query UI: input → `POST /pageIndex/retrieve/{type}/{objectId}/{count}` (query in body) → ranked leaves
   (score, title, content).
4. Chat knowledge source: add a **PageIndex attach option** to `ContextPanel._attachTypes` (`:274-281`); reuse
   the `contextRefs` attach flow. If `usePageIndex` config flag is added, expose a chat-config toggle in
   `formDef.js`. Consider mirroring `autoVectorize`-on-attach (`ChatService.java:1082-1104`) with an optional
   auto-build-page-index on attach.
5. Model opt-in: add `"pageIndex": true` beside `"vectorize": true` in `modelDef.js` for relevant models
   (`data.data`, `data.note`) to drive the action affordance (consumed like `isVectorize()`).

**Verify.** `npx vite build` + `npx vitest run`, then `e2e/pageIndex.spec.js` with `ensureSharedTestUser()`
(never admin): select a doc → build → success toast; open tree viewer → nodes render; run a query → ranked
results. LLM-touching paths `--workers=1`, gated behind the LLM env flag.

**Risks.** Enums lowercase on wire / UPPERCASE in Java, list projections may return raw lowercase — compare
`nodeType` case-insensitively in JS. `groupId` in UI queries = numeric `.id`, not objectId.
`/rest/model/search` is cached — `cache:false` for freshly-built trees.

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

1. **PBAC-gate retrieval** (`AccessPoint.pageIndexRetrieve` + `pageIndexTree`/`pageIndexDelete` +
   `PageIndexUtil.getTree`) — hard prerequisite, blocks everything.
2. **Tier 1 REST** — depends on #1; prerequisite for Tier 3.
3. **Tier 2** — MCP + chat depend on the Objects7 wrappers from #1 (MCP is in Objects7, doesn't need REST).
   MCP navigation before flag-gated RAG injection.
4. **Tier 3 Ux752** — depends on Tier 1 REST being deployed.

## Layering confirmation

Business logic stays in Objects7 (`PageIndexUtil`, `AccessPoint`, `ChatUtil`, `Am7ToolProvider`). Service7
`PageIndexService` is pure transport (`AccessPoint` + `ServiceUtil`). MCP is transport over `IToolProvider`,
touching only `AccessPoint`/`PageIndexUtil`. Ux752 consumes REST at `https://localhost:8443`. No ISO in
Objects7; no upward dependency; REST never bypasses PBAC — the prerequisite closes the one bypass.

## Key files to change / add

- **Objects7:** `client/AccessPoint.java` (+`pageIndexRetrieve`/`pageIndexTree`/`pageIndexDelete`),
  `util/PageIndexUtil.java` (+`getTree`), `olio/llm/ChatUtil.java` (PageIndex branch in `getDataCitations`),
  `mcp/server/Am7ToolProvider.java` (+ tools). Chat config model (+`usePageIndex`, infer).
- **Service7 (new):** `rest/services/PageIndexService.java`.
- **Ux752 (new/edit):** `src/workflows/pageIndex.js`, `src/components/pageIndexTree.js`, `src/workflows/index.js`,
  `src/views/object.js`, `src/chat/ContextPanel.js`, `src/core/modelDef.js`, `src/core/formDef.js`,
  `e2e/pageIndex.spec.js`.
- **Tests:** `TestPageIndex` (gate cases), `TestPageIndexCitations` (new), `objects/tests/mcp/TestMcpMemory`
  (extend), REST integration test, `e2e/pageIndex.spec.js`.
