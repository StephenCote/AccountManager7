# SCIM REST Service Implementation Plan

## Overview

This document outlines the strategy for implementing SCIM 2.0 (System for Cross-domain Identity Management) REST services in AccountManager7. As designed, AccountManager already supports multi-dimensional IAM, so this implementation is primarily a **mapping exercise** between SCIM schemas and existing AccountManager models.

## Current State Analysis

### Existing Infrastructure Ready for SCIM

| Component | Status | Notes |
|-----------|--------|-------|
| URL Pattern `/scim/*` | ✅ Configured | Already defined in `web.xml` |
| Token Authentication | ✅ Ready | `TokenFilter` applies to `/scim/*` |
| CORS Headers | ✅ Configured | Origins and headers configured |
| REST Framework | ✅ Jersey 3.1.5 | Jakarta EE 11 / JAX-RS |
| Generic CRUD | ✅ `ModelService` | Works with any model type |
| Authorization | ✅ Policy-based | `AccessPoint` + `AuthorizationUtil` |

### IAM Model Mapping

AccountManager models map directly to SCIM resources:

```
SCIM Resource          AccountManager Models
─────────────────────────────────────────────────
/Users                 system.user + identity.person
/Groups                auth.group
/Roles (extension)     auth.role
/Entitlements          auth.permission
/Schemas               Model schema definitions
/ServiceProviderConfig Static configuration
/ResourceTypes         Model type registry
/Bulk                  Existing batch operations
```

---

## Phase 1: Core SCIM Service Structure

### 1.1 Create ScimService.java

**Location**: `AccountManagerService7/src/main/java/org/cote/rest/services/ScimService.java`

```java
@Path("/scim/v2")
@RolesAllowed({"user"})
public class ScimService {

    // Discovery Endpoints
    @GET @Path("/ServiceProviderConfig")
    public Response getServiceProviderConfig();

    @GET @Path("/ResourceTypes")
    public Response getResourceTypes();

    @GET @Path("/Schemas")
    public Response getSchemas();

    @GET @Path("/Schemas/{schemaId}")
    public Response getSchema(@PathParam("schemaId") String schemaId);
}
```

### 1.2 Create ScimUserService.java

**Location**: `AccountManagerService7/src/main/java/org/cote/rest/services/ScimUserService.java`

```java
@Path("/scim/v2/Users")
@RolesAllowed({"user"})
public class ScimUserService {

    @GET @Path("/{id}")
    public Response getUser(@PathParam("id") String id);

    @GET @Path("/")
    public Response listUsers(
        @QueryParam("filter") String filter,
        @QueryParam("startIndex") @DefaultValue("1") int startIndex,
        @QueryParam("count") @DefaultValue("100") int count,
        @QueryParam("attributes") String attributes,
        @QueryParam("excludedAttributes") String excludedAttributes
    );

    @POST @Path("/")
    public Response createUser(String json);

    @PUT @Path("/{id}")
    public Response replaceUser(@PathParam("id") String id, String json);

    @PATCH @Path("/{id}")
    public Response updateUser(@PathParam("id") String id, String json);

    @DELETE @Path("/{id}")
    public Response deleteUser(@PathParam("id") String id);
}
```

### 1.3 Create ScimGroupService.java

**Location**: `AccountManagerService7/src/main/java/org/cote/rest/services/ScimGroupService.java`

```java
@Path("/scim/v2/Groups")
@RolesAllowed({"user"})
public class ScimGroupService {

    @GET @Path("/{id}")
    public Response getGroup(@PathParam("id") String id);

    @GET @Path("/")
    public Response listGroups(...);

    @POST @Path("/")
    public Response createGroup(String json);

    @PUT @Path("/{id}")
    public Response replaceGroup(@PathParam("id") String id, String json);

    @PATCH @Path("/{id}")
    public Response updateGroup(@PathParam("id") String id, String json);

    @DELETE @Path("/{id}")
    public Response deleteGroup(@PathParam("id") String id);
}
```

---

## Phase 2: SCIM Adapter Layer

### 2.1 User Mapping

**SCIM User ↔ system.user + identity.person**

