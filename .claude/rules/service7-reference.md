---
paths:
  - "src/AccountManagerService7/**"
  - "src/AccountManagerUx752/**"
---

# AccountManagerService7 — REST Gotchas Reference

> The non-derivable parts of the REST layer. Behavioral rules are in `llm-conduct.md`;
> architecture/layering (Service7 is transport only, never bypasses PBAC) in `architecture.md`;
> cross-layer query/serialization/PATCH/foreign-model patterns in `model-api.md`. The lean
> orientation lives in `AccountManagerService7/CLAUDE.md`. The explanatory tour (Jersey config,
> JAAS/JWT flow, every service's route table, WebSocket, web.xml, error format, pagination,
> integration testing) was moved to `src/aiDocs/Service7Reference.md`.

## ServiceUtil - Principal Context

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

## `/rest/model/search` request body

Always `"schema":"io.query"` — the bare `"schema":"query"` fails model lookup and the route silently 404s (see `troubleshooting.md`).

**Query JSON structure** (for POST to `/rest/model/search`):
```json
{
    "schema": "io.query",
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

## Model API — query, serialization, PATCH & foreign models

> Relocated to `model-api.md` (default query fields, schema requirement, partial/full returns, field
> projection, `/full` endpoint, `toFullString`, deserializer modules, condensed fields, create response,
> list schema-loss, PATCH, nested foreign models, Olio full records).
