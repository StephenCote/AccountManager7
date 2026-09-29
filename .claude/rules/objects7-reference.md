---
paths:
  - "src/AccountManagerObjects7/**"
  - "src/AccountManagerISO42001/**"
  - "src/AccountManagerService7/**"
  - "src/AccountManagerAgent7/**"
  - "src/AccountManagerConsole7/**"
---

# AccountManagerObjects7 — Gotchas Reference

> The non-derivable traps for the core module — things the code does not tell you and that have
> each cost a session. Behavioral rules are in `llm-conduct.md`; architecture/layering in
> `architecture.md`; cross-layer query/serialization/PATCH/foreign-model patterns in `model-api.md`.
> The lean orientation lives in `AccountManagerObjects7/CLAUDE.md`. The explanatory tour of the
> module (design philosophy, providers, vault, query API, PBAC, Olio map system, validation, bulk
> ops, attributes, credentials, journaling, file layout) was moved to
> `src/aiDocs/Objects7DeepReference.md` — read that when you need the how-to, not the trap.

## Model schema definitions

Models are defined as JSON files in `src/main/resources/models/` organized by domain (common, auth, data, olio, etc.).

**Model inheritance resolution:** Depth-first traversal with last-wins for field conflicts. When a model inherits from multiple parents, each parent tree is resolved depth-first, and later field definitions override earlier ones.

**`likeInherits` is metadata only — it inherits nothing and creates no table.** It is a
`ModelSchema.java` getter/setter that **no code in `RecordFactory` or `DBUtil` ever reads**: no DDL
effect, no field-inheritance effect. A model declaring `likeInherits: [data.directory]` instead of
`inherits: [...]` gets no table and resolves no parent fields, so every field access (`name`,
`groupId`, …) fails with `Invalid field`. Hit on `olio.sd.config` — `a7_olio_sd_config_0_1` was never
created, and two Docker builds **exited 0** while failing at the curl step, so nothing surfaced the
cause. Always use real `inherits: [...]`; if you find `likeInherits` in a model definition, treat it
as a design annotation, then fix it to `inherits` and rebuild the WAR.

**Never re-declare an inherited field or constraint.** `DBUtil Index collision` and
`Column does not exist` ERROR lines at startup or in test output are **real schema defects**, not
noise: they mean a model added a constraint or column already inherited from a parent
(`data.directory`, `common.nameId`, `common.parent`, …), and the duplicate produces DDL errors on
every single startup. This was introduced during ChapBook model work and then written off as
"pre-existing schema-init noise" — that was deflection, not diagnosis (see `llm-conduct.md`). Before
adding a field or constraint to a model JSON, walk the `inherits` chain and confirm it isn't already
declared upstream.

**…but `Index collision` has a second, distinct cause — diamond inheritance — so establish which one
you have before hunting for a duplicate declaration.** `RecordUtil.getHints`/`getConstraints`
(`RecordUtil.java:449-465`) `addAll` down **every** `inherits` branch with no `contains` check, so a
model that reaches one ancestor by two paths collects that ancestor's hints twice and
`DBUtil.getSchemaIndexes` (`DBUtil.java:628-639`) reports the second as `Index collision`. The model
JSON declares nothing wrong in that case, and unlike the re-declaration case above it produces **no
DDL error** — `DBUtil` skips the duplicate, which is the correct outcome.

Distinguishing test: open the model JSON. If it declares the colliding name in its own `hints` /
`constraints` (or a field it also inherits), it is the re-declaration defect — fix the JSON. If it
declares no `hints`/`constraints` at all, walk `inherits` for two paths to the same ancestor; that is
the collector, not the model, and editing the JSON to break the diamond can silently change behavior
(`inherits("data.directory")` alone is load-bearing at seven sites, including the group-only PBAC
shortcut in `AccessPoint.java:200`).

Live example, diagnosed and deliberately left unfixed: **`iso42001.certificationRequest`** logs
`(objectId)`, `(id)`, `(urn)` on every boot — one model out of 230. Full diagnosis, the seven-site
impact table, and the recommended collector-level fix are in **`aiDocs/KnownIssues.md` KI-69**. Read
that before touching either the model or the collector.

