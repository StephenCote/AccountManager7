# AccountManagerObjects7

AccountManager7 is a ground-up rewrite of the Account Manager system with a flexible, JSON-based object model that supports strong typing without reflection, tighter serialization control, and portable schema/data.

## Design Philosophy

### Schema-Driven Architecture

The system is fundamentally **schema-first**: models are defined declaratively in JSON and interpreted at runtime rather than generated into code. This enables:
- Runtime model introspection and manipulation
- Dynamic field resolution and type checking
- Portable schema definitions independent of implementation
- No reflection-based magic - explicit type handling via `FieldEnumType`

### Composition via Multiple Inheritance

Models compose behavior through the `inherits` array using **depth-first, last-wins** resolution. Rather than deep class hierarchies, models mix in capabilities:
```
data.note inherits [data.directory, common.description, common.attributeList]
                     └── groupId     └── description    └── attributes
```

### Layered Authorization

Authorization follows a **declarative, participation-based** model:
1. **Schema layer**: `access.roles` on models/fields defines required roles
2. **Participation layer**: PBAC links actors to resources with effect qualifiers
3. **Client layer**: `AccessPoint` enforces authorization on all operations
4. **Bypass option**: Internal utilities can skip authorization when appropriate

### Separation: Universe vs World (Olio)

Simulation data follows an **immutable template / mutable instance** pattern:
- **Universe**: Static reference data, templates, word lists - shared across worlds
- **World**: Evolution-specific state - characters, events, inventory - reset independently

### Performance-Conscious Design

- **Batch operations**: Create/update arrays of records in single transactions
- **Query planning**: Explicit control over projection and foreign reference depth
- **Field locks**: Prevent concurrent edits without full record locking
- **Participation caching**: Authorization decisions are cacheable

### Maturity Characteristics

This is a **mature internal platform** with:
- Comprehensive meta-model (schema introspection, validation rules, computed fields)
- Flexible authorization (PBAC supporting GBAC/RBAC/ABAC patterns)
- Extensibility points (providers, factories, custom rules)
- Some legacy/deprecated paths (file-based persistence is iceboxed)

The design prioritizes **explicit over implicit**: type information comes from schema, authorization is declarative, and field access uses typed getters (`getEnum()`, `get()`) rather than reflection.

## Core Architecture

### JSON Model Schema System

Models are defined as JSON files in `src/main/resources/models/` organized by domain (common, auth, data, olio, etc.).

**Key model properties:**
- `name`: Fully qualified model name (e.g., `auth.group`, `common.parent`)
- `inherits`: Array of parent models for multiple inheritance
- `fields`: Array of field definitions
- `constraints`: Uniqueness constraints (e.g., `"name, parentId, organizationId"`)
- `query`: Suggested (default) fields used for querying
- `hints`: Field index guidance
- `factory`: Optional custom factory class
- `dedicatedParticipation`: Whether model has its own participation table

**Model inheritance resolution:** Depth-first traversal with last-wins for field conflicts. When a model inherits from multiple parents, each parent tree is resolved depth-first, and later field definitions override earlier ones.

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

### Field Providers

Providers implement `IProvider` interface and are invoked during record operations (NEW, CREATE, UPDATE, READ, INSPECT). They enable computed fields, encryption, and path resolution.

**IProvider interface:**
```java
void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model);
void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field);
```

**Built-in Providers:**

| Provider | Purpose |
|----------|---------|
| `PathProvider` | Computes hierarchical `path` by walking parent chain |
| `EncryptFieldProvider` | Encrypts/decrypts fields via VaultService |
| `ComputeProvider` | Computes values from other fields (AVG, PERC, PERC20) |

**Provider field configuration:**
```json
{
  "name": "path",
  "type": "string",
  "virtual": true,
  "provider": "org.cote.accountmanager.provider.PathProvider",
  "priority": 2,
  "baseModel": "auth.group",
  "baseProperty": "groupId"
}
```

### Computed Fields

Fields can be automatically computed from other fields using `ComputeProvider`:

```json
{
  "name": "athleticism",
  "type": "int",
  "virtual": true,
  "provider": "org.cote.accountmanager.provider.ComputeProvider",
  "compute": "AVG",
  "fields": ["physicalStrength", "physicalEndurance", "agility", "speed"],
  "priority": 30
}
```

**ComputeEnumType values:**
- `AVG`: Average of listed fields
- `PERC`: Percentage calculation
- `PERC20`: Percentage with 20-point scaling

### Field Priority

The `priority` field (integer, lower = earlier) controls the order providers are processed. This is critical when computed fields depend on other computed fields:

```json
// willpower computes first (priority: 1)
{ "name": "willpower", "compute": "AVG", "fields": ["mentalEndurance", "mentalStrength"], "priority": 1 }

// athleticism computes later (priority: 30) - can depend on willpower
{ "name": "athleticism", "compute": "AVG", "fields": ["willpower", "agility"], "priority": 30 }
```

**Priority guidelines:**
- `1-10`: Base computed fields with no dependencies
- `10-20`: Fields depending on base computations
- `20-30`: Higher-level aggregates
- `100`: Post-processing (e.g., journaling)

### Field Encryption

Fields marked with `encrypt: true` are automatically encrypted/decrypted via `EncryptFieldProvider` and `VaultService`:

```json
{
  "name": "streamSource",
  "type": "string",
  "provider": "org.cote.accountmanager.provider.EncryptFieldProvider",
  "priority": 10,
  "encrypt": true
}
```

**Requirements:**
- Model must inherit from `crypto.vaultExt`
- Organization must have an initialized vault

### VaultService (Complex Encryption)

`VaultService` provides organization-level encryption with key management:

**Architecture:**
- **Organization Vault**: Per-organization asymmetric key pair
- **Active Key**: Rotating symmetric key for data encryption
- **Protected Credential**: Password-protected vault key stored on filesystem
- **Salt**: Per-vault salt for key derivation

**Key classes:**
- `VaultService`: Singleton service for vault operations
- `VaultBean`: Vault configuration wrapper
- `CryptoBean`: Key/cipher container

