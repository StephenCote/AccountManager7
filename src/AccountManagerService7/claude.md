# AccountManagerService7 - REST Service Layer

This document describes the REST service layer built on top of AccountManagerObjects7.

## Overview

AccountManagerService7 is a Jersey-based REST API that exposes AccountManagerObjects7 functionality. It provides:
- RESTful CRUD operations for any model type
- JAAS-based authentication with JWT token support
- Path-based resource access
- WebSocket real-time messaging
- Role-based endpoint authorization

## Architecture

### Jersey Configuration

The REST services are configured via `RestServiceConfig` which extends `ResourceConfig`:

```java
// RestServiceConfig.java
public class RestServiceConfig extends ResourceConfig {
    public RestServiceConfig() {
        packages("org.cote.rest.services");
        register(RolesAllowedDynamicFeature.class);  // Enables @RolesAllowed
        register(MultiPartFeature.class);            // File uploads
    }
}
```

### Authentication Flow

#### 1. JAAS Login Module

Authentication uses a custom JAAS module (`AM7LoginModule`) that:
- Validates credentials against AM7 user records
- Supports password and token-based authentication
- Integrates with the PolicyContext for authorization

```
Login Request → AM7LoginModule → Credential Verification → Principal Creation
```

#### 2. JWT Token Filter

The `TokenFilter` intercepts requests to validate Bearer tokens:

```java
// TokenFilter.java - Request filter for JWT validation
@Provider
@Priority(Priorities.AUTHENTICATION)
public class TokenFilter implements ContainerRequestFilter {
    @Override
    public void filter(ContainerRequestContext ctx) {
        String auth = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            // Validate JWT and set security context
        }
    }
}
```

#### 3. Token Types

Two token types are supported:
- **JWT Tokens**: Standard JSON Web Tokens for API access
- **API Tokens**: Stored in user credential records for long-lived access

### ServiceUtil - Principal Context

`ServiceUtil` provides utilities for accessing the authenticated user context:

```java
// Get the authenticated user from the request
BaseRecord user = ServiceUtil.getPrincipalUser(request);

// Get organization context
BaseRecord org = ServiceUtil.getOrganization(request, organizationPath);

// Build queries with pagination
Query query = ServiceUtil.getQuery(user, type, request);
query.setRequestRange(startIndex, recordCount);
```

**Important**: Always use `ServiceUtil.getPrincipalUser()` to get the current user. This handles:
- JWT token extraction and validation
- Session-based authentication fallback
- Anonymous user handling

## Core REST Services

### ModelService - Generic CRUD

`ModelService` provides generic CRUD operations for any model type:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/model/{type}` | GET | Search/list records |
| `/model/{type}` | POST | Create new record |
| `/model/{type}` | PUT | Update existing record |
| `/model/{type}` | DELETE | Delete record(s) |
| `/model/{type}/{objectId}` | GET | Get by ID |
| `/model/{type}/path/{path}` | GET | Get by path |

**Query Parameters**:
- `startRecord` - Pagination start index
- `recordCount` - Page size
- `query` - Base64-encoded Query object

Example:
```
GET /rest/model/data.group?startRecord=0&recordCount=10
Authorization: Bearer <jwt-token>
```

### PathService - Path-Based Operations

`PathService` handles path-based resource access:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/path/find/{type}/{path}` | GET | Find by path |
| `/path/make/{type}/{path}` | GET | Find or create path |
| `/path/list/{type}/{path}` | GET | List children at path |

Paths follow the pattern: `/{organizationPath}/.../{resourcePath}`

Example:
```
GET /rest/path/find/data.group/Development/Home/Data
```

### ListService - Pagination