**Related trap when fixing any of this: the persisted schema wins over the resource — but ONLY after
`IOSystem.open()` has finished. Adding a NEW FIELD to a model JSON needs no migration at all.**

Two paths, and conflating them wastes a lot of effort in both directions.

*Path 1 — boot DDL patch, resource-driven. This is how you add a field.* `IOSystem.open()`
(`IOSystem.java:132-160`) loops `ModelNames.MODELS` and, for every model whose table already exists,
executes `dbUtil.generatePatchSchema(schema)` → `addPatchColumns` → `ALTER TABLE <t> ADD COLUMN
<coldef>;` for each column the schema declares and the table lacks (`DBUtil.java:959-983`). The
schema it patches against comes from the **resource**, not the blob: the loop calls
`RecordFactory.getSchema(m)` at `:133`, `getSchema` tries `getIOSchema()` first, and `getIOSchema` is
gated on `IOSystem.isInitialized()` — which is `getActiveContext() != null`, and `activeContext` is
not assigned until `IOSystem.java:208`, *after* the loop. So `getIOSchema` returns null there,
`getSchema` falls through to `importSchemaFromResource()`, and the resource-derived schema is then
cached in the in-process `schemas` map for the life of the JVM — so every later `getSchema` call also
sees the new field and never consults the blob.

⇒ **To add a COLUMN-BACKED field: edit the model JSON and restart. That is the entire data-model
change.** No `addFieldToSchema`, no `updateSchemaDefinition`, no `PUT /rest/schema`, nothing to run
per database. The propagation bound is a **restart** — a running JVM will not pick it up.

**Four preconditions, all of which the `upstream` field happened to satisfy. Check them before
relying on this:**
1. **The field must be column-backed.** `getMissingColumns` (`DBUtil.java:937-940`) skips `virtual`,
   `ephemeral` and `referenced` fields and anything with no SQL data type (e.g. an unreferenced
   foreign list). A `referenced` or participation-backed field gets **no column and no plumbing**
   from the boot patch — see the `common.attributeList` notes in `model-api.md`.
2. **It must be nullable, or carry a DDL-emittable default.** `generateSchemaLine` emits `not null`
   when the field declares `allowNull:false` (or is identity) and emits a `default` clause **only**
   for INT/DOUBLE/LONG/BOOLEAN/ZONETIME/TIMESTAMP — never for string/enum. So
   `ALTER TABLE … ADD COLUMN x varchar(16) not null;` is **rejected by Postgres on a populated
   table**, and `dbUtil.execute` **swallows the `SQLException`** (logs ERROR, continues). The column
   then simply never exists and every access fails oddly. A `"default"` in the JSON is an
   *instantiation-time* default, not DDL — so every pre-existing row reads SQL `NULL`. Handle null
   as the primary path.
3. **The patch branch requires `!dbUtil.isConstrained(schema)`.**
4. **The model must be in `ModelNames.MODELS` at `IOSystem.open()` time.** True today for the Olio
   and ISO registrations (`RestServiceEventListener` registers them before `open`), but
   `OlioModelNames.use()` is also called at request time in several services — so this is a
   precondition to confirm, not a given.

The stale persisted blob is then never consulted for that model **for the life of the JVM** — with
one exception: `RecordFactory.unloadSchema`/`clearCache` evict the cached resource-derived schema,
after which `getSchema` reads the (possibly field-less) blob until restart. `updateSchemaDefinition`
is safe because it writes the blob first; a **bare unload is not**. `CacheService.clearCaches()` does
not call it, so the REST cache-clear route is harmless.

**Measured proof, 2026-09-15.** `dialect` was added to `system.connection` by exactly this route. In
`am7db` the persisted blob for `system.connection` **does not contain the string `dialect` at all**
(`convert_from(schemadata,'UTF8')`), yet the column exists, holds real values (10 `OLLAMA`,
17 `OPENAI_COMPAT`, 6 `OPENAI` across 135 rows) and `TestConnectionDialect` passes 5/5. A
resource-only field works end to end against a stale blob. (`am72db`'s blob *does* carry `dialect`,
so the two databases disagree and both work — further evidence the blob is not load-bearing here.)