**Vault lifecycle:**
```java
// Get or create organization vault
VaultBean vault = VaultService.getInstance().getCreateVault(user, "vaultName", orgId);

// Encrypt a field value
VaultService.getInstance().vaultField(vault, record, field);

// Decrypt a field value
VaultService.getInstance().unvaultField(vault, record, field);
```

**Vaulted record tracking:**
- `vaulted`: Boolean indicating encryption status
- `vaultId`: Reference to vault used
- `keyId`: Reference to symmetric key used
- `vaultedFields`: List of encrypted field names
- `unvaultedFields`: List of decrypted field names (transient)

### Model and Field-Level Access Control

Access control can be defined at both model and field levels via `access.roles`:

**Model-level access** (applies to all operations on the model):
```json
{
  "name": "access.accessRequest",
  "access": {
    "roles": {
      "create": ["Requesters"],
      "read": ["RequestReaders"],
      "update": ["RequestUpdaters", "RequestAdministrators"],
      "delete": ["RequestAdministrators"],
      "admin": ["RequestAdministrators"]
    }
  }
}
```

**Field-level access** (restricts specific field operations):
```json
{
  "name": "approvalStatus",
  "type": "enum",
  "access": {
    "roles": {
      "update": ["Approvers", "RequestUpdaters"]
    }
  }
}
```

**Field-level access on foreign references** (e.g., `identity.contactInformationExt`):
```json
{
  "name": "contactInformation",
  "baseModel": "identity.contactInformation",
  "type": "model",
  "foreign": true,
  "access": {
    "roles": {
      "read": ["AccountUsersReaders"]
    }
  }
}
```

**How it works:**
- Roles are resolved within the organization context
- Dynamic policies are assembled based on query fields
- Field-level access is evaluated when the field is included in a query plan

### Record Serialization

`RecordSerializer` and `RecordDeserializer` handle JSON conversion for `BaseRecord` objects:

- Support for field condensing (short names)
- Foreign key handling (serializes as `fieldName_FK` with ID value)
- Recursion prevention for self-referential models
- Configurable filtering of virtual, ephemeral, and foreign fields
- Automatic compression/encryption handling for byte stores

**Key classes:**
- `RecordSerializer`: Extends Jackson `JsonSerializer<BaseRecord>`
- `RecordDeserializer`: Extends Jackson `StdDeserializer<T extends BaseRecord>`
- `RecordSerializerConfig` / `RecordDeserializerConfig`: Configuration factories

### List Serialization Schema Loss

**IMPORTANT:** When serializing lists of records, the `schema` property may only appear on the first item in the array. Subsequent items may omit the schema to reduce payload size.

**Serialized list example:**
```json
{
    "results": [
        { "schema": "data.group", "id": 1, "name": "First" },
        { "id": 2, "name": "Second" },     // No schema!
        { "id": 3, "name": "Third" }       // No schema!
    ]
}
```

**On the client/consumer side, you must patch the schema back in:**
```java
// Java pattern for restoring schema
String schemaName = results.get(0).getSchema();
for (int i = 1; i < results.size(); i++) {
    if (results.get(i).getSchema() == null) {
        results.get(i).setSchema(schemaName);
    }
}
```

This optimization is controlled by the serializer and happens automatically for homogeneous lists. Always assume lists may have schema only on the first element and restore as needed.

### Patch Updates (Partial Updates)

For small updates, use PATCH instead of full record update. A patch only includes the changed fields plus identity fields:

**Building a patch:**
```java
// Get existing record
BaseRecord existing = accessPoint.find(user, query);

// Create patch with only identity + changed fields
BaseRecord patch = RecordFactory.newInstance(existing.getSchema());
patch.set("id", existing.get("id"));           // Required: identity field
patch.set("objectId", existing.get("objectId")); // Required: identity field
patch.set("description", "New description");    // Changed field

// Apply patch via AccessPoint
accessPoint.update(user, patch);
```

**Patch via JSON (REST API):**
```json
{
    "schema": "data.group",
    "id": 123,
    "objectId": "abc-123-def",
    "description": "Updated description only"
}
```

**Patch rules:**
- Must include at least one identity field (`id`, `objectId`, or `urn`)
- Only fields present in the patch are updated
- Omitted fields remain unchanged
- Foreign fields can be patched by providing the ID reference
- The `schema` field is required to identify the model type

## Data Organization Hierarchy

### Organizations (Multi-tenant boundary)
Top-level isolation scope. All data belongs to an organization via `organizationId`. Organizations themselves use parent hierarchy.

### Groups (Container hierarchy)
Directory-like containers for organizing data. Groups use `common.parent` for self-referential hierarchy. The `common.directory` model adds `groupId` to place records within a group.

### Parents (Self-referential hierarchy)
Models inheriting `common.parent` can form trees within themselves. Some models use ONLY parent hierarchy (e.g., groups, roles), while others use group hierarchy OR both.

**Hierarchy patterns:**
- **Group + optional Parent**: Models like `data.note` must specify a group and may optionally have a parent within that group
- **Parent only**: Models like `auth.group` and `auth.role` use only parent hierarchy
- **Neither**: Models like `system.audit` don't use hierarchical organization

## Query System

### Default Query Fields in Model Definitions

**IMPORTANT:** Model definitions include a `query` array that specifies the default fields returned when querying that model. These fields are inherited from parent models.

```json
// common.base defines core query fields
{
  "name": "common.base",
  "query": ["id", "urn", "objectId", "ownerId"]
}

// data.directory adds directory-specific fields
{
  "name": "data.directory",
  "query": ["groupId", "groupPath", "organizationId"]
}
```

The effective query fields for any model are the union of its `query` array with all inherited model query arrays. For example, a model inheriting `data.directory` would have query fields: `[id, urn, objectId, ownerId, groupId, groupPath, organizationId, ...]`.

**Why this matters:** When fetching records without explicit field projection, only the query fields (plus identity fields) are returned. Nested foreign models and non-query fields are NOT automatically included.

### Query Class

