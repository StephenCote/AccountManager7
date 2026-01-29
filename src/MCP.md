# MCP (Model Context Protocol) Integration Plan

## Overview

This document outlines the strategy for:
1. **Adding MCP REST service** to AccountManager7
2. **Refactoring custom data injection/filtering** (citations, keyframes, reminders, image tokens) to use MCP-compliant syntax
3. **Making AM7 a generic MCP server** leveraging its object model and embedding support

## What is MCP?

Model Context Protocol (MCP) is an open standard for connecting AI assistants to external data sources and tools. It provides:
- **Resources** - Contextual data (documents, embeddings, structured data)
- **Tools** - Executable functions the AI can invoke
- **Prompts** - Reusable prompt templates
- **Sampling** - Request completions from the AI

AccountManager7's multi-dimensional object model, vector/embedding support, and existing REST infrastructure make it an ideal MCP server.

---

## Part 1: Current State Analysis

### Existing Custom Injection Patterns

| Pattern | Current Syntax | Location | Purpose |
|---------|---------------|----------|---------|
| Citations | `--- BEGIN CITATIONS ---`...`--- END CITATIONS ---` | Server injection | RAG context |
| Reminders | `(Reminder: {...})` | Appended to messages | State persistence |
| Keyframes | `(KeyFrame: {...})` | Narrative injection | Temporal state |
| Metrics | `(Metrics: {...})` | Client injection | Biometric data |
| Image Tokens | `${image.[id].[tags]}` | Content tokens | Dynamic images |
| Thoughts | `<think>...</think>` | LLM response | Reasoning traces |

### Current Filtering (chat.js)

```javascript
// Multiple ad-hoc filtering functions
pruneOut(cnt, "--- CITATION", "END CITATIONS ---")
pruneTag(cnt, "think")
pruneTag(cnt, "thought")
pruneToMark(cnt, "(Metrics")
pruneToMark(cnt, "(Reminder")
pruneToMark(cnt, "(KeyFrame")
```

### Problems with Current Approach

1. **Inconsistent syntax** - Mix of XML tags, markers, and custom tokens
2. **Brittle parsing** - String-based filtering prone to edge cases
3. **No schema** - Injected data has no formal structure
4. **Client/server coupling** - Both sides must know all patterns
5. **Limited extensibility** - Adding new patterns requires code changes

---

## Part 2: MCP-Compliant Syntax

### Unified Context Block Format

Replace all custom patterns with MCP-style context blocks:

```xml
<mcp:context type="[type]" id="[optional-id]" ephemeral="[true|false]">
{
  "schema": "[schema-uri]",
  "data": { ... }
}
</mcp:context>
```

### Migration Mapping

| Current | MCP Equivalent |
|---------|---------------|
| `--- BEGIN CITATIONS ---` | `<mcp:context type="resource" uri="am7://citations/...">` |
| `(Reminder: {...})` | `<mcp:context type="resource" uri="am7://reminder/..." ephemeral="true">` |
| `(KeyFrame: {...})` | `<mcp:context type="resource" uri="am7://keyframe/..." ephemeral="true">` |
| `(Metrics: {...})` | `<mcp:context type="resource" uri="am7://metrics/..." ephemeral="true">` |
| `${image.[id].[tags]}` | `<mcp:resource uri="am7://media/[id]" tags="[tags]" />` |
| `<think>...</think>` | `<mcp:context type="reasoning" ephemeral="true">` |

### New Syntax Examples

**Citation Block (RAG Context)**:
```xml
<mcp:context type="resource" uri="am7://vector/search/chat-history">
{
  "schema": "urn:am7:vector:search-result",
  "query": "original search query",
  "results": [
    {
      "uri": "am7://chat/session-123/message-5",
      "content": "Previous conversation excerpt...",
      "score": 0.89,
      "metadata": {
        "timestamp": "2024-01-15T10:30:00Z",
        "participants": ["user", "assistant"]
      }
    }
  ]
}
</mcp:context>
```

**Reminder Block**:
```xml
<mcp:context type="resource" uri="am7://reminder/user-123" ephemeral="true">
{
  "schema": "urn:am7:narrative:reminder",
  "items": [
    { "key": "user_preference", "value": "prefers formal tone" },
    { "key": "ongoing_task", "value": "debugging authentication flow" }
  ],
  "expires": "2024-01-15T12:00:00Z"
}
</mcp:context>
```

**Keyframe Block**:
```xml
<mcp:context type="resource" uri="am7://keyframe/scene-456" ephemeral="true">
{
  "schema": "urn:am7:narrative:keyframe",
  "timestamp": "2024-01-15T10:30:00Z",
  "state": {
    "location": "office",
    "characters": ["alice", "bob"],
    "mood": "tense",
    "objectives": ["resolve conflict", "find solution"]
  }
}
</mcp:context>
```

**Metrics Block**:
```xml
<mcp:context type="resource" uri="am7://metrics/biometric" ephemeral="true">
{
  "schema": "urn:am7:biometric:face-analysis",
  "dominant_emotion": "happy",
  "emotion_scores": {
    "happy": 0.85,
    "neutral": 0.10,
    "sad": 0.05
  },
  "dominant_race": "Asian",
  "age_range": [25, 35]
}
</mcp:context>
```

**Image Resource**:
```xml
<mcp:resource uri="am7://media/data.data/12345" type="image/png">
  <mcp:tags>portrait, character, fantasy</mcp:tags>
  <mcp:thumbnail>am7://media/data.data/12345?size=256</mcp:thumbnail>
</mcp:resource>
```

**Reasoning Block**:
```xml
<mcp:context type="reasoning" ephemeral="true">
{
  "schema": "urn:am7:llm:reasoning",
  "steps": [
    "First, I need to understand the user's request...",
    "The relevant context suggests..."
  ]
}
</mcp:context>
```

---

## Part 3: MCP Server Architecture

### Server Capabilities

AccountManager7 as MCP server provides:

| Capability | AM7 Feature | MCP Mapping |
|------------|-------------|-------------|
| **Resources** | Object model (any schema) | `resources/list`, `resources/read` |
| **Vector Search** | VectorUtil hybrid search | `resources/search` (custom) |
| **Tools** | REST endpoints | `tools/list`, `tools/call` |
| **Prompts** | PromptConfig templates | `prompts/list`, `prompts/get` |
| **Sampling** | ChatService | `sampling/createMessage` |

### MCP Endpoint Structure

```
/mcp/v1/
├── initialize              # Handshake and capability negotiation
├── resources/
│   ├── list               # List available resources
│   ├── read               # Read specific resource
│   ├── search             # Semantic search (AM7 extension)
│   ├── subscribe          # Subscribe to resource changes
│   └── templates/list     # List resource templates
├── tools/
│   ├── list               # List available tools
│   └── call               # Execute a tool
├── prompts/
│   ├── list               # List prompt templates
│   └── get                # Get specific prompt
└── sampling/
    └── createMessage      # Request LLM completion
```

