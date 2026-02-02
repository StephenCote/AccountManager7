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

### Phase 5b: Vector Reference URI Migration
- [ ] Add `vectorReferenceObjectId` field to `VectorModelStore` model schema
- [ ] Update `VectorUtil.createVectorStore()` to populate `vectorReferenceObjectId`
- [ ] Update `VectorUtil.newVectorStore()` to include objectId in returned records
- [ ] Update hybrid SQL query to SELECT objectId (or JOIN to source table)
- [ ] Add `Am7Uri.toUri(BaseRecord)` utility for URI construction from any record
- [ ] Update `VectorChatHistoryListFactory` to store URI refs on vector records
- [ ] Update `Chat.createNarrativeVector()` to pass URI refs in ParameterList

### Phase 5c: RAG Pipeline MCP Wrappers
- [ ] Create `ContentExtractionResult` class for structured `DocumentUtil` output
- [ ] Make chunking methods public (or create `ChunkingService` facade)
- [ ] Refactor `chunkByChapter()` to separate content from metadata (stop JSON-as-string)
- [ ] Create `McpCitationResolver` to decompose `ChatUtil.getDataCitations()` logic
- [ ] Update `ChatUtil.getCitationText()` to output MCP context blocks
- [ ] Update `ChatService.chat()` to use `McpContextBuilder` for citation injection
- [ ] Expose `am7_extract_content`, `am7_chunk_content` as MCP tools
- [ ] Update `VectorService` REST endpoints to map to MCP tool format

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

### Phase 9: Testing (see Part 12 + Part 15 for full test specs)
- [ ] Phase 9a: Protocol layer unit tests (Am7Uri, adapters, context builder/parser/filter)
- [ ] Phase 9b: URI & reference unit tests (edge cases, round-trip, chat vector refs)
- [ ] Phase 9c: Content & chunking unit tests (all formats, all strategies, edge cases)
- [ ] Phase 9d: Vector store & hybrid search unit tests (CRUD, scoring, distinct, objectId)
- [ ] Phase 9e: Citation resolver unit tests (all strategies, dedup, error handling)
- [ ] Phase 9f: MCP tool layer unit tests (search, vectorize, extract, chunk, chat tools)
- [ ] Phase 9g: Integration tests (full RAG pipeline, re-vectorize, chunking comparison)
- [ ] Create test resource files (PDF, DOCX, chapter text, unicode)
- [ ] Configure CI/CD for MCP test suite
- [ ] Achieve >90% code coverage for MCP + refactored components
- [ ] MCP spec conformance tests
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

## Part 13: Object Reference Refactoring for MCP

### Current Object Reference Flow

Object references in the chat and vector systems currently use internal Java `BaseRecord` instances with raw database IDs. This creates tight coupling that MCP integration must address.

#### Chat → Vector Reference Chain

```
ChatService.chat() REST endpoint
  │
  ├─ ChatRequest carries references as nested BaseRecord fields:
  │   chatConfig, promptConfig, session (OpenAIRequest)
  │
  ├─ ChatUtil.getOpenAIRequest(user, chatRequest)
  │   └─ OlioUtil.getFullRecord(chatConfig)   → Resolves by DB ID
  │   └─ OlioUtil.getFullRecord(promptConfig)  → Resolves by DB ID
  │   └─ OlioUtil.getFullRecord(session)       → Resolves by DB ID
  │
  ├─ Chat(user, chatConfig, promptConfig)
  │   └─ chatConfig.get("systemCharacter")  → Nested BaseRecord ref
  │   └─ chatConfig.get("userCharacter")    → Nested BaseRecord ref
  │
  ├─ Chat.createNarrativeVector(user, req)
  │   └─ ParameterList with raw BaseRecord refs:
  │       plist.parameter("chatConfig", chatConfig)
  │       plist.parameter("systemCharacter", chatConfig.get("systemCharacter"))
  │       plist.parameter("userCharacter", chatConfig.get("userCharacter"))
  │
  └─ VectorChatHistoryListFactory
      └─ Attaches raw BaseRecord refs to each vector record:
          v2.set("systemCharacter", systemChar)
          v2.set("userCharacter", userChar)
          v2.set("chatConfig", chatConfig)
          v2.set("promptConfig", promptConfig)
```

#### VectorUtil Return Values

`VectorUtil.find()` returns `List<BaseRecord>` where each record is a `VectorModelStore` containing:

```java
// VectorUtil.newVectorStore() - creates PARTIAL references
BaseRecord vsr = RecordFactory.newInstance(refType);  // Schema-only skeleton
vsr.setValue(FieldNames.FIELD_ID, ref);                // Only the DB ID
vs.setValue(FieldNames.FIELD_VECTOR_REFERENCE, vsr);   // Partial ref (ID + schema)
vs.setValue(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, refType);  // Schema name as string
vs.setValue(FieldNames.FIELD_SCORE, score);             // Similarity score
vs.setValue(FieldNames.FIELD_CONTENT, content);         // Chunk text
```

The caller must resolve partial references back to full objects:
```java
// ChatUtil.getDataCitations() line 868-888
BaseRecord recRef = RecordFactory.importRecord(dataR);
Query rq = QueryUtil.createQuery(recRef.getSchema(), FieldNames.FIELD_OBJECT_ID, objId);
BaseRecord frec = accessPoint.find(user, rq);  // Load full object by ID
```

### Problems for MCP Compatibility

| Problem | Current Behavior | MCP Requirement |
|---------|-----------------|-----------------|
| **Non-serializable refs** | `BaseRecord` objects with Java-internal identity | URI-based resource identifiers |
| **Partial references** | Skeleton `BaseRecord` with only DB `id` field | Full `am7://` URI with org/type/objectId |
| **Reference resolution** | `getFullRecord()` + DB query by internal ID | `resources/read` by URI |
| **Vector result format** | `List<BaseRecord>` with mixed fields | MCP resource list with scores and URIs |
| **Character metadata in vectors** | Raw nested `BaseRecord` refs on vector records | URI references to character resources |
| **ParameterList coupling** | Java `ParameterList` with typed `BaseRecord` params | JSON-serializable tool arguments |

### Refactoring: URI-Based References

#### 1. Add URI Resolution to BaseRecord

```java
// New: adapter/Am7Uri.java (from Part 9)
// Every BaseRecord that has objectId can produce a URI

public static String toUri(BaseRecord record) {
    String orgPath = record.get(FieldNames.FIELD_ORGANIZATION_PATH);  // or resolve
    String type = record.getSchema();
    String objectId = record.get(FieldNames.FIELD_OBJECT_ID);
    return "am7://" + orgPath + "/" + type + "/" + objectId;
}
```

#### 2. Refactor Vector Store References

```java
// Current: VectorUtil.newVectorStore()
BaseRecord vsr = RecordFactory.newInstance(refType);
vsr.setValue(FieldNames.FIELD_ID, ref);  // Internal ID only

// New: Include URI in vector results
vs.setValue("vectorReferenceUri",
    Am7Uri.builder()
        .organization(orgPath)
        .type(refType)
        .id(objectId)  // Must carry objectId, not just DB id
        .build()
);
```

**Key change**: The `findByEmbedding()` hybrid SQL query (VectorUtil lines 67-100) currently selects `vectorReference` (the internal long ID) and `vectorReferenceType` (schema string). The query must also select `objectId` from the referenced record so that MCP-compatible URIs can be constructed without a second lookup.

#### 3. Refactor Chat Vector Parameters

```java
// Current: Chat.createNarrativeVector()
ParameterList plist = ParameterList.newParameterList(FIELD_VECTOR_REFERENCE, req);
plist.parameter("chatConfig", chatConfig);           // Raw BaseRecord
plist.parameter("systemCharacter", chatConfig.get("systemCharacter"));  // Raw BaseRecord

// New: Pass URI references alongside or instead of raw records
plist.parameter("chatConfigUri", Am7Uri.toUri(chatConfig));
plist.parameter("systemCharacterUri", Am7Uri.toUri(chatConfig.get("systemCharacter")));
plist.parameter("userCharacterUri", Am7Uri.toUri(chatConfig.get("userCharacter")));
```

#### 4. MCP Tool Arguments for Chat

Replace internal `ChatRequest` with MCP-compatible tool call:

```json
{
  "name": "am7_chat",
  "arguments": {
    "message": "user message",
    "chatConfigUri": "am7://default/olio.llm.chatConfig/chat-789",
    "promptConfigUri": "am7://default/olio.llm.promptConfig/prompt-456",
    "dataReferences": [
      "am7://default/data.data/doc-123",
      "am7://default/olio.charPerson/char-456"
    ],
    "tags": ["am7://default/common.tag/tag-001"],
    "includeHistory": true
  }
}
```

#### 5. MCP Resource Format for Vector Results

```json
{
  "resources": [
    {
      "uri": "am7://default/common.vectorModelStore/vec-001",
      "name": "chunk-3-of-12",
      "mimeType": "text/plain",
      "metadata": {
        "score": 0.89,
        "chunk": 3,
        "chunkCount": 12,
        "vectorReferenceUri": "am7://default/data.data/doc-123",
        "vectorReferenceType": "data.data"
      },
      "contents": [
        {
          "uri": "am7://default/common.vectorModelStore/vec-001",
          "mimeType": "text/plain",
          "text": "The extracted chunk content..."
        }
      ]
    }
  ]
}
```

---

## Part 14: RAG Pipeline Refactoring for MCP

### Current RAG Architecture

The RAG pipeline has four stages, each requiring refactoring for MCP:

```
┌──────────────────────────────────────────────────────────────────────┐
│  1. CONTENT EXTRACTION (DocumentUtil)                                │
│     DocumentUtil.getStringContent(BaseRecord model)                  │
│     ├─ text/* → ByteModelUtil.getValueString()                       │
│     ├─ PDF   → readPDF() via PDFBox                                  │
│     ├─ Word  → readDocument() via Apache Tika                        │
│     ├─ Notes → model.get("text")                                     │
│     └─ Custom → ModelSchema.getVector() provider                     │
│                                                                      │
│  2. CHUNKING (VectorUtil)                                            │
│     ├─ SENTENCE: BreakIterator, groups N sentences per chunk         │
│     ├─ WORD: StringTokenizer, groups N words per chunk               │
│     ├─ CHAPTER: Splits on "Chapter" prefix, nests sentences          │
│     └─ UNKNOWN: Single chunk (no splitting)                          │
│                                                                      │
│  3. EMBEDDING + STORAGE (EmbeddingUtil → VectorModelStore)           │
│     EmbeddingUtil.getEmbedding(chunkText) → float[]                  │
│     Creates VectorModelStore records with:                           │
│       embedding, content, chunk, vectorReference, vectorRefType      │
│                                                                      │
│  4. HYBRID SEARCH + CITATION (VectorUtil → ChatUtil)                 │
│     findByEmbedding(): CTE with semantic + keyword ranked search     │
│     Score = 1/(k + semantic_rank) + 1/(k + keyword_rank)             │
│     ChatUtil.getDataCitations() → citation XML injection             │
│     "--- BEGIN CITATIONS ---" ... "--- END CITATIONS ---"            │
└──────────────────────────────────────────────────────────────────────┘
```

### Stage 1: Content Extraction — MCP Refactoring

**Current** (`DocumentUtil.getStringContent()`):
- Takes a `BaseRecord`, returns a `String`
- No metadata about extraction (source type, page count, encoding)
- No error details exposed to caller
- Custom vector providers are model-schema-level hooks

**MCP refactoring needed**:

```java
// New: DocumentUtil returns structured extraction result
public class ContentExtractionResult {
    private String content;
    private String sourceUri;         // am7://org/type/id
    private String mimeType;          // Original content type
    private int pageCount;            // For PDF/Word
    private int characterCount;
    private Map<String, Object> extractionMetadata;
}

// Expose as MCP tool
{
  "name": "am7_extract_content",
  "description": "Extract text content from a document for vectorization",
  "inputSchema": {
    "type": "object",
    "properties": {
      "uri": { "type": "string", "description": "am7:// URI of document" },
      "format": { "enum": ["text", "markdown", "structured"], "default": "text" }
    },
    "required": ["uri"]
  }
}
```

**Key change**: `DocumentUtil.getStringContent()` currently discards extraction metadata. The MCP tool wrapper should capture and return this metadata alongside the content so clients can make informed chunking decisions.

### Stage 2: Chunking — MCP Refactoring

**Current** (`VectorUtil` private methods):
- `chunkBySentence(String block, int chunkSize)` — BreakIterator, groups N sentences
- `chunkByWord(String block, int chunkSize)` — StringTokenizer, groups N words
- `chunkByChapter(String name, String path, String block, int chunkSize)` — Split on "Chapter" keyword, creates VectorChunk JSON with chapter metadata
- All methods are private/package-private, tightly coupled to `createVectorStore()`

**Problems**:
1. Chunking strategies are hard-coded, not discoverable
2. `chunkByChapter()` returns `List<String>` where each string is a JSON-serialized `VectorChunk` — mixing content with metadata
3. No overlap/sliding window support
4. No way for MCP clients to preview chunks before vectorizing

**MCP refactoring needed**:

```json
// Expose chunking as MCP tool
{
  "name": "am7_chunk_content",
  "description": "Split content into chunks for vectorization. Preview chunks without creating embeddings.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "uri": { "type": "string", "description": "am7:// URI of content source" },
      "content": { "type": "string", "description": "Direct text content (alternative to uri)" },
      "chunkType": { "enum": ["SENTENCE", "WORD", "CHAPTER"], "default": "SENTENCE" },
      "chunkSize": { "type": "integer", "default": 5, "description": "Sentences/words per chunk" },
      "preview": { "type": "boolean", "default": false, "description": "Preview only, do not store" }
    }
  }
}

// Structured chunk result (replaces raw String/JSON mixing)
{
  "chunks": [
    {
      "index": 0,
      "content": "chunk text...",
      "metadata": {
        "chapter": 1,
        "chapterTitle": "Introduction",
        "sentenceCount": 5,
        "wordCount": 87,
        "sourceUri": "am7://default/data.data/doc-123"
      }
    }
  ],
  "totalChunks": 12,
  "chunkType": "CHAPTER",
  "chunkSize": 5
}
```

**Key changes**:
- Make `chunkBySentence`, `chunkByWord`, `chunkByChapter` public or expose through a `ChunkingService`
- Return structured chunk objects instead of raw strings / JSON-as-string
- The `chunkByChapter()` method currently serializes `VectorChunk` to JSON string then stores that JSON as the chunk content — this should be separated into content + metadata fields on the vector store record

### Stage 3: Embedding + Storage — MCP Refactoring

**Current** (`VectorUtil.createVectorStore()` + `EmbeddingUtil`):
- `createVectorStore(model, chunkType, chunkSize)` — orchestrates entire pipeline
- `generateEmbeddings(String[])` — calls `EmbeddingUtil.getEmbedding()` per chunk
- `EmbeddingUtil` supports LOCAL and OPENAI service types
- Writes `VectorModelStore` records with `vectorReference` (DB long id) and `vectorReferenceType` (schema name)

**Problems for MCP**:
1. `vectorReference` stores internal DB `long id`, not a portable identifier
2. No way to specify embedding model/dimensions via API
3. `EmbeddingUtil` service configuration is constructor-injected, not per-request
4. No batch embedding support (processes one chunk at a time in loop)

**MCP refactoring needed**:

```json
// Enhanced vectorize tool
{
  "name": "am7_vectorize",
  "description": "Create vector embeddings for a document or content",
  "inputSchema": {
    "type": "object",
    "properties": {
      "uri": { "type": "string", "description": "am7:// URI of document to vectorize" },
      "content": { "type": "string", "description": "Direct content (alternative to uri)" },
      "chunkType": { "enum": ["SENTENCE", "WORD", "CHAPTER"] },
      "chunkSize": { "type": "integer" },
      "replaceExisting": { "type": "boolean", "default": true }
    },
    "required": ["uri"]
  }
}
```

**Storage changes**:
```java
// Current VectorModelStore fields
vectorReference: long        // Internal DB ID
vectorReferenceType: String  // Schema name

// Add for MCP compatibility
vectorReferenceUri: String   // "am7://default/data.data/doc-123"
```

The `findByEmbedding()` SQL (lines 67-100 of VectorUtil) joins only on `vectorReference` (long id). To support MCP URI-based lookups, either:
- **Option A**: Add `objectId` to the VectorModelStore and include it in the SELECT, so URIs can be constructed from results without a second query
- **Option B**: JOIN to the referenced table to pull `objectId` at query time (more expensive but no schema change)

Option A is recommended — add `vectorReferenceObjectId` (String) to the vector store record and populate it during `createVectorStore()`.

### Stage 4: Hybrid Search + Citations — MCP Refactoring

**Current hybrid search** (`VectorUtil.findByEmbedding()`):

```sql
WITH semantic_search AS (
    SELECT ... RANK() OVER (ORDER BY embedding <=> ?) AS rank
    FROM ${tableName}
    WHERE (vectorReference = ? or ? = 0) AND (vectorReferenceType = ? or ? IS NULL)
    LIMIT ?
),
keyword_search AS (
    SELECT ... RANK() OVER (ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC)
    FROM ${tableName}, plainto_tsquery('english', ?) query
    WHERE to_tsvector('english', content) @@ query
    AND (vectorReference = ? or ? = 0) AND (vectorReferenceType = ? or ? IS NULL)
    LIMIT ?
)
SELECT ...
    COALESCE(1.0 / (? + semantic_search.rank), 0.0) +
    COALESCE(1.0 / (? + keyword_search.rank), 0.0) AS score
FROM semantic_search FULL OUTER JOIN keyword_search ON ...
ORDER BY score DESC LIMIT ?
```

**Parameters**: 16-18 positional parameters bound via PreparedStatement.

**Current citation building** (`ChatUtil.getDataCitations()`):
- Deserializes data references from `ChatRequest.FIELD_DATA` (JSON strings)
- Resolves each reference by `objectId` query
- Branches: tags-only search, chat history refs, character person refs, general refs
- Checks for `~/Notes/Summaries/{name} - Summary` notes and includes their vectors
- Formats as `<citation schema="..." chapter="..." name="..." id="...">content</citation>`
- Wraps in `--- BEGIN CITATIONS --- ... --- END CITATIONS ---`

**MCP refactoring needed**:

#### A. Vector Search as MCP Tool

The `am7_vector_search` tool (from Part 5) maps to `findByEmbedding()` but needs to:

1. Accept URI-based scope references instead of internal IDs:
```json
{
  "name": "am7_vector_search",
  "arguments": {
    "query": "search text",
    "scopeUri": "am7://default/data.data/doc-123",
    "vectorModels": ["common.vectorModelStore"],
    "tags": ["am7://default/common.tag/tag-001"],
    "limit": 10,
    "threshold": 0.6,
    "distinct": false
  }
}
```

2. Return MCP-formatted results:
```json
{
  "content": [
    {
      "type": "resource",
      "resource": {
        "uri": "am7://default/common.vectorModelStore/vec-001",
        "mimeType": "text/plain",
        "text": "chunk content..."
      },
      "metadata": {
        "score": 0.89,
        "semanticRank": 2,
        "keywordRank": 5,
        "chunk": 3,
        "chunkCount": 12,
        "sourceUri": "am7://default/data.data/doc-123",
        "sourceType": "data.data"
      }
    }
  ]
}
```

#### B. Citation Injection → MCP Context Blocks

Replace the ad-hoc citation string building with `McpContextBuilder`:

```java
// Current: ChatService.java lines 248-263
String citDesc = "--- CITATION INSTRUCTIONS ---\n" +
    "Use the following citations to generate a response to: \"" + msg + "\"\n" +
    "--- BEGIN CITATIONS ---\n" + citRef + "\n--- END CITATIONS ---\n";

// New: MCP context block
McpContextBuilder ctx = new McpContextBuilder();
ctx.addResource(
    "am7://vector/citations/" + chatId,
    "urn:am7:vector:search-result",
    Map.of(
        "query", userMessage,
        "instruction", PromptUtil.getUserCitationTemplate(promptConfig, chatConfig),
        "results", vectorResults.stream().map(v -> Map.of(
            "uri", v.get("vectorReferenceUri"),
            "content", v.get(FieldNames.FIELD_CONTENT),
            "score", v.get(FieldNames.FIELD_SCORE),
            "chunk", v.get(FieldNames.FIELD_CHUNK),
            "sourceType", v.get(FieldNames.FIELD_VECTOR_REFERENCE_TYPE)
        )).collect(Collectors.toList())
    ),
    true  // ephemeral
);
```

#### C. Refactor getDataCitations() Logic

The complex branching in `ChatUtil.getDataCitations()` (lines 858-942) should be decomposed:

```java
// Current: Monolithic method with branching by reference type
// - Tags-only → tag-scoped vector search
// - ChatRequest refs → format entire chat history as citation
// - CharPerson refs → search chat history vectors + vector store
// - General refs → search vector store + check for summary notes

// New: McpCitationResolver with strategy pattern
public class McpCitationResolver {

    public List<McpContextBlock> resolve(BaseRecord user, String query,
                                          List<String> dataRefs) {
        List<McpContextBlock> blocks = new ArrayList<>();
        List<BaseRecord> tags = new ArrayList<>();
        List<BaseRecord> refs = new ArrayList<>();

        // Classify references by type
        classifyReferences(user, dataRefs, tags, refs);

        if (tags.size() > 0 && refs.size() == 0) {
            blocks.addAll(resolveTagSearch(user, query, tags));
        }
        for (BaseRecord ref : refs) {
            blocks.addAll(resolveByType(user, query, ref, tags));
        }
        return blocks;
    }

    private List<McpContextBlock> resolveByType(BaseRecord user, String query,
                                                  BaseRecord ref, List<BaseRecord> tags) {
        String schema = ref.getSchema();
        if (OlioModelNames.MODEL_CHAT_REQUEST.equals(schema)) {
            return resolveChatHistoryRef(user, ref);
        } else if (OlioModelNames.MODEL_CHAR_PERSON.equals(schema)) {
            return resolveCharacterRef(user, query, ref, tags);
        } else {
            return resolveGeneralRef(user, query, ref, tags);
        }
    }
}
```

### Summary: RAG Components Requiring Changes

| Component | File | Lines | Change Required |
|-----------|------|-------|-----------------|
| `DocumentUtil.getStringContent()` | DocumentUtil.java | 158-205 | Return structured `ContentExtractionResult` with metadata |
| `VectorUtil.chunkBySentence()` | VectorUtil.java | 466-498 | Make public, return structured chunks |
| `VectorUtil.chunkByWord()` | VectorUtil.java | 380-406 | Make public, return structured chunks |
| `VectorUtil.chunkByChapter()` | VectorUtil.java | 408-446 | Stop serializing VectorChunk to JSON-as-string; separate content/metadata |
| `VectorUtil.createVectorStore()` | VectorUtil.java | 312-377 | Store `vectorReferenceObjectId` for URI construction |
| `VectorUtil.newVectorStore()` | VectorUtil.java | 253-271 | Include objectId and URI in returned records |
| `VectorUtil.findByEmbedding()` | VectorUtil.java | 173-246 | SELECT objectId; return MCP-compatible resource list |
| Hybrid SQL query | VectorUtil.java | 67-100 | Add objectId to SELECT or JOIN to source table |
| `EmbeddingUtil.getEmbedding()` | EmbeddingUtil.java | 149-211 | No structural change; wrap as MCP tool |
| `ChatUtil.getDataCitations()` | ChatUtil.java | 858-942 | Decompose into `McpCitationResolver`; use URI-based refs |
| `ChatUtil.getCitationText()` | ChatUtil.java | 576-600 | Output MCP context blocks instead of `<citation>` XML |
| `ChatUtil.getFilteredCitationText()` | ChatUtil.java | 560-575 | Filter MCP context blocks for dedup |
| `ChatService.chat()` | ChatService.java | 209-271 | Use `McpContextBuilder` for citation injection |
| `Chat.createNarrativeVector()` | Chat.java | 386-424 | Pass URI refs alongside BaseRecord refs |
| `VectorChatHistoryListFactory` | VectorChatHistoryListFactory.java | 33-64 | Store URI refs on vector records |
| `AccessPoint.vectorize()` | AccessPoint.java | 676-726 | Return MCP-compatible result |
| `VectorService.vectorize()` | VectorService.java | 49-63 | Map to MCP tool call format |

### Implementation Approach

Refactoring should be additive, not breaking:

1. **Add** `vectorReferenceObjectId` field to `VectorModelStore` model schema
2. **Add** URI construction to `newVectorStore()` alongside existing ID references
3. **Add** `McpCitationResolver` as a new class that wraps existing `getDataCitations()` logic
4. **Add** MCP tool wrappers around existing `VectorUtil` methods
5. **Deprecate** but keep the `--- BEGIN CITATIONS ---` format during transition
6. **Switch** `ChatService` to use `McpContextBuilder` output once client parser is ready

---

## Part 15: Aggressive Unit Tests — Vector References & RAG Pipeline

Part 12 tests cover the MCP protocol layer (services, adapters, context builder/parser/filter). This section adds exhaustive tests for the refactored internals from Parts 13 and 14 — the vector reference URI system, content extraction, chunking, hybrid search wrappers, and the citation resolver.

### Test Structure (Additions)

```
AccountManagerObjects7/src/test/java/org/cote/accountmanager/
├── mcp/adapter/
│   ├── Am7UriEdgeCaseTest.java          # URI edge cases and security
│   └── Am7UriRoundTripTest.java         # URI ↔ BaseRecord round-trip
├── util/
│   ├── VectorUtilChunkingTest.java      # All chunking strategies
│   ├── VectorUtilStoreTest.java         # Vector store CRUD + URI refs
│   ├── VectorUtilHybridSearchTest.java  # Hybrid search with MCP params
│   ├── DocumentUtilExtractionTest.java  # Content extraction all formats
│   └── EmbeddingUtilTest.java           # Embedding generation + errors
├── olio/llm/
│   ├── McpCitationResolverTest.java     # Citation resolver strategies
│   ├── ChatVectorReferenceTest.java     # Chat → vector reference flow
│   └── VectorChatHistoryMcpTest.java    # Factory with URI refs

AccountManagerService7/src/test/java/org/cote/rest/services/
├── mcp/
│   ├── McpVectorSearchToolTest.java     # Vector search via MCP tool
│   ├── McpVectorizeToolTest.java        # Vectorize via MCP tool
│   ├── McpExtractContentToolTest.java   # Content extraction via MCP tool
│   ├── McpChunkContentToolTest.java     # Chunking via MCP tool
│   ├── McpChatToolTest.java             # Chat with URI refs via MCP tool
│   └── integration/
│       ├── McpRagPipelineTest.java      # Full RAG: extract → chunk → embed → search → cite
│       └── McpVectorReferenceFlowTest.java  # URI ref lifecycle
```

---

### Am7UriEdgeCaseTest.java

```java
public class Am7UriEdgeCaseTest {

    // --- VALID PARSING ---

    @Test
    public void testParseMinimalUri() {
        Am7Uri uri = Am7Uri.parse("am7://org/type/id");
        assertEquals("org", uri.getOrganization());
        assertEquals("type", uri.getType());
        assertEquals("id", uri.getId());
    }

    @Test
    public void testParseDottedModelType() {
        Am7Uri uri = Am7Uri.parse("am7://default/olio.llm.chatConfig/abc-123");
        assertEquals("olio.llm.chatConfig", uri.getType());
    }

    @Test
    public void testParseUuidObjectId() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        Am7Uri uri = Am7Uri.parse("am7://default/data.data/" + uuid);
        assertEquals(uuid, uri.getId());
    }

    @Test
    public void testParseWithQueryParams() {
        Am7Uri uri = Am7Uri.parse("am7://default/vector/search?q=hello+world&limit=10&threshold=0.7");
        assertEquals("hello+world", uri.getQueryParam("q"));
        assertEquals("10", uri.getQueryParam("limit"));
        assertEquals("0.7", uri.getQueryParam("threshold"));
    }

    @Test
    public void testParseMediaUri() {
        Am7Uri uri = Am7Uri.parse("am7://default/media/data.data/img-456");
        assertTrue(uri.isMedia());
        assertEquals("data.data", uri.getMediaType());
        assertEquals("img-456", uri.getId());
    }

    @Test
    public void testParseMediaUriWithSizeParam() {
        Am7Uri uri = Am7Uri.parse("am7://default/media/data.data/img-456?size=256");
        assertTrue(uri.isMedia());
        assertEquals("256", uri.getQueryParam("size"));
    }

    // --- INVALID INPUT ---

    @Test(expected = IllegalArgumentException.class)
    public void testParseNullThrows() {
        Am7Uri.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseEmptyThrows() {
        Am7Uri.parse("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseWrongSchemeThrows() {
        Am7Uri.parse("http://default/system.user/abc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseMissingOrgThrows() {
        Am7Uri.parse("am7:///system.user/abc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseMissingTypeThrows() {
        Am7Uri.parse("am7://default//abc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseMissingIdThrows() {
        Am7Uri.parse("am7://default/system.user/");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseTraversalAttackThrows() {
        Am7Uri.parse("am7://default/../../../etc/passwd");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInjectionAttackThrows() {
        Am7Uri.parse("am7://default/system.user/'; DROP TABLE users;--");
    }

    // --- BUILDER ---

    @Test
    public void testBuilderAllFields() {
        String uri = Am7Uri.builder()
            .organization("myorg")
            .type("data.data")
            .id("doc-789")
            .build();
        assertEquals("am7://myorg/data.data/doc-789", uri);
    }

    @Test
    public void testBuilderVectorSearch() {
        String uri = Am7Uri.builder()
            .organization("default")
            .vectorSearch()
            .queryParam("q", "search term")
            .queryParam("limit", "10")
            .build();
        assertTrue(uri.startsWith("am7://default/vector/search?"));
        assertTrue(uri.contains("q=search+term"));
    }

    @Test(expected = IllegalStateException.class)
    public void testBuilderMissingOrgThrows() {
        Am7Uri.builder().type("data.data").id("abc").build();
    }

    @Test(expected = IllegalStateException.class)
    public void testBuilderMissingTypeThrows() {
        Am7Uri.builder().organization("default").id("abc").build();
    }

    // --- EQUALITY & HASHING ---

    @Test
    public void testParsedUriEquality() {
        Am7Uri a = Am7Uri.parse("am7://default/system.user/abc-123");
        Am7Uri b = Am7Uri.parse("am7://default/system.user/abc-123");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testDifferentUrisNotEqual() {
        Am7Uri a = Am7Uri.parse("am7://default/system.user/abc-123");
        Am7Uri b = Am7Uri.parse("am7://default/system.user/xyz-456");
        assertNotEquals(a, b);
    }

    @Test
    public void testToStringRoundTrip() {
        String original = "am7://default/olio.llm.chatConfig/abc-123";
        Am7Uri uri = Am7Uri.parse(original);
        assertEquals(original, uri.toString());
    }
}
```