| SCIM Attribute | AccountManager Field | Notes |
|----------------|---------------------|-------|
| `id` | `system.user.objectId` | Unique identifier |
| `externalId` | `system.user.urn` | External reference |
| `userName` | `system.user.name` | Login name |
| `active` | `system.user.status` | Map to `UserStatusEnumType` |
| `displayName` | Computed | `person.firstName + person.lastName` |
| `name.givenName` | `identity.person.firstName` | |
| `name.middleName` | `identity.person.middleName` | |
| `name.familyName` | `identity.person.lastName` | |
| `emails` | `identity.contact` | Filter by `type=EMAIL` |
| `phoneNumbers` | `identity.contact` | Filter by `type=PHONE` |
| `addresses` | `identity.address` | Full address mapping |
| `groups` | Role/Group participation | Via `ListService` |
| `roles` | `auth.role` membership | Via participation |
| `entitlements` | `auth.permission` | Via participation |
| `meta.created` | `FIELD_CREATED_DATE` | |
| `meta.lastModified` | `FIELD_MODIFIED_DATE` | |
| `meta.resourceType` | `"User"` | Static |
| `meta.location` | Computed | `/scim/v2/Users/{id}` |

**Adapter Class**: `ScimUserAdapter.java`

```java
public class ScimUserAdapter {

    public static JSONObject toScim(BaseRecord user, BaseRecord person) {
        JSONObject scim = new JSONObject();
        scim.put("schemas", Arrays.asList("urn:ietf:params:scim:schemas:core:2.0:User"));
        scim.put("id", user.get(FieldNames.FIELD_OBJECT_ID));
        scim.put("userName", user.get(FieldNames.FIELD_NAME));
        scim.put("active", mapStatus(user.get(FieldNames.FIELD_STATUS)));

        // Name object
        if (person != null) {
            JSONObject name = new JSONObject();
            name.put("givenName", person.get("firstName"));
            name.put("middleName", person.get("middleName"));
            name.put("familyName", person.get("lastName"));
            name.put("formatted", formatName(person));
            scim.put("name", name);
            scim.put("displayName", formatName(person));
        }

        // Contact information
        scim.put("emails", mapContacts(person, ContactEnumType.EMAIL));
        scim.put("phoneNumbers", mapContacts(person, ContactEnumType.PHONE));
        scim.put("addresses", mapAddresses(person));

        // Groups and roles
        scim.put("groups", mapGroupMembership(user));
        scim.put("roles", mapRoles(user));

        // Meta
        scim.put("meta", buildMeta(user, "User"));

        return scim;
    }

    public static BaseRecord fromScim(JSONObject scim, BaseRecord contextUser) {
        // Create or update system.user record
        BaseRecord user = RecordFactory.newInstance(ModelNames.MODEL_USER);
        user.set(FieldNames.FIELD_NAME, scim.getString("userName"));
        user.set(FieldNames.FIELD_STATUS, mapActiveToStatus(scim.getBoolean("active")));

        // Create associated person record
        BaseRecord person = RecordFactory.newInstance(ModelNames.MODEL_PERSON);
        JSONObject name = scim.optJSONObject("name");
        if (name != null) {
            person.set("firstName", name.optString("givenName"));
            person.set("middleName", name.optString("middleName"));
            person.set("lastName", name.optString("familyName"));
        }

        // Map contacts and addresses...

        return user;
    }
}
```

### 2.2 Group Mapping

**SCIM Group ↔ auth.group**

| SCIM Attribute | AccountManager Field | Notes |
|----------------|---------------------|-------|
| `id` | `auth.group.objectId` | |
| `externalId` | `auth.group.urn` | |
| `displayName` | `auth.group.name` | |
| `members` | Participation | Via `ListService.listMembers()` |
| `members[].value` | Member `objectId` | |
| `members[].display` | Member `name` | |
| `members[].$ref` | Resource URI | `/scim/v2/Users/{id}` or `/scim/v2/Groups/{id}` |
| `members[].type` | `"User"` or `"Group"` | Determined by model type |
| `meta.*` | Standard meta fields | |

**Adapter Class**: `ScimGroupAdapter.java`

---

## Phase 3: SCIM Filter Support

### 3.1 Filter Parser

SCIM filter syntax (RFC 7644) must be translated to AccountManager `Query` objects.

