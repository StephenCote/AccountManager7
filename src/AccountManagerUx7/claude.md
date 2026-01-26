# AccountManagerUx7 - Client Application

This document describes the client-side application built on top of AccountManagerService7 and AccountManagerObjects7.

## Overview

AccountManagerUx7 is a Mithril.js-based single-page application that provides a web interface for the Account Manager system. It features:
- Schema-driven UI generation
- Form validation matching server-side rules
- Client-side caching
- WebSocket real-time messaging
- Tailwind CSS styling

## Architecture

### Technology Stack

| Component | Technology |
|-----------|------------|
| Framework | Mithril.js |
| Styling | Tailwind CSS |
| Build | esbuild |
| Icons | Material Symbols, file-icon-vectors |

### Core Modules

The application is organized into these key modules:

| Module | File | Purpose |
|--------|------|---------|
| `am7model` | model/modelDef.js, model/model.js | Schema definition and model utilities |
| `am7client` | client/am7client.js | REST API client |
| `page` | client/pageClient.js | Application state and context |
| `am7view` | client/view.js | View utilities |
| `am7decorator` | client/decorator.js | UI decorators (icons, thumbnails) |

### Build Process

The application is built via `node build.js`:
- Concatenates all JS files (IIFEs, not ES modules)
- Minifies with esbuild
- Bundles CSS from Tailwind and dependencies
- Outputs to `dist/app.bundle.min.js` and `dist/app.bundle.min.css`

**Development**: Use `index.html` (loads individual files)
**Production**: Use `index.prod.html` (loads bundled files)

## Schema Integration

### Client-Side Schema (am7model)

The schema is generated from `/rest/schema` and stored in `model/modelDef.js`. This file is the client-side equivalent of the server's model definitions.

**Critical**: The client schema must be kept in sync with the server schema. Regenerate `modelDef.js` when models change.

Structure:
```javascript
let am7model = {
    "jsonModelKey": "schema",      // The key used to identify model type in JSON
    "categories": [...],           // UI navigation categories
    "models": [...],               // All model definitions
    "enums": {...},                // Enum value lists
    "validationRules": [...]       // Client-side validation rules
};
```

### Default Query Fields in Model Definitions

**IMPORTANT:** Each model in the schema includes a `query` array that specifies the default fields returned when querying. These fields are inherited from parent models.

```javascript
// Model definitions in modelDef.js include query arrays
{ "name": "common.base", "query": ["id", "urn", "objectId", "ownerId"] }
{ "name": "data.directory", "query": ["groupId", "groupPath", "organizationId"] }
```

When building queries, `am7client.newQuery()` automatically populates the `request` array from all inherited model query arrays:

```javascript
let q = am7client.newQuery("olio.charPerson");
// q.entity.request is auto-populated:
// ["id", "urn", "objectId", "name", "groupId", "groupPath", "organizationId", ...]

// To see what query fields a model has:
let queryFields = am7model.inheritsFrom("olio.charPerson")
    .filter(m => m.query)
    .map(m => m.query)
    .flat(1);
```

**Why this matters:** Only fields in the `request` array are returned. Nested foreign models must be explicitly added to get their data.

### Model Utilities (model/model.js)

Key functions for working with models:

| Function | Purpose |
|----------|---------|
| `am7model.getModel(type)` | Get model definition by type name |
| `am7model.getModelField(model, name)` | Get field definition |
| `am7model.getModelFields(model)` | Get all fields including inherited |
| `am7model.hasField(model, name)` | Check if field exists |
| `am7model.newPrimitive(type)` | Create new entity with defaults |
| `am7model.inherits(model, parent)` | Check inheritance |
| `am7model.inheritsFrom(model)` | Get full inheritance chain |
| `am7model.isGroup(model)` | Check if model is a group/directory |
| `am7model.isParent(model)` | Check if model has parent reference |

### The jsonModelKey

**Critical**: Every object must have a `schema` property (accessed via `am7model.jsonModelKey`) that identifies its model type. This is used throughout the codebase.

```javascript
// Creating an object
let group = am7model.newPrimitive("data.group");
// group.schema === "data.group"

// Reading the type
let type = obj[am7model.jsonModelKey];  // Returns "data.group"
```

### Model Instances (prepareInstance)

For form editing, wrap entities in instances:

```javascript
// Create instance with API accessors
let instance = am7model.prepareInstance(entity, formDef);

// Access fields via API (applies decorators)
let name = instance.api.name();        // Read
instance.api.name("New Name");         // Write

// Track changes
instance.changes;                      // Array of changed field names
instance.patch();                      // Get object with only changed fields

// Validation
instance.validate();                   // Validate all fields
instance.validateField.name();         // Validate specific field
instance.validationErrors;             // Map of field -> error message
```

### Field Decorators

Instances automatically apply decorators for type conversion:

| Field Type | Decorator | Behavior |
|------------|-----------|----------|
| `timestamp` | dateTimeDecorator | Converts between Date and epoch milliseconds |
| `zonetime` | dateTimeZDecorator | Handles ISO datetime strings |
| `blob` | blobDecorator | Base64 encode/decode |
| `double`, `int` | numberDecorator | String to number conversion |
| `color` | colorDecorator | Hex color handling |

Decorator pattern:
```javascript
// decorateIn: UI value -> entity value
// decorateOut: entity value -> UI value
instance.decorate("fieldName", {
    decorateIn: (inst, field, value) => { ... },
    decorateOut: (inst, field, value) => { ... }
});
```

## API Client (am7client)

### REST Methods

| Method | Purpose |
|--------|---------|
| `am7client.get(type, objectId)` | Get by object ID |
| `am7client.getFull(type, objectId)` | Get full object (all fields) |
| `am7client.create(type, obj)` | Create new record |
| `am7client.patch(type, obj)` | Update record |
| `am7client.delete(type, objectId)` | Delete record |
| `am7client.search(query)` | Execute search query |
| `am7client.find(type, objType, path)` | Find by path |
| `am7client.make(type, objType, path)` | Find or create path |
| `am7client.list(type, parentId, fields, start, count)` | List with pagination |

### Query Builder

Use `am7client.newQuery()` to build queries:

```javascript
let q = am7client.newQuery("data.group");

// Set field conditions
q.field("name", "MyGroup");           // Default comparator is EQUALS
let fld = q.field("groupPath", "");
fld.comparator = "like";
fld.value = "/Home/%";

// Pagination
q.range(0, 25);                        // startRecord, recordCount

// Sorting
q.sort("name");
q.order("ascending");                  // or "descending"

// Caching
q.cache(true);                         // Enable/disable caching

// Execute
let results = await am7client.search(q);
```

**Query Entity Structure** (sent to server):
```javascript
q.entity = {
    schema: "io.query",
    type: "data.group",
    fields: [...],
    order: "ascending",
    comparator: "group_and",
    recordCount: 10,
    request: [...]                     // Fields to return
};
```

### Request Fields (Field Projection)

The `request` array in queries determines which fields are returned. This is automatically populated from the model's `query` property in the schema:

```javascript
// Auto-populated from model definition
q.entity.request = am7model
    .inheritsFrom(modelType)
    .filter(m => m.query)
    .map(m => m.query)
    .flat(1)
    .filter((v, i, z) => z.indexOf(v) == i);
```

**Important**: If you need specific nested fields, add them to the request array or use `getFull()`.

### Client-Side Caching

The client caches responses by type and action:

```javascript
// Cache structure
cache[type][action][key] = value;

// Clear specific type
am7client.clearCache("data.group");

// Clear all
am7client.clearCache();

// Clear on server too
am7client.clearCache("data.group", false);  // bLocalOnly = false
```

Caching is automatic for GET requests. Search results are cached using `q.key()` as the cache key.

### Authentication

```javascript
// Login with credentials
am7client.loginWithPassword("/Development", "username", "password", (success) => {
    if (success) {
        // User is logged in
    }
});

// Or use uwm.login() which also refreshes cache and gets user
uwm.login("/Development", "username", "password", {}, (user) => {
    // user object or null
});

// Logout
am7client.logout();
```

## Page Context (page)

The `page` object manages application state:

### User and Authentication

```javascript
page.user                              // Current user object
page.authenticated()                   // Returns true if logged in
page.application                       // Application profile

// Context model
page.context().roles                   // User's system roles
page.context().entitlements            // Cached entitlements
page.context().tags                    // Cached tags
```

### Navigation

```javascript
page.home()                            // Go to home
page.nav(path)                         // Navigate to path
page.navigateToPath(pathId)            // Navigate by path ID
m.route.set("/navigator/" + objectId)  // Direct route
```

### Object Operations