Two consequences worth knowing: the added column is **nullable with no DDL default** for enum/string
fields (`DBUtil.generateSchemaLine` emits a DDL `default` only for INT/DOUBLE/LONG/BOOLEAN/
ZONETIME/TIMESTAMP), so a `"default"` in the JSON is an *instantiation-time* default and every
pre-existing row reads SQL `NULL` — handle null as the primary path, never assume the default string
is in the column. And because the `ALTER` is additive and nullable, it succeeds on a populated table.

*Path 2 — runtime reads, blob-driven. This is the real trap.* Once `IOSystem` is initialized,
`RecordFactory.getSchema()` (`:388-399`) → `getIOSchema()` (`:299-316`) reads the serialized
`ModelSchema` from `a7_system_modelschema_0_1` and only falls back to the resource when there is no
row. So **changing the shape of an EXISTING field** (type, `maxLength`, validation, `shortName`,
access roles) on a provisioned deployment is not picked up by the boot patch — that loop only adds
missing *columns*. For those, call `RecordFactory.updateSchemaDefinition(ModelSchema)` (`:475-507`),
or start against a fresh database. Note `updateSchemaDefinition` ends at `unloadSchema` +
`CacheUtil.clearCache()`, so its effect is **in-process only** — a second JVM (Console7) against the
same DB keeps the stale schema until *it* restarts.

**Never `RecordFactory.releaseCustomSchema(name)` to "refresh" a schema.** It is
`DROP TABLE IF EXISTS <t> CASCADE` (`:594`) plus a delete of the modelschema row, and its
system-model guard is **commented out** (`:565-568`) — it only logs a warning and proceeds. On
`system.connection` that would destroy every connection row and cascade into `chatConfig`.
Also note `removeFieldFromSchema` (`:534-559`) does `ALTER TABLE ... DROP COLUMN IF EXISTS` with
**no** off-by-default property gate, contrary to the rule in `architecture.md`; `SchemaService
.deleteField` reaches it and guards only on `FieldSchema.isSystem()`.

> An earlier revision of this section said flatly that "editing a model `.json` has no runtime
> effect" on a provisioned deployment. That is true for Path 2 and **false for Path 1**, which is the
> common case (adding a field). On 2026-09-15 it led a planner and an architect to independently
> design, and nearly ship, a `addFieldToSchema` migration plus a REST migration route that were both
> unnecessary — and the REST route would have set `system=false`, putting the new field within reach
> of the ungated `DROP COLUMN` above. Stephen caught it: "the model system should be able to add /
> update columns per model." It can.

### Field Schema Properties

Fields support several modifiers that control persistence and behavior:

| Modifier | Persisted | Description |
|----------|-----------|-------------|
| `foreign` | ID only | References another model; list types use participation tables |
| `virtual` | No | Computed on-the-fly via a `provider` class |
| `ephemeral` | No | Exists in memory during request lifecycle only |
| `referenced` | Yes | Stored in a separate reference table with `referenceModel`/`referenceId` |
| `identity` | Yes | Used to uniquely identify records (id, urn, objectId) |

**Virtual vs Ephemeral:** Both are non-persisted but have different lifecycles. Virtual fields are computed via providers when accessed. Ephemeral fields are transient working data that exists only during request processing.

## System vs User-Defined Roles

**System Roles** (have system impact):
- Created during organization initialization
- Referenced in model `access.roles` definitions
- Used for API authorization (e.g., `AccountAdministrators`, `AccountUsersReaders`)
- Membership checked by `AccessPoint` for operation authorization
- Examples: `AccountAdministrators`, `RequestApprovers`, `DataReaders`

**User-Defined Roles** (no system impact):
- Created by users for their own organizational purposes
- Allow users to assign entitlements to things they own
- Can represent external access models
- Useful for modeling third-party RBAC within AM7
- Have no effect on AM7 system authorization