**Supported Operations**:
```
eq    - equals           → QueryField comparator "equals"
ne    - not equals       → QueryField comparator "not_equals"
co    - contains         → QueryField comparator "like" with wildcards
sw    - starts with      → QueryField comparator "like" with trailing wildcard
ew    - ends with        → QueryField comparator "like" with leading wildcard
pr    - present          → QueryField comparator "not_null"
gt/ge - greater than     → QueryField comparator "greater_than"
lt/le - less than        → QueryField comparator "less_than"
and/or/not - logical     → Query field grouping
```

**Implementation**: `ScimFilterParser.java`

```java
public class ScimFilterParser {

    public Query parse(String filter, String modelType) {
        Query q = QueryUtil.createQuery(modelType);

        // Tokenize and parse SCIM filter expression
        List<FilterToken> tokens = tokenize(filter);
        QueryField qf = parseExpression(tokens, q);

        return q;
    }

    private QueryField parseExpression(List<FilterToken> tokens, Query q) {
        // Handle: userName eq "john"
        // Handle: name.givenName sw "J"
        // Handle: emails[type eq "work"].value co "@example.com"
        // Handle: meta.lastModified gt "2024-01-01T00:00:00Z"
        // Handle: (userName eq "john") or (userName eq "jane")
    }

    private String mapScimAttribute(String scimAttr) {
        // Map SCIM paths to AccountManager field names
        return switch(scimAttr) {
            case "userName" -> FieldNames.FIELD_NAME;
            case "id" -> FieldNames.FIELD_OBJECT_ID;
            case "externalId" -> FieldNames.FIELD_URN;
            case "active" -> FieldNames.FIELD_STATUS;
            case "name.givenName" -> "firstName";
            case "name.familyName" -> "lastName";
            case "meta.created" -> FieldNames.FIELD_CREATED_DATE;
            case "meta.lastModified" -> FieldNames.FIELD_MODIFIED_DATE;
            default -> scimAttr;
        };
    }
}
```

---

## Phase 4: SCIM Patch Operations

### 4.1 RFC 7644 Patch Support

SCIM PATCH uses RFC 7644 format (not RFC 6902 JSON Patch):

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
  "Operations": [
    { "op": "add", "path": "emails", "value": [{"value": "new@example.com", "type": "work"}] },
    { "op": "replace", "path": "name.givenName", "value": "NewName" },
    { "op": "remove", "path": "phoneNumbers[type eq \"fax\"]" }
  ]
}
```

**Implementation**: `ScimPatchHandler.java`

```java
public class ScimPatchHandler {

    public BaseRecord applyPatch(BaseRecord record, JSONObject patchRequest) {
        JSONArray operations = patchRequest.getJSONArray("Operations");

        for (int i = 0; i < operations.length(); i++) {
            JSONObject op = operations.getJSONObject(i);
            String operation = op.getString("op").toLowerCase();
            String path = op.optString("path");
            Object value = op.opt("value");

            switch (operation) {
                case "add" -> handleAdd(record, path, value);
                case "replace" -> handleReplace(record, path, value);
                case "remove" -> handleRemove(record, path);
            }
        }

        return record;
    }
}
```

---

## Phase 5: Bulk Operations

### 5.1 Bulk Endpoint

**Location**: Add to `ScimService.java`

```java
@POST @Path("/Bulk")
public Response processBulk(String json) {
    JSONObject request = new JSONObject(json);
    JSONArray operations = request.getJSONArray("Operations");
    JSONArray results = new JSONArray();

    for (int i = 0; i < operations.length(); i++) {
        JSONObject op = operations.getJSONObject(i);
        String method = op.getString("method");
        String path = op.getString("path");
        JSONObject data = op.optJSONObject("data");
        String bulkId = op.optString("bulkId");

        // Route to appropriate handler
        Response result = routeBulkOperation(method, path, data);
        results.put(buildBulkResult(bulkId, result));
    }

    return Response.ok(buildBulkResponse(results)).build();
}
```

---

## Phase 6: Schema Discovery

### 6.1 ServiceProviderConfig

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"],
  "documentationUri": "https://accountmanager.example.com/docs/scim",
  "patch": { "supported": true },
  "bulk": { "supported": true, "maxOperations": 1000, "maxPayloadSize": 1048576 },
  "filter": { "supported": true, "maxResults": 200 },
  "changePassword": { "supported": true },
  "sort": { "supported": true },
  "etag": { "supported": true },
  "authenticationSchemes": [
    {
      "type": "oauthbearertoken",
      "name": "OAuth Bearer Token",
      "description": "Authentication via OAuth 2.0 Bearer Token"
    }
  ]
}
```