```javascript
// Promise-based operations
page.openObject(type, objectId)
page.createObject(type, entity)
page.patchObject(type, entity)
page.deleteObject(type, objectId)
page.findObject(type, objType, path)
page.makePath(type, objType, path)
page.listObjects(type, parentId, start, count)
page.search(query)
```

### WebSocket

```javascript
page.wss.connect(userId)               // Connect WebSocket
page.wss.send(message)                 // Send message
page.wss.close()                       // Disconnect
```

## Forms and Validation

### Form Definitions (formDef.js)

Forms customize how models are displayed/edited:

```javascript
forms.group = {
    label: "Group",
    icon: "folder",
    fields: {
        name: {
            layout: "half",
            label: "Group Name"
        },
        groupType: {
            layout: "half",
            limit: ["DATA", "USER", "BUCKET"]  // Restrict enum options
        },
        description: {
            format: "textarea",
            rows: 3
        }
    }
};
```

### Field Formats

| Format | Renders As |
|--------|------------|
| (default) | Text input |
| `textarea` | Multiline text |
| `select` | Dropdown (for enums) |
| `checkbox` | Boolean checkbox |
| `color` | Color picker |
| `range` | Slider |
| `date` | Date picker |
| `password` | Password input |
| `textlist` | Text area parsed as array |

### Client-Side Validation

Validation rules from the schema are enforced client-side:

```javascript
// Field-level validation
let field = {
    name: "email",
    type: "string",
    required: true,
    maxLength: 255,
    rules: ["${emailRule}"]
};

// Validation rules (from schema)
am7model.validationRules = [
    {
        name: "emailRule",
        expression: "^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$",
        type: "boolean"
    }
];

// Validate instance
let valid = instance.validate();
if (!valid) {
    console.log(instance.validationErrors);
}
```

## UI Components

### Component Registration

Components are registered on `page.components`:

```javascript
page.components.dnd                    // Drag and drop
page.components.dialog                 // Modal dialogs
page.components.pagination             // Pagination controls
page.components.form                   // Form rendering
page.components.tree                   // Tree view
```

### Views

Views are Mithril components in `client/view/`:

| View | Route | Purpose |
|------|-------|---------|
| main.js | `/` | Home page |
| sig.js | `/sig` | Login page |
| list.js | `/list/:type/:id` | List view |
| object.js | `/object/:type/:id` | Object detail/edit |
| navigator.js | `/navigator/:id` | File navigator |
| chat.js | `/chat/:id` | Chat interface |
| game.js | `/game/:id` | Game interface |

### Router (applicationRouter.js)

Routes are defined using Mithril's router:

```javascript
m.route(document.getElementById("app"), "/", {
    "/": page.views.main,
    "/sig": page.views.sig,
    "/list/:type/:id": page.views.list,
    "/object/:type/:id": page.views.object,
    // ...
});
```

## Integration with Service Layer

### API Base Path

```javascript
var sBase = g_application_path + "/rest";  // Set in HTML
```

All REST calls go through `m.request()` with `withCredentials: true` for session cookies.

### Handling Partial Objects

Remember that the API returns partial objects by default. Use these patterns:

```javascript
// Get with default fields (fast, cached)
let obj = await am7client.get("olio.charPerson", objectId);

// Get full object (slower, all fields)
let fullObj = await am7client.getFull("olio.charPerson", objectId);

// Specify exact fields in query
let q = am7client.newQuery("olio.charPerson");
q.entity.request = ["id", "name", "objectId", "statistics", "store"];
let results = await am7client.search(q);
```

### Schema Property Requirement

**Critical**: Always include the `schema` property when creating/patching objects:

```javascript
// Correct
let newGroup = {
    schema: "data.group",
    name: "MyGroup",
    groupType: "DATA"
};
await am7client.create("data.group", newGroup);

// Also correct - use newPrimitive
let newGroup = am7model.newPrimitive("data.group");
newGroup.name = "MyGroup";
await am7client.create("data.group", newGroup);
```

### Handling List Models (Schema Loss Fix)

**IMPORTANT:** When receiving lists from the API, the `schema` property may only appear on the first item. The server condenses repeated schemas to reduce payload size.

