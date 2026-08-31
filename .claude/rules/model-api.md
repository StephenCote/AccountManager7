# Model API — cross-layer query, serialization, PATCH & foreign-model patterns

Consolidated, canonical reference for the query/serialization behaviors that bite across **both**
the Objects7 (Java) layer and the Service7 (REST/JSON) layer. Relocated here from the two module
docs so the rules live once. Deep module-specific material stays in `objects7-reference.md` /
`service7-reference.md`; behavioral rules in `llm-conduct.md`; layering in `architecture.md`.

## Default query fields in model definitions

Model definitions include a `query` array specifying the default fields returned when querying that
model. These are **inherited** from parent models; the effective set is the union across the
inheritance chain.

```json
{ "name": "common.base",     "query": ["id", "urn", "objectId", "ownerId"] }
{ "name": "data.directory",  "query": ["groupId", "groupPath", "organizationId"] }
```

A model inheriting `data.directory` therefore has query fields
`[id, urn, objectId, ownerId, groupId, groupPath, organizationId, ...]`.

**Why it matters:** without an explicit field projection, only the query fields (plus identity
fields) are returned. Nested foreign models and non-query fields are NOT automatically included.
Clients can fetch the full schema (with these `query` arrays) via `GET /rest/schema`
(`SchemaUtil.getSchemaJSON()`) and should cache it to understand field types, enums, and inheritance.

## Field projection & accessing nested foreign models

**Foreign model fields are NOT populated by default** — you get `null` or just the ID reference,
and `List<model>` fields come back empty or as IDs. To get actual nested data you must plan for it.

**Java (Objects7):**
```java
// Solution 1: recursive plan for most fields
Query q = QueryUtil.createQuery("olio.charPerson", FieldNames.FIELD_OBJECT_ID, personId);
q.planMost(true);                 // recursively plan most fields
BaseRecord person = accessPoint.find(user, q);

// Solution 2: request specific (and nested-path) fields
q.setRequest(new String[] {"id","objectId","name","statistics","store","profile.portrait"});

// Solution 3: custom QueryPlan for fine control
QueryPlan plan = q.getPlan(q.getType());
plan.getPlanFields().addAll(Arrays.asList("id","name","statistics","instinct"));
QueryPlan statsPlan = plan.plan("statistics", new String[]{"physicalStrength","agility","speed"});
QueryPlan instinctPlan = plan.plan("instinct", new String[0]);
instinctPlan.planForCommonFields(true);
```
`QueryPlan` methods: `planForCommonFields(recurse)`, `planForMostFields(recurse, filterList)`,
`plan(fieldName, fields)`, `getSubPlan(fieldName)`, `unplan(fieldName)`, `filterRecord(record)`.

**REST (Service7):**
- **Pattern 1 — `/full` endpoint:** `GET /rest/model/{type}/{objectId}/full` uses `planMost(true)`
  to recursively fetch nested models (excludes expensive blobs/large lists via filters).
- **Pattern 2 — request fields in query:**
  ```json
  POST /rest/model/search
  { "schema":"io.query", "type":"olio.charPerson",
    "request":["id","name","statistics","store","profile.portrait"],
    "fields":[{ "name":"objectId","comparator":"EQUALS","value":"abc-123" }] }
  ```
- **Pattern 3 — nested path syntax:** `"statistics"`, `"profile.portrait"`, `"profile.portrait.groupPath"`.

Default GET returns minimal fields (`id, objectId, name, urn, organizationId, ownerId`); everything
else is opt-in via `request` or `/full`.

### `planMost(true)` can exceed PostgreSQL's 100-argument function limit

Recursive `planMost(true)` on a model whose nested foreign `MODEL` fields are themselves wide
generates a `JSON_BUILD_OBJECT` sub-query per model field (`StatementUtil
.getParticipationSelectTemplate`, `modelMode=true`). Recursion expands the nested model's own
foreign fields transitively, and past 100 arguments PostgreSQL throws
`PSQLException: cannot pass more than 100 arguments to a function`. `DBSearch` catches it and
returns null, so the only surface is `AUDIT INVALID … No results` — it reads exactly like an
authorization denial or an empty table.