### Am7UriRoundTripTest.java

```java
public class Am7UriRoundTripTest {

    @Test
    public void testBaseRecordToUriAndBack() {
        BaseRecord user = RecordFactory.newInstance(ModelNames.MODEL_USER);
        user.set(FieldNames.FIELD_OBJECT_ID, "user-abc-123");
        user.set(FieldNames.FIELD_ORGANIZATION_PATH, "default");

        String uri = Am7Uri.toUri(user);
        assertEquals("am7://default/system.user/user-abc-123", uri);

        Am7Uri parsed = Am7Uri.parse(uri);
        assertEquals("system.user", parsed.getType());
        assertEquals("user-abc-123", parsed.getId());
        assertEquals("default", parsed.getOrganization());
    }

    @Test
    public void testChatConfigToUri() {
        BaseRecord chatConfig = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG);
        chatConfig.set(FieldNames.FIELD_OBJECT_ID, "chat-789");
        chatConfig.set(FieldNames.FIELD_ORGANIZATION_PATH, "default");

        String uri = Am7Uri.toUri(chatConfig);
        assertTrue(uri.contains("olio.llm.chatConfig"));
        assertTrue(uri.contains("chat-789"));
    }

    @Test
    public void testVectorStoreRecordToUri() {
        BaseRecord vectorStore = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
        vectorStore.set(FieldNames.FIELD_OBJECT_ID, "vec-001");
        vectorStore.set(FieldNames.FIELD_ORGANIZATION_PATH, "default");

        String uri = Am7Uri.toUri(vectorStore);
        assertTrue(uri.contains("common.vectorModelStore"));
    }

    @Test
    public void testNullObjectIdReturnsNull() {
        BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_USER);
        // No objectId set
        assertNull(Am7Uri.toUri(rec));
    }

    @Test
    public void testAllModelTypesProduceValidUris() {
        // Iterate all registered model types and verify URI generation
        for (String modelName : ModelNames.MODELS) {
            BaseRecord rec = RecordFactory.newInstance(modelName);
            rec.set(FieldNames.FIELD_OBJECT_ID, "test-" + modelName.hashCode());
            rec.set(FieldNames.FIELD_ORGANIZATION_PATH, "default");

            String uri = Am7Uri.toUri(rec);
            assertNotNull("URI null for model: " + modelName, uri);
            assertTrue("Invalid URI for model: " + modelName, uri.startsWith("am7://"));

            // Verify round-trip
            Am7Uri parsed = Am7Uri.parse(uri);
            assertEquals(modelName, parsed.getType());
        }
    }
}
```

---

### VectorUtilChunkingTest.java

```java
public class VectorUtilChunkingTest {

    private VectorUtil vectorUtil;

    @Before
    public void setup() {
        vectorUtil = new VectorUtil(LLMServiceEnumType.LOCAL, "http://localhost:8081", "test-token");
    }

    // --- SENTENCE CHUNKING ---

    @Test
    public void testChunkBySentenceSingleSentence() {
        String text = "This is a single sentence.";
        List<String> chunks = vectorUtil.chunkBySentence(text, 1);
        assertEquals(1, chunks.size());
        assertEquals("This is a single sentence.", chunks.get(0).trim());
    }

    @Test
    public void testChunkBySentenceGrouping() {
        String text = "First sentence. Second sentence. Third sentence. Fourth sentence.";
        List<String> chunks = vectorUtil.chunkBySentence(text, 2);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contains("First"));
        assertTrue(chunks.get(0).contains("Second"));
        assertTrue(chunks.get(1).contains("Third"));
        assertTrue(chunks.get(1).contains("Fourth"));
    }

    @Test
    public void testChunkBySentenceUnevenGroups() {
        String text = "One. Two. Three. Four. Five.";
        List<String> chunks = vectorUtil.chunkBySentence(text, 2);
        assertEquals(3, chunks.size());  // 2, 2, 1
    }

    @Test
    public void testChunkBySentenceEmptyInput() {
        List<String> chunks = vectorUtil.chunkBySentence("", 5);
        assertTrue(chunks.isEmpty());
    }

    @Test
    public void testChunkBySentenceNullInput() {
        List<String> chunks = vectorUtil.chunkBySentence(null, 5);
        assertTrue(chunks.isEmpty());
    }

    @Test
    public void testChunkBySentenceSmartQuoteNormalization() {
        String text = "\u201CThis is quoted.\u201D Another sentence.";  // Curly quotes
        List<String> chunks = vectorUtil.chunkBySentence(text, 1);
        // Should normalize curly quotes to straight quotes
        assertFalse(chunks.get(0).contains("\u201C"));
    }

    @Test
    public void testChunkBySentenceAbbreviations() {
        // BreakIterator should handle abbreviations like "Dr." "Mr." etc.
        String text = "Dr. Smith went to Washington. He met the president.";
        List<String> chunks = vectorUtil.chunkBySentence(text, 1);
        // This tests BreakIterator behavior — may be 1 or 2 chunks depending on locale
        assertTrue(chunks.size() >= 1 && chunks.size() <= 2);
    }

    @Test
    public void testChunkBySentenceLargeParagraph() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("Sentence number ").append(i).append(". ");
        }
        List<String> chunks = vectorUtil.chunkBySentence(sb.toString(), 10);
        assertEquals(10, chunks.size());
        // Each chunk should have roughly 10 sentences
    }

    // --- WORD CHUNKING ---

    @Test
    public void testChunkByWordBasic() {
        String text = "one two three four five six";
        List<String> chunks = vectorUtil.chunkByWord(text, 3);
        assertEquals(2, chunks.size());
        assertEquals("one two three", chunks.get(0).trim());
        assertEquals("four five six", chunks.get(1).trim());
    }

    @Test
    public void testChunkByWordUnevenWords() {
        String text = "a b c d e f g";
        List<String> chunks = vectorUtil.chunkByWord(text, 3);
        assertEquals(3, chunks.size());  // 3, 3, 1
    }

    @Test
    public void testChunkByWordSingleWord() {
        String text = "hello";
        List<String> chunks = vectorUtil.chunkByWord(text, 10);
        assertEquals(1, chunks.size());
        assertEquals("hello", chunks.get(0).trim());
    }

    @Test
    public void testChunkByWordEmptyInput() {
        List<String> chunks = vectorUtil.chunkByWord("", 5);
        assertTrue(chunks.isEmpty());
    }

    @Test
    public void testChunkByWordPreservesSpacing() {
        String text = "hello world foo bar";
        List<String> chunks = vectorUtil.chunkByWord(text, 2);
        // Each chunk should have single spaces between words
        for (String chunk : chunks) {
            assertFalse(chunk.contains("  "));  // No double spaces
        }
    }

    @Test
    public void testChunkByWordLargeDocument() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("word").append(i).append(" ");
        }
        List<String> chunks = vectorUtil.chunkByWord(sb.toString(), 500);
        assertEquals(20, chunks.size());
    }

    // --- CHAPTER CHUNKING ---

    @Test
    public void testChunkByChapterSingleChapter() {
        String text = "Chapter 1 The Beginning\nSome content here. More content follows.";
        List<String> chunks = vectorUtil.chunkByChapter("TestDoc", "/test", text, 5);
        assertFalse(chunks.isEmpty());
        // First chunk should contain chapter metadata
    }

    @Test
    public void testChunkByChapterMultipleChapters() {
        String text = "Chapter 1 Introduction\nFirst chapter content.\n\n" +
                       "Chapter 2 Background\nSecond chapter content.\n\n" +
                       "Chapter 3 Conclusion\nThird chapter content.";
        List<String> chunks = vectorUtil.chunkByChapter("TestDoc", "/test", text, 5);
        assertTrue(chunks.size() >= 3);
    }

    @Test
    public void testChunkByChapterNoChapters() {
        String text = "This is plain text with no chapter markers at all.";
        List<String> chunks = vectorUtil.chunkByChapter("TestDoc", "/test", text, 5);
        // Should handle gracefully — either single chunk or empty
        assertNotNull(chunks);
    }

    @Test
    public void testChunkByChapterMetadataPreserved() {
        String text = "Chapter 1 The Setup\nFirst sentence. Second sentence.";
        List<String> chunks = vectorUtil.chunkByChapter("TestDoc", "/test", text, 5);
        // Chunks currently contain JSON with chapter metadata
        String firstChunk = chunks.get(0);
        assertTrue(firstChunk.contains("chapter") || firstChunk.contains("Chapter"));
    }

    @Test
    public void testChunkByChapterEmptyInput() {
        List<String> chunks = vectorUtil.chunkByChapter("TestDoc", "/test", "", 5);
        assertTrue(chunks.isEmpty());
    }

    // --- CHUNK SIZE EDGE CASES ---

    @Test
    public void testChunkSizeZero() {
        String text = "Some content here.";
        // chunkSize of 0 should not cause infinite loop or crash
        List<String> chunks = vectorUtil.chunkBySentence(text, 0);
        assertNotNull(chunks);
    }

    @Test
    public void testChunkSizeNegative() {
        String text = "Some content here.";
        List<String> chunks = vectorUtil.chunkBySentence(text, -1);
        assertNotNull(chunks);
    }

    @Test
    public void testChunkSizeLargerThanContent() {
        String text = "Short.";
        List<String> chunks = vectorUtil.chunkBySentence(text, 1000);
        assertEquals(1, chunks.size());
    }
}
```