```javascript
// Server returns (schema only on first item):
[
    { "schema": "data.group", "id": 1, "name": "First" },
    { "id": 2, "name": "Second" },    // No schema!
    { "id": 3, "name": "Third" }      // No schema!
]

// MUST restore schema on all items using updateListModel:
am7model.updateListModel(results, fieldDef);
// Now all items have schema property

// Or manually:
if (results.length > 0 && results[0][am7model.jsonModelKey]) {
    let schemaName = results[0][am7model.jsonModelKey];
    for (let i = 1; i < results.length; i++) {
        if (!results[i][am7model.jsonModelKey]) {
            results[i][am7model.jsonModelKey] = schemaName;
        }
    }
}
```

**When this happens:**
- Search results (`am7client.search()`)
- List results (`am7client.list()`)
- Any API response returning arrays of records

**Always call `am7model.updateListModel(results, fieldDef)` after receiving list data.**

### Enum Fields

Read enum values directly - they're already resolved:

```javascript
// Correct - enum is already a string
let groupType = entity.groupType;  // "DATA"

// Wrong - don't parse or convert
let groupType = GroupType.valueOf(entity.groupType);  // Not needed!
```

## Common Patterns

### Creating and Saving an Entity

```javascript
// 1. Create instance
let entity = am7model.newPrimitive("data.group");
let instance = am7model.prepareInstance(entity, am7model.forms.group);

// 2. Set values via API
instance.api.name("My Group");
instance.api.groupType("DATA");

// 3. Validate
if (!instance.validate()) {
    console.error(instance.validationErrors);
    return;
}

// 4. Save
let created = await am7client.create(entity[am7model.jsonModelKey], entity);

// 5. Clear cache
am7client.clearCache("data.group");
```

### Updating an Entity

```javascript
// 1. Load and wrap
let entity = await am7client.get("data.group", objectId);
let instance = am7model.prepareInstance(entity);

// 2. Modify
instance.api.description("Updated description");

// 3. Get patch (only changed fields + identity)
let patch = instance.patch();

// 4. Save
await am7client.patch(patch[am7model.jsonModelKey], patch);
```

### Patch Updates (Partial Updates)

Use patch for small updates instead of sending the entire entity:

```javascript
// Using instance.patch() automatically builds minimal patch:
let instance = am7model.prepareInstance(entity);
instance.api.description("New description");
let patch = instance.patch();
// patch contains: { schema, id, objectId, description } - only changed + identity

await am7client.patch(patch[am7model.jsonModelKey], patch);
```

**Manual patch building:**
```javascript
let patch = {
    schema: "data.group",
    objectId: entity.objectId,    // Required: identity field
    description: "New description" // Only the changed field
};
await am7client.patch("data.group", patch);
```

**Patch rules:**
- Must include `schema` (model type)
- Must include at least one identity field (`id`, `objectId`, or `urn`)
- Only fields present are updated - omitted fields unchanged

### Accessing Nested Foreign Models

**By default, foreign model fields are NOT populated.** You must explicitly request them.

**Problem - foreign fields return null:**
```javascript
let person = await am7client.get("olio.charPerson", objectId);
console.log(person.statistics);  // null or undefined!
console.log(person.store);       // null or undefined!
```

**Solution 1 (PREFERRED): Use getFull() for all nested data:**

`getFull` loads the complete object with ALL nested foreign models populated. This is the simplest approach and avoids needing to figure out query plans or incrementally load child foreign references.

```javascript
let person = await am7client.getFull("olio.charPerson", objectId);
console.log(person.statistics);  // populated BaseRecord
console.log(person.store);       // populated BaseRecord
console.log(person.profile.portrait);  // nested data available
```

**When to use getFull vs queries:**
- **getFull**: When you need complete object data with all nested foreign models (e.g., loading a character for display/editing)
- **Queries**: When you need to filter/search large datasets and only need a few fields for large results, or multiple complex objects with query plans to retrieve all desired  data.  getFull on the client calls planMost(true) on the backend query.
- Don't call getFull on a list of results, use a query plan for that

**Pattern for loading a list with full data:**
```javascript
// 1. Get list of IDs from a directory
let charList = await am7client.list("olio.charPerson", groupObjectId, null, 0, 50);

// 2. Load each with getFull to get complete nested data
let fullChars = [];
for (let char of charList) {
    let fullChar = await am7client.getFull("olio.charPerson", char.objectId);
    if (fullChar) fullChars.push(fullChar);
}
```