The `Query` class (`org.cote.accountmanager.io.Query`) provides hierarchical query building:

```java
Query q = new Query(user, "auth.group");
q.field("name", "MyGroup");
q.field("type", ComparatorEnumType.EQUALS, "DATA");
q.setRequestRange(0, 100);
q.planMost(false);  // Plan for most fields without recursion
```

**Query features:**
- Field comparators: EQUALS, NOT_EQUALS, LIKE, ILIKE, IN, NOT_IN, GREATER_THAN, LESS_THAN, GROUP_AND, GROUP_OR
- Nested query groups for complex conditions
- Pagination via `startRecord` and `recordCount`
- Sorting via `sortField` and `order`

### QueryPlan (Projection + Depth Control)

QueryPlan specifies which fields to retrieve and how deeply to follow foreign references:

```java
query.plan();                    // Initialize plan
query.planCommon(false);         // Request common fields only
query.planMost(true, filterList); // Request most fields with recursion
query.planField("members", new String[]{"name", "id"}, true); // Specific field with sub-fields
```

### Accessing Nested Foreign Models

**By default, foreign model fields are NOT populated** when retrieving records. You must explicitly plan for them.

**Problem:** Fetching a person only returns IDs for foreign fields:
```java
Query q = QueryUtil.createQuery("olio.charPerson", FieldNames.FIELD_OBJECT_ID, personId);
BaseRecord person = accessPoint.find(user, q);
// person.get("statistics") → null or unpopulated
// person.get("store") → null or unpopulated
```

**Solution 1: Use planMost() for recursive retrieval:**
```java
Query q = QueryUtil.createQuery("olio.charPerson", FieldNames.FIELD_OBJECT_ID, personId);
q.planMost(true);  // Recursively plan for most fields
BaseRecord person = accessPoint.find(user, q);
// person.get("statistics") → populated BaseRecord
```

**Solution 2: Explicitly request specific nested fields:**
```java
Query q = QueryUtil.createQuery("olio.charPerson", FieldNames.FIELD_OBJECT_ID, personId);
q.setRequest(new String[] {
    "id", "objectId", "name",      // Base fields
    "statistics",                   // Foreign model - will be populated
    "store",                        // Foreign model - will be populated
    "profile.portrait"              // Nested path - get portrait from profile
});
BaseRecord person = accessPoint.find(user, q);
```

**Solution 3: Build a custom QueryPlan for fine control:**
```java
Query q = QueryUtil.createQuery("olio.charPerson", FieldNames.FIELD_OBJECT_ID, personId);
QueryPlan plan = q.getPlan(q.getType());

// Plan specific fields on the main model
plan.getPlanFields().addAll(Arrays.asList("id", "name", "statistics", "instinct"));

// Create sub-plan for nested model with specific fields
QueryPlan statsPlan = plan.plan("statistics", new String[]{"physicalStrength", "agility", "speed"});

// Recursively plan another nested model
QueryPlan instinctPlan = plan.plan("instinct", new String[0]);
instinctPlan.planForCommonFields(true);

BaseRecord person = accessPoint.find(user, q);
```

**QueryPlan methods:**
- `planForCommonFields(recurse)`: Plan for common fields from model definition
- `planForMostFields(recurse, filterList)`: Plan for most non-blob fields
- `plan(fieldName, fields)`: Create sub-plan for a foreign field
- `getSubPlan(fieldName)`: Get existing sub-plan
- `unplan(fieldName)`: Remove a field from the plan
- `filterRecord(record)`: Filter a record to match the plan (useful post-retrieval)

### StatementUtil (Database Operations)

`StatementUtil` generates SQL statements from queries and records:

- `getSelectTemplate()`: Builds SELECT with JOIN clauses for foreign references
- `getInsertTemplate()` / `getUpdateTemplate()` / `getDeleteTemplate()`: CRUD operations
- `getInnerSelectTemplate()`: Generates subqueries for foreign lists via participation tables
- Supports PostgreSQL (primary) and H2 databases
- Handles vector fields via pgvector extension

### Search and Pagination

The `ISearch` interface provides query execution with pagination support:

**ISearch methods:**
```java
BaseRecord findRecord(Query query);           // Single result
BaseRecord[] findRecords(Query query);        // Multiple results
QueryResult find(Query query);                // Full result with pagination info
int count(Query query);                       // Count only
BaseRecord findByPath(user, model, path, orgId);  // Path-based lookup
```

**Pagination with Query:**
```java
Query q = new Query(user, "data.note");
q.field("groupId", groupId);
q.setRequestRange(0, 50);        // Start at 0, return 50 records
q.setValue("sortField", "name");
q.setValue("order", OrderEnumType.ASCENDING.toString());
q.planMost(false);

QueryResult result = IOSystem.getActiveContext().getSearch().find(q);
int count = result.getCount();           // Records in this page
long totalCount = result.getTotalCount(); // Total matching records
BaseRecord[] records = result.getResults();
```

**Iterating through pages:**
```java
int pageSize = 100;
long startRecord = 0;
QueryResult result;

do {
    Query q = new Query(user, "data.note");
    q.field("groupId", groupId);
    q.setRequestRange(startRecord, pageSize);
    q.planCommon(false);

    result = IOSystem.getActiveContext().getSearch().find(q);

    for (BaseRecord record : result.getResults()) {
        // Process record
    }

    startRecord += pageSize;
} while (result.getCount() == pageSize);
```

**QueryResult fields:**
- `count`: Number of records in current result set
- `totalCount`: Total records matching query (for pagination UI)
- `results`: Array of matching records
- `response`: Operation status (SUCCEEDED, FAILED, etc.)
- `queryKey` / `queryHash`: For caching

## Identity and Access Types

AccountManager7 separates identity concepts similar to IAM systems:

### Identity Hierarchy

**`identity.person`** - The actual identity (like IAM Identity)
- Represents a real individual
- Contains personal information: name, birthDate, gender, etc.
- Can have multiple `users` and `accounts` linked
- Has `personality`, `behavior`, and `profile` extensions
- Stored in organization's "Persons" group