---

### VectorUtilStoreTest.java

```java
public class VectorUtilStoreTest {

    // --- VECTOR REFERENCE OBJECT ID ---

    @Test
    public void testCreateVectorStoreIncludesObjectId() {
        BaseRecord model = createTestDocument("doc-123", "Test content for vectorization.");
        List<BaseRecord> chunks = vectorUtil.createVectorStore(model, ChunkEnumType.SENTENCE, 1);

        assertFalse(chunks.isEmpty());
        for (BaseRecord chunk : chunks) {
            // New: vectorReferenceObjectId must be populated
            String refObjId = chunk.get("vectorReferenceObjectId");
            assertNotNull("vectorReferenceObjectId should not be null", refObjId);
            assertEquals("doc-123", refObjId);
        }
    }

    @Test
    public void testCreateVectorStorePreservesReferenceType() {
        BaseRecord model = createTestDocument("doc-456", "Content.");
        List<BaseRecord> chunks = vectorUtil.createVectorStore(model, ChunkEnumType.WORD, 10);

        for (BaseRecord chunk : chunks) {
            String refType = chunk.get(FieldNames.FIELD_VECTOR_REFERENCE_TYPE);
            assertEquals(model.getSchema(), refType);
        }
    }

    @Test
    public void testCreateVectorStoreChunkNumbering() {
        BaseRecord model = createTestDocument("doc-789", "One. Two. Three. Four. Five.");
        List<BaseRecord> chunks = vectorUtil.createVectorStore(model, ChunkEnumType.SENTENCE, 1);

        for (int i = 0; i < chunks.size(); i++) {
            int chunkNum = chunks.get(i).get(FieldNames.FIELD_CHUNK);
            assertEquals(i, chunkNum);
        }
        // All should agree on total count
        int expectedCount = chunks.size();
        for (BaseRecord chunk : chunks) {
            int chunkCount = chunk.get("chunkCount");
            assertEquals(expectedCount, chunkCount);
        }
    }

    @Test
    public void testCreateVectorStoreReplacesExisting() {
        BaseRecord model = createTestDocument("doc-replace", "First version.");
        vectorUtil.createVectorStore(model, ChunkEnumType.SENTENCE, 1);
        int firstCount = vectorUtil.countVectorStore(model);
        assertTrue(firstCount > 0);

        // Re-vectorize with different content
        model.set(FieldNames.FIELD_CONTENT, "Second version with more sentences. Another sentence here.");
        vectorUtil.createVectorStore(model, ChunkEnumType.SENTENCE, 1);
        int secondCount = vectorUtil.countVectorStore(model);

        // Old vectors should have been deleted, new ones created
        assertTrue(secondCount > 0);
    }

    @Test
    public void testDeleteVectorStoreReturnsCount() {
        BaseRecord model = createTestDocument("doc-del", "Content to delete.");
        vectorUtil.createVectorStore(model, ChunkEnumType.SENTENCE, 1);

        int deleted = vectorUtil.deleteVectorStore(model);
        assertTrue(deleted > 0);

        // Verify store is empty
        assertEquals(0, vectorUtil.countVectorStore(model));
    }

    @Test
    public void testDeleteVectorStoreByModelType() {
        BaseRecord model = createTestDocument("doc-typed", "Content.");
        vectorUtil.createVectorStore(model, ChunkEnumType.SENTENCE, 1);

        int deleted = vectorUtil.deleteVectorStore(model, ModelNames.MODEL_VECTOR_MODEL_STORE);
        assertTrue(deleted > 0);
    }

    @Test
    public void testGetVectorStoreReturnsAllChunks() {
        BaseRecord model = createTestDocument("doc-get", "First. Second. Third.");
        vectorUtil.createVectorStore(model, ChunkEnumType.SENTENCE, 1);

        List<BaseRecord> store = vectorUtil.getVectorStore(model);
        assertEquals(3, store.size());
    }

    @Test
    public void testNewVectorStorePartialReference() {
        // Simulate what findByEmbedding returns
        BaseRecord vs = vectorUtil.newVectorStore(
            1L, "key1", "vault1", false, 100L,
            42L, "data.data", 0.85, 3, "chunk content"
        );

        BaseRecord ref = vs.get(FieldNames.FIELD_VECTOR_REFERENCE);
        assertNotNull(ref);
        assertEquals(42L, (long) ref.get(FieldNames.FIELD_ID));
        assertEquals("data.data", vs.get(FieldNames.FIELD_VECTOR_REFERENCE_TYPE));
        assertEquals(0.85, (double) vs.get(FieldNames.FIELD_SCORE), 0.001);
        assertEquals(3, (int) vs.get(FieldNames.FIELD_CHUNK));
        assertEquals("chunk content", vs.get(FieldNames.FIELD_CONTENT));
    }

    @Test
    public void testNewVectorStoreNullReference() {
        BaseRecord vs = vectorUtil.newVectorStore(
            1L, "key1", "vault1", false, 100L,
            0L, null, 0.5, 0, "content"
        );

        BaseRecord ref = vs.get(FieldNames.FIELD_VECTOR_REFERENCE);
        assertNull(ref);  // ref=0 and refType=null → no reference created
    }

    @Test
    public void testSortAndLimitOrdering() {
        List<BaseRecord> results = new ArrayList<>();
        results.add(createVectorResult(0.5));
        results.add(createVectorResult(0.9));
        results.add(createVectorResult(0.3));
        results.add(createVectorResult(0.7));

        List<BaseRecord> sorted = vectorUtil.sortAndLimit(results, 2);

        assertEquals(2, sorted.size());
        assertEquals(0.9, (double) sorted.get(0).get(FieldNames.FIELD_SCORE), 0.001);
        assertEquals(0.7, (double) sorted.get(1).get(FieldNames.FIELD_SCORE), 0.001);
    }

    @Test
    public void testSortAndLimitLargerThanList() {
        List<BaseRecord> results = List.of(createVectorResult(0.8));
        List<BaseRecord> sorted = vectorUtil.sortAndLimit(results, 100);
        assertEquals(1, sorted.size());
    }

    @Test
    public void testSortAndLimitEmptyList() {
        List<BaseRecord> sorted = vectorUtil.sortAndLimit(new ArrayList<>(), 10);
        assertTrue(sorted.isEmpty());
    }
}
```

---

### VectorUtilHybridSearchTest.java