`ListService` provides optimized pagination endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/list/{type}` | GET | Paginated list with query |
| `/list/{type}/count` | GET | Total count for query |
| `/list/{type}/parent/{parentId}` | GET | List by parent ID |

Returns `QueryResult` containing:
- `results` - Array of records
- `count` - Total matching records
- `startRecord` - Current offset
- `recordCount` - Page size

### AuthorizationService - Access Control

`AuthorizationService` manages role/permission membership:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/authorization/member` | POST | Check membership |
| `/authorization/member` | PUT | Add member |
| `/authorization/member` | DELETE | Remove member |
| `/authorization/authorize` | POST | Check authorization |
| `/authorization/entitlements/{type}/{id}` | GET | Get entitlements |

### LoginService - Authentication

`LoginService` handles authentication and session management:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/login` | POST | Authenticate user |
| `/login/token` | GET | Get JWT token |
| `/login/refresh` | GET | Refresh JWT token |
| `/login/logout` | GET | End session |
| `/login/principal` | GET | Get current principal info |

**Token Response**:
```json
{
    "token": "eyJhbG...",
    "organizationPath": "/Development",
    "userName": "admin"
}
```

### ResourceService - Media/Files

`ResourceService` handles file/media operations:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/resource/{type}/{objectId}` | GET | Get resource content |
| `/resource/{type}/{objectId}` | POST | Upload resource content |
| `/resource/thumbnail/{objectId}` | GET | Get thumbnail |

Supports streaming for large files and media content.

## WebSocket Services

### WebSocketService

Real-time messaging via WebSocket at `/wss/`:

**Features**:
- Session management per user
- Chat messaging between users
- Real-time notifications
- Audio streaming support

**Message Format**:
```json
{
    "action": "message|chat|audio|subscribe",
    "target": "userId or channelId",
    "data": { ... }
}
```

**Session Handling**:
```java
@OnOpen
public void onOpen(Session session, @PathParam("uid") String uid) {
    // Register session for user
    sessions.put(uid, session);
}

@OnMessage
public void onMessage(String message, Session session) {
    // Route message to target user/channel
}
```

## Configuration

### web.xml Parameters

Key context parameters in `web.xml`:

| Parameter | Description |
|-----------|-------------|
| `auth.provider` | Authentication provider class |
| `ssl.binary` | SSL certificate path |
| `ssl.private.path` | Private key path |
| `am7.admin.password` | Initial admin password |
| `am7.db.url` | Database JDBC URL |
| `am7.db.user` | Database username |
| `am7.db.password` | Database password |

### Security Constraints

Security constraints define role-based access:

```xml
<security-constraint>
    <web-resource-collection>
        <web-resource-name>API</web-resource-name>
        <url-pattern>/rest/*</url-pattern>
    </web-resource-collection>
    <auth-constraint>
        <role-name>ApiUser</role-name>
    </auth-constraint>
</security-constraint>
```

### JAAS Configuration

The JAAS login configuration (`login.config`):

```
AM7 {
    org.cote.accountmanager.jaas.AM7LoginModule required;
};
```

## Common Patterns

### Error Handling

Services return standard HTTP status codes:
- `200` - Success
- `400` - Bad request (invalid parameters)
- `401` - Unauthorized (authentication required)
- `403` - Forbidden (insufficient permissions)
- `404` - Not found
- `500` - Internal server error

Error responses include a message body:
```json
{
    "error": true,
    "message": "Record not found"
}
```

### Request/Response Format

All requests and responses use JSON (application/json):

```java
@POST
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public Response createRecord(String jsonBody) {
    BaseRecord record = RecordDeserializer.fromJson(jsonBody);
    // ... process
    return Response.ok(RecordSerializer.toJson(result)).build();
}
```

### Query Building

Build queries programmatically via the API:

```java
// Client-side query construction
Query query = new Query();
query.setType("data.group");
query.field("name", ComparatorEnumType.EQUALS, "MyGroup");
query.field("groupPath", ComparatorEnumType.LIKE, "/Home/%");

// Send as Base64-encoded parameter
String encoded = Base64.encode(RecordSerializer.toJson(query));
// GET /rest/model/data.group?query={encoded}
```