**`identity.account`** - External system accounts (like IAM Account)
- Represents an account in an external system or application
- Contains `type`, `status`, `accountId`, `referenceId`
- Used to track accounts outside AM7 (e.g., cloud provider accounts, third-party services)
- Does NOT provide AM7 system access

**`system.user`** - The AM7 system user
- The actual login/authentication identity for AM7
- Has `homeDirectory` (group), `status`, `type`
- Required for all AM7 operations (contextUser)
- Linked to a person via `person.users` participation

**Relationship example:**
```
identity.person (John Smith)
├── users: [system.user (jsmith)]        # AM7 login
├── accounts: [identity.account (AWS), identity.account (Azure)]  # External accounts
├── personality: identity.personality
└── contactInformation: identity.contactInformation
```

### Roles and Permissions

**`auth.role`** - Groups permissions, assigned to users/accounts/persons/groups

**RoleEnumType:**
- `USER`: Role for system.user assignments
- `ACCOUNT`: Role for identity.account assignments
- `PERSON`: Role for identity.person assignments
- `UNKNOWN`: Default/unspecified

**`auth.permission`** - Controls access to resources and actions

**PermissionEnumType:**
- `DATA`, `GROUP`, `ROLE`, `PERMISSION`: Resource-type permissions
- `ACCOUNT`, `USER`, `PERSON`: Identity-type permissions
- `OBJECT`, `APPLICATION`: Generic permissions

### System vs User-Defined Roles

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

## Participation-Based Access Control (PBAC)

### Core Concept

Participation records link actors to resources with optional effect qualifiers. This provides the foundation for GBAC, RBAC, and ABAC models.

**Participation table structure:**
- `participationModel`: The model type being participated in
- `participationId`: ID of the parent record
- `participantModel`: The model type of the participant
- `participantId`: ID of the participant
- Additional fields for relationship context

### Authorization Flow

User-driven operations follow: **Query -> AccessClient -> PBAC Evaluation -> Execution**

Utility operations may bypass AccessClient for performance, going directly: **Query -> Execution**

### AccessPoint Class

`AccessPoint` (`org.cote.accountmanager.client.AccessPoint`) wraps all CRUD operations with authorization:

```java
accessPoint.create(contextUser, record);     // Checks canCreate
accessPoint.update(contextUser, record);     // Checks canUpdate
accessPoint.delete(contextUser, record);     // Checks canDelete
accessPoint.find(contextUser, query);        // Checks canRead
accessPoint.list(contextUser, query);        // Checks query authorization
```

**Authorization features:**
- Policy-based decisions with caching
- Bulk operation optimization with container-level approval
- Field-level locking support
- Audit trail generation

### Model-Level Access Binding

Models can define role requirements for operations via `ModelAccessRoles`:
```json
"roles": {
  "read": ["AccountUserReadersRole"],
  "update": ["AccountUsersRole"]
}
```

## Olio Population Dynamics

Olio ("hodgepodge") is a simulation framework for population dynamics, character interactions, and world evolution.

### Universe vs World Architecture

**Universe (Templates):**
- Contains static reference data and templates
- Shared across multiple worlds
- Includes: location data, word lists, name databases, clothing templates, etc.
- Located in separate group hierarchy

**World (Instances):**
- Contains evolution-specific mutable state
- Characters, events, relationships, inventory
- Copied/generated from universe templates
- Can be reset independently

### Core Olio Models

Located in `src/main/resources/models/olio/`:

- **Realm**: Geographic/political region with origin location
- **Event**: Hierarchical event structure (epochs, increments)
- **Interaction**: Actor-to-actor exchanges with roles, threats, outcomes
- **Character extensions**: personality, statistics, state, instinct

### Time System

**Hierarchy:**
- **Epoch**: Root time period (default: 1 year)
- **Realm Event**: Location-specific epoch segment
- **Increment**: Atomic time unit for actions

**Clock class** manages time state per realm with methods:
- `getEpoch()`, `getEvent()`, `getIncrement()`
- `realmClock(realm)` for realm-specific time

### Context Rules & Evolution Rules

**IOlioContextRule**: Evaluated during context initialization
- `pregenerate()`: Before world generation
- `generate()`: Create locations/regions
- `postgenerate()`: After generation
- `generateRegion()`: Per-realm setup

**IOlioEvolveRule**: Evaluated during evolution cycles
- `continueEpoch()`, `continueRealmEvent()`, `continueRealmIncrement()`
- `evaluateRealmIncrement()`: Process time increment

### Population Management

```java
OlioContext ctx = new OlioContext(config);
ctx.initialize();

// Access population data
List<BaseRecord> population = ctx.getRealmPopulation(realm);
Map<String, List<BaseRecord>> demographics = ctx.getDemographicMap(location);
```

### Olio Map System (MGRS-Inspired)

The Olio simulation uses a coordinate system inspired by **MGRS (Military Grid Reference System)** for spatial positioning. This enables hierarchical location tracking from large regions down to individual meter positions.

**IMPORTANT:** Full coordinate resolution requires **both** the geolocation object (type='cell') **and** the state object. The cell identifies a 100m x 100m space, while state.currentEast/currentNorth identifies position within that cell at 1-meter resolution.

#### Default Map Setup

**Grid Zone Designator (GZD):**
- Hardcoded as **"30K"** - a zone in the middle of the south Atlantic, chosen arbitrarily to avoid conflict with real geographic data

**kident Grid System:**
- Column letters: `ABCDEFGHJKLMNPQRSTUVWXYZ` (24 letters, omitting I and O)
- Row letters: `ABCDEFGHJKLMNPQRSTUV` (20 letters, omitting I and O)
- A **random kident** is chosen to represent the initial 'world' region
- The world can grow/move to adjacent kidents via dynamic population of cells

#### Map Preparation Process

**`prepareMapGrid()`** - Initializes the base grid:
- Creates the GZD zone record
- Populates all kident (100km²) grid squares as `admin2` type locations