```java
public class VectorUtilHybridSearchTest {

    // --- SEARCH BY MODEL REFERENCE ---

    @Test
    public void testFindWithModelScope() {
        BaseRecord doc = createAndVectorize("doc-scope", "Relevant content about testing.");
        BaseRecord unrelated = createAndVectorize("doc-other", "Completely different topic.");

        List<BaseRecord> results = vectorUtil.find(doc, "testing");

        // Should only return chunks from doc, not unrelated
        for (BaseRecord r : results) {
            BaseRecord ref = r.get(FieldNames.FIELD_VECTOR_REFERENCE);
            assertEquals(doc.get(FieldNames.FIELD_ID), ref.get(FieldNames.FIELD_ID));
        }
    }

    @Test
    public void testFindWithNullModelSearchesAll() {
        createAndVectorize("doc-a", "Content about cats.");
        createAndVectorize("doc-b", "Content about cats and dogs.");

        List<BaseRecord> results = vectorUtil.find(null, "data.data",
            new BaseRecord[0], new String[]{ModelNames.MODEL_VECTOR_MODEL_STORE},
            "cats", 10, 0.6, false);

        // Should return results from both documents
        assertTrue(results.size() >= 2);
    }

    @Test
    public void testFindWithTagFilter() {
        BaseRecord doc = createAndVectorize("doc-tagged", "Content to search.");
        BaseRecord tag = createTag("test-tag");
        applyTag(doc, tag);

        List<BaseRecord> results = vectorUtil.find(null, "data.data",
            new BaseRecord[]{tag},
            new String[]{ModelNames.MODEL_VECTOR_MODEL_STORE},
            "content", 10, 0.6, false);

        assertFalse(results.isEmpty());
    }

    @Test
    public void testFindWithMultipleTags() {
        BaseRecord doc = createAndVectorize("doc-multi", "Multi-tagged content.");
        BaseRecord tag1 = createTag("tag-1");
        BaseRecord tag2 = createTag("tag-2");
        applyTag(doc, tag1);
        applyTag(doc, tag2);

        List<BaseRecord> results = vectorUtil.find(null, "data.data",
            new BaseRecord[]{tag1, tag2},
            new String[]{ModelNames.MODEL_VECTOR_MODEL_STORE},
            "content", 10, 0.6, false);

        assertFalse(results.isEmpty());
    }

    // --- SCORING ---

    @Test
    public void testHybridScoreIsPositive() {
        createAndVectorize("doc-score", "Testing the hybrid search scoring mechanism.");

        List<BaseRecord> results = vectorUtil.find(null, "data.data",
            new BaseRecord[0], new String[]{ModelNames.MODEL_VECTOR_MODEL_STORE},
            "hybrid search scoring", 5, 0.6, false);

        for (BaseRecord r : results) {
            double score = r.get(FieldNames.FIELD_SCORE);
            assertTrue("Score should be positive: " + score, score > 0.0);
        }
    }

    @Test
    public void testHybridScoreOrderDescending() {
        createAndVectorize("doc-order", "First sentence about algorithms. " +
            "Second sentence about data structures. Third about algorithms and complexity.");

        List<BaseRecord> results = vectorUtil.find(null, "algorithms", 5, 0.0);

        for (int i = 1; i < results.size(); i++) {
            double prev = results.get(i - 1).get(FieldNames.FIELD_SCORE);
            double curr = results.get(i).get(FieldNames.FIELD_SCORE);
            assertTrue("Results not in descending score order", prev >= curr);
        }
    }

    // --- LIMIT & THRESHOLD ---

    @Test
    public void testFindRespectsLimit() {
        createAndVectorize("doc-limit", buildLargeText(50));  // 50 sentences

        List<BaseRecord> results = vectorUtil.find(null, "data.data",
            new BaseRecord[0], new String[]{ModelNames.MODEL_VECTOR_MODEL_STORE},
            "sentence", 3, 0.0, false);

        assertTrue(results.size() <= 3);
    }

    @Test
    public void testFindLimitZeroReturnsEmpty() {
        createAndVectorize("doc-lim0", "Content.");
        List<BaseRecord> results = vectorUtil.find(null, "data.data",
            new BaseRecord[0], new String[]{ModelNames.MODEL_VECTOR_MODEL_STORE},
            "content", 0, 0.0, false);
        assertTrue(results.isEmpty());
    }

    // --- DISTINCT ---

    @Test
    public void testFindDistinctDeduplicatesByReference() {
        BaseRecord doc = createAndVectorize("doc-distinct",
            "Repeated theme about cats. Another chunk about cats. Third cats chunk.");

        List<BaseRecord> distinct = vectorUtil.find(null, "data.data",
            new BaseRecord[0], new String[]{ModelNames.MODEL_VECTOR_MODEL_STORE},
            "cats", 10, 0.0, true);  // distinct=true

        // Should return at most 1 result per vectorReference
        Set<Long> refs = new HashSet<>();
        for (BaseRecord r : distinct) {
            BaseRecord ref = r.get(FieldNames.FIELD_VECTOR_REFERENCE);
            if (ref != null) {
                assertTrue("Duplicate reference in distinct mode",
                    refs.add((long) ref.get(FieldNames.FIELD_ID)));
            }
        }
    }

    // --- EMPTY / EDGE CASES ---

    @Test
    public void testFindEmptyQueryReturnsResults() {
        createAndVectorize("doc-empty-q", "Some content.");
        // Empty query — keyword search won't match, but semantic may
        List<BaseRecord> results = vectorUtil.find(null, "", 5, 0.0);
        assertNotNull(results);
    }

    @Test
    public void testFindNoMatchesReturnsEmptyList() {
        // No documents vectorized at all, or query totally irrelevant
        List<BaseRecord> results = vectorUtil.find(null, "xyzzy-nonexistent-term-999", 10, 0.99);
        assertNotNull(results);
        // May or may not be empty depending on threshold
    }

    @Test
    public void testFindByTagOnlyNoModelRef() {
        BaseRecord tag = createTag("orphan-tag");
        List<BaseRecord> results = vectorUtil.findByTag(ModelNames.MODEL_VECTOR_MODEL_STORE,
            new BaseRecord[]{tag});
        assertNotNull(results);
    }

    // --- VECTOR REFERENCE URI IN RESULTS ---

    @Test
    public void testFindResultsContainObjectIdForUri() {
        BaseRecord doc = createAndVectorize("doc-uri-test", "Content for URI test.");

        List<BaseRecord> results = vectorUtil.find(doc, "content");

        for (BaseRecord r : results) {
            // After refactoring, results should have vectorReferenceObjectId
            String refObjId = r.get("vectorReferenceObjectId");
            assertNotNull("Results must include vectorReferenceObjectId for MCP URI construction",
                refObjId);
            assertEquals("doc-uri-test", refObjId);
        }
    }
}
```

---

### DocumentUtilExtractionTest.java

```java
public class DocumentUtilExtractionTest {

    @Test
    public void testExtractFromTextData() {
        BaseRecord data = createDataRecord("text/plain", "Hello world");
        String content = DocumentUtil.getStringContent(data);
        assertEquals("Hello world", content);
    }

    @Test
    public void testExtractFromJsonData() {
        BaseRecord data = createDataRecord("application/json", "{\"key\": \"value\"}");
        String content = DocumentUtil.getStringContent(data);
        assertNotNull(content);
        assertTrue(content.contains("key"));
    }

    @Test
    public void testExtractFromNoteWithTextField() {
        BaseRecord note = RecordFactory.newInstance(ModelNames.MODEL_NOTE);
        note.set("text", "Note content here.");
        String content = DocumentUtil.getStringContent(note);
        assertEquals("Note content here.", content);
    }

    @Test
    public void testExtractFromPdfBytes() {
        byte[] pdfBytes = loadTestResource("test-document.pdf");
        String content = DocumentUtil.readPDF(pdfBytes);
        assertNotNull(content);
        assertFalse(content.isEmpty());
    }

    @Test
    public void testExtractFromWordDocument() {
        byte[] docBytes = loadTestResource("test-document.docx");
        String content = DocumentUtil.readDocument(docBytes);
        assertNotNull(content);
        assertFalse(content.isEmpty());
    }

    @Test
    public void testSmartQuoteNormalization() {
        String input = "\u201CHello\u201D and \u2018World\u2019";
        String result = DocumentUtil.replaceSmartQuotes(input);
        assertFalse(result.contains("\u201C"));
        assertFalse(result.contains("\u201D"));
        assertFalse(result.contains("\u2018"));
        assertFalse(result.contains("\u2019"));
    }

    @Test
    public void testExtractFromNullRecordReturnsNull() {
        String content = DocumentUtil.getStringContent(null);
        assertNull(content);
    }

    @Test
    public void testExtractFromEmptyDataReturnsEmpty() {
        BaseRecord data = createDataRecord("text/plain", "");
        String content = DocumentUtil.getStringContent(data);
        assertTrue(content == null || content.isEmpty());
    }

    @Test
    public void testExtractFromXmlData() {
        BaseRecord data = createDataRecord("text/xml", "<root><item>value</item></root>");
        String content = DocumentUtil.getStringContent(data);
        assertNotNull(content);
    }

    @Test
    public void testExtractFromJavascriptData() {
        BaseRecord data = createDataRecord("application/x-javascript", "function test() { return 1; }");
        String content = DocumentUtil.getStringContent(data);
        assertNotNull(content);
        assertTrue(content.contains("function"));
    }

    // --- STRUCTURED EXTRACTION RESULT (Post-refactor) ---

    @Test
    public void testStructuredExtractionIncludesMetadata() {
        BaseRecord data = createDataRecord("text/plain", "Test content");
        ContentExtractionResult result = DocumentUtil.getStructuredContent(data);

        assertNotNull(result.getContent());
        assertEquals("text/plain", result.getMimeType());
        assertNotNull(result.getSourceUri());
        assertTrue(result.getCharacterCount() > 0);
    }

    @Test
    public void testStructuredExtractionPdfPageCount() {
        BaseRecord pdfData = createPdfDataRecord("test-multipage.pdf");
        ContentExtractionResult result = DocumentUtil.getStructuredContent(pdfData);

        assertTrue(result.getPageCount() > 0);
        assertEquals("application/pdf", result.getMimeType());
    }
}
```

---

### McpCitationResolverTest.java