### 6.2 ResourceTypes

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:ResourceType"],
  "Resources": [
    {
      "id": "User",
      "name": "User",
      "endpoint": "/Users",
      "schema": "urn:ietf:params:scim:schemas:core:2.0:User",
      "schemaExtensions": [
        {
          "schema": "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User",
          "required": false
        }
      ]
    },
    {
      "id": "Group",
      "name": "Group",
      "endpoint": "/Groups",
      "schema": "urn:ietf:params:scim:schemas:core:2.0:Group"
    }
  ]
}
```

### 6.3 Schema Definitions

Map AccountManager model schemas to SCIM schema format:

```java
public class ScimSchemaGenerator {

    public JSONObject generateUserSchema() {
        // Read models/system.user.json
        // Read models/identity.person.json
        // Transform to SCIM schema format
    }
}
```

---

## Implementation Checklist

### Phase 1: Foundation
- [ ] Create `ScimService.java` with discovery endpoints
- [ ] Create `ScimUserService.java` with CRUD endpoints
- [ ] Create `ScimGroupService.java` with CRUD endpoints
- [ ] Register services in `RestServiceConfig.java`

### Phase 2: Adapters
- [ ] Implement `ScimUserAdapter.java`
- [ ] Implement `ScimGroupAdapter.java`
- [ ] Implement `ScimRoleAdapter.java` (extension)
- [ ] Create contact/address mapping utilities

### Phase 3: Filtering
- [ ] Implement `ScimFilterParser.java`
- [ ] Map SCIM operators to Query comparators
- [ ] Handle complex path expressions
- [ ] Support logical operators (and/or/not)

### Phase 4: Patch Operations
- [ ] Implement `ScimPatchHandler.java`
- [ ] Handle add/replace/remove operations
- [ ] Support path expressions with filters

### Phase 5: Bulk Operations
- [ ] Add bulk endpoint to `ScimService.java`
- [ ] Implement operation routing
- [ ] Handle bulkId references
- [ ] Support failOnErrors parameter

### Phase 6: Schema Discovery
- [ ] Generate ServiceProviderConfig
- [ ] Generate ResourceTypes
- [ ] Transform model schemas to SCIM format
- [ ] Support schema extensions

### Testing
- [ ] Create SCIM conformance tests
- [ ] Test filter expressions
- [ ] Test patch operations
- [ ] Test bulk operations
- [ ] Integration tests with common SCIM clients

---

## File Structure

```
AccountManagerService7/src/main/java/org/cote/rest/
└── services/
    └── scim/
        ├── ScimService.java           # Discovery endpoints
        ├── ScimUserService.java       # /Users endpoints
        ├── ScimGroupService.java      # /Groups endpoints
        ├── adapter/
        │   ├── ScimUserAdapter.java   # User mapping
        │   ├── ScimGroupAdapter.java  # Group mapping
        │   └── ScimRoleAdapter.java   # Role extension
        ├── filter/
        │   ├── ScimFilterParser.java  # Filter parsing
        │   └── FilterToken.java       # Token model
        ├── patch/
        │   └── ScimPatchHandler.java  # Patch operations
        └── schema/
            └── ScimSchemaGenerator.java # Schema discovery
```

---

## Conclusion

AccountManager7's existing multi-dimensional IAM architecture provides all the foundational capabilities needed for SCIM 2.0 compliance. The implementation primarily requires:

1. **Mapping layer** - Translate between SCIM JSON and BaseRecord models
2. **Filter translation** - Convert SCIM filter syntax to Query objects
3. **Patch handling** - Implement SCIM-specific patch operations
4. **Schema exposure** - Generate SCIM schemas from model definitions

The existing `AccessPoint`, authentication, and authorization infrastructure can be reused directly, minimizing implementation effort while ensuring security and compliance.