**`prepareK100()`** - Prepares a single kident (100km x 100km):
- Creates `MAP_EXTERIOR_FEATURE_WIDTH` x `MAP_EXTERIOR_FEATURE_HEIGHT` (100 x 100) feature cells
- Each feature cell = 1 square kilometer, identified as `admin2` type
- Calls `connectRegions()` to blend terrain types and establish water dynamics (mountain to coast flow)
- Uses `TerrainUtil.blastAndWalk()` for terrain generation

**`prepareCells()`** - Prepares cells within a 1 sq km feature:
- Creates `MAP_EXTERIOR_CELL_WIDTH` x `MAP_EXTERIOR_CELL_HEIGHT` (10 x 10) interior cells
- Each cell = 100m x 100m (multiplied by `MAP_EXTERIOR_CELL_MULTIPLIER` = 10)
- Applies terrain from parent feature via `TerrainUtil.blastCells()`
- Paints points of interest via `PointOfInterestUtil`

#### MGRS-Style Coordinate Structure

```
Full coordinate example: 30K AB 12 34 056 078
                         │   │  │  │  │   │
                         │   │  │  │  │   └─ state.currentNorth (meters within cell, 0-99)
                         │   │  │  │  └───── state.currentEast (meters within cell, 0-99)
                         │   │  │  └──────── cell.northings (cell Y within feature, 0-9)
                         │   │  └─────────── cell.eastings (cell X within feature, 0-9)
                         │   └────────────── kident (100Km square: column letter + row letter)
                         └────────────────── GZD (Grid Zone Designator, always "30K")
```

#### Location Hierarchy

| Level | Type/Model | Description | Scale | Code Reference |
|-------|------------|-------------|-------|----------------|
| GZD | `country` | Grid Zone Designator (hardcoded "30K") | ~100,000km² | `GeoLocationUtil.GZD` |
| Kident | `admin2` | 100km x 100km grid square | 10,000 km² | `prepareMapGrid()` |
| Feature | `feature` | 1km x 1km grid cell within kident | 1 km² | `prepareK100()` |
| Cell | `cell` | 100m x 100m grid cell within feature | 10,000 m² | `prepareCells()` |
| Intra-cell | state | 1m x 1m position within cell | 1 m² | `state.currentEast/North` |

#### Position Storage on Characters

Character position requires **both** the `state.currentLocation` (cell reference) **and** `state.currentEast/currentNorth` (intra-cell position):

```java
BaseRecord state = person.get("state");

// Cell reference (the actual 100m x 100m cell object)
BaseRecord cell = state.get("currentLocation");  // geolocation with type='cell'
int cellEast = cell.get("eastings");             // Cell X within feature (0-9)
int cellNorth = cell.get("northings");           // Cell Y within feature (0-9)

// Intra-cell position (meter-level precision within the 100m x 100m cell)
int currentEast = state.get("currentEast");      // X within cell (0-99 meters)
int currentNorth = state.get("currentNorth");    // Y within cell (0-99 meters)
```

**Finest Resolution:** 1 meter (currentEast/currentNorth range 0-99 within a 100m x 100m cell)

**Full position calculation (within a feature):**
```
Absolute X = (cellEast * 100) + currentEast     // Total meters from west edge of feature
Absolute Y = (cellNorth * 100) + currentNorth   // Total meters from north edge of feature
```

**Full position calculation (within a kident):**
```java
// GeoLocationUtil.getXCoordinateToState() does this calculation:
int featureEast = parentFeature.get("eastings");  // Feature X within kident (0-99)
int cellEast = cell.get("eastings");              // Cell X within feature (0-9)
int stateEast = state.get("currentEast");         // Meters within cell (0-99)

// Total meters from kident west edge:
int absoluteX = (featureEast * 1000) + (cellEast * 100) + stateEast;
```

#### Distance Calculations

Use `GeoLocationUtil` for distance and direction calculations:

```java
import org.cote.accountmanager.olio.GeoLocationUtil;

// Simple Pythagorean distance between two points
double dist = GeoLocationUtil.distance(x1, y1, x2, y2);

// Full distance between two character states (handles cell + meter position)
double distance = GeoLocationUtil.getDistanceToState(state1, state2);

// Get direction from one state to another
DirectionEnumType direction = GeoLocationUtil.getDirectionFromState(fromState, toState);
```

#### Interaction Distances

Key distance constants used throughout Olio:

| Constant | Value | Purpose |
|----------|-------|---------|
| `PROXIMATE_CONTACT_DISTANCE` | 1.5 meters | Close interaction range (touching, handing items) |
| `MAXIMUM_CONTACT_DISTANCE` | 5 meters | Talk/communication range |
| `MAXIMUM_OBSERVATION_DISTANCE` | 10 cells (~1km) | Maximum visibility/detection range |

#### Direction System

Olio uses 8 cardinal/ordinal directions (`DirectionEnumType`):

```
    NORTH
      │
WEST ─┼─ EAST
      │
    SOUTH

Plus: NORTHEAST, NORTHWEST, SOUTHEAST, SOUTHWEST
```

#### Movement Mechanics

**Walk action** - Move by direction and distance:
```java
// Walk.java - directional movement
// Moves character in specified direction by specified distance
// Handles cell boundary transitions automatically
```

**WalkTo action** - Move toward a target:
```java
// WalkTo.java - target-based movement
// Moves character toward target location until within PROXIMATE_CONTACT_DISTANCE
// Calculates direction automatically from current position
```

**Low-level movement** via `StateUtil`:
```java
import org.cote.accountmanager.olio.StateUtil;

// Move by one meter in specified direction, handling cell boundaries
StateUtil.moveByOneMeterInCell(state, direction);

// This automatically:
// - Updates currentEast/currentNorth
// - Handles cell boundary crossing (updates eastings/northings)
// - Updates currentLocation reference when entering new cell
```

#### Cell Crossing Bug (Fixed January 2026)

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

#### Visibility and Observation

Characters can observe entities within `MAXIMUM_OBSERVATION_DISTANCE` (10 cells ≈ 1km):