### Service Implementation

**Location**: `AccountManagerService7/src/main/java/org/cote/rest/services/mcp/`

```
mcp/
├── McpService.java           # Core MCP endpoints
├── McpResourceService.java   # Resource operations
├── McpToolService.java       # Tool invocation
├── McpPromptService.java     # Prompt templates
├── McpSamplingService.java   # LLM sampling
├── adapter/
│   ├── ResourceAdapter.java  # BaseRecord → MCP Resource
│   ├── ToolAdapter.java      # REST endpoint → MCP Tool
│   └── PromptAdapter.java    # PromptConfig → MCP Prompt
├── protocol/
│   ├── McpRequest.java       # Request DTOs
│   ├── McpResponse.java      # Response DTOs
│   └── McpError.java         # Error handling
└── context/
    ├── ContextParser.java    # Parse mcp:context blocks
    ├── ContextBuilder.java   # Build mcp:context blocks
    └── ContextFilter.java    # Filter/display logic
```

---

## Part 4: Resource Mapping

### AM7 Models as MCP Resources

Every AM7 model becomes an MCP resource:

```javascript
// MCP Resource URI format
am7://[organization]/[model-type]/[object-id]

// Examples
am7://default/system.user/abc-123
am7://default/data.data/doc-456
am7://default/olio.llm.chatConfig/chat-789
```

### Resource Templates

```json
{
  "resourceTemplates": [
    {
      "uriTemplate": "am7://{org}/{type}/{id}",
      "name": "AM7 Object",
      "description": "Any AccountManager7 object by type and ID",
      "mimeType": "application/json"
    },
    {
      "uriTemplate": "am7://{org}/vector/search?q={query}&type={type}&limit={limit}",
      "name": "Vector Search",
      "description": "Semantic search across vectorized content",
      "mimeType": "application/json"
    },
    {
      "uriTemplate": "am7://{org}/media/{type}/{id}",
      "name": "Media Resource",
      "description": "Binary media (images, audio, documents)",
      "mimeType": "*/*"
    }
  ]
}
```

### Resource Read Implementation

```java
@Path("/mcp/v1/resources")
public class McpResourceService {

    @POST @Path("/read")
    public Response readResource(McpResourceRequest request) {
        String uri = request.getUri();

        // Parse AM7 URI
        Am7Uri parsed = Am7Uri.parse(uri);  // am7://org/type/id

        // Fetch via AccessPoint
        BaseRecord record = IOSystem.getActiveContext()
            .getAccessPoint()
            .findByObjectId(user, parsed.getType(), parsed.getId());

        if (record == null) {
            return McpError.notFound(uri);
        }

        // Convert to MCP Resource format
        McpResource resource = ResourceAdapter.toMcp(record, parsed);

        return Response.ok(resource).build();
    }

    @POST @Path("/search")
    public Response searchResources(McpSearchRequest request) {
        // Leverage VectorUtil hybrid search
        String query = request.getQuery();
        String type = request.getType();
        int limit = request.getLimit();
        double threshold = request.getThreshold();

        List<BaseRecord> results = IOSystem.getActiveContext()
            .getVectorUtil()
            .find(type, query, limit, threshold);

        List<McpResource> resources = results.stream()
            .map(r -> ResourceAdapter.toMcp(r, null))
            .collect(Collectors.toList());

        return Response.ok(new McpSearchResponse(resources)).build();
    }
}
```

---

## Part 5: Tool Mapping

### AM7 Endpoints as MCP Tools

Expose REST endpoints as invocable MCP tools:

```json
{
  "tools": [
    {
      "name": "am7_create_object",
      "description": "Create a new object in AccountManager7",
      "inputSchema": {
        "type": "object",
        "properties": {
          "type": { "type": "string", "description": "Model type (e.g., data.data)" },
          "name": { "type": "string" },
          "groupPath": { "type": "string" },
          "attributes": { "type": "object" }
        },
        "required": ["type", "name"]
      }
    },
    {
      "name": "am7_vector_search",
      "description": "Semantic search across embedded content",
      "inputSchema": {
        "type": "object",
        "properties": {
          "query": { "type": "string" },
          "types": { "type": "array", "items": { "type": "string" } },
          "limit": { "type": "integer", "default": 10 },
          "threshold": { "type": "number", "default": 0.7 }
        },
        "required": ["query"]
      }
    },
    {
      "name": "am7_chat",
      "description": "Send a chat message using configured LLM",
      "inputSchema": {
        "type": "object",
        "properties": {
          "message": { "type": "string" },
          "chatConfigId": { "type": "string" },
          "includeHistory": { "type": "boolean", "default": true }
        },
        "required": ["message", "chatConfigId"]
      }
    },
    {
      "name": "am7_vectorize",
      "description": "Create embeddings for a document",
      "inputSchema": {
        "type": "object",
        "properties": {
          "objectId": { "type": "string" },
          "type": { "type": "string" },
          "chunkType": { "enum": ["SENTENCE", "WORD", "CHAPTER"] },
          "chunkSize": { "type": "integer" }
        },
        "required": ["objectId", "type"]
      }
    }
  ]
}
```

### Tool Call Implementation

```java
@Path("/mcp/v1/tools")
public class McpToolService {

    @POST @Path("/call")
    public Response callTool(McpToolCallRequest request) {
        String toolName = request.getName();
        Map<String, Object> args = request.getArguments();

        switch (toolName) {
            case "am7_create_object":
                return handleCreateObject(args);
            case "am7_vector_search":
                return handleVectorSearch(args);
            case "am7_chat":
                return handleChat(args);
            case "am7_vectorize":
                return handleVectorize(args);
            default:
                return McpError.unknownTool(toolName);
        }
    }

    private Response handleVectorSearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        List<String> types = (List<String>) args.getOrDefault("types", List.of());
        int limit = (int) args.getOrDefault("limit", 10);
        double threshold = (double) args.getOrDefault("threshold", 0.7);

        // Call VectorUtil
        List<BaseRecord> results = IOSystem.getActiveContext()
            .getVectorUtil()
            .find(types, query, limit, threshold);

        return Response.ok(new McpToolResult(results)).build();
    }
}
```

---

## Part 6: Context Injection Refactor

### Server-Side: Context Builder

Replace current injection with MCP context builder:

```java
// Current (ChatService.java)
String citDesc = "--- BEGIN CITATIONS ---" + citRef + "--- END CITATIONS ---";
chat.continueChat(req, citDesc + message);

// New (MCP-compliant)
McpContextBuilder ctx = new McpContextBuilder();

// Add citations as MCP resource context
ctx.addResource("am7://vector/citations/" + chatId, "urn:am7:vector:search-result",
    Map.of(
        "query", originalQuery,
        "results", citationResults
    ),
    true  // ephemeral
);

// Add reminders
ctx.addResource("am7://reminder/" + userId, "urn:am7:narrative:reminder",
    reminderData, true);

// Add keyframes
ctx.addResource("am7://keyframe/" + sceneId, "urn:am7:narrative:keyframe",
    keyframeData, true);

// Build injected content
String mcpContext = ctx.build();
chat.continueChat(req, mcpContext + message);
```

**Context Builder Implementation**:

```java
public class McpContextBuilder {
    private List<McpContextBlock> blocks = new ArrayList<>();

    public McpContextBuilder addResource(String uri, String schema,
                                         Map<String, Object> data,
                                         boolean ephemeral) {
        blocks.add(new McpContextBlock("resource", uri, schema, data, ephemeral));
        return this;
    }

    public McpContextBuilder addReasoning(List<String> steps) {
        blocks.add(new McpContextBlock("reasoning", null,
            "urn:am7:llm:reasoning", Map.of("steps", steps), true));
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();
        for (McpContextBlock block : blocks) {
            sb.append("<mcp:context type=\"").append(block.type).append("\"");
            if (block.uri != null) {
                sb.append(" uri=\"").append(block.uri).append("\"");
            }
            if (block.ephemeral) {
                sb.append(" ephemeral=\"true\"");
            }
            sb.append(">\n");
            sb.append(JSONUtil.objectToJson(Map.of(
                "schema", block.schema,
                "data", block.data
            )));
            sb.append("\n</mcp:context>\n");
        }
        return sb.toString();
    }
}
```

### Client-Side: Context Parser & Filter

Replace ad-hoc filtering with unified MCP context parser:

```javascript
// New: mcp/contextParser.js
class McpContextParser {
    static CONTEXT_PATTERN = /<mcp:context([^>]*)>([\s\S]*?)<\/mcp:context>/gi;
    static RESOURCE_PATTERN = /<mcp:resource([^>]*?)\/>/gi;

    /**
     * Parse all MCP context blocks from content
     * @returns {McpContext[]} Array of parsed context objects
     */
    static parse(content) {
        const contexts = [];
        let match;

        // Parse context blocks
        while ((match = this.CONTEXT_PATTERN.exec(content)) !== null) {
            const attrs = this.parseAttributes(match[1]);
            const body = match[2].trim();

            contexts.push({
                type: attrs.type,
                uri: attrs.uri,
                ephemeral: attrs.ephemeral === 'true',
                raw: match[0],
                start: match.index,
                end: match.index + match[0].length,
                data: this.tryParseJson(body)
            });
        }

        // Parse inline resources
        while ((match = this.RESOURCE_PATTERN.exec(content)) !== null) {
            const attrs = this.parseAttributes(match[1]);
            contexts.push({
                type: 'resource',
                uri: attrs.uri,
                resourceType: attrs.type,
                tags: attrs.tags?.split(',').map(t => t.trim()),
                raw: match[0],
                start: match.index,
                end: match.index + match[0].length,
                inline: true
            });
        }

        return contexts.sort((a, b) => a.start - b.start);
    }

    static parseAttributes(attrString) {
        const attrs = {};
        const pattern = /(\w+)="([^"]*)"/g;
        let match;
        while ((match = pattern.exec(attrString)) !== null) {
            attrs[match[1]] = match[2];
        }
        return attrs;
    }

    static tryParseJson(str) {
        try {
            return JSON.parse(str);
        } catch (e) {
            return { raw: str };
        }
    }
}
```

**Context Filter**:

```javascript
// New: mcp/contextFilter.js
class McpContextFilter {
    constructor(options = {}) {
        this.showEphemeral = options.showEphemeral ?? false;
        this.showReasoning = options.showReasoning ?? false;
        this.renderResources = options.renderResources ?? true;
    }

    /**
     * Filter content for display
     * @returns {FilterResult} Filtered content + extracted contexts
     */
    filter(content) {
        const contexts = McpContextParser.parse(content);
        const extracted = {
            citations: [],
            reminders: [],
            keyframes: [],
            metrics: [],
            reasoning: [],
            resources: []
        };

        let filtered = content;

        // Process in reverse order to preserve indices
        for (let i = contexts.length - 1; i >= 0; i--) {
            const ctx = contexts[i];

            // Categorize
            this.categorize(ctx, extracted);

            // Determine if we should remove
            const shouldRemove =
                (ctx.ephemeral && !this.showEphemeral) ||
                (ctx.type === 'reasoning' && !this.showReasoning);

            if (shouldRemove) {
                // Remove from content
                filtered = filtered.substring(0, ctx.start) +
                           filtered.substring(ctx.end);
            } else if (ctx.inline && ctx.type === 'resource' && this.renderResources) {
                // Render inline resources
                const rendered = this.renderResource(ctx);
                filtered = filtered.substring(0, ctx.start) +
                           rendered +
                           filtered.substring(ctx.end);
            }
        }

        return {
            content: filtered.trim(),
            contexts: extracted
        };
    }

    categorize(ctx, extracted) {
        if (!ctx.uri) {
            if (ctx.type === 'reasoning') extracted.reasoning.push(ctx);
            return;
        }

        if (ctx.uri.includes('/citations/') || ctx.uri.includes('/vector/')) {
            extracted.citations.push(ctx);
        } else if (ctx.uri.includes('/reminder/')) {
            extracted.reminders.push(ctx);
        } else if (ctx.uri.includes('/keyframe/')) {
            extracted.keyframes.push(ctx);
        } else if (ctx.uri.includes('/metrics/')) {
            extracted.metrics.push(ctx);
        } else if (ctx.uri.includes('/media/')) {
            extracted.resources.push(ctx);
        }
    }

    renderResource(ctx) {
        if (ctx.uri.includes('/media/')) {
            // Render as image
            const url = this.uriToUrl(ctx.uri);
            return `<img src="${url}" class="max-w-[256px] rounded-lg my-2 cursor-pointer"
                    data-mcp-uri="${ctx.uri}"
                    data-mcp-tags="${ctx.tags?.join(',') || ''}" />`;
        }
        return ctx.raw;
    }

    uriToUrl(uri) {
        // am7://org/media/data.data/12345 → /media/org/data.data/12345
        const match = uri.match(/am7:\/\/([^/]+)\/media\/([^/]+)\/(.+)/);
        if (match) {
            return `${g_application_path}/media/${match[1]}/${match[2]}/${match[3]}`;
        }
        return uri;
    }
}
```

### Integration in chat.js