```java
// System role - affects authorization
BaseRecord sysRole = IOSystem.getActiveContext().getPathUtil()
    .findPath(user, ModelNames.MODEL_ROLE, "/AccountAdministrators", RoleEnumType.USER.toString(), orgId);

// User-defined role - for user's own organization
BaseRecord userRole = RecordFactory.newInstance(ModelNames.MODEL_ROLE);
userRole.set("name", "ProjectManagers");
userRole.set("type", RoleEnumType.USER.toString());
userRole.set("parentId", customRolesGroup.get("id"));
// This role has no system authorization impact
```

## Olio

**Character position needs BOTH objects.** Full coordinate resolution requires the geolocation
record (`state.currentLocation`, type `cell`, a 100 m × 100 m space) **and** the state record
(`state.currentEast`/`currentNorth`, 1 m resolution inside that cell). Map-system details
(GZD/kident/feature/cell hierarchy, `GeoLocationUtil` distances, `DirectionEnumType`, movement)
are in `src/aiDocs/Objects7DeepReference.md`.

### Cell Crossing Bug (Fixed January 2026)

**Problem:** When a character crossed a cell boundary (e.g., east=95 → east=5), the movement loop would enter an infinite loop because absolute position calculations were incorrect after the crossing.

**Root Cause:** In `Walk.java` and `WalkTo.java`, the call to `StateUtil.queueUpdateLocation()` was using the default `includeLocation=false` parameter. This meant:
1. `moveByOne()` correctly updated the in-memory `currentLocation` FK to the new cell
2. `moveByOne()` reset `currentEast`/`currentNorth` for the new cell (e.g., 95→0)
3. But `updateLocationImmediate()` only saved `currentEast`/`currentNorth` to the database - NOT the new `currentLocation` FK
4. Subsequent reads got the OLD cell FK from database with NEW position coordinates
5. Absolute position was wrong: `oldCell.eastings * 100 + newPosition` instead of `newCell.eastings * 100 + newPosition`

**Fix:** Always pass `includeLocation=true` when updating location after movement:
```java
// In Walk.java and WalkTo.java:
StateUtil.queueUpdateLocation(context, actor, true);  // Always include currentLocation FK
```

**Key Files:**
- [Walk.java](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/actions/Walk.java) - Line 91
- [WalkTo.java](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/actions/WalkTo.java) - Line 70
- [StateUtil.java](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/StateUtil.java) - `updateLocationImmediate()` method

**Pattern:** When calling `queueUpdateLocation()` after any operation that might change cells, always pass `true` for `includeLocation`.

## Field Type Resolution and Enum Handling

**IMPORTANT:** Always check the model schema definition to understand a field's data type before attempting to read or manipulate values. The schema contains critical type information that determines the correct accessor method.

### CRITICAL: Check Field Data Types Before Use

**This is a common source of ClassCastException errors.** When reading fields from models, especially fields accessed dynamically by name, always verify the data type in the schema first.

**Example - instinct model fields:**
```json
// In instinctModel.json - note these are DOUBLE, not int!
{
  "name": "cooperate",
  "type": "double",   // <-- ALWAYS check this!
  "minValue": -100,
  "maxValue": 100
}
```

**Incorrect (causes ClassCastException):**
```java
// WRONG - assumes int but schema defines double
int cooperate = instinct.get("cooperate");  // ClassCastException!
```

**Correct:**
```java
// RIGHT - matches schema's "type": "double"
double cooperate = instinct.get("cooperate");
```

**Before writing code that reads model fields:**
1. Open the model JSON file in `src/main/resources/models/`
2. Find the field definition
3. Check the `type` property (string, int, long, double, boolean, enum, model, list, etc.)
4. Use the correct Java type in your code

## Common Patterns

### Reading and writing `byteStore` — always via `ByteModelUtil`

Read a `byteStore` field with `ByteModelUtil.getValue(record)` /
`ByteModelUtil.getValue(record, fieldName)` and write it with
`ByteModelUtil.setValue(record, bytes)`. **Never** `record.get(FieldNames.FIELD_BYTE_STORE)` or
`record.set(...)` directly.