```java
// Get visible characters from current position
List<BaseRecord> visible = CharacterUtil.getVisibleCharacters(ctx, observer);

// Check if specific target is visible
boolean canSee = GeoLocationUtil.getDistanceToState(observerState, targetState)
                 <= (MAXIMUM_OBSERVATION_DISTANCE * 100);  // Convert cells to meters
```

#### Cell Features and Terrain

Each `olio.locCell` can contain:
- `terrainType`: Ground type (affects movement speed, visibility)
- `features`: List of geographic features in the cell
- `structures`: Buildings, shelters, etc.

### Working with Individual Olio Objects

**IMPORTANT:** Olio code expects fully and deeply populated objects with all nested foreign models resolved.

### Query Planning for Full Data

Use `OlioUtil.planMost(query)` when building queries for Olio objects:

```java
import org.cote.accountmanager.olio.OlioUtil;

// Build query with full data planning
Query q = QueryUtil.createQuery("olio.charPerson", FieldNames.FIELD_OBJECT_ID, objectId);
OlioUtil.planMost(q);  // Plans for all nested foreign models
BaseRecord person = IOSystem.getActiveContext().getSearch().findRecord(q);

// Now all nested foreign models are populated:
// - state.currentLocation
// - statistics (all stat fields)
// - store.apparel, store.items
// - profile.portrait
// - instinct, personality, etc.
```

### Utilities That Already Return Full Records

**Note:** `GameUtil.findCharacter()` already calls `OlioUtil.planMost()` internally. Do NOT call `getFullRecord()` after utilities that already return full data:

```java
// CORRECT - findCharacter already returns full data
BaseRecord person = GameUtil.findCharacter(objectId);
// person already has all nested data

// WRONG - redundant double-load
BaseRecord person = GameUtil.findCharacter(objectId);
person = OlioUtil.getFullRecord(person);  // Unnecessary!
```

### When to Use getFullRecord()

Use `OlioUtil.getFullRecord(record)` only when you have a **partial** record and need full data:

```java
// When you have a partial record from a list or minimal query
BaseRecord partial = someRecordWithMinimalData;
BaseRecord full = OlioUtil.getFullRecord(partial);
// full now has all nested foreign models populated
```

**How getFullRecord works:**
- Creates a query from the record's id/objectId/urn
- Applies `OlioUtil.planMost(query)` for Olio-specific query planning
- Returns a new fully-populated record from the database

### Personality & Interaction System

**Big 5 (OCEAN) personality traits:**
- Openness, Conscientiousness, Extraversion, Agreeableness, Neuroticism

**MBTI compatibility** for relationship modeling

**Interaction model** captures:
- Actor/Interactor roles and alignments
- Threat assessments
- Reasons and outcomes
- Claims/motivations for narrative generation

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

### Schema-Driven Type Information

Each field in a model schema defines:
- `type`: The field type (string, int, long, enum, model, list, etc.)
- `baseClass`: For enums, the fully-qualified Java enum class (e.g., `org.cote.accountmanager.schema.type.GroupEnumType`)
- `baseModel`: For foreign/model fields, the target model name
- `baseType`: For lists, the element type (e.g., `model` for `List<BaseRecord>`)

Access schema information via:
```java
ModelSchema ms = RecordFactory.getSchema("auth.group");
FieldSchema fs = ms.getFieldSchema("type");
FieldEnumType fieldType = fs.getFieldType();  // Returns ENUM
String enumClass = fs.getBaseClass();         // Returns the Java enum class
```

### Reading Enum Values Correctly

Enum fields store values as strings internally but can be retrieved as typed Java enums:

```java
// WRONG - Don't manually parse enum strings
String typeStr = record.get("type");
GroupEnumType type = GroupEnumType.valueOf(typeStr);  // Unnecessary!

// CORRECT - Use getEnum() for typed enum access
GroupEnumType type = record.getEnum("type");

// For string representation (e.g., for display or serialization)
String typeStr = record.get("type");  // Returns "DATA", "USER", etc.
```

**How it works internally:**
1. `EnumValueType` stores the string value and the `baseClass` from the schema
2. `getValue()` returns the string representation
3. `getEnumValue()` uses `RecordFactory.getEnumValue(baseClass, value)` to return the typed Java enum

### Setting Enum Values

Enums can be set using either the enum constant or its string representation:

```java
// Both are valid:
record.set("type", GroupEnumType.DATA);           // Enum constant
record.set("type", GroupEnumType.DATA.toString()); // String representation
record.set("type", "DATA");                        // Raw string
```

The `EnumValueType.setValue()` validates against the `baseClass` - invalid enum values throw `ValueException`.

### Field Type Checking Pattern

When working with records generically, check the field type first:

```java
FieldType field = record.getField("someField");
if (field.getValueType() == FieldEnumType.ENUM) {
    SomeEnum value = record.getEnum("someField");
} else if (field.getValueType() == FieldEnumType.MODEL) {
    BaseRecord value = record.get("someField");
} else if (field.getValueType() == FieldEnumType.LIST) {
    List<?> value = record.get("someField");
}
```

### Common Field Types (FieldEnumType)

| Type | Java Type | Notes |
|------|-----------|-------|
| `STRING` | String | Default text type |
| `INT` | Integer | 32-bit integer |
| `LONG` | Long | 64-bit integer (used for IDs) |
| `DOUBLE` | Double | Floating point |
| `BOOLEAN` | Boolean | |
| `ENUM` | String/Enum | Use `getEnum()` for typed access |
| `MODEL` | BaseRecord | Foreign single reference |
| `LIST` | List<?> | Check `baseType` for element type |
| `BLOB` | byte[] | Binary data |
| `TIMESTAMP` | Date | Legacy date type |
| `ZONETIME` | ZonedDateTime | Preferred date/time type |
| `FLEX` | varies | Abstract/polymorphic field |

## Key Utility Classes