```javascript
// Replace current filtering
import { McpContextParser, McpContextFilter } from './mcp/index.js';

const contextFilter = new McpContextFilter({
    showEphemeral: false,      // Hide citations, reminders, etc.
    showReasoning: false,      // Hide <think> blocks
    renderResources: true      // Render images inline
});

// In message rendering
function renderMessage(msg, index) {
    // Parse and filter MCP contexts
    const { content, contexts } = contextFilter.filter(msg.content);

    // Store extracted contexts for potential UI display
    msg._mcpContexts = contexts;

    // Render filtered content
    return m(".message", [
        m.trust(marked.parse(content)),

        // Optional: Show context indicators
        contexts.citations.length > 0 && m(".citation-indicator",
            `${contexts.citations.length} citations`),
        contexts.reminders.length > 0 && m(".reminder-indicator",
            `${contexts.reminders.length} reminders`)
    ]);
}
```

---

## Part 7: Image Token Migration

### Current → MCP Migration

```javascript
// Current syntax
"${image.12345.portrait,character}"

// MCP syntax
'<mcp:resource uri="am7://default/media/data.data/12345" type="image/png">' +
'<mcp:tags>portrait, character</mcp:tags>' +
'</mcp:resource>'

// Or compact inline form
'<mcp:resource uri="am7://media/12345" tags="portrait,character" />'
```

### Backwards Compatibility Layer

```javascript
// mcp/legacyTokenAdapter.js
class LegacyTokenAdapter {
    static IMAGE_TOKEN_PATTERN = /\$\{image\.([^.}]+)(?:\.([^}]+))?\}/g;

    /**
     * Convert legacy ${image...} tokens to MCP resource syntax
     */
    static upgrade(content) {
        return content.replace(this.IMAGE_TOKEN_PATTERN, (match, idOrTags, tags) => {
            // Determine if first group is ID or tags
            const isId = /^[a-f0-9-]{36}$/.test(idOrTags);

            if (isId) {
                const tagList = tags || '';
                return `<mcp:resource uri="am7://media/${idOrTags}" tags="${tagList}" />`;
            } else {
                // Tags only (unresolved)
                const tagList = idOrTags + (tags ? ',' + tags : '');
                return `<mcp:resource uri="am7://media/pending" tags="${tagList}" resolve="true" />`;
            }
        });
    }

    /**
     * Convert MCP resources back to legacy tokens (for editing)
     */
    static downgrade(content) {
        const pattern = /<mcp:resource uri="am7:\/\/media\/([^"]+)" tags="([^"]*)"[^/]*\/>/g;
        return content.replace(pattern, (match, id, tags) => {
            if (id === 'pending') {
                return '${image.' + tags + '}';
            }
            return '${image.' + id + '.' + tags + '}';
        });
    }
}
```

---

## Part 8: MCP Protocol Implementation

### Initialize Handshake

```java
@Path("/mcp/v1")
public class McpService {

    @POST @Path("/initialize")
    public Response initialize(McpInitializeRequest request) {
        McpInitializeResponse response = new McpInitializeResponse();

        response.setProtocolVersion("2024-11-05");
        response.setServerInfo(Map.of(
            "name", "AccountManager7",
            "version", getVersion()
        ));

        // Declare capabilities
        response.setCapabilities(Map.of(
            "resources", Map.of(
                "subscribe", true,
                "listChanged", true
            ),
            "tools", Map.of(),
            "prompts", Map.of(
                "listChanged", true
            ),
            "logging", Map.of()
        ));

        return Response.ok(response).build();
    }

    @POST @Path("/initialized")
    public Response initialized() {
        // Client has completed initialization
        return Response.ok().build();
    }
}
```

### Resource Subscription (SSE)

```java
@GET @Path("/resources/subscribe")
@Produces(MediaType.SERVER_SENT_EVENTS)
public void subscribeResources(@QueryParam("uri") String uri,
                               @Context SseEventSink sink,
                               @Context Sse sse) {
    // Register subscription
    String subscriptionId = UUID.randomUUID().toString();

    // Watch for changes via AM7's event system
    IOSystem.getActiveContext().getEventBus()
        .subscribe(uri, (event) -> {
            SseEvent sseEvent = sse.newEventBuilder()
                .name("resource/updated")
                .data(McpNotification.resourceUpdated(uri))
                .build();
            sink.send(sseEvent);
        });
}
```

---

## Part 9: File Structure

```
AccountManagerService7/src/main/java/org/cote/rest/services/
└── mcp/
    ├── McpService.java              # Core protocol endpoints
    ├── McpResourceService.java      # Resource operations
    ├── McpToolService.java          # Tool invocation
    ├── McpPromptService.java        # Prompt templates
    ├── McpSamplingService.java      # LLM sampling
    │
    ├── adapter/
    │   ├── ResourceAdapter.java     # BaseRecord → MCP Resource
    │   ├── ToolAdapter.java         # Endpoint → MCP Tool
    │   ├── PromptAdapter.java       # PromptConfig → MCP Prompt
    │   └── Am7Uri.java              # URI parser for am7:// scheme
    │
    ├── protocol/
    │   ├── McpRequest.java          # Base request
    │   ├── McpResponse.java         # Base response
    │   ├── McpError.java            # Error codes
    │   ├── McpNotification.java     # Event notifications
    │   └── dto/
    │       ├── McpResource.java
    │       ├── McpTool.java
    │       ├── McpPrompt.java
    │       └── McpToolResult.java
    │
    └── context/
        ├── McpContextBuilder.java   # Build mcp:context blocks
        ├── McpContextParser.java    # Parse mcp:context blocks
        └── McpContextFilter.java    # Filter for display

AccountManagerUx7/client/
└── mcp/
    ├── index.js                     # Module exports
    ├── contextParser.js             # Client-side parser
    ├── contextFilter.js             # Client-side filter
    ├── resourceResolver.js          # Resolve MCP URIs
    └── legacyTokenAdapter.js        # Backwards compatibility
```

---

## Part 10: Implementation Checklist

### Phase 1: MCP Protocol Foundation
- [ ] Create `mcp/` package structure
- [ ] Implement `McpService.java` with initialize endpoint
- [ ] Create protocol DTOs (request/response classes)
- [ ] Add `Am7Uri` parser for `am7://` scheme
- [ ] Register MCP endpoints in `RestServiceConfig`
- [ ] Configure `/mcp/*` URL pattern in web.xml

### Phase 2: Resource Operations
- [ ] Implement `McpResourceService.java`
- [ ] Create `ResourceAdapter` for BaseRecord conversion
- [ ] Implement `resources/list` endpoint
- [ ] Implement `resources/read` endpoint
- [ ] Implement `resources/search` (vector search integration)
- [ ] Add resource templates for common patterns

### Phase 3: Tool Operations
- [ ] Implement `McpToolService.java`
- [ ] Create `ToolAdapter` for endpoint mapping
- [ ] Expose vector search as MCP tool
- [ ] Expose chat as MCP tool
- [ ] Expose CRUD operations as MCP tools