**Query JSON structure** (for POST to `/rest/model/search`):
```json
{
    "schema": "query",
    "type": "olio.char.person",
    "organizationId": 123,
    "request": ["id", "name", "objectId", "statistics"],
    "startRecord": 0,
    "recordCount": 25,
    "fields": [
        {
            "name": "name",
            "comparator": "LIKE",
            "value": "John%"
        }
    ]
}
```

**Key Query Properties**:

| Property | Description |
|----------|-------------|
| `type` | Model type to query (e.g., "data.group", "olio.char.person") |
| `request` | Array of field names to return (field projection) |
| `startRecord` | Pagination offset (0-based) |
| `recordCount` | Page size |
| `fields` | Array of field conditions (filters) |
| `order` | Sort order specification |

**Field Condition Properties**:

| Property | Description |
|----------|-------------|
| `name` | Field name to filter on |
| `comparator` | EQUALS, NOT_EQUALS, LIKE, GREATER_THAN, LESS_THAN, etc. |
| `value` | Value to compare against |

### Pagination Pattern

Standard pagination approach:

```java
// Request
GET /rest/list/data.group?startRecord=0&recordCount=25

// Response
{
    "results": [...],
    "count": 150,
    "startRecord": 0,
    "recordCount": 25
}

// Calculate pages: totalPages = ceil(count / recordCount)
```

## Custom Object Model Integration

AM7 uses a schema-driven object model that affects how clients interact with the API. Understanding these patterns is critical for proper serialization/deserialization.

### Default Query Fields in Model Definitions

**IMPORTANT:** Each model definition includes a `query` array that specifies the default fields returned. These fields are inherited from parent models.

```json
// Example from model definitions
{ "name": "common.base", "query": ["id", "urn", "objectId", "ownerId"] }
{ "name": "data.directory", "query": ["groupId", "groupPath", "organizationId"] }
```

The schema (available via `GET /rest/schema`) contains these query arrays. Use them to understand which fields are returned by default and which require explicit projection.

### Schema Requirement

**Clients must have access to the model schema to properly deserialize responses.**

The `/rest/schema` endpoint returns the complete schema definition:

```
GET /rest/schema
```

This returns `SchemaUtil.getSchemaJSON()` which contains all model definitions, field types, inheritance hierarchies, and field metadata. Clients should cache this schema and use it to:
- Understand field types (especially enums)
- Know which fields exist on a model
- Handle inheritance correctly

### Partial Object Returns (Default Behavior)

**By default, the API does NOT return all fields on an object.**

When retrieving records, only a subset of fields is returned to optimize performance and reduce payload size. The fields returned depend on:
- The query's `request` field projection
- Default fields for the model type (typically identity fields)

Example - Default GET returns minimal fields:
```
GET /rest/model/olio.char.person/{objectId}

Response contains: id, objectId, name, urn, organizationId, ownerId
Missing: statistics, store, instinct, state, etc.
```

### Requesting Specific Fields (Field Projection)

To retrieve specific fields, use `Query.setRequest()` to specify field projection:

```java
// Server-side pattern
Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
q.setRequest(new String[] {
    FieldNames.FIELD_ID,
    FieldNames.FIELD_NAME,
    "statistics",    // Request nested model
    "store"          // Request another nested model
});
BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
```

Client-side, include requested fields in the Query JSON:
```json
{
    "schema": "query",
    "type": "olio.char.person",
    "request": ["id", "name", "objectId", "statistics", "store"],
    "fields": [...]
}
```

### The `/full` Endpoint Pattern

For retrieving complete objects with all nested data, use the `/full` endpoint variant:

| Standard Endpoint | Full Endpoint |
|-------------------|---------------|
| `/model/{type}/{objectId}` | `/model/{type}/{objectId}/full` |