**Solution 2: Add foreign fields to query request:**
```javascript
let q = am7client.newQuery("olio.charPerson");
q.field("objectId", objectId);

// Add foreign model fields to request array:
q.entity.request.push("statistics");    // Foreign model
q.entity.request.push("store");         // Foreign model
q.entity.request.push("profile");       // Foreign model (includes portrait)

let results = await am7client.search(q);
let person = results.results[0];
console.log(person.statistics);  // now populated
console.log(person.profile.portrait);  // access nested data from returned object
```

**Solution 3: Build query from form definition:**
```javascript
// Form definitions can specify which fields to fetch
let form = am7model.forms.charPerson;

// Build request from form fields + model query fields
let queryFields = am7model.queryFields("olio.charPerson");
let formFields = Object.keys(form.fields || {});
let request = [...new Set([...queryFields, ...formFields])];

let q = am7client.newQuery("olio.charPerson");
q.entity.request = request;
```

**IMPORTANT: No nested path syntax!**
The server supports nested path syntax like "profile.portrait" on the record object only. All queries must use a hierarchical query plan or request the foreign model field and then access nested data from the returned object:
```javascript
// WRONG - will cause server error when used in a query:
q.entity.request.push("profile.portrait");  // ERROR: Field not found!
/// On server though, object.get("profile.portrait") is a valid operation, it only works on the individual record object though 
// CORRECT - request the foreign model:
q.entity.request.push("profile");  // Request the profile object
// Then access nested data from result:
let portrait = person.profile.portrait;  // Access after query returns
```

## Client-Side Data Loading Patterns (IMPORTANT)

**CRITICAL RULE: Always use client-side data loading patterns FIRST before considering server modifications.**

### ALWAYS Check the Model Schema First!

**Before requesting ANY field, ALWAYS look at the model definition in modelDef.js to verify:**
1. The field actually exists on that model
2. The correct field name (case-sensitive)
3. The correct data type
4. Whether the field is a foreign model reference or a direct property

```javascript
// Check what fields exist on a model:
let model = am7model.getModel("olio.charPerson");
let fields = am7model.getModelFields(model);
console.log(fields.map(f => f.name));  // List all field names

// Check a specific field:
let field = am7model.getModelField(model, "profile");
console.log(field);  // See field definition, type, etc.
```

**Common mistakes to avoid:**
- Requesting fields that don't exist (causes server error)
- Using nested paths like "profile.portrait" (NOT supported - request "profile" instead)
- Misspelling field names
- Assuming a field exists without checking the model

When data appears to be missing or incomplete, the issue is almost always that:
1. Foreign model fields weren't requested
2. The field name is wrong or doesn't exist
3. `updateListModel()` wasn't called after receiving list data

**DO NOT** modify server endpoints to return additional data. The client has all the tools needed to fetch complete data.

### Using am7view.viewQuery() vs am7client.newQuery()

**Prefer `am7view.viewQuery()`** - it automatically populates request fields from:
1. The model's `query` array (defined in modelDef.js)
2. All inherited models' `query` arrays
3. Base fields like id, objectId, name, groupId, etc.

You typically only need to add **foreign model fields** that aren't in the defaults:

```javascript
// RECOMMENDED - uses model's default query fields
let q = am7view.viewQuery("olio.charPerson");
q.field("groupId", parentDir.id);
q.range(0, 50);

// Add foreign model fields you need (check modelDef.js first!)
q.entity.request.push("profile", "state");

let qr = await page.search(q);
if (qr && qr.results) {
    am7model.updateListModel(qr.results);  // ALWAYS call this!
    // Access nested data from returned objects:
    let portrait = qr.results[0].profile?.portrait;
}
```

**Use `am7client.newQuery()`** when you need full control:

```javascript
let q = am7client.newQuery("olio.charPerson");
// request array is auto-populated from model inheritance
// but you may need to add nested fields manually
```

### Loading olio.charPerson with Complete Data

Characters require explicit field requests for profile data. Check modelDef.js for exact field names:

```javascript
async function loadCharacters(groupId) {
    // FIRST: Check what fields exist on the model!
    // let model = am7model.getModel("olio.charPerson");
    // console.log(am7model.getModelFields(model).map(f => f.name));

    let q = am7view.viewQuery("olio.charPerson");
    q.field("groupId", groupId);
    q.range(0, 50);

    // Add foreign model fields (NOT nested paths!)
    // Only request fields that exist on olio.charPerson model
    q.entity.request.push("profile");  // Foreign model - includes portrait

    let qr = await page.search(q);
    if (qr && qr.results) {
        am7model.updateListModel(qr.results);
        // Access nested data from returned objects:
        // char.profile.portrait (if portrait exists)
        return qr.results;
    }
    return [];
}
```