### Phase 4: Context Refactor (Server)
- [ ] Create `McpContextBuilder.java`
- [ ] Refactor `ChatService` citation injection
- [ ] Refactor reminder injection
- [ ] Refactor keyframe injection
- [ ] Refactor metrics injection
- [ ] Update `ChatUtil.getDataCitations()` for MCP format

### Phase 5: Context Refactor (Client)
- [ ] Create `mcp/` client module
- [ ] Implement `McpContextParser`
- [ ] Implement `McpContextFilter`
- [ ] Create `LegacyTokenAdapter` for backwards compatibility
- [ ] Update `chat.js` to use new filter
- [ ] Remove legacy `pruneOut`, `pruneTag`, `pruneToMark` functions
- [ ] Test backwards compatibility with existing conversations

### Phase 6: Prompt Templates
- [ ] Implement `McpPromptService.java`
- [ ] Create `PromptAdapter` for PromptConfig
- [ ] Expose existing prompt templates via MCP
- [ ] Add prompt argument support

### Phase 7: Sampling (Optional)
- [ ] Implement `McpSamplingService.java`
- [ ] Integrate with existing ChatService
- [ ] Support streaming responses

### Phase 8: Subscriptions (Optional)
- [ ] Add SSE endpoint for resource subscriptions
- [ ] Integrate with AM7 event system
- [ ] Implement `listChanged` notifications

### Phase 9: Testing & Documentation
- [ ] Create MCP conformance tests
- [ ] Test with Claude Desktop MCP client
- [ ] Test with other MCP clients
- [ ] Document available resources and tools
- [ ] Create migration guide for existing integrations

---

## Part 11: API Quick Reference

### Resources

```
POST /mcp/v1/resources/list
{ "cursor": "optional-pagination-cursor" }

POST /mcp/v1/resources/read
{ "uri": "am7://org/type/id" }

POST /mcp/v1/resources/search
{ "query": "search text", "types": ["data.data"], "limit": 10 }

GET /mcp/v1/resources/subscribe?uri=am7://...
(Server-Sent Events)
```

### Tools

```
POST /mcp/v1/tools/list
{}

POST /mcp/v1/tools/call
{
  "name": "am7_vector_search",
  "arguments": {
    "query": "authentication patterns",
    "limit": 5
  }
}
```

### Prompts

```
POST /mcp/v1/prompts/list
{}

POST /mcp/v1/prompts/get
{
  "name": "chat_system_prompt",
  "arguments": {
    "character": "assistant",
    "tone": "professional"
  }
}
```

---

## Part 12: Unit Tests

### Test Structure

```
AccountManagerService7/src/test/java/org/cote/rest/services/mcp/
├── McpServiceTest.java              # Protocol initialization tests
├── McpResourceServiceTest.java      # Resource CRUD tests
├── McpToolServiceTest.java          # Tool invocation tests
├── McpPromptServiceTest.java        # Prompt template tests
├── adapter/
│   ├── ResourceAdapterTest.java     # BaseRecord ↔ MCP conversion
│   ├── ToolAdapterTest.java         # Tool mapping tests
│   └── Am7UriTest.java              # URI parsing tests
├── context/
│   ├── McpContextBuilderTest.java   # Context block generation
│   ├── McpContextParserTest.java    # Context block parsing
│   └── McpContextFilterTest.java    # Display filtering tests
└── integration/
    ├── McpEndToEndTest.java         # Full flow integration tests
    └── McpCompatibilityTest.java    # MCP spec compliance tests

AccountManagerUx7/client/mcp/
└── __tests__/
    ├── contextParser.test.js        # Client-side parser tests
    ├── contextFilter.test.js        # Client-side filter tests
    └── legacyTokenAdapter.test.js   # Backwards compatibility tests
```

### Server-Side Unit Tests (Java)

#### Am7UriTest.java
```java
public class Am7UriTest {

    @Test
    public void testParseValidUri() {
        Am7Uri uri = Am7Uri.parse("am7://default/system.user/abc-123");

        assertEquals("default", uri.getOrganization());
        assertEquals("system.user", uri.getType());
        assertEquals("abc-123", uri.getId());
    }

    @Test
    public void testParseMediaUri() {
        Am7Uri uri = Am7Uri.parse("am7://default/media/data.data/img-456");

        assertTrue(uri.isMedia());
        assertEquals("data.data", uri.getType());
        assertEquals("img-456", uri.getId());
    }

    @Test
    public void testParseVectorSearchUri() {
        Am7Uri uri = Am7Uri.parse("am7://default/vector/search?q=test&limit=10");

        assertTrue(uri.isVectorSearch());
        assertEquals("test", uri.getQueryParam("q"));
        assertEquals("10", uri.getQueryParam("limit"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidScheme() {
        Am7Uri.parse("http://example.com/resource");
    }

    @Test
    public void testBuildUri() {
        String uri = Am7Uri.builder()
            .organization("default")
            .type("auth.group")
            .id("grp-789")
            .build();

        assertEquals("am7://default/auth.group/grp-789", uri);
    }
}
```

#### ResourceAdapterTest.java
```java
public class ResourceAdapterTest {

    private BaseRecord testUser;
    private BaseRecord testGroup;

    @Before
    public void setup() {
        testUser = RecordFactory.newInstance(ModelNames.MODEL_USER);
        testUser.set(FieldNames.FIELD_OBJECT_ID, "user-123");
        testUser.set(FieldNames.FIELD_NAME, "testuser");
        testUser.set(FieldNames.FIELD_CREATED_DATE, ZonedDateTime.now());

        testGroup = RecordFactory.newInstance(ModelNames.MODEL_GROUP);
        testGroup.set(FieldNames.FIELD_OBJECT_ID, "group-456");
        testGroup.set(FieldNames.FIELD_NAME, "TestGroup");
    }

    @Test
    public void testUserToMcpResource() {
        McpResource resource = ResourceAdapter.toMcp(testUser, null);

        assertEquals("am7://default/system.user/user-123", resource.getUri());
        assertEquals("testuser", resource.getName());
        assertEquals("application/json", resource.getMimeType());
        assertNotNull(resource.getContents());
    }

    @Test
    public void testGroupToMcpResource() {
        McpResource resource = ResourceAdapter.toMcp(testGroup, null);

        assertEquals("am7://default/auth.group/group-456", resource.getUri());
        assertEquals("TestGroup", resource.getName());
    }

    @Test
    public void testMcpResourceToBaseRecord() {
        McpResource resource = new McpResource();
        resource.setUri("am7://default/system.user/new-user");
        resource.setContents(Map.of(
            "name", "newuser",
            "status", "ACTIVE"
        ));

        BaseRecord record = ResourceAdapter.fromMcp(resource, ModelNames.MODEL_USER);

        assertEquals("newuser", record.get(FieldNames.FIELD_NAME));
    }

    @Test
    public void testMediaResourceConversion() {
        BaseRecord media = RecordFactory.newInstance(ModelNames.MODEL_DATA);
        media.set(FieldNames.FIELD_OBJECT_ID, "img-789");
        media.set(FieldNames.FIELD_NAME, "test.png");
        media.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");

        McpResource resource = ResourceAdapter.toMcp(media, null);

        assertEquals("image/png", resource.getMimeType());
        assertTrue(resource.getUri().contains("/media/"));
    }
}
```

