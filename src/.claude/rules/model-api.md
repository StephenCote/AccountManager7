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
  { "schema":"query", "type":"olio.charPerson",
    "request":["id","name","statistics","store","profile.portrait"],
    "fields":[{ "name":"objectId","comparator":"EQUALS","value":"abc-123" }] }
  ```
- **Pattern 3 — nested path syntax:** `"statistics"`, `"profile.portrait"`, `"profile.portrait.groupPath"`.

Default GET returns minimal fields (`id, objectId, name, urn, organizationId, ownerId`); everything
else is opt-in via `request` or `/full`.

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

**Java:**
```java
BaseRecord patch = RecordFactory.newInstance(existing.getSchema());
patch.set("id", existing.get("id"));            // identity (required)
patch.set("objectId", existing.get("objectId"));// identity (required)
patch.set("description", "New description");     // changed field
accessPoint.update(user, patch);
```

**REST:** `PATCH /rest/model`
```json
{ "schema":"data.group", "id":123, "objectId":"abc-123-def", "description":"Updated description only" }
```

**Rules:** must include `schema` + at least one identity field (`id`, `objectId`, or `urn`); only
present fields are updated; omitted fields unchanged; foreign fields patch by ID reference; returns
`true` on success.

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

## Typed query field values (reminder)

`organizationId`/`groupId` are `long` — send **numbers**, not strings (`{value: 2}` not
`{value: "2"}`) or the condition silently matches nothing. `/rest/model/search` is cached by query
key — set `cache:false` for views that must see just-created/edited/deleted records.