The `/full` endpoint uses `Query.planMost(true)` which:
- Requests most fields on the model
- Recursively plans nested models
- Excludes certain expensive fields (blobs, large lists) based on filters

```java
// ModelService.java - /full endpoint implementation
@GET
@Path("/{type}/{objectId}/full")
public Response getFullModelByObjectId(...) {
    Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
    q.planMost(true);  // Plan for most fields, recursively
    BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
    return Response.status(200).entity(rec.toFullString()).build();
}
```

### Serialization: toFullString() vs toString()

Records have two serialization methods:

| Method | Behavior |
|--------|----------|
| `toString()` | Serializes only fields that have been explicitly set |
| `toFullString()` | Serializes all fields including defaults and computed values |

**The REST API uses `toFullString()` for responses** to ensure complete data representation:

```java
// Correct - includes all populated fields
return Response.status(200).entity(rec.toFullString()).build();

// Avoid - may omit fields that weren't explicitly set
return Response.status(200).entity(rec.toString()).build();
```

### Deserialization with Schema Context

When deserializing JSON into records, the deserializer uses the schema to:
- Determine correct field types (don't re-parse enums)
- Handle foreign key references
- Resolve nested model types

```java
// Server-side deserialization pattern
BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class,
    RecordDeserializerConfig.getFilteredModule());

// The schema field in JSON tells the deserializer which model to use
// {
//     "schema": "data.group",
//     "name": "MyGroup",
//     "groupType": "DATA"  // Enum - deserializer uses schema to know this
// }
```

**Critical**: The `schema` field in JSON is required for proper deserialization. Without it, the deserializer cannot determine field types.

### RecordDeserializerConfig Modules

Different deserializer configurations for different use cases:

| Module | Use Case |
|--------|----------|
| `getFilteredModule()` | Standard API input - filters sensitive fields |
| `getUnfilteredModule()` | Internal use - allows all fields |
| `getForeignModule()` | Handles foreign key resolution |

```java
// Standard API endpoint
BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class,
    RecordDeserializerConfig.getFilteredModule());

// Internal processing (e.g., game state)
BaseRecord params = JSONUtil.importObject(json, LooseRecord.class,
    RecordDeserializerConfig.getUnfilteredModule());
```

### Condensed Fields (shortName)

Model fields can have a `shortName` property for compact serialization. This reduces payload size for bandwidth-constrained scenarios.

**Standard format** (full field names):
```json
{
    "schema": "data.group",
    "name": "MyGroup",
    "groupType": "DATA",
    "organizationId": 123
}
```

**Condensed format** (short names):
```json
{
    "s": "data.group",
    "n": "MyGroup",
    "gt": "DATA",
    "oi": 123
}
```

The deserializer auto-detects condensed format when `detectCondensedFields` is enabled (default). Look for the `s` key (short for `schema`) instead of `schema`.

**Schema example showing shortName**:
```json
{
    "name": "groupType",
    "type": "enum",
    "shortName": "gt"
}
```

### Create Response Pattern

When creating records, the API returns only identity fields (not the full object):

```java
// ModelService.java - create returns minimal response
for(FieldType f : oop.getFields()) {
    FieldSchema fs = schema.getFieldSchema(f.getName());
    if(fs.isIdentity() || fs.getName().equals(FieldNames.FIELD_GROUP_ID)
       || fs.getName().equals(FieldNames.FIELD_PARENT_ID)) {
        outFields.add(f.getName());
    }
}
return oop.copyRecord(outFields.toArray(new String[0])).toFullString();
```

This means create responses contain: `id`, `objectId`, `urn`, `groupId`/`parentId`, `organizationId` - but NOT the full object. If you need the complete record after creation, perform a subsequent GET with `/full`.

### List Serialization Schema Loss

**IMPORTANT:** When the API returns lists of records, the `schema` property may only appear on the first item. Subsequent items omit it to reduce payload size.

**API response example:**
```json
{
    "results": [
        { "schema": "data.group", "id": 1, "name": "First" },
        { "id": 2, "name": "Second" },
        { "id": 3, "name": "Third" }
    ]
}
```

**Client must restore schema on subsequent items:**
```javascript
// JavaScript pattern
if (results.length > 0 && results[0].schema) {
    const schemaName = results[0].schema;
    results.forEach(r => { if (!r.schema) r.schema = schemaName; });
}
```

### PATCH for Partial Updates

Use HTTP PATCH for small updates instead of full record PUT. A patch only includes identity fields plus changed fields:

**Endpoint:** `PATCH /rest/model`

**Request body:**
```json
{
    "schema": "data.group",
    "id": 123,
    "objectId": "abc-123-def",
    "description": "Updated description only"
}
```

**PATCH rules:**
- Must include `schema` to identify model type
- Must include at least one identity field (`id`, `objectId`, or `urn`)
- Only fields present in the request are updated
- Omitted fields remain unchanged
- Returns `true` on success

**Example PATCH call:**
```bash
curl -X PATCH \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"schema":"data.group","objectId":"abc-123","description":"New desc"}' \
  https://api/rest/model
```

### Accessing Nested Foreign Models

Foreign model fields are NOT populated by default. Use these patterns to retrieve nested data:

**Pattern 1: Use the `/full` endpoint:**
```
GET /rest/model/olio.charPerson/{objectId}/full
```
This uses `planMost(true)` to recursively fetch nested models.

**Pattern 2: Specify request fields in query:**
```json
POST /rest/model/search
{
    "schema": "query",
    "type": "olio.charPerson",
    "request": ["id", "name", "statistics", "store", "profile.portrait"],
    "fields": [{ "name": "objectId", "comparator": "EQUALS", "value": "abc-123" }]
}
```

**Pattern 3: Nested path syntax for deep fields:**
```json
{
    "request": [
        "id", "name",
        "statistics",              // Entire nested model
        "profile.portrait",        // Specific nested field
        "profile.portrait.groupPath"  // Deeper nesting
    ]
}
```

**What happens without explicit request:**
- Foreign fields return `null` or only the ID reference
- List<model> fields return empty or IDs only
- To get actual data, you must include the field in `request` or use `/full`

## Integration with AccountManagerObjects7

The REST layer integrates with the objects layer through:

1. **AccessPoint**: All CRUD operations go through `AccessPoint` for PBAC enforcement
2. **ServiceUtil**: Provides the bridge between HTTP context and AM7 context
3. **RecordSerializer/Deserializer**: JSON conversion for request/response bodies
4. **Query System**: Complex queries are passed through and executed server-side
5. **Schema**: Model schemas drive serialization, deserialization, and field projection

**Important**: The REST layer does NOT bypass authorization. All operations respect PBAC rules.

## Utility Services

### StreamService

Streaming endpoint for large data:
- Chunked transfer encoding
- Progress callbacks
- Resume support

### CacheService

Cache management endpoints:
- Clear model caches
- Invalidate specific entries
- Cache statistics

### PolicyService

Policy evaluation endpoints:
- Evaluate policy against context
- Policy CRUD operations
- Policy testing/simulation

## Testing

### Integration Testing

Use the REST endpoints for integration testing:

```java
@Test
public void testModelCRUD() {
    // Create
    Response resp = target("/rest/model/data.group")
        .request()
        .header("Authorization", "Bearer " + token)
        .post(Entity.json(groupJson));
    assertEquals(200, resp.getStatus());

    // Read
    resp = target("/rest/model/data.group/" + id)
        .request()
        .header("Authorization", "Bearer " + token)
        .get();
    // ...
}
```

### Authentication Testing

Always test with proper authentication:
1. Obtain JWT token via `/rest/login/token`
2. Include `Authorization: Bearer <token>` header
3. Verify 401 responses for unauthenticated requests
4. Verify 403 responses for unauthorized operations