#### McpContextBuilderTest.java
```java
public class McpContextBuilderTest {

    @Test
    public void testBuildCitationContext() {
        McpContextBuilder builder = new McpContextBuilder();

        builder.addResource(
            "am7://vector/citations/chat-123",
            "urn:am7:vector:search-result",
            Map.of(
                "query", "test query",
                "results", List.of(
                    Map.of("uri", "am7://chat/msg-1", "content", "Result 1", "score", 0.9)
                )
            ),
            true  // ephemeral
        );

        String output = builder.build();

        assertTrue(output.contains("<mcp:context type=\"resource\""));
        assertTrue(output.contains("uri=\"am7://vector/citations/chat-123\""));
        assertTrue(output.contains("ephemeral=\"true\""));
        assertTrue(output.contains("\"query\""));
        assertTrue(output.contains("</mcp:context>"));
    }

    @Test
    public void testBuildReasoningContext() {
        McpContextBuilder builder = new McpContextBuilder();

        builder.addReasoning(List.of(
            "Step 1: Analyze the request",
            "Step 2: Gather relevant context"
        ));

        String output = builder.build();

        assertTrue(output.contains("type=\"reasoning\""));
        assertTrue(output.contains("Step 1"));
        assertTrue(output.contains("Step 2"));
    }

    @Test
    public void testBuildMultipleContexts() {
        McpContextBuilder builder = new McpContextBuilder();

        builder.addResource("am7://reminder/user-1", "urn:am7:narrative:reminder",
            Map.of("items", List.of("Remember this")), true);

        builder.addResource("am7://keyframe/scene-1", "urn:am7:narrative:keyframe",
            Map.of("state", Map.of("location", "office")), true);

        String output = builder.build();

        // Should contain both context blocks
        int contextCount = output.split("<mcp:context").length - 1;
        assertEquals(2, contextCount);
    }

    @Test
    public void testBuildEmptyReturnsEmpty() {
        McpContextBuilder builder = new McpContextBuilder();
        String output = builder.build();
        assertEquals("", output);
    }
}
```

#### McpContextParserTest.java
```java
public class McpContextParserTest {

    @Test
    public void testParseResourceContext() {
        String content = """
            Hello
            <mcp:context type="resource" uri="am7://citations/123" ephemeral="true">
            {"schema": "test", "data": {"key": "value"}}
            </mcp:context>
            World
            """;

        List<McpContext> contexts = McpContextParser.parse(content);

        assertEquals(1, contexts.size());
        assertEquals("resource", contexts.get(0).getType());
        assertEquals("am7://citations/123", contexts.get(0).getUri());
        assertTrue(contexts.get(0).isEphemeral());
    }

    @Test
    public void testParseInlineResource() {
        String content = "Image: <mcp:resource uri=\"am7://media/img-1\" tags=\"portrait,photo\" />";

        List<McpContext> contexts = McpContextParser.parse(content);

        assertEquals(1, contexts.size());
        assertTrue(contexts.get(0).isInline());
        assertEquals("am7://media/img-1", contexts.get(0).getUri());
        assertEquals(List.of("portrait", "photo"), contexts.get(0).getTags());
    }

    @Test
    public void testParseMultipleContexts() {
        String content = """
            <mcp:context type="resource" uri="am7://a">{"data":1}</mcp:context>
            Some text
            <mcp:context type="reasoning" ephemeral="true">{"steps":[]}</mcp:context>
            """;

        List<McpContext> contexts = McpContextParser.parse(content);

        assertEquals(2, contexts.size());
        assertEquals("resource", contexts.get(0).getType());
        assertEquals("reasoning", contexts.get(1).getType());
    }

    @Test
    public void testParsePreservesPositions() {
        String content = "A<mcp:context type=\"x\">B</mcp:context>C";

        List<McpContext> contexts = McpContextParser.parse(content);

        assertEquals(1, contexts.get(0).getStart());  // After 'A'
        assertTrue(contexts.get(0).getEnd() > contexts.get(0).getStart());
    }

    @Test
    public void testParseNoContextsReturnsEmpty() {
        String content = "Just regular text without any MCP contexts.";

        List<McpContext> contexts = McpContextParser.parse(content);

        assertTrue(contexts.isEmpty());
    }
}
```

#### McpResourceServiceTest.java
```java
@RunWith(MockitoJUnitRunner.class)
public class McpResourceServiceTest {

    @Mock
    private IOSystem ioSystem;

    @Mock
    private AccessPoint accessPoint;

    @InjectMocks
    private McpResourceService resourceService;

    @Before
    public void setup() {
        when(ioSystem.getActiveContext().getAccessPoint()).thenReturn(accessPoint);
    }

    @Test
    public void testReadResource() {
        BaseRecord user = createMockUser("user-123", "testuser");
        when(accessPoint.findByObjectId(any(), eq("system.user"), eq("user-123")))
            .thenReturn(user);

        McpResourceRequest request = new McpResourceRequest();
        request.setUri("am7://default/system.user/user-123");

        Response response = resourceService.readResource(request);

        assertEquals(200, response.getStatus());
        McpResource resource = (McpResource) response.getEntity();
        assertEquals("testuser", resource.getName());
    }

    @Test
    public void testReadResourceNotFound() {
        when(accessPoint.findByObjectId(any(), eq("system.user"), eq("nonexistent")))
            .thenReturn(null);

        McpResourceRequest request = new McpResourceRequest();
        request.setUri("am7://default/system.user/nonexistent");

        Response response = resourceService.readResource(request);

        assertEquals(404, response.getStatus());
    }

    @Test
    public void testListResources() {
        List<BaseRecord> users = List.of(
            createMockUser("user-1", "alice"),
            createMockUser("user-2", "bob")
        );
        when(accessPoint.list(any(), any())).thenReturn(new QueryResult(users, 2));

        McpListRequest request = new McpListRequest();
        request.setType("system.user");

        Response response = resourceService.listResources(request);

        assertEquals(200, response.getStatus());
        McpListResponse listResponse = (McpListResponse) response.getEntity();
        assertEquals(2, listResponse.getResources().size());
    }

    @Test
    public void testSearchResources() {
        List<BaseRecord> results = List.of(createMockVectorResult("doc-1", 0.9));
        when(ioSystem.getActiveContext().getVectorUtil().find(any(), any(), anyInt(), anyDouble()))
            .thenReturn(results);

        McpSearchRequest request = new McpSearchRequest();
        request.setQuery("test search");
        request.setLimit(10);

        Response response = resourceService.searchResources(request);

        assertEquals(200, response.getStatus());
    }

    private BaseRecord createMockUser(String objectId, String name) {
        BaseRecord user = RecordFactory.newInstance(ModelNames.MODEL_USER);
        user.set(FieldNames.FIELD_OBJECT_ID, objectId);
        user.set(FieldNames.FIELD_NAME, name);
        return user;
    }
}
```

