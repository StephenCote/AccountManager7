package org.cote.accountmanager.mcp.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 DTOs for the MCP server protocol.
 * All types are plain POJOs serialized/deserialized by Jackson.
 */
public class McpJsonRpc {

	// =========================================================================
	// JSON-RPC envelope types
	// =========================================================================

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Request {
		private String jsonrpc = "2.0";
		private Object id;
		private String method;
		private Map<String, Object> params;

		public String getJsonrpc() { return jsonrpc; }
		public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }

		public Object getId() { return id; }
		public void setId(Object id) { this.id = id; }

		public String getMethod() { return method; }
		public void setMethod(String method) { this.method = method; }

		public Map<String, Object> getParams() { return params; }
		public void setParams(Map<String, Object> params) { this.params = params; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Response {
		private String jsonrpc = "2.0";
		private Object id;
		private Object result;

		public Response() {}
		public Response(Object id, Object result) {
			this.id = id;
			this.result = result;
		}

		public String getJsonrpc() { return jsonrpc; }
		public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }

		public Object getId() { return id; }
		public void setId(Object id) { this.id = id; }

		public Object getResult() { return result; }
		public void setResult(Object result) { this.result = result; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ErrorResponse {
		private String jsonrpc = "2.0";
		private Object id;
		private ErrorBody error;

		public ErrorResponse() {}
		public ErrorResponse(Object id, ErrorBody error) {
			this.id = id;
			this.error = error;
		}

		public String getJsonrpc() { return jsonrpc; }
		public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }

		public Object getId() { return id; }
		public void setId(Object id) { this.id = id; }

		public ErrorBody getError() { return error; }
		public void setError(ErrorBody error) { this.error = error; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ErrorBody {
		public static final int PARSE_ERROR = -32700;
		public static final int INVALID_REQUEST = -32600;
		public static final int METHOD_NOT_FOUND = -32601;
		public static final int INVALID_PARAMS = -32602;
		public static final int INTERNAL_ERROR = -32603;

		private int code;
		private String message;
		private Object data;

		public ErrorBody() {}
		public ErrorBody(int code, String message) {
			this.code = code;
			this.message = message;
		}

		public int getCode() { return code; }
		public void setCode(int code) { this.code = code; }

		public String getMessage() { return message; }
		public void setMessage(String message) { this.message = message; }

		public Object getData() { return data; }
		public void setData(Object data) { this.data = data; }
	}

	// =========================================================================
	// MCP initialize result types
	// =========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class InitializeResult {
		private String protocolVersion;
		private ServerCapabilities capabilities;
		private ServerInfo serverInfo;
		private String instructions;

		public String getProtocolVersion() { return protocolVersion; }
		public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }

		public ServerCapabilities getCapabilities() { return capabilities; }
		public void setCapabilities(ServerCapabilities capabilities) { this.capabilities = capabilities; }

		public ServerInfo getServerInfo() { return serverInfo; }
		public void setServerInfo(ServerInfo serverInfo) { this.serverInfo = serverInfo; }

		public String getInstructions() { return instructions; }
		public void setInstructions(String instructions) { this.instructions = instructions; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ServerCapabilities {
		private Map<String, Object> resources;
		private Map<String, Object> tools;

		public Map<String, Object> getResources() { return resources; }
		public void setResources(Map<String, Object> resources) { this.resources = resources; }

		public Map<String, Object> getTools() { return tools; }
		public void setTools(Map<String, Object> tools) { this.tools = tools; }
	}

	public static class ServerInfo {
		private String name;
		private String version;

		public ServerInfo() {}
		public ServerInfo(String name, String version) {
			this.name = name;
			this.version = version;
		}

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }

		public String getVersion() { return version; }
		public void setVersion(String version) { this.version = version; }
	}

	// =========================================================================
	// Resource types
	// =========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Resource {
		private String uri;
		private String name;
		private String description;
		private String mimeType;

		public Resource() {}
		public Resource(String uri, String name, String description, String mimeType) {
			this.uri = uri;
			this.name = name;
			this.description = description;
			this.mimeType = mimeType;
		}

		public String getUri() { return uri; }
		public void setUri(String uri) { this.uri = uri; }

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }

		public String getDescription() { return description; }
		public void setDescription(String description) { this.description = description; }

		public String getMimeType() { return mimeType; }
		public void setMimeType(String mimeType) { this.mimeType = mimeType; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ResourceTemplate {
		private String uriTemplate;
		private String name;
		private String description;
		private String mimeType;

		public ResourceTemplate() {}
		public ResourceTemplate(String uriTemplate, String name, String description, String mimeType) {
			this.uriTemplate = uriTemplate;
			this.name = name;
			this.description = description;
			this.mimeType = mimeType;
		}

		public String getUriTemplate() { return uriTemplate; }
		public void setUriTemplate(String uriTemplate) { this.uriTemplate = uriTemplate; }

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }

		public String getDescription() { return description; }
		public void setDescription(String description) { this.description = description; }

		public String getMimeType() { return mimeType; }
		public void setMimeType(String mimeType) { this.mimeType = mimeType; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ResourceContent {
		private String uri;
		private String mimeType;
		private String text;
		private String blob;

		public ResourceContent() {}

		public static ResourceContent text(String uri, String mimeType, String text) {
			ResourceContent rc = new ResourceContent();
			rc.uri = uri;
			rc.mimeType = mimeType;
			rc.text = text;
			return rc;
		}

		public static ResourceContent blob(String uri, String mimeType, String base64) {
			ResourceContent rc = new ResourceContent();
			rc.uri = uri;
			rc.mimeType = mimeType;
			rc.blob = base64;
			return rc;
		}

		public String getUri() { return uri; }
		public void setUri(String uri) { this.uri = uri; }

		public String getMimeType() { return mimeType; }
		public void setMimeType(String mimeType) { this.mimeType = mimeType; }

		public String getText() { return text; }
		public void setText(String text) { this.text = text; }

		public String getBlob() { return blob; }
		public void setBlob(String blob) { this.blob = blob; }
	}

	// =========================================================================
	// Tool types
	// =========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Tool {
		private String name;
		private String description;
		private Map<String, Object> inputSchema;

		public Tool() {}
		public Tool(String name, String description, Map<String, Object> inputSchema) {
			this.name = name;
			this.description = description;
			this.inputSchema = inputSchema;
		}

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }

		public String getDescription() { return description; }
		public void setDescription(String description) { this.description = description; }

		public Map<String, Object> getInputSchema() { return inputSchema; }
		public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolResult {
		private List<ToolContent> content = new ArrayList<>();
		@JsonProperty("isError")
		private boolean isError;

		public ToolResult() {}

		public static ToolResult success(String text) {
			ToolResult r = new ToolResult();
			r.content.add(ToolContent.text(text));
			return r;
		}

		public static ToolResult error(String message) {
			ToolResult r = new ToolResult();
			r.isError = true;
			r.content.add(ToolContent.text(message));
			return r;
		}

		public List<ToolContent> getContent() { return content; }
		public void setContent(List<ToolContent> content) { this.content = content; }

		@JsonProperty("isError")
		public boolean isError() { return isError; }
		public void setError(boolean isError) { this.isError = isError; }
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolContent {
		private String type;
		private String text;

		public ToolContent() {}

		public static ToolContent text(String text) {
			ToolContent tc = new ToolContent();
			tc.type = "text";
			tc.text = text;
			return tc;
		}

		public String getType() { return type; }
		public void setType(String type) { this.type = type; }

		public String getText() { return text; }
		public void setText(String text) { this.text = text; }
	}
}