`byte_store` data may be transparently compressed (`ByteModelUtil.tryCompress`) and/or encrypted
(`EncryptFieldProvider`/`VaultService`) depending on model config. A raw `.get()` returns the stored
bytes as-is — compressed and/or ciphertext — not the logical value, and hands silent garbage to any
downstream consumer expecting real bytes. Found 2026-07-15 in `PictureBookService.java`, which had
4+ raw `FIELD_BYTE_STORE` reads feeding portrait bytes into the SD pipeline; the likely root cause of
"reference images obviously aren't used."

Grep for `FIELD_BYTE_STORE` as a review step on any code touching binary blobs. But **fixing** is
scoped to the feature in hand: when this surfaced raw access elsewhere (e.g. Vault), Stephen's
instruction was to note those and leave them, not fix them as a drive-by.

## Validation System

### Built-in Validation Rules

| Rule | Description |
|------|-------------|
| `$minLen5` | **Not a length check.** Expression `[A-Za-z0-9]{5}`, evaluated with `Matcher.find()` (`ValidationUtil.java:189`) — it means "contains **five consecutive alphanumerics** anywhere". So `jane.doe` (8 chars) FAILS, because its longest alphanumeric run is 4, while `a.bcdef` passes on the run `bcdef`. The name is misleading; verified against `validationRules/minLen5Rule.json`. `Factory.getCreateUser` enforces it independently and returns **null** for a name that fails, so any caller-side validator must be at least this strict or it will produce a null user. |
| `$notEmpty` | Field must not be empty |
| `$trim` | Trims whitespace (applied automatically) |

## Bulk Operations

### Batch Update

Update multiple records at once. **Important:** All records must have the same fields set, or the batch will fail:

```java
// Set same field on all records
for (BaseRecord rec : records) {
    rec.set(FieldNames.FIELD_DESCRIPTION, "Updated: " + UUID.randomUUID());
}

int updated = accessPoint.update(user, records);
```

### Cross-Participation Note

When creating records with mutual relationships (e.g., partners), create records first, then create participations separately:

```java
// Create records first
ioContext.getRecordUtil().createRecords(new BaseRecord[] {person1, person2});

// Then create cross-participations
BaseRecord p1 = ParticipationFactory.newParticipation(user, person1, "partners", person2);
BaseRecord p2 = ParticipationFactory.newParticipation(user, person2, "partners", person1);
ioContext.getRecordUtil().createRecords(new BaseRecord[] {p1, p2});
```

## LLM prompt templates (location)

This module owns the runtime LLM prompt templates under `src/main/resources/olio/llm/`
(`prompt.config.json`, `prompts/compliance.json`, `prompts/chatOperations.json`,
`prompts/ageGuidance.json`, `templates/chatConfig.rpg.json`). Their content is maintained in code and
by the ISO 42001 subsystem — not documented here.

### Consuming LLM-extracted fields: guard for the literal string `"null"`

A null-or-blank check (`x != null && !x.isBlank()`) is **not sufficient** for any field an LLM
extracted into a JSON/Map structure. LLMs routinely emit the literal four-character string `"null"` —
or `"n/a"`, `"none"`, `"unknown"`, `"unspecified"` — as the *value* when they can't determine an
attribute, rather than omitting the key or using a real JSON null. That text sails through a blank
check and straight into anything that concatenates it, producing output like
"a null null null woman with … null eyes."

Caught 2026-07-16 by Stephen reading an actual generated SD prompt:
`NarrativeUtil.buildPortraitPromptFromExtractedData()` guarded with `Map.getOrDefault(key, "")` +
`!isBlank()`, which catches a missing key or an empty string but not a present key whose value is the
text `"null"`. Confirmed again 2026-07-20 — `PictureBookUtil.createCharPerson`'s ethnicity mapping hit
it live against `catatone.docx`, where the LLM returned `"null"` for `ethnicity` far more often than a
real absence.

⇒ Use **`NarrativeUtil.isMeaningful(String)`** (made public for exactly this reason) for any new
LLM-field consumption in Objects7 rather than writing a fresh blank/null check. Treat this as a
standing risk for all LLM-extraction-consuming code, not a PictureBook quirk.