#### McpToolServiceTest.java
```java
public class McpToolServiceTest {

    @Test
    public void testListTools() {
        McpToolService service = new McpToolService();

        Response response = service.listTools();

        assertEquals(200, response.getStatus());
        McpToolListResponse toolList = (McpToolListResponse) response.getEntity();

        // Should include standard tools
        assertTrue(toolList.getTools().stream()
            .anyMatch(t -> t.getName().equals("am7_vector_search")));
        assertTrue(toolList.getTools().stream()
            .anyMatch(t -> t.getName().equals("am7_create_object")));
    }

    @Test
    public void testCallVectorSearchTool() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vector_search");
        request.setArguments(Map.of(
            "query", "authentication",
            "limit", 5
        ));

        Response response = toolService.callTool(request);

        assertEquals(200, response.getStatus());
        McpToolResult result = (McpToolResult) response.getEntity();
        assertNotNull(result.getContent());
    }

    @Test
    public void testCallUnknownTool() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("nonexistent_tool");
        request.setArguments(Map.of());

        Response response = toolService.callTool(request);

        assertEquals(404, response.getStatus());
        McpError error = (McpError) response.getEntity();
        assertEquals("unknown_tool", error.getCode());
    }

    @Test
    public void testToolInputValidation() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vector_search");
        request.setArguments(Map.of());  // Missing required 'query'

        Response response = toolService.callTool(request);

        assertEquals(400, response.getStatus());
        McpError error = (McpError) response.getEntity();
        assertEquals("invalid_params", error.getCode());
    }
}
```

### Client-Side Unit Tests (JavaScript)

#### contextParser.test.js
```javascript
import { McpContextParser } from '../contextParser.js';

describe('McpContextParser', () => {

    test('parses resource context block', () => {
        const content = `
            <mcp:context type="resource" uri="am7://test/123" ephemeral="true">
            {"schema": "test", "data": {"key": "value"}}
            </mcp:context>
        `;

        const contexts = McpContextParser.parse(content);

        expect(contexts).toHaveLength(1);
        expect(contexts[0].type).toBe('resource');
        expect(contexts[0].uri).toBe('am7://test/123');
        expect(contexts[0].ephemeral).toBe(true);
        expect(contexts[0].data.schema).toBe('test');
    });

    test('parses inline resource tag', () => {
        const content = 'Here is <mcp:resource uri="am7://media/img-1" tags="portrait,photo" /> an image';

        const contexts = McpContextParser.parse(content);

        expect(contexts).toHaveLength(1);
        expect(contexts[0].inline).toBe(true);
        expect(contexts[0].uri).toBe('am7://media/img-1');
        expect(contexts[0].tags).toEqual(['portrait', 'photo']);
    });

    test('parses multiple contexts in order', () => {
        const content = `
            <mcp:context type="a" uri="u1">1</mcp:context>
            text
            <mcp:context type="b" uri="u2">2</mcp:context>
        `;

        const contexts = McpContextParser.parse(content);

        expect(contexts).toHaveLength(2);
        expect(contexts[0].type).toBe('a');
        expect(contexts[1].type).toBe('b');
        expect(contexts[0].start).toBeLessThan(contexts[1].start);
    });

    test('handles malformed JSON gracefully', () => {
        const content = '<mcp:context type="x">not valid json</mcp:context>';

        const contexts = McpContextParser.parse(content);

        expect(contexts).toHaveLength(1);
        expect(contexts[0].data.raw).toBe('not valid json');
    });

    test('returns empty array for no contexts', () => {
        const content = 'Just plain text';

        const contexts = McpContextParser.parse(content);

        expect(contexts).toHaveLength(0);
    });
});
```

#### contextFilter.test.js
```javascript
import { McpContextFilter } from '../contextFilter.js';

describe('McpContextFilter', () => {

    test('removes ephemeral contexts by default', () => {
        const content = 'Hello <mcp:context type="resource" uri="am7://x" ephemeral="true">data</mcp:context> World';
        const filter = new McpContextFilter();

        const result = filter.filter(content);

        expect(result.content).toBe('Hello  World');
        expect(result.contexts.citations).toHaveLength(0);
    });

    test('shows ephemeral when configured', () => {
        const content = 'Hello <mcp:context type="resource" uri="am7://x" ephemeral="true">data</mcp:context> World';
        const filter = new McpContextFilter({ showEphemeral: true });

        const result = filter.filter(content);

        expect(result.content).toContain('<mcp:context');
    });

    test('categorizes citations correctly', () => {
        const content = '<mcp:context type="resource" uri="am7://vector/citations/123">data</mcp:context>';
        const filter = new McpContextFilter();

        const result = filter.filter(content);

        expect(result.contexts.citations).toHaveLength(1);
    });

    test('categorizes reminders correctly', () => {
        const content = '<mcp:context type="resource" uri="am7://reminder/user-1" ephemeral="true">data</mcp:context>';
        const filter = new McpContextFilter();

        const result = filter.filter(content);

        expect(result.contexts.reminders).toHaveLength(1);
    });

    test('renders inline images when enabled', () => {
        const content = 'Image: <mcp:resource uri="am7://media/img-1" tags="photo" />';
        const filter = new McpContextFilter({ renderResources: true });

        const result = filter.filter(content);

        expect(result.content).toContain('<img');
        expect(result.content).toContain('data-mcp-uri');
    });

    test('preserves non-context text', () => {
        const content = 'Start <mcp:context type="x" ephemeral="true">hidden</mcp:context> End';
        const filter = new McpContextFilter();

        const result = filter.filter(content);

        expect(result.content.trim()).toBe('Start  End');
    });
});
```