| Class | Purpose |
|-------|---------|
| `RecordFactory` | Creates model instances from schema, enum validation |
| `Factory` | High-level factory with `newInstance()` supporting ParameterList |
| `QueryUtil` | Query building helpers |
| `IOSystem` | Active context access (reader, writer, search) |
| `RecordUtil` | Field introspection, identity checks, batch operations |
| `FieldUtil` | Field value manipulation, flex field handling |
| `MemberUtil` | Participation/membership management |
| `ModelSchema` | Access model definition and field schemas |
| `FieldSchema` | Access field type, baseClass, modifiers |
| `VaultService` | Organization-level encryption and key management |
| `CryptoBean` | Key/cipher container for cryptographic operations |
| `PathUtil` | Path resolution and directory creation |
| `AttributeUtil` | Add/get/query flexible key-value attributes |
| `FieldLockUtil` | Field-level locking for concurrent edit prevention |
| `RecordValidator` | Validates records against schema rules |
| `ValidationUtil` | Access built-in validation rules |
| `ParticipationFactory` | Create participation records for M:N relationships |

## Common Patterns

### Creating Records
```java
BaseRecord record = RecordFactory.newInstance("auth.group");
record.set("name", "MyGroup");
record.set("type", GroupEnumType.DATA.toString());
IOSystem.getActiveContext().getRecordUtil().createRecord(record);
```

### Querying with Authorization
```java
Query q = QueryUtil.createQuery("auth.group", "name", "MyGroup");
q.field("organizationId", user.get("organizationId"));
BaseRecord result = accessPoint.find(user, q);
```

### Foreign List Management
Foreign lists use participation tables. Add/remove members via `MemberUtil`:
```java
IOSystem.getActiveContext().getMemberUtil().member(owner, group, user, effect, true);
```

### Factory and ParameterList

Use `Factory.newInstance()` with `ParameterList` for creating records with initialization parameters:

```java
// Create with path and name
ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/MyData");
plist.parameter(FieldNames.FIELD_NAME, "MyRecord");
BaseRecord record = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, owner, null, plist);

// Create credential with type and password
ParameterList credParams = ParameterUtil.newParameterList(FieldNames.FIELD_TYPE, "hashed_password");
credParams.parameter("password", "secretPassword");
BaseRecord cred = factory.newInstance(ModelNames.MODEL_CREDENTIAL, user, null, credParams);
```

**Common parameters:**
- `path`: Directory path for the record (triggers `groupId` resolution)
- `name`: Record name
- Model-specific parameters handled by custom factories

## Validation System

### Model-Level Validation

Models can define validation rules that apply to fields:

```json
{
  "name": "system.user",
  "validation": {
    "rules": [
      {
        "fields": ["name"],
        "rules": ["$minLen5"]
      }
    ]
  }
}
```

### Field-Level Validation

Fields can specify range validation:

```json
{
  "name": "physicalStrength",
  "type": "int",
  "minValue": 0,
  "maxValue": 20,
  "validateRange": true
}
```

### Built-in Validation Rules

| Rule | Description |
|------|-------------|
| `$minLen5` | Minimum length of 5 characters |
| `$notEmpty` | Field must not be empty |
| `$trim` | Trims whitespace (applied automatically) |

### Validation Utilities

```java
// Validate a record against its schema rules
boolean valid = RecordValidator.validate(record);

// Check hierarchy to prevent circular references
boolean hierarchyOk = HierarchyValidator.checkHierarchy(record, FieldNames.FIELD_PARENT_ID);

// Get a validation rule by name
BaseRecord rule = ValidationUtil.getRule("$notEmpty");
```

### Custom Validation Rules

`policy.validationRule` model supports:
- `expression`: Regex or comparison expression
- `function`: Reference to a policy function
- `comparator`: Comparison type (EQUALS, GREATER_THAN, etc.)
- `rules`: Nested child rules
- `errorMessage`: Custom error message
- `replacementValue`: Value to use when validation fails

## Bulk Operations

### Batch Create

Create multiple records in a single transaction for better performance:

```java
List<BaseRecord> records = new ArrayList<>();
for (int i = 0; i < 100; i++) {
    records.add(createRecord(i));
}

// Batch create via AccessPoint (with authorization)
accessPoint.create(user, records.toArray(new BaseRecord[0]));

// Batch create via RecordUtil (no authorization check)
ioContext.getRecordUtil().createRecords(records.toArray(new BaseRecord[0]));
```

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

## Field Locks

Prevent concurrent editing of specific fields using `FieldLockUtil`:

```java
// Lock a field (only the locking user can unlock)
boolean locked = FieldLockUtil.lockField(user, record, FieldNames.FIELD_NAME);

// Check if a field is locked
boolean isLocked = FieldLockUtil.isFieldLocked(user, record, FieldNames.FIELD_NAME);

// Get all locks on a record
List<String> locks = FieldLockUtil.getFieldLocks(user, record);

// Unlock a field (must be the user who created the lock)
boolean unlocked = FieldLockUtil.unlockField(user, record, FieldNames.FIELD_NAME);

// Clear all locks for a model/record
FieldLockUtil.clearFieldLocks(user, ModelNames.MODEL_DATA, recordId, null);
```

**Behavior:**
- Locked fields can still be read
- Updates to unlocked fields succeed even when other fields are locked
- Different users can see locks but cannot unlock fields they didn't lock

## Attributes

Models inheriting `common.attributeList` get a flexible key-value attribute system:

### Adding Attributes

```java
// Add typed attributes
AttributeUtil.addAttribute(record, "isActive", true);          // boolean
AttributeUtil.addAttribute(record, "priority", 5);             // int
AttributeUtil.addAttribute(record, "category", "important");   // string
```

### Reading Attributes

```java
// Get attribute record
BaseRecord attr = AttributeUtil.getAttribute(record, "isActive");

// Get typed value with default
boolean isActive = AttributeUtil.getAttributeValue(record, "isActive", false);
int priority = AttributeUtil.getAttributeValue(record, "priority", 0);
String category = AttributeUtil.getAttributeValue(record, "category", "default");
```

### Attribute Storage

Attributes use `referenced` storage - they're stored in a separate reference table linked by `referenceModel` and `referenceId`, not as inline data.

## Credentials and Authentication

### Creating Credentials