```java
public class McpCitationResolverTest {

    private McpCitationResolver resolver;

    @Before
    public void setup() {
        resolver = new McpCitationResolver();
    }

    // --- TAG-ONLY RESOLUTION ---

    @Test
    public void testResolveTagOnlySearch() {
        BaseRecord tag = createTag("test-tag");
        List<String> dataRefs = List.of(serializeRef(tag));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "search query", dataRefs);

        assertFalse(blocks.isEmpty());
        // Should have searched both chat history and vector store
        assertTrue(blocks.stream().anyMatch(b -> b.getUri().contains("vector")));
    }

    @Test
    public void testResolveMultipleTagsSearch() {
        BaseRecord tag1 = createTag("tag-1");
        BaseRecord tag2 = createTag("tag-2");
        List<String> dataRefs = List.of(serializeRef(tag1), serializeRef(tag2));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", dataRefs);
        assertNotNull(blocks);
    }

    // --- CHAT REQUEST RESOLUTION ---

    @Test
    public void testResolveChatRequestRefIncludesHistory() {
        BaseRecord chatReq = createChatRequestRef("chat-ref-123");
        List<String> dataRefs = List.of(serializeRef(chatReq));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", dataRefs);

        assertFalse(blocks.isEmpty());
        // Should contain chat history as context
        assertTrue(blocks.stream().anyMatch(b ->
            b.getSchema() != null && b.getSchema().contains("chat")));
    }

    // --- CHARACTER PERSON RESOLUTION ---

    @Test
    public void testResolveCharacterPersonSearchesChatHistory() {
        BaseRecord charPerson = createCharPerson("char-person-123");
        List<String> dataRefs = List.of(serializeRef(charPerson));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "character query", dataRefs);

        assertFalse(blocks.isEmpty());
    }

    @Test
    public void testResolveCharacterPersonWithTags() {
        BaseRecord charPerson = createCharPerson("char-456");
        BaseRecord tag = createTag("person-tag");
        List<String> dataRefs = List.of(serializeRef(charPerson), serializeRef(tag));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", dataRefs);
        assertNotNull(blocks);
    }

    // --- GENERAL REFERENCE RESOLUTION ---

    @Test
    public void testResolveGeneralRefSearchesVectorStore() {
        BaseRecord doc = createDocumentRef("doc-gen-123");
        List<String> dataRefs = List.of(serializeRef(doc));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", dataRefs);
        assertNotNull(blocks);
    }

    @Test
    public void testResolveGeneralRefChecksSummaryNote() {
        BaseRecord doc = createDocumentRef("doc-summary");
        // Assuming a summary note exists at ~/Notes/Summaries/doc-summary - Summary
        List<String> dataRefs = List.of(serializeRef(doc));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", dataRefs);

        // Should include summary content if summary exists
        assertNotNull(blocks);
    }

    // --- MIXED REFERENCES ---

    @Test
    public void testResolveMixedRefs() {
        BaseRecord doc = createDocumentRef("doc-mix-1");
        BaseRecord chat = createChatRequestRef("chat-mix-2");
        BaseRecord tag = createTag("mix-tag");
        List<String> dataRefs = List.of(
            serializeRef(doc), serializeRef(chat), serializeRef(tag));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "mixed query", dataRefs);

        // Should produce blocks for each reference type
        assertFalse(blocks.isEmpty());
    }

    // --- DEDUPLICATION ---

    @Test
    public void testResolveDeduplicatesCitations() {
        BaseRecord doc = createDocumentRef("doc-dup");
        // If the same citation text appears in existing messages, it should be filtered
        List<String> dataRefs = List.of(serializeRef(doc));

        OpenAIRequest req = createRequestWithMessage("existing citation content");
        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", dataRefs);

        // Blocks matching existing message content should be excluded
        for (McpContextBlock block : blocks) {
            assertFalse("Duplicate citation should be filtered",
                block.getContent().equals("existing citation content"));
        }
    }

    // --- EMPTY / NULL ---

    @Test
    public void testResolveEmptyRefsReturnsEmpty() {
        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", List.of());
        assertTrue(blocks.isEmpty());
    }

    @Test
    public void testResolveNullRefsReturnsEmpty() {
        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", null);
        assertTrue(blocks.isEmpty());
    }

    @Test
    public void testResolveEmptyQueryStillReturns() {
        BaseRecord doc = createDocumentRef("doc-empty-q");
        List<String> dataRefs = List.of(serializeRef(doc));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "", dataRefs);
        // Should return blocks even with empty query (vector chunks from reference)
        assertNotNull(blocks);
    }

    @Test
    public void testResolveInvalidRefGracefullySkipped() {
        List<String> dataRefs = List.of("{invalid json}", "", "null");

        List<McpContextBlock> blocks = resolver.resolve(testUser, "query", dataRefs);
        // Should not throw; invalid refs should be skipped
        assertNotNull(blocks);
    }

    // --- MCP CONTEXT BLOCK FORMAT ---

    @Test
    public void testResolvedBlocksHaveUris() {
        BaseRecord doc = createDocumentRef("doc-uri-block");
        vectorizeDocument(doc);
        List<String> dataRefs = List.of(serializeRef(doc));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "content", dataRefs);

        for (McpContextBlock block : blocks) {
            assertNotNull("Block URI should not be null", block.getUri());
            assertTrue("Block URI should use am7:// scheme",
                block.getUri().startsWith("am7://"));
        }
    }

    @Test
    public void testResolvedBlocksAreEphemeral() {
        BaseRecord doc = createDocumentRef("doc-eph");
        vectorizeDocument(doc);
        List<String> dataRefs = List.of(serializeRef(doc));

        List<McpContextBlock> blocks = resolver.resolve(testUser, "content", dataRefs);

        for (McpContextBlock block : blocks) {
            assertTrue("Citation blocks should be ephemeral", block.isEphemeral());
        }
    }
}
```

---

### ChatVectorReferenceTest.java

```java
public class ChatVectorReferenceTest {

    @Test
    public void testCreateNarrativeVectorPassesUriRefs() {
        Chat chat = createTestChat();
        OpenAIRequest req = chat.getChatPrompt();
        req.getMessages().add(new OpenAIMessage("user", "Test message"));

        chat.saveSession(req);

        // Verify vector records include URI references
        List<BaseRecord> vectors = IOSystem.getActiveContext().getVectorUtil()
            .getVectorStore(req);

        for (BaseRecord v : vectors) {
            // After refactoring, vectors should carry URI references
            String chatConfigUri = v.get("chatConfigUri");
            String systemCharUri = v.get("systemCharacterUri");

            // At minimum, traditional refs should still be present
            assertNotNull(v.get(FieldNames.FIELD_VECTOR_REFERENCE));
        }
    }

    @Test
    public void testGetNarrativeForVectorIncludesCharacterDetails() {
        Chat chat = createTestChatWithCharacters("Alice", "Bob");
        OpenAIRequest req = chat.getChatPrompt();
        req.getMessages().add(new OpenAIMessage("user", "Hello there"));

        // The narrative should include character name, age, race, gender
        String narrative = chat.getNarrativeForVector(req.getMessages().get(
            req.getMessages().size() - 1));

        assertTrue(narrative.contains("Alice") || narrative.contains("Bob"));
    }

    @Test
    public void testGetNarrativeForVectorNullCharacterGraceful() {
        Chat chat = createTestChatNoCharacters();
        OpenAIRequest req = chat.getChatPrompt();
        req.getMessages().add(new OpenAIMessage("user", "No characters"));

        // Should fall back to role name, not throw
        String narrative = chat.getNarrativeForVector(req.getMessages().get(
            req.getMessages().size() - 1));

        assertNotNull(narrative);
        assertTrue(narrative.contains("user"));
    }

    @Test
    public void testVectorChatHistoryFactoryAttachesAllRefs() {
        ParameterList plist = ParameterList.newParameterList(
            FieldNames.FIELD_VECTOR_REFERENCE, mockReq);
        plist.parameter("chatConfig", testChatConfig);
        plist.parameter("promptConfig", testPromptConfig);
        plist.parameter("systemCharacter", testSystemChar);
        plist.parameter("userCharacter", testUserChar);
        plist.parameter("content", "Test content");
        plist.parameter(FieldNames.FIELD_CHUNK, ChunkEnumType.WORD);
        plist.parameter(FieldNames.FIELD_CHUNK_COUNT, 100);

        BaseRecord vlist = IOSystem.getActiveContext().getFactory()
            .newInstance(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY_LIST,
                testUser, null, plist);

        List<BaseRecord> vectors = vlist.get("vectors");
        assertFalse(vectors.isEmpty());

        for (BaseRecord v : vectors) {
            assertEquals(testChatConfig, v.get("chatConfig"));
            assertEquals(testPromptConfig, v.get("promptConfig"));
            assertEquals(testSystemChar, v.get("systemCharacter"));
            assertEquals(testUserChar, v.get("userCharacter"));
        }
    }
}
```

---

### MCP Tool Tests (Service Layer)

#### McpVectorSearchToolTest.java

```java
public class McpVectorSearchToolTest {

    @Test
    public void testSearchWithScopeUri() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vector_search");
        request.setArguments(Map.of(
            "query", "test query",
            "scopeUri", "am7://default/data.data/doc-123",
            "limit", 5,
            "threshold", 0.6
        ));

        Response response = toolService.callTool(request);

        assertEquals(200, response.getStatus());
        McpToolResult result = (McpToolResult) response.getEntity();
        // Results should have MCP resource format with URIs
        for (Map<String, Object> item : result.getContentList()) {
            assertNotNull(item.get("resource"));
            Map<String, Object> resource = (Map<String, Object>) item.get("resource");
            assertTrue(resource.get("uri").toString().startsWith("am7://"));
        }
    }

    @Test
    public void testSearchWithTagUris() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vector_search");
        request.setArguments(Map.of(
            "query", "tagged content",
            "tags", List.of("am7://default/common.tag/tag-001"),
            "limit", 10
        ));

        Response response = toolService.callTool(request);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testSearchResultsIncludeScoreMetadata() {
        // Vectorize test document first
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vector_search");
        request.setArguments(Map.of("query", "search term", "limit", 5));

        Response response = toolService.callTool(request);
        McpToolResult result = (McpToolResult) response.getEntity();

        for (Map<String, Object> item : result.getContentList()) {
            Map<String, Object> meta = (Map<String, Object>) item.get("metadata");
            assertNotNull("Missing score metadata", meta.get("score"));
            assertNotNull("Missing chunk metadata", meta.get("chunk"));
            assertNotNull("Missing sourceUri", meta.get("sourceUri"));
        }
    }

    @Test
    public void testSearchMissingQueryReturns400() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vector_search");
        request.setArguments(Map.of("limit", 5));  // No query

        Response response = toolService.callTool(request);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void testSearchInvalidScopeUriReturns404() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vector_search");
        request.setArguments(Map.of(
            "query", "test",
            "scopeUri", "am7://default/data.data/nonexistent-id"
        ));

        Response response = toolService.callTool(request);
        // Should return 404 or empty results, not 500
        assertTrue(response.getStatus() == 200 || response.getStatus() == 404);
    }
}
```

#### McpVectorizeToolTest.java

```java
public class McpVectorizeToolTest {

    @Test
    public void testVectorizeByUri() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vectorize");
        request.setArguments(Map.of(
            "uri", "am7://default/data.data/doc-123",
            "chunkType", "SENTENCE",
            "chunkSize", 5
        ));

        Response response = toolService.callTool(request);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testVectorizeReplaceExistingTrue() {
        // Vectorize once
        toolService.callTool(createVectorizeRequest("am7://default/data.data/doc-replace"));
        // Vectorize again with replace
        McpToolCallRequest request = createVectorizeRequest("am7://default/data.data/doc-replace");
        request.getArguments().put("replaceExisting", true);

        Response response = toolService.callTool(request);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testVectorizeInvalidUriReturns404() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vectorize");
        request.setArguments(Map.of(
            "uri", "am7://default/data.data/nonexistent"
        ));

        Response response = toolService.callTool(request);
        assertEquals(404, response.getStatus());
    }

    @Test
    public void testVectorizeInvalidChunkTypeReturns400() {
        McpToolCallRequest request = new McpToolCallRequest();
        request.setName("am7_vectorize");
        request.setArguments(Map.of(
            "uri", "am7://default/data.data/doc-123",
            "chunkType", "INVALID_TYPE"
        ));

        Response response = toolService.callTool(request);
        assertEquals(400, response.getStatus());
    }
}
```

