# PageIndex — Design

**Status:** design (approved, not yet implemented) · **Date:** 2026-07-07 · **Target module:** AccountManagerObjects7

A hierarchical table-of-contents index capability for Objects7: for any model that opts in, build and
persist a tree of summary nodes (root → sections → leaf chunks) over the model's document content, and
provide a reasoning-based tree-walk retrieval API — a complement to the existing flat vector embeddings.

Modeled on [PageIndex (VectifyAI)](https://github.com/VectifyAI/PageIndex); reference source studied
locally at `C:\Projects\GitHub\PageIndex`. The concept: retrieval by navigating an explicit document
structure ("similarity ≠ relevance") instead of, or in addition to, flat vector similarity.

This design mirrors the **existing embedding/vector pattern** in Objects7 rather than inventing new
machinery. It was produced via planner → architect (APPROVED) → reconciliation against the real
reference source. Layering is legal: everything lives in Objects7, no ISO/Service7 knowledge, no upward
dependency.

---

## 1. How embeddings work today (the pattern being mirrored)

Ground truth, verified in code — this shapes the whole design:

- **Opt-in is a root model-JSON attribute**, not a field and not an inherited abstract model:
  `"vector": "org.cote.accountmanager.olio.VectorProvider"` → parsed into `ModelSchema.vector`
  (`ModelSchema.java:42,70-76`). Example: `models/olio/charPersonModel.json:12`.
- **The provider only does `describe()`** (content extraction). `VectorProvider.describe()`
  (`olio/VectorProvider.java:27`) builds the text to embed; its `provide()` lifecycle methods are
  **empty** (`:64-75`). The provider does **not** persist on record CREATE/UPDATE.
- **Vectorization is triggered explicitly**, not by persistence: `AccessPoint.vectorize(user, model,
  objectId, chunkType, chunkSize)` (`client/AccessPoint.java:676-726`) — PBAC-gated `find` →
  `canUpdate` → `createVectorStore` → `writer.write`, audited via `ActionEnumType.VECTORIZE`.
- **Chunking + embedding** live in `util/VectorUtil.java` (`createVectorStore:312`, chunkers
  `chunkBySentence/chunkByWord/chunkByChapter` `:380-498`, `generateEmbeddings:501`).
- **Persistence model:** `common.vectorExt` (abstract, `models/common/vectorExtModel.json`) supplies
  `content` (EncryptFieldProvider, `encrypt:false`), `embedding` (`type:"vector"`, maxLength 768,
  pgvector), `chunk`, `chunkCount`, and inherits `crypto.vaultExt`. `data.vectorModelStore`
  (`models/data/vectorModelStoreModel.json`) inherits it and adds a polymorphic
  `vectorReference`/`vectorReferenceType` (`$flex` foreign) + ephemeral `score`. Vector models are
  discovered at runtime via `ms.inherits(MODEL_VECTOR_EXT)` (`VectorUtil.getVectorModels:107`).
- **Content extraction dispatcher:** `DocumentUtil.getStringContent()` (`util/DocumentUtil.java:167`)
  — if `ms.getVector() != null`, instantiate the provider via `ProviderUtil.getProviderInstance()`
  and call `describe()`; else fall back to byteStore/PDF/text extraction.
- **Light-service transport:** `tools/EmbeddingUtil.java` posts JSON over HTTP via `ClientUtil.post`
  to the DGX Spark Python service (`/generate_embedding`, `/generate_summary`, `/extract_keywords`,
  etc.), configured by `test.embedding.{type,server,authorizationToken}` and wired at startup
  (`RestServiceEventListener.java:222`, Objects7 `BaseTest.java:155`, `ConsoleMain.java:127`,
  `Assistant.java:78`). `VoiceUtil` is the same pattern for TTS/STT.
- **Hierarchy conventions:** `common.parent` (`models/common/parentModel.json`) = self-referential
  `parentId` with `$notInParent`; `HierarchyValidator.checkHierarchy(record, FIELD_PARENT_ID)` guards
  cycles; `PathProvider` computes `path` by walking the parent chain.

---

## 2. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Trigger | **Explicit API `AccessPoint.pageIndex(...)`**, not a CREATE/UPDATE persistence hook | Matches the proven `vectorize()` pattern. Building fires LLM/light-service network calls; putting that in the write path couples every source-record write to DGX Spark latency/outages. "Automatic" is satisfied by an orchestration layer above persistence (a caller/agent invoking the API after create, ideally async). *(Confirmed by Stephen 2026-07-07.)* |
| Retrieval scoring | **Embedding cosine** (default) | Self-contained; no new LLM prompt path, no security-review gate. LLM section-selection (the reference's true reasoning approach) is a deferred variant — it would require routing through `resources/olio/llm/` templates + security-reviewer sign-off. |
| Node storage | **Inherit `common.vectorExt`** for `content`/`embedding`/`chunk` | Architect fix — reuse the canonical vector-storage mixin instead of re-declaring those fields (avoids dimension/vault drift). |
| Group placement | **Grouped, in the source record's group** (via `data.directory`) | A record with a `groupId` gets the group-only PBAC shortcut; groupless forces field/role-level checks and foreign-field role-grant pain. Keeps retrieval PBAC coherent with the indexed document. |
| Provider package | `org.cote.accountmanager.provider.PageIndexProvider` | Alongside `PathProvider`/`EncryptFieldProvider`/`ComputeProvider` (better placed than `VectorProvider`, which sits under `olio`). |

---

## 3. New models (`models/data/`)

### `data.pageIndexNode` (`pageIndexNodeModel.json`) — the tree node

Inherits: `common.parent` (tree + cycle guard), `data.directory` (`groupId`), **`common.vectorExt`**
(`content`, `embedding`, `chunk`, `chunkCount`, `crypto.vaultExt`), plus the standard objectId/
organization mixins.

New fields (the genuinely novel ones):

| Field | Type | Purpose |
|---|---|---|
| `nodeType` | enum (`PageIndexNodeEnumType`: ROOT/SECTION/CHUNK) | node role; read via `getEnum()` (lowercase on wire, UPPERCASE in Java) |
| `title` | string | section heading / node label |
| `summary` | string | LLM/light-service section summary (reasoning-traversal layer) |
| `startOffset` / `endOffset` | int | offsets into the source (char offsets for text/MD; page indices for PDF) |
| `level` | int | tree depth |
| `ordinal` | int | sibling order |
| `sourceReference` | `$flex` foreign (`foreignType: "sourceReferenceType"`) | back-ref to the source record — mirrors `vectorModelStore.vectorReference` |
| `sourceReferenceType` | string | schema name of the source record |

- `content` (leaf chunk text) and optional `embedding` (title+summary embedding for the cosine
  shortlist) come **from `common.vectorExt`** — do not re-declare.
- `query`: `["parentId", "sourceReference", "sourceReferenceType", "nodeType", "level", "ordinal"]`.
- `hints`: `["sourceReference, sourceReferenceType"]`.
- `constraints`: `"sourceReference, sourceReferenceType, ordinal, parentId, organizationId"`.
- Table `A7_data_pageIndexNode_0` — verify with `DBUtil.getTableName(...)`; never hand-write.

### `data.pageIndex` (`pageIndexModel.json`) — optional container/root descriptor

Analogous to a vector list: `sourceReference`/`sourceReferenceType`, `rootNode` foreign ref,
`nodeCount`, generation timestamp/model used. Optional — the node tree alone (via `parentId` +
`sourceReference`) is sufficient; include only if a single addressable per-document handle is wanted.

### Constants / enums

- `ModelNames.MODEL_PAGE_INDEX`, `MODEL_PAGE_INDEX_NODE`.
- New `FieldNames.FIELD_*` where absent (`SOURCE_REFERENCE`, `SOURCE_REFERENCE_TYPE`, `SUMMARY`,
  `START_OFFSET`, `END_OFFSET`, `LEVEL`, `ORDINAL`, `NODE_TYPE`) — reuse existing where present.
- `PageIndexNodeEnumType` under `schema/type`.
- `ActionEnumType.PAGE_INDEX` — follow the manual `VECTORIZE` precedent (`ActionEnumType.java:68`) and
  hand-maintain the `@XmlEnum` annotation like the peer enums (there are no `.xsd` files in the repo, so
  there is no source schema to regenerate from — the "add to XSD" step does not apply).
- Being core `data.*` models, they load with the standard `ModelNames` set (no `OlioModelNames.use()`).

---

## 4. Opt-in mechanism

- Add `ModelSchema.pageIndex` attribute + getter/setter, exactly parallel to `.vector`
  (`ModelSchema.java:42,70-76`).
- A model opts in with `"pageIndex": "org.cote.accountmanager.provider.PageIndexProvider"` at the JSON
  root, just like `"vector": "...VectorProvider"`.
- `PageIndexProvider implements IProvider`: `describe()` delegates to
  `DocumentUtil.getStringContent(model)` (reuses PDF/docx/text handling); a model may ship a subclass
  with a custom `describe()` for structured records (as `VectorProvider` special-cases narratives).
  `provide()` lifecycle methods left **empty** (explicit trigger).

---

## 5. Trigger API

`AccessPoint.pageIndex(user, model, objectId)` — mirrors `vectorize()` (`AccessPoint.java:676`):
PBAC-gated `find` → `canUpdate` → `PageIndexUtil.createPageIndex(rec)` → `writer.write(nodes)`, audited
via `ActionEnumType.PAGE_INDEX`. Plus `countPageIndex` / `deletePageIndex` (mirroring
`VectorUtil.countVectorStore` / `deleteVectorStore`) for clean rebuilds. You call the API to (re)build
an object's index — same contract as vectorize. A REST surface on `ModelService`/a new action is a
later, optional Service7 change (out of scope here).

---

## 6. Build algorithm (`PageIndexUtil`, companion to `VectorUtil`)

1. **Extract** content via `DocumentUtil.getStringContent(model)`.
2. **Structural split** into a tree: `ROOT` → `SECTION`(s) → `CHUNK` leaves, tracking
   `startOffset`/`endOffset`, `level`, `ordinal`. See the reference-porting notes (§8) — the
   header/markdown builder ports cleanly to Java; the PDF TOC pipeline is the pluggable Python seam.
3. **Summarize interior nodes** bottom-up (leaf → section → root) via the **`Chat` /
   `LLMConnectionManager` client** using the reference's summary prompt (§8, portable verbatim). The
   Chat client is **connection-configured and provider-agnostic** — it resolves to OpenAI or a local
   model per the configured connection, so summarization is NOT hard-wired to any provider. Do **not**
   use `EmbeddingUtil.getSummary()` (`/generate_summary`) — that endpoint is local-only (returns null
   for non-LOCAL service types) and would wrongly couple summaries to the local service. Degrade
   gracefully if the summary call errors/returns empty (fall back to a truncated leading content
   excerpt) so a node summary is never silently null.
4. **Optionally embed** each node's title+summary (via `EmbeddingUtil.getEmbedding`) to populate the
   `embedding` field for the cosine shortlist.
5. **Persist** all nodes as a batch (`getWriter().write(BaseRecord[])`), `parentId` links set after the
   root is written; `HierarchyValidator.checkHierarchy` guards cycles.

## 7. Retrieval (`PageIndexUtil`, embedding-cosine default)

`List<BaseRecord> retrieve(BaseRecord model, String query, int limit)`:

1. Load the root node(s) for `sourceReference`/`sourceReferenceType` via `QueryUtil.createQuery` with
   an **explicit `organizationId`** condition (required — `data.directory`-derived queries else fail
   PBAC with "Group could not be found"); callers set `cache:false` for fresh reads.
2. **Reasoning descent:** at each level, score child `title`+`summary` embeddings (cosine, via
   `EmbeddingUtil`) against the query; descend into the top-k relevant children; recurse to leaves.
3. Return the matching leaf `CHUNK` nodes (and/or their ancestor summaries) up to `limit`.

In-process — it repeatedly reads child nodes on each hop, so retrieval **must** stay in Objects7. It
reads via `IOSystem.getActiveContext().getSearch()` directly — the documented **utility-bypass** pattern
(same as `VectorUtil`), NOT through `AccessPoint`. `retrieve()` takes no `user` and does not enforce
PBAC on the reads; today it has no caller outside tests and no REST surface, so this is not exploitable.
**Rule:** any future REST/Service7 exposure of `retrieve()` MUST gate through `AccessPoint.find`/`canRead`
(resolve the source record's authorization first, as the build path `AccessPoint.pageIndex` already does)
or add a `user` param and authorize the node reads. Node queries (`loadRoots`/`loadChildren`/`countPageIndex`/
`deletePageIndex`) carry an explicit `organizationId` condition and `cache:false` for fresh reads.

**Known v1 inefficiency:** the persisted node `embedding` cannot be projected by the standard reader
(VECTOR type), so `nodeEmbedding()` re-embeds each visited node's title+summary live during retrieval —
an extra embedding call per node, and the stored embedding is never read back. Acceptable v1; a follow-up
would read vectors via the raw-SQL path `VectorUtil` uses.

**Deferred variant — LLM tree-search:** the reference's canonical retrieval is LLM-driven traversal.
Its prompt (returns `{"thinking": ..., "node_list": [node_id, ...]}` given the tree + query) is
available verbatim if higher quality is wanted later; not in v1.

**Prompt-location convention (reconciled with precedent).** The shipped summary + TOC prompts are inline
Java constants in `PageIndexUtil` — this is **consistent with established Objects7 precedent**:
`ChatUtil` keeps ad-hoc *structural/creative* task prompts inline (`autoScenePrompt`, `vprompt`,
`iprompt`, the summarize sysPrompt). The `resources/olio/llm/` templates are reserved for
**policy/bias/narrative-shaping** prompts (prompt configs, the `compliance.json` bias directive, narrate
templates) that ISO 42001 / security must maintain centrally. So the rule is: a new prompt path that
carries policy/bias or reshapes narrative MUST live under `resources/olio/llm/` and get security-reviewer
sign-off; a plain structural instruction (like these) may be an inline constant. The deferred LLM
tree-search variant would need that gate only if it introduces policy/bias content.

---

## 8. Reference-porting notes (`C:\Projects\GitHub\PageIndex`)

The reference has **two independent tree builders** — this drives the bake-in-Java vs. Python split:

- **Markdown/header builder** (`pageindex/page_index_md.py`) — **fully deterministic, zero LLM.**
  ATX-header regex (`^(#{1,6})\s+(.+)$`, skipping fenced code) → stack-based level-nesting outline
  builder → optional small-node thinning. **Ports to Java almost verbatim** and is the basis for the
  Java structural splitter (text/markdown/docx-derived content).
- **PDF builder** (`pageindex/page_index.py`, ~1150 lines) — **heavily LLM-driven**: per-page TOC
  detection, TOC transformation, physical-page-offset matching, self-healing verification with
  fallback across three modes (with-page-numbers → no-page-numbers → no-TOC), recursive large-node
  splitting. We took the concept of its **no-TOC generate path** (`generate_toc_init`/`continue`) and
  reimplemented it in Java as the LLM-TOC builder (see §5/§9) — NOT via Python. **Content extraction is
  handled by the existing Objects7 `DocumentUtil.getStringContent` (Java, PDF + DOCX + text); no Python
  is used anywhere in the implementation.**

**Node schema (reference → Objects7):** `title`→`title`; `node_id` (4-digit)→`objectId`/`ordinal`
(keep `node_id` only if mirroring their traversal); `start_index`/`end_index` (PDF pages) or `line_num`
(MD)→`startOffset`/`endOffset`; `summary`/`prefix_summary`→`summary`; `text` (leaf)→`content`;
`nodes` (children)→`parentId` tree.

**Reusable prompts (inline f-strings, portable verbatim):**
- Node summary (`utils.py:579`): *"You are given a part of a document, your task is to generate a
  description of the partial document about what are main points covered… Directly return the
  description, do not include any other text."*
- LLM tree-search (`examples/tutorials/tree-search/README.md`): returns
  `{"thinking": ..., "node_list": [...]}` given the query + tree.

**Config knobs worth mirroring** (`config.yaml`): `max_page_num_each_node` / `max_token_num_each_node`
(recursive-split threshold — split only if a node exceeds **both**), `if_add_node_summary`,
`if_add_node_text`, summary token threshold (below it, MD stores raw text as the summary).

**Deps (Python side, if the light-service route is taken):** LiteLLM, PyMuPDF/PyPDF2, python-dotenv,
pyyaml. The LLM abstraction is a thin LiteLLM wrapper — swappable for any client; every prompt expects
strict JSON.

---

## 9. Implementation is fully Java-native (Python not used)

The final implementation is 100% Java in Objects7 — **no Python**. This settled the earlier
bake-in-Java vs. Python-light-service question decisively in favor of Java, because:
- **Content extraction** already exists in Objects7: `DocumentUtil.getStringContent(model)` extracts
  PDF, DOCX, and text natively (verified on AIME.pdf, Vore.docx, The Verse.docx). No external service
  needed for extraction.
- **Persistence, PBAC, and retrieval must be in Objects7 regardless** — a Python service could only
  *return* a tree; it can't own persistence or per-node PBAC traversal.
- **Structural chunking** is done in Java: a deterministic markdown/header splitter for docs with
  headers, and an **LLM-TOC builder** (Chat client, porting the reference's no-TOC generate concept)
  for prose PDFs/DOCXs, with a deterministic flat fallback. Summarization is the Chat client too.

So every stage — extract → split (header or LLM-TOC → flat fallback) → summarize → embed → persist →
retrieve — runs in-process in Objects7 against the existing DocumentUtil / Chat / EmbeddingUtil /
AccessPoint infrastructure. The Python `/build_page_index` seam considered during design was **not
built and will not be used.**

---

## 10. Implementation steps

1. `ModelSchema.pageIndex` attribute + getter/setter.
2. `ModelNames`/`FieldNames` constants, `PageIndexNodeEnumType`, `ActionEnumType.PAGE_INDEX` (hand-added, per the `VECTORIZE` precedent — no XSD in the repo).
3. `pageIndexNodeModel.json` (+ optional `pageIndexModel.json`).
4. `PageIndexProvider implements IProvider` (`describe()` → `DocumentUtil.getStringContent`; empty
   `provide()`).
5. `PageIndexUtil` (build: extract → structural split → summarize → batch persist; retrieve: root
   query → cosine descent → leaves; plus `countPageIndex`/`deletePageIndex`).
6. `AccessPoint.pageIndex(...)` (PBAC-gated, audited) mirroring `vectorize()`.
7. Opt one test model in via `"pageIndex": "...PageIndexProvider"`.
8. `mvn -o -pl AccountManagerObjects7 compile`, then the JUnit below.

## 11. Verification

- New JUnit `TestPageIndex` in `src/test/java/org/cote/accountmanager/objects/tests/`, modeled on
  `TestDocumentSearch.java` / `TestVectorStore.java`: Java `BaseTest`-style test user (**never
  admin**), a real docx/pdf (`Objects7/media/`), call `AccessPoint.pageIndex(...)`, assert node count
  > 0 and tree shape (root exists, leaves carry offsets/content, `parentId` links resolve), then
  `PageIndexUtil.retrieve(doc, "…question…", 10)` returns relevant leaves.
- LLM/light-service calls (summary, embeddings) hit the **DGX Spark `192.168.1.42`** via
  `test.embedding.{type,server,authorizationToken}`; run **single-threaded**, gated behind the LLM env
  flag so the default parallel suite doesn't fire at it.
- `mvn -o -pl AccountManagerObjects7 -Dtest=TestPageIndex test` → `BUILD SUCCESS`. **Never** reset/drop
  schema (new tables are additive; Stephen owns destructive changes).

## 12. Risks / open items / known gaps

- **PDF/DOCX extraction does NOT handle OCR or hard encodings (known gap).** Extraction uses
  `DocumentUtil.getStringContent` (programmatic text extraction). It fails or yields garbage on:
  scanned / image-only PDFs (no embedded text layer), PDFs with custom/subset font encodings that need
  downcasting to ASCII, and other non-plain-text sources. When extraction returns empty/garbage, the
  whole PageIndex tree for that document is empty/meaningless — there is no OCR fallback today.
  **Future mitigation (a separate, extraction-preprocessing Python service — distinct from the TOC seam
  in §9 that was rejected):** a service that routes each document to the right extraction strategy —
  (a) programmatic text-block extraction, (b) custom-font → ASCII downcasting, (c) OCR via Tesseract,
  or (d) a robust cloud extractor such as **Azure Document Intelligence** — and returns clean text that
  `DocumentUtil`/`PageIndexUtil` then consume unchanged. This is an *input-quality* concern, not a
  structuring one; the Java LLM-TOC pipeline is unaffected by where the clean text comes from.
- **LLM-TOC quality/cost:** the LLM-TOC builder depends on the model returning well-formed JSON with
  locatable verbatim `startMarker`s; unlocatable markers are dropped, and a doc that yields none falls
  back to a flat tree. Build cost scales with document size (one+ TOC chat call per ~16K-char group,
  plus per-node summaries + embeddings) — expensive on very large docs (e.g. The Verse.docx).
- **Multi-group continuation is order-append only (v1):** groups are structured independently and
  appended in document order (forward-only marker search de-dupes overlap); true cross-group outline
  continuation (reference `generate_toc_continue`) is deferred.
- **Staleness:** like vectors today, editing the source doesn't auto-invalidate the index —
  `deletePageIndex` + re-`pageIndex` is the rebuild story (same limitation vectors have).
- **Summarization provider:** summaries go through the `Chat`/`LLMConnectionManager` client, which is
  connection-configured (OpenAI or local) — NOT the local-only `EmbeddingUtil.getSummary()`
  (`/generate_summary`, which returns null for non-LOCAL types). Graceful fallback to a content excerpt
  if the call fails. (During current work the local LLMs are unavailable, so tests run against OpenAI
  via the connection/config in `AccountManagerObjects7/src/test/resources/resource.properties`; the
  test chat config + connection settings must be pointed at OpenAI, and separately for any Ux testing
  since that uses a different DB.)
- **Offset fidelity:** offsets are meaningful relative to the extracted string, not the original
  binary, across PDF/docx extraction.

---

## Key file references

- `client/AccessPoint.java:676-726` — `vectorize()`, the trigger to mirror.
- `schema/ModelSchema.java:42,70-76` — the `.vector` attribute to parallel as `.pageIndex`.
- `olio/VectorProvider.java:27,64-75` — opt-in provider; `describe()` real, `provide()` empty.
- `util/VectorUtil.java:312,380-498` — `createVectorStore` + chunkers to generalize.
- `util/DocumentUtil.java:167-192` — content-extraction dispatcher.
- `tools/EmbeddingUtil.java:102,111` — light-service transport (`/generate_summary`,
  `/generate_embedding`).
- `resources/models/common/vectorExtModel.json` — inherit this (don't re-declare content/embedding).
- `resources/models/data/vectorModelStoreModel.json` — flex-foreign reference pattern to mirror.
- `resources/models/common/parentModel.json` — `common.parent` tree convention.
- `schema/type/ActionEnumType.java:68` — `VECTORIZE` precedent for adding `PAGE_INDEX`.
- `objects/tests/TestDocumentSearch.java`, `TestVectorStore.java` — JUnit template.
- Reference: `C:\Projects\GitHub\PageIndex` — `pageindex/page_index_md.py` (portable builder),
  `pageindex/page_index.py` (LLM PDF pipeline), `pageindex/utils.py` (prompts), `config.yaml` (knobs).