```java
ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_TYPE, "hashed_password");
plist.parameter("password", "userPassword");
BaseRecord cred = factory.newInstance(ModelNames.MODEL_CREDENTIAL, user, null, plist);
ioContext.getRecordUtil().createRecord(cred);
```

### Credential Types

| Type | Description |
|------|-------------|
| `HASHED_PASSWORD` | Salted hash of password |
| `TOKEN` | Bearer token |
| `KEY` | Cryptographic key |
| `CERTIFICATE` | X.509 certificate |

### Verifying Credentials

```java
ParameterList verifyParams = ParameterUtil.newParameterList("password", "userPassword");
VerificationEnumType result = factory.verify(user, credential, verifyParams);
// Returns: VERIFIED, FAILED_VERIFICATION, EXPIRED, etc.
```

### Credential Storage

- Passwords are salted and hashed (never stored in plaintext)
- Salt is stored in `hash.salt` field
- Credential is linked to owner via `referenceType` and `referenceId`

## Journaling (WIP)

The journaling system provides change tracking and audit trails for models. **Note:** This feature is a work in progress.

### Enabling Journaling

Models inherit from `data.journalExt` to enable journaling:

```json
{
  "name": "myModel.journaledData",
  "inherits": ["data.directory", "data.journalExt"],
  "fields": [...]
}
```

### Journal Structure

**`data.journal`** - Container for change history:
- `journaled`: Boolean indicating journaling is active
- `journalVersion`: Current version number (starts at 1.0)
- `journalHash`: Hash of current state for integrity
- `journalEntries`: List of `data.journalEntry` records

**`data.journalEntry`** - Individual change record:
- `journalDate`: Timestamp of the change
- `version`: Version number for this entry
- `baseline`: Whether this is a baseline snapshot
- `modified`: Flex model containing only the changed fields
- `fields`: List of field names that were modified
- `hash`: Hash of the modified fields (inherited from `crypto.hashExt`)

### How It Works

The `JournalProvider` (priority: 100) intercepts CREATE, UPDATE, and DELETE operations:

**On CREATE:**
1. Creates a new `data.journal` record
2. Creates initial journal entry with all non-excluded fields
3. Sets `journalVersion` to 1.0

**On UPDATE:**
1. Loads existing journal and builds baseline from all previous entries
2. Compares incoming fields against baseline
3. Creates new journal entry containing only changed fields
4. Computes hash of changed fields for integrity

**Excluded from journaling:**
- Identity fields (id, objectId, urn)
- Ephemeral fields
- Virtual/computed fields
- The journal field itself
- Fields with default values unchanged

### Usage Example

```java
// Create a custom journaled model (loaded from resources)
ModelSchema ms = RecordFactory.getCustomSchemaFromResource("journalObject", "journalObject");
ModelNames.loadCustomModels();

// Create record - journal is automatically created
BaseRecord data = RecordFactory.model("journalObject").newInstance();
data.set(FieldNames.FIELD_NAME, "MyData");
data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
data.set(FieldNames.FIELD_BYTE_STORE, "Initial content".getBytes());
ioContext.getRecordUtil().createRecord(data, true);

// Update record - only changed fields are journaled
data.set(FieldNames.FIELD_DESCRIPTION, "New description");
ioContext.getRecordUtil().updateRecord(data);

// Access journal
BaseRecord journal = data.get(FieldNames.FIELD_JOURNAL);
List<BaseRecord> entries = journal.get(FieldNames.FIELD_JOURNAL_ENTRIES);
```

### Current Limitations (WIP)

- Baseline reconstruction walks entries in reverse order
- Hash chain verification not fully implemented
- No built-in rollback/restore functionality yet
- Integration with file-based persistence is iceboxed

## Deprecated Features

**File-based persistence** (journaling, baselining, distributed ledger): Currently iceboxed. Focus on database persistence via PostgreSQL.

## File Structure

```
src/main/resources/
├── models/           # JSON model definitions
│   ├── common/       # Base models (parent, groupExt, nameId)
│   ├── auth/         # Authentication (group, role, permission, credential)
│   ├── data/         # Data storage (byteStore, journalExt)
│   ├── olio/         # Simulation models
│   ├── policy/       # Policy/authorization models
│   └── system/       # System models (organization, user)
├── facts/            # Policy fact definitions
├── functions/        # Policy function definitions
└── olio/             # Olio reference data (clothing, animals, personality)

src/main/java/org/cote/accountmanager/
├── record/           # BaseRecord, serialization
├── io/               # Query, IOSystem, readers/writers
├── io/db/            # StatementUtil, DBUtil
├── client/           # AccessPoint
├── policy/           # PolicyUtil, FactUtil
├── olio/             # Population simulation
└── schema/           # FieldNames, ModelNames, enums
```

## Development Guidelines

### Unit Test Coverage for Server Code

**IMPORTANT:** When modifying server-side code (especially in `AccountManagerObjects7` or `AccountManagerService7`), always ensure your changes are covered by unit tests.

**Before submitting changes:**
1. Identify which existing tests cover the modified code
2. Run those tests to verify the changes work correctly
3. If no tests exist, create new tests in the appropriate test class
4. Verify tests pass before claiming the change is complete

**Test location patterns:**
- `AccountManagerObjects7` code → Tests in `src/test/java/org/cote/accountmanager/objects/tests/`
- Olio-specific code → Tests in `src/test/java/org/cote/accountmanager/objects/tests/olio/`
- GameUtil changes → `TestGameUtil.java`
- InteractionUtil changes → Tests that exercise interactions (e.g., `TestGameUtil#TestInteract`)

**Running specific tests:**
```bash
# Run a specific test class
mvn test -Dtest=TestGameUtil

# Run a specific test method
mvn test -Dtest=TestGameUtil#TestInteract

# Run with verbose output
mvn test -Dtest=TestGameUtil -e
```

**Do NOT claim a change is working unless you have:**
1. Compiled the code successfully (`mvn compile`)
2. Run relevant unit tests (`mvn test -Dtest=...`)
3. Verified the tests pass (check for `BUILD SUCCESS`)