#### McpRagPipelineTest.java (Integration)

```java
@RunWith(Arquillian.class)
public class McpRagPipelineTest {

    /**
     * Full end-to-end test: create document → vectorize → search → get citations
     */
    @Test
    public void testFullRagPipeline() {
        // 1. Create a document via MCP tool
        Response createResp = callTool("am7_create_object", Map.of(
            "type", "data.data",
            "name", "rag-test-doc",
            "groupPath", "~/Test",
            "content", "Machine learning is a subset of artificial intelligence. " +
                        "Neural networks are a key component. " +
                        "Deep learning uses multiple layers of neural networks."
        ));
        assertEquals(200, createResp.getStatus());
        String docUri = extractUri(createResp);

        // 2. Vectorize the document
        Response vectorizeResp = callTool("am7_vectorize", Map.of(
            "uri", docUri,
            "chunkType", "SENTENCE",
            "chunkSize", 1
        ));
        assertEquals(200, vectorizeResp.getStatus());

        // 3. Search for related content
        Response searchResp = callTool("am7_vector_search", Map.of(
            "query", "What is deep learning?",
            "scopeUri", docUri,
            "limit", 3,
            "threshold", 0.5
        ));
        assertEquals(200, searchResp.getStatus());
        McpToolResult searchResult = searchResp.readEntity(McpToolResult.class);
        assertFalse("Search should return results", searchResult.getContentList().isEmpty());

        // 4. Verify results reference back to original document
        for (Map<String, Object> item : searchResult.getContentList()) {
            Map<String, Object> meta = (Map<String, Object>) item.get("metadata");
            assertEquals(docUri, meta.get("sourceUri"));
            assertNotNull(meta.get("score"));
            assertTrue((double) meta.get("score") > 0.0);
        }

        // 5. Verify results contain relevant content
        boolean foundRelevant = searchResult.getContentList().stream()
            .anyMatch(item -> {
                Map<String, Object> resource = (Map<String, Object>) item.get("resource");
                String text = (String) resource.get("text");
                return text.toLowerCase().contains("deep learning") ||
                       text.toLowerCase().contains("neural network");
            });
        assertTrue("Search results should contain relevant content", foundRelevant);

        // 6. Cleanup
        callTool("am7_delete_object", Map.of("type", "data.data", "objectId", extractId(docUri)));
    }

    /**
     * Test vectorize → delete → re-vectorize cycle
     */
    @Test
    public void testRevectorizeAfterContentChange() {
        String docUri = createAndGetUri("revec-test", "Original content about cats.");

        // First vectorize
        callTool("am7_vectorize", Map.of("uri", docUri, "chunkType", "SENTENCE", "chunkSize", 1));
        Response firstSearch = callTool("am7_vector_search", Map.of(
            "query", "cats", "scopeUri", docUri, "limit", 5));
        int firstCount = countResults(firstSearch);
        assertTrue(firstCount > 0);

        // Update content and re-vectorize
        // (via direct object update + re-vectorize)
        callTool("am7_vectorize", Map.of(
            "uri", docUri,
            "chunkType", "SENTENCE",
            "chunkSize", 1,
            "replaceExisting", true
        ));

        // Search should still work
        Response secondSearch = callTool("am7_vector_search", Map.of(
            "query", "cats", "scopeUri", docUri, "limit", 5));
        assertNotNull(secondSearch);
    }

    /**
     * Test that chunking strategy affects search quality
     */
    @Test
    public void testChunkingStrategyComparison() {
        String content = "Chapter 1 Introduction\n" +
            "Machine learning has transformed the technology landscape. " +
            "It enables computers to learn from data without explicit programming.\n\n" +
            "Chapter 2 Methods\n" +
            "Supervised learning uses labeled data. Unsupervised learning finds patterns. " +
            "Reinforcement learning maximizes reward signals.\n\n" +
            "Chapter 3 Applications\n" +
            "Applications include image recognition, natural language processing, and robotics.";

        String sentenceDocUri = createAndGetUri("chunk-sentence", content);
        String chapterDocUri = createAndGetUri("chunk-chapter", content);

        // Vectorize with different strategies
        callTool("am7_vectorize", Map.of(
            "uri", sentenceDocUri, "chunkType", "SENTENCE", "chunkSize", 1));
        callTool("am7_vectorize", Map.of(
            "uri", chapterDocUri, "chunkType", "CHAPTER", "chunkSize", 5));

        // Both should return results for the same query
        Response sentenceResults = callTool("am7_vector_search", Map.of(
            "query", "supervised learning", "scopeUri", sentenceDocUri, "limit", 3));
        Response chapterResults = callTool("am7_vector_search", Map.of(
            "query", "supervised learning", "scopeUri", chapterDocUri, "limit", 3));

        assertTrue(countResults(sentenceResults) > 0);
        assertTrue(countResults(chapterResults) > 0);
    }

    /**
     * Test citation context blocks have correct MCP format
     */
    @Test
    public void testCitationContextBlockFormat() {
        String docUri = createAndVectorizeDoc("citation-test",
            "Einstein developed the theory of relativity.");

        // Trigger chat with citation
        Response chatResp = callTool("am7_chat", Map.of(
            "message", "Tell me about Einstein",
            "chatConfigUri", testChatConfigUri,
            "dataReferences", List.of(docUri)
        ));

        assertEquals(200, chatResp.getStatus());

        // Verify the injected context uses MCP format, not legacy delimiters
        McpToolResult result = chatResp.readEntity(McpToolResult.class);
        String responseText = extractResponseText(result);

        // The response itself shouldn't contain raw MCP context blocks (they're ephemeral)
        assertFalse("Response should not contain raw MCP context markers",
            responseText.contains("<mcp:context"));
    }
}
```

---

### Test Configuration Additions

```properties
# MCP RAG Test Configuration
test.mcp.vector.enabled=true
test.mcp.vector.embeddingService=LOCAL
test.mcp.vector.embeddingUrl=http://localhost:8081
test.mcp.vector.embeddingToken=test-token

# Test document paths
test.mcp.resources.dir=src/test/resources/mcp/
test.mcp.resources.pdf=test-document.pdf
test.mcp.resources.docx=test-document.docx
test.mcp.resources.text=test-content.txt
```

```
src/test/resources/mcp/
├── test-document.pdf            # Multi-page PDF for extraction tests
├── test-document.docx           # Word document for extraction tests
├── test-content.txt             # Plain text with known content
├── test-chapter-book.txt        # Text with "Chapter N" markers
└── test-smart-quotes.txt        # Text with curly quotes/unicode
```

---

### Updated Implementation Checklist

```markdown
### Phase 9a: Unit Tests — Protocol Layer (from Part 12)
- [ ] Am7UriTest.java
- [ ] ResourceAdapterTest.java
- [ ] McpContextBuilderTest.java
- [ ] McpContextParserTest.java
- [ ] McpResourceServiceTest.java
- [ ] McpToolServiceTest.java
- [ ] Client-side: contextParser.test.js
- [ ] Client-side: contextFilter.test.js
- [ ] Client-side: legacyTokenAdapter.test.js

### Phase 9b: Unit Tests — URI & References
- [ ] Am7UriEdgeCaseTest.java (valid, invalid, security, builder, equality)
- [ ] Am7UriRoundTripTest.java (BaseRecord ↔ URI for all model types)
- [ ] ChatVectorReferenceTest.java (Chat → vector URI flow)
- [ ] VectorChatHistoryMcpTest.java (Factory with URI refs)

### Phase 9c: Unit Tests — Content & Chunking
- [ ] DocumentUtilExtractionTest.java (text, JSON, PDF, Word, notes, null, empty)
- [ ] VectorUtilChunkingTest.java (sentence, word, chapter, edge cases)
- [ ] Test chunk size 0, negative, larger-than-content
- [ ] Test smart quote normalization
- [ ] Test BreakIterator abbreviation handling

### Phase 9d: Unit Tests — Vector Store & Search
- [ ] VectorUtilStoreTest.java (CRUD, objectId, chunk numbering, delete, replace)
- [ ] VectorUtilHybridSearchTest.java (scope, tags, scoring, limit, distinct)
- [ ] Test vector results contain vectorReferenceObjectId for URI construction
- [ ] Test sort-and-limit ordering and edge cases
- [ ] Test partial reference creation in newVectorStore()

### Phase 9e: Unit Tests — Citation Resolver
- [ ] McpCitationResolverTest.java (tag-only, chat ref, character, general, mixed)
- [ ] Test citation deduplication against existing messages
- [ ] Test empty/null/invalid reference handling
- [ ] Test resolved blocks use am7:// URIs and ephemeral flag
- [ ] Test summary note lookup integration

### Phase 9f: Unit Tests — MCP Tool Layer
- [ ] McpVectorSearchToolTest.java (URI scope, tags, score metadata, errors)
- [ ] McpVectorizeToolTest.java (by URI, replace, invalid URI, invalid chunk type)
- [ ] McpExtractContentToolTest.java (extract via URI, format options)
- [ ] McpChunkContentToolTest.java (preview mode, all chunk types)
- [ ] McpChatToolTest.java (URI-based refs, citation context format)

### Phase 9g: Integration Tests
- [ ] McpRagPipelineTest.java (create → vectorize → search → cite, end-to-end)
- [ ] McpVectorReferenceFlowTest.java (URI ref lifecycle, re-vectorize cycle)
- [ ] Test chunking strategy comparison (sentence vs chapter vs word)
- [ ] Test citation context block MCP format in chat responses
- [ ] Create test resource files (PDF, DOCX, chapter text, unicode)
- [ ] Configure test properties for vector/embedding service
- [ ] Achieve >90% code coverage for refactored components
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
