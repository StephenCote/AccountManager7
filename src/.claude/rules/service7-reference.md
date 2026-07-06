# AccountManagerService7 — Deep Reference

> Deep technical reference for the REST service layer. Behavioral rules are in `llm-conduct.md`;
> architecture/layering in `architecture.md`; cross-layer query/serialization/PATCH/foreign-model
> patterns are consolidated in `model-api.md`. The lean orientation lives in
> `AccountManagerService7/CLAUDE.md`.

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

## Model API — query, serialization, PATCH & foreign models

> Relocated to `model-api.md` (default query fields, schema requirement, partial/full returns, field
> projection, `/full` endpoint, `toFullString`, deserializer modules, condensed fields, create response,
> list schema-loss, PATCH, nested foreign models, Olio full records).

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