### Common Model Field Requirements

**ALWAYS check modelDef.js first to verify field names exist!**

**olio.charPerson** - Character with profile:
```javascript
// Request foreign model, then access nested data from result
request.push("profile");  // Portrait accessed via char.profile.portrait
```

**olio.item** - Item with thumbnail:
```javascript
request.push("store", "thumbnail", "name", "objectId");
```

**data.media** - Media with content:
```javascript
request.push("groupPath", "contentType", "name", "objectId");
```

### Handling ID Fields (objectId vs id)

Server responses may use `objectId` or `id` depending on context. Always handle both:

```javascript
// Helper function pattern
function getEntityId(entity) {
    return entity ? (entity.objectId || entity.id) : null;
}

// Usage
let charId = getEntityId(character);
if (charId) {
    await api.claimCharacter(charId);
}
```

### Complete Pattern: Loading and Displaying Characters

```javascript
async function loadAvailableCharacters() {
    try {
        // 1. Find the population directory
        let popDir = await page.findObject("auth.group", "data", gridPath + "/Population");
        if (!popDir) {
            console.warn("Population directory not found");
            return [];
        }

        // 2. Build query with proper fields (check modelDef.js for valid field names!)
        let q = am7view.viewQuery("olio.charPerson");
        q.field("groupId", popDir.id);
        q.range(0, 50);
        // Only add foreign model fields that exist on olio.charPerson
        q.entity.request.push("profile");  // Profile includes portrait

        // 3. Execute query
        let qr = await page.search(q);

        // 4. ALWAYS restore schema on list items
        if (qr && qr.results) {
            am7model.updateListModel(qr.results);
            // Access nested data: char.profile.portrait
            return qr.results;
        }
    } catch (e) {
        console.error("Failed to load characters", e);
    }
    return [];
}
```

### Troubleshooting Missing Data

If data appears as `null`, `undefined`, or displays incorrectly (e.g., "? • ?"):

1. **Check the query request fields** - Are nested fields included?
   ```javascript
   console.log(q.entity.request);  // Verify fields are present
   ```

2. **Verify updateListModel was called** - Schema may be missing:
   ```javascript
   am7model.updateListModel(results);
   console.log(results[1]?.schema);  // Should not be undefined
   ```

3. **Check field names match model** - Use exact field names from schema:
   ```javascript
   let model = am7model.getModel("olio.charPerson");
   console.log(am7model.getModelFields(model).map(f => f.name));
   ```

4. **Use getFull() to verify data exists**:
   ```javascript
   let full = await am7client.getFull("olio.charPerson", objectId);
   console.log(full);  // See all available fields
   ```

**REMEMBER**: If you think you need to modify the server to return more data, STOP and check if you can request that data client-side first. The answer is almost always YES.

### Search with Pagination

```javascript
async function loadPage(type, parentId, pageNum, pageSize) {
    let q = am7client.newQuery(type);
    q.field("groupId", parentId);
    q.range(pageNum * pageSize, pageSize);
    q.sort("name");
    q.order("ascending");

    let result = await am7client.search(q);
    // result = { results: [...], count: total, ... }

    return {
        items: result.results,
        total: result.count,
        pages: Math.ceil(result.count / pageSize)
    };
}
```

## File Structure

```
AccountManagerUx7/
├── build.js                  # Build script
├── index.html                # Development entry
├── index.prod.html           # Production entry
├── client/
│   ├── am7client.js          # REST client
│   ├── pageClient.js         # App state/context
│   ├── view.js               # View utilities
│   ├── decorator.js          # UI decorators
│   ├── formDef.js            # Form definitions
│   ├── chat.js               # Chat utilities
│   ├── components/           # UI components
│   │   ├── form.js
│   │   ├── dialog.js
│   │   ├── pagination.js
│   │   └── ...
│   └── view/                 # Route views
│       ├── main.js
│       ├── list.js
│       ├── object.js
│       └── ...
├── model/
│   ├── modelDef.js           # Generated schema
│   └── model.js              # Model utilities
├── dist/                     # Build output
├── styles/                   # CSS files
└── node_modules/             # Dependencies
```