Measured on `olio.pb.book` → `olio.world`. So **do not use `GET /rest/model/{type}/{objectId}/full`
(`ModelService.getFullModelByObjectId`, which calls `planMost(true)`) for `olio.pb.book`, or any
model whose nested foreign models are field-heavy.** Use `POST /rest/model/search` with an explicit
`request` projection instead; the `findBookBySlug` path does this and works.

## Serialization: `toFullString()` vs `toString()`

| Method | Behavior |
|--------|----------|
| `toString()` | Serializes only fields explicitly set |
| `toFullString()` | Serializes all fields including defaults and computed values |

**The REST API uses `toFullString()` for responses** to ensure complete data representation:
```java
return Response.status(200).entity(rec.toFullString()).build();  // correct
```

## Deserialization with schema context

The deserializer uses the schema to determine field types (don't re-parse enums), resolve foreign
keys, and resolve nested model types. **The `schema` field in JSON is required** — without it the
deserializer cannot determine field types.

```java
BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class,
    RecordDeserializerConfig.getFilteredModule());
```

`RecordDeserializerConfig` modules:

| Module | Use case |
|--------|----------|
| `getFilteredModule()` | Standard API input — filters sensitive fields |
| `getUnfilteredModule()` | Internal use — allows all fields |
| `getForeignModule()` | Handles foreign key resolution |

## Condensed fields (`shortName`)

Model fields can define a `shortName` for compact serialization. The deserializer auto-detects
condensed format when `detectCondensedFields` is enabled (default) — look for the `s` key (short for
`schema`) instead of `schema`.

```json
// full:      { "schema":"data.group", "name":"MyGroup", "groupType":"DATA", "organizationId":123 }
// condensed: { "s":"data.group", "n":"MyGroup", "gt":"DATA", "oi":123 }
// schema:    { "name":"groupType", "type":"enum", "shortName":"gt" }
```

## List serialization schema loss

When serializing/returning **lists**, the `schema` property may appear only on the first item;
subsequent items omit it to reduce payload. Always restore it on the consumer side.

```java
// Java
String schemaName = results.get(0).getSchema();
for (int i = 1; i < results.size(); i++)
    if (results.get(i).getSchema() == null) results.get(i).setSchema(schemaName);
```
```javascript
// JavaScript
if (results.length && results[0].schema) {
  const s = results[0].schema;
  results.forEach(r => { if (!r.schema) r.schema = s; });
}
```

## Create response pattern

Create returns only identity fields (`id`, `objectId`, `urn`, `groupId`/`parentId`,
`organizationId`) — NOT the full object. If you need the complete record after creation, do a
subsequent `GET .../full`.

## PATCH — partial updates

For small updates use PATCH instead of a full record PUT/update. A patch includes identity fields
plus only the changed fields. This is also the safest way to update a record that references a
groupless model (avoids re-persisting a full `planMost` graph that would demand extra role grants).

**Java — use the field-name overload of `newInstance`, NOT the bare one:**
```java
// RIGHT: materialises ONLY these fields
BaseRecord patch = RecordFactory.newInstance(existing.getSchema(),
    new String[] {"id", "objectId", "name", "description"});
patch.set("id", existing.get("id"));            // identity (required)
patch.set("objectId", existing.get("objectId"));// identity (required)
patch.set("name", existing.get("name"));        // validated field (see below)
patch.set("description", "New description");    // changed field
accessPoint.update(user, patch);
```

> **`RecordFactory.newInstance(model)` — the bare overload — materialises EVERY field of the model at
> its default value, and the writer persists every field present on the record it is handed. So a
> "patch" built that way silently overwrites every field the caller did not set.** Measured on `am7db`
> 2026-08-15: an `olio.pb.book` patch that set only `world` blanked `slug` and `description` and reset
> `bookStatus` to `UNKNOWN`. Nothing failed — `update` returned success — and the damage surfaced only
> as a later `find` on `slug` that returned nothing. **Always pass the explicit field-name array**
> (`newInstance(model, String[])`), or build the patch with `existing.copyRecord(fields)`.

**REST:** `PATCH /rest/model`
```json
{ "schema":"data.group", "id":123, "objectId":"abc-123-def", "description":"Updated description only" }
```

**Rules:** must include `schema` + at least one identity field (`id`, `objectId`, or `urn`); only
present fields are updated; omitted fields unchanged; foreign fields patch by ID reference; returns
`true` on success.

**…plus every field the model's validation requires — "identity + changed fields" alone is not enough.**
The writer validates **the patch record itself**, not the merged result. So a model carrying a validated
non-identity field rejects a patch that omits it, even though the stored record satisfies the rule. Most
commonly this is `name`: anything inheriting `common.nameId` has a `\S` rule on it, so a patch without
`name` fails with `Validation of <model>.name (null) failed pattern \S` → `WriterException: Record failed
validation in IO DATABASE` → `AUDIT INVALID … Failed to modify record`.

This failure is quiet and easy to ship. It surfaces only in the log — the update call returns a value most
callers discard, so the code path reports success while nothing was written. Two habits avoid it:
- Include the model's validated fields (start with `name`) in every patch, taking the value from what you
  already know rather than from a freshly-created record — `AccessPoint.create` returns **identity fields
  only**, so `created.get("name")` is null.
- **Never discard the update result.** `getAccessPoint().update(...)` returning false/null is the only
  signal you get; swallowing it converts a persistent failure into a silent no-op.

### PATCH does not cascade — it writes only the model you called it on

`AccessPoint.update()` persists fields on **the exact model instance you pass in**. Set a field on a
nested foreign record (`charPerson.narrative.sdPrompt`), attach that object to a parent-level patch
(`charPerson`), and the parent's FK pointer is updated while the nested record's own field change is
silently dropped. Confirmed live: `narrative.sdPrompt` read back null after `createCharPerson()` set
it post-creation and patched only the parent's `narrative` FK. It broke the same feature twice.

⇒ **Issue a second, separate patch against the nested record itself** (identity + changed fields, via
the explicit field-name `newInstance` idiom above so validation doesn't reject it).

**The named exceptions** (Stephen, 2026-07-16): cascading *does* happen automatically for
memberships/participation writes and for reverse-reference attachment during a **`create`**. Do not
generalize those exceptions to plain nested-model-field PATCHes.

**`referenced` fields never persist through a parent patch at all.** `common.attributeList`'s
`attributes` is `referenced` storage (separate reference table keyed by `referenceModel`/`referenceId`),
not a column. Two distinct failures, found 2026-07-19:

1. A `copyRecord([...])` patch containing only `id`/`objectId`/`attributes` produces an **empty SQL
   `SET` clause** — `UPDATE A7_x SET  WHERE id = ?`, a real syntax error — because none of the three
   are columns. Adding a real column (e.g. `organizationId`, as `EpochUtil` does) clears the syntax
   error but not the actual problem, because:
2. Even with a valid `SET`, **the attribute row is still never written.** Re-querying with
   `setCache(false)` showed `attributes: []`.

⇒ Don't fold an attribute into a parent patch. Call `AttributeUtil.addAttribute(rec, name, val)` (new)
or mutate the existing attribute's value with `existingAttr.setFlex(FieldNames.FIELD_VALUE, val)`, then
persist **that attribute record directly** — `getRecordUtil().createRecord(newAttr)` or
`.updateRecord(existingAttr)`. This is already the pattern in `LibraryUtil.java:45` and `EpochUtil`
(neither routes attributes through `AccessPoint.update()`); see `PictureBookUtil.tagApparelSceneIndex`.

## Working with Olio objects (full records)

Olio code expects fully, deeply populated objects (`state.currentLocation`, `profile.portrait`,
`statistics`, `store.apparel`, `instinct`, etc.). Use `OlioUtil.planMost(query)` when building
queries for Olio objects:

```java
Query q = QueryUtil.createQuery("olio.charPerson", FieldNames.FIELD_OBJECT_ID, objectId);
OlioUtil.planMost(q);
BaseRecord person = IOSystem.getActiveContext().getSearch().findRecord(q);
```

- `GameUtil.findCharacter()` **already** calls `OlioUtil.planMost()` internally — do NOT call
  `getFullRecord()` after it (redundant double-load).
- Use `OlioUtil.getFullRecord(record)` only when you have a **partial** record (from a list or
  minimal projection) and need full data. It builds a query from the record's id/objectId/urn,
  applies `OlioUtil.planMost(query)`, and returns a new fully-populated record.

## Typed query field values

**The rule is general: a query condition's value must match the field's `FieldEnumType`, or the
condition silently becomes `<field> = null` and matches nothing.** `Query.field()` routes the value
through `FieldUtil.setFlex(record, name, type, value)`, which dispatches on the *schema's* type for
that field; a mismatch is caught and logged inside `FieldUtil` and the call site sees nothing.

- `organizationId` / `groupId` are `long` — send **numbers**, not strings (`{value: 2}` not
  `{value: "2"}`).
- **A `foreign` `model` field takes the RECORD, not its id.** `setFlex` calls `setModel()` for a
  `MODEL`-typed field, so a `Long` is rejected; `StatementUtil` (`:1367`) casts the value to
  `BaseRecord` and reads its `id` itself. So query by record:
  ```java
  // WRONG — condition becomes "workflow = null", matches nothing, logs nothing here
  QueryUtil.createQuery("olio.pb.node", "workflow", workflow.get(FieldNames.FIELD_ID));
  // RIGHT
  QueryUtil.createQuery("olio.pb.node", "workflow", workflow);
  ```
  The audit line is the tell: `(workflow = null && organizationId = 7) … No results` versus
  `(workflow = {schema:"olio.pb.workflow",id:1,…} && organizationId = 7)`.

`/rest/model/search` is cached by query key — set `cache:false` for views that must see
just-created/edited/deleted records.

### Cache invalidation does not follow nested references

The same staleness bites at the Java `Query` layer, and it is worse there because it applies to fields
you did not query directly. `CacheDBSearch.clearCache(BaseRecord)` only invalidates entries whose
cached **top-level** result matches the updated record's own schema + identity. So if parent A
(`olio.charPerson`) was fetched and cached with a nested field populated (`profile`), and you then
update that nested record directly (patch `identity.profile.portrait`), **A's cache entry is not
invalidated** — re-fetching A with the same `Query` shape returns the stale nested value.
`RecordReader.populate()`'s per-instance memoization compounds it by skipping re-fetch on an
already-populated instance.

Found 2026-07-16: a test verification step reported a portrait as unlinked while the DB and the
writing code's own logs both proved it persisted. The cause was the *test's* earlier query having
cached the pre-update parent.

Two further confirmations (2026-07-19):
- It also hits **participation-backed list fields** (`olio.store.apparel`, linked via `MemberUtil.member()`).
- `RecordReader.populate(rec, fields)` is a **no-op when the field already holds BaseRecord's
  default-instantiated empty list** — it never issues the read at all, cache or no cache.

⇒ Any code or test that updates a nested/foreign/participation field and then verifies it by re-fetching
a **different parent** record must re-fetch with an explicit fresh `Query` and `setCache(false)`. Do not
substitute `reader.populate(x, [field])`, and do not assume re-querying is inherently fresh. See
`PictureBookUtil.selectSceneApparel`.

## `AccessPoint.list` is NOT a per-record authorization boundary

`AccessPoint.find` authorizes the query shape **and then** runs `AuthorizationUtil.canRead` on the
result before returning it (`AccessPoint.java:513-517`). `AccessPoint.list` (`:623-636`) authorizes
the query shape via `authorizeQuery` and returns whatever `search` returned, **with no per-record
filtering**. Measured on `am7db` 2026-08-16 with two users in one organization: a by-objectId read of
another user's group-scoped record is correctly `AUDIT DENY`, while an org-wide list with an explicit
numeric `organizationId` condition returns it (`AUDIT PERMIT`).

⇒ An explicit `organizationId` condition satisfies PBAC's *query* requirement; it is not a tenancy or
compartment filter. Any endpoint that lists group-scoped records must constrain by `groupId` (or filter
per record itself) rather than relying on `list` to do it.