#### legacyTokenAdapter.test.js
```javascript
import { LegacyTokenAdapter } from '../legacyTokenAdapter.js';

describe('LegacyTokenAdapter', () => {

    describe('upgrade', () => {

        test('converts ID+tags token to MCP resource', () => {
            const legacy = '${image.abc-123.portrait,fantasy}';

            const upgraded = LegacyTokenAdapter.upgrade(legacy);

            expect(upgraded).toBe('<mcp:resource uri="am7://media/abc-123" tags="portrait,fantasy" />');
        });

        test('converts tags-only token to pending MCP resource', () => {
            const legacy = '${image.portrait,character}';

            const upgraded = LegacyTokenAdapter.upgrade(legacy);

            expect(upgraded).toBe('<mcp:resource uri="am7://media/pending" tags="portrait,character" resolve="true" />');
        });

        test('handles multiple tokens in content', () => {
            const legacy = 'Here is ${image.abc.tag1} and ${image.def.tag2}';

            const upgraded = LegacyTokenAdapter.upgrade(legacy);

            expect(upgraded).toContain('am7://media/abc');
            expect(upgraded).toContain('am7://media/def');
        });

        test('leaves non-token text unchanged', () => {
            const text = 'Just regular text without tokens';

            const upgraded = LegacyTokenAdapter.upgrade(text);

            expect(upgraded).toBe(text);
        });
    });

    describe('downgrade', () => {

        test('converts MCP resource back to legacy token', () => {
            const mcp = '<mcp:resource uri="am7://media/xyz-789" tags="portrait,photo" />';

            const downgraded = LegacyTokenAdapter.downgrade(mcp);

            expect(downgraded).toBe('${image.xyz-789.portrait,photo}');
        });

        test('converts pending MCP resource to tags-only token', () => {
            const mcp = '<mcp:resource uri="am7://media/pending" tags="portrait" />';

            const downgraded = LegacyTokenAdapter.downgrade(mcp);

            expect(downgraded).toBe('${image.portrait}');
        });
    });

    describe('round-trip', () => {

        test('upgrade then downgrade preserves ID token', () => {
            const original = '${image.abc-123.portrait,fantasy}';

            const roundTrip = LegacyTokenAdapter.downgrade(
                LegacyTokenAdapter.upgrade(original)
            );

            expect(roundTrip).toBe(original);
        });
    });
});
```

### Integration Tests

#### McpEndToEndTest.java
```java
@RunWith(Arquillian.class)
public class McpEndToEndTest {

    @Test
    public void testFullResourceLifecycle() {
        // Initialize MCP session
        Response initResponse = target("/mcp/v1/initialize")
            .request()
            .post(Entity.json(new McpInitializeRequest()));
        assertEquals(200, initResponse.getStatus());

        // Create a resource
        McpToolCallRequest createRequest = new McpToolCallRequest();
        createRequest.setName("am7_create_object");
        createRequest.setArguments(Map.of(
            "type", "data.data",
            "name", "test-doc",
            "groupPath", "~/Test"
        ));

        Response createResponse = target("/mcp/v1/tools/call")
            .request()
            .post(Entity.json(createRequest));
        assertEquals(200, createResponse.getStatus());

        McpToolResult createResult = createResponse.readEntity(McpToolResult.class);
        String objectId = extractObjectId(createResult);

        // Read the resource back
        McpResourceRequest readRequest = new McpResourceRequest();
        readRequest.setUri("am7://default/data.data/" + objectId);

        Response readResponse = target("/mcp/v1/resources/read")
            .request()
            .post(Entity.json(readRequest));
        assertEquals(200, readResponse.getStatus());

        // Delete the resource
        McpToolCallRequest deleteRequest = new McpToolCallRequest();
        deleteRequest.setName("am7_delete_object");
        deleteRequest.setArguments(Map.of(
            "type", "data.data",
            "objectId", objectId
        ));

        Response deleteResponse = target("/mcp/v1/tools/call")
            .request()
            .post(Entity.json(deleteRequest));
        assertEquals(200, deleteResponse.getStatus());
    }

    @Test
    public void testVectorSearchIntegration() {
        // Create and vectorize a document
        // ... setup code ...

        // Search via MCP
        McpSearchRequest searchRequest = new McpSearchRequest();
        searchRequest.setQuery("test content");
        searchRequest.setTypes(List.of("data.data"));
        searchRequest.setLimit(5);

        Response response = target("/mcp/v1/resources/search")
            .request()
            .post(Entity.json(searchRequest));

        assertEquals(200, response.getStatus());
        McpSearchResponse results = response.readEntity(McpSearchResponse.class);
        assertFalse(results.getResources().isEmpty());
    }

    @Test
    public void testContextInjectionFlow() {
        // Simulate chat with citation injection
        McpContextBuilder builder = new McpContextBuilder();
        builder.addResource("am7://vector/citations/test", "urn:am7:vector:search-result",
            Map.of("results", List.of()), true);

        String injected = builder.build() + "User message";

        // Verify context can be parsed back
        List<McpContext> parsed = McpContextParser.parse(injected);
        assertEquals(1, parsed.size());
        assertTrue(parsed.get(0).isEphemeral());
    }
}
```

### Test Configuration

#### test.properties additions
```properties
# MCP Test Configuration
test.mcp.enabled=true
test.mcp.port=8080
test.mcp.context=/rest

# Mock external services
test.mcp.mock.vectorService=true
test.mcp.mock.chatService=true
```

### Implementation Checklist Updates

Add to Phase 9 in the implementation checklist:

```markdown
### Phase 9: Unit Tests
- [ ] Create test package structure under `src/test/java/org/cote/rest/services/mcp/`
- [ ] Implement `Am7UriTest.java` - URI parsing tests
- [ ] Implement `ResourceAdapterTest.java` - conversion tests
- [ ] Implement `McpContextBuilderTest.java` - context generation tests
- [ ] Implement `McpContextParserTest.java` - context parsing tests
- [ ] Implement `McpResourceServiceTest.java` - REST endpoint tests
- [ ] Implement `McpToolServiceTest.java` - tool invocation tests
- [ ] Create client-side test directory `mcp/__tests__/`
- [ ] Implement `contextParser.test.js`
- [ ] Implement `contextFilter.test.js`
- [ ] Implement `legacyTokenAdapter.test.js`
- [ ] Implement `McpEndToEndTest.java` - integration tests
- [ ] Configure test properties for MCP
- [ ] Add tests to CI/CD pipeline
- [ ] Achieve >80% code coverage for MCP package
```

---

## Conclusion

This MCP integration provides:

1. **Standardized protocol** - AM7 becomes accessible to any MCP-compatible client
2. **Unified syntax** - All custom injections use consistent `<mcp:context>` format
3. **Clean separation** - Context data is structured and typed, not string-hacked
4. **Extensibility** - New context types follow the same pattern
5. **Backwards compatibility** - Legacy tokens can be upgraded transparently
6. **Rich capabilities** - Full object model + vector search exposed via MCP

The combination of AM7's multi-dimensional object model, hybrid vector search, and existing REST infrastructure makes it uniquely suited as a comprehensive MCP server for AI assistants.
