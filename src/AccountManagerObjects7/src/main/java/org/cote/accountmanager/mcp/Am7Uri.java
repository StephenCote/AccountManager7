package org.cote.accountmanager.mcp;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * URI utility for the am7:// scheme used by MCP resource identification.
 *
 * Format: am7://[organization]/[model-type]/[object-id]
 * Examples:
 *   am7://default/system.user/abc-123
 *   am7://default/data.data/doc-456
 *   am7://default/media/data.data/img-789
 *   am7://default/vector/search?q=hello&limit=10
 */
public class Am7Uri {

	private static final Logger logger = LogManager.getLogger(Am7Uri.class);
	public static final String SCHEME = "am7";
	public static final String SCHEME_PREFIX = SCHEME + "://";

	private static final Pattern TRAVERSAL_PATTERN = Pattern.compile("\\.\\.[\\\\/]");
	private static final Pattern INJECTION_PATTERN = Pattern.compile("[;'\"\\\\`]");

	private String organization;
	private String type;
	private String id;
	private boolean media = false;
	private String mediaType;
	private Map<String, String> queryParams = new LinkedHashMap<>();

	private Am7Uri() {}

	public static Am7Uri parse(String uri) {
		if (uri == null || uri.isEmpty()) {
			throw new IllegalArgumentException("URI cannot be null or empty");
		}
		if (!uri.startsWith(SCHEME_PREFIX)) {
			throw new IllegalArgumentException("URI must start with " + SCHEME_PREFIX + ": " + uri);
		}

		// Check for traversal attacks
		if (TRAVERSAL_PATTERN.matcher(uri).find()) {
			throw new IllegalArgumentException("URI contains path traversal: " + uri);
		}
		// Check for injection attacks
		if (INJECTION_PATTERN.matcher(uri.substring(SCHEME_PREFIX.length())).find()) {
			throw new IllegalArgumentException("URI contains invalid characters: " + uri);
		}

		String remainder = uri.substring(SCHEME_PREFIX.length());

		// Split off query params
		String path;
		Map<String, String> params = new LinkedHashMap<>();
		int qIdx = remainder.indexOf('?');
		if (qIdx >= 0) {
			path = remainder.substring(0, qIdx);
			String queryString = remainder.substring(qIdx + 1);
			for (String param : queryString.split("&")) {
				int eq = param.indexOf('=');
				if (eq > 0) {
					params.put(param.substring(0, eq), param.substring(eq + 1));
				}
			}
		} else {
			path = remainder;
		}

		String[] parts = path.split("/", -1);

		Am7Uri result = new Am7Uri();
		result.queryParams = params;

		if (parts.length < 2 || parts[0].isEmpty()) {
			throw new IllegalArgumentException("URI missing organization: " + uri);
		}

		// Strategy: model types contain dots (e.g., "olio.llm.chatConfig", "data.data", "system.user")
		// or are special keywords ("media", "vector", "reminder", "keyframe", "metrics", "citations").
		// Organization path segments do NOT contain dots.
		// Parse from the end: last segment = id, second-to-last = type, everything before = org.
		// For URIs with 3 segments (org/type/id), the simple case applies.
		// For URIs with 4+ segments, find the type by looking for a dotted segment from the end.

		// Check for media URIs anywhere in path
		int mediaIdx = -1;
		for (int i = 0; i < parts.length; i++) {
			if ("media".equals(parts[i]) && i + 2 < parts.length) {
				mediaIdx = i;
				break;
			}
		}

		if (mediaIdx >= 0 && mediaIdx + 2 < parts.length) {
			// Media URI: am7://[org...]/media/mediaType/id
			result.media = true;
			result.type = "media";
			result.mediaType = parts[mediaIdx + 1];
			if (parts[mediaIdx + 2].isEmpty()) {
				throw new IllegalArgumentException("URI missing id: " + uri);
			}
			result.id = parts[mediaIdx + 2];
			result.organization = String.join("/", java.util.Arrays.copyOfRange(parts, 0, mediaIdx));
			if (result.organization.isEmpty()) {
				throw new IllegalArgumentException("URI missing organization: " + uri);
			}
		}
		else if (parts.length == 2 && !params.isEmpty()) {
			// e.g. am7://default/vector?q=x
			result.organization = parts[0];
			result.type = parts[1];
			result.id = null;
		}
		else if (parts.length >= 3) {
			// Find the type segment: scan backwards from second-to-last for a dotted segment
			// The last segment is always the id
			String lastPart = parts[parts.length - 1];
			if (lastPart.isEmpty()) {
				throw new IllegalArgumentException("URI missing id: " + uri);
			}
			result.id = lastPart;

			// Find the type: look for a dotted segment, starting from the second-to-last
			int typeIdx = -1;
			for (int i = parts.length - 2; i >= 1; i--) {
				if (parts[i].contains(".")) {
					typeIdx = i;
					break;
				}
			}

			if (typeIdx >= 1) {
				// Found a dotted type segment
				result.type = parts[typeIdx];
				result.organization = String.join("/", java.util.Arrays.copyOfRange(parts, 0, typeIdx));
			} else {
				// No dotted segment found; assume simple org/type/id or org/special/id
				// The second-to-last is the type, everything before is org
				result.type = parts[parts.length - 2];
				result.organization = String.join("/", java.util.Arrays.copyOfRange(parts, 0, parts.length - 2));
			}

			if (result.organization.isEmpty()) {
				throw new IllegalArgumentException("URI missing organization: " + uri);
			}
			if (result.type.isEmpty()) {
				throw new IllegalArgumentException("URI missing type: " + uri);
			}
		}
		else {
			throw new IllegalArgumentException("URI missing type: " + uri);
		}

		return result;
	}

	/**
	 * Build an am7:// URI from a BaseRecord.
	 * Returns null if the record has no objectId.
	 */
	public static String toUri(BaseRecord record) {
		if (record == null) {
			return null;
		}
		String objectId = record.get(FieldNames.FIELD_OBJECT_ID);
		if (objectId == null || objectId.isEmpty()) {
			return null;
		}
		String orgPath = record.get(FieldNames.FIELD_ORGANIZATION_PATH);
		if (orgPath == null || orgPath.isEmpty()) {
			orgPath = "default";
		}
		// Remove leading slash if present
		if (orgPath.startsWith("/")) {
			orgPath = orgPath.substring(1);
		}
		String schema = record.getSchema();
		if (schema == null || schema.isEmpty()) {
			return null;
		}
		return SCHEME_PREFIX + orgPath + "/" + schema + "/" + objectId;
	}

	public static Builder builder() {
		return new Builder();
	}

	// Getters
	public String getOrganization() { return organization; }
	public String getType() { return type; }
	public String getId() { return id; }
	public boolean isMedia() { return media; }
	public String getMediaType() { return mediaType; }
	public String getQueryParam(String key) { return queryParams.get(key); }
	public Map<String, String> getQueryParams() { return queryParams; }

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof Am7Uri)) return false;
		Am7Uri other = (Am7Uri) obj;
		return Objects.equals(organization, other.organization)
			&& Objects.equals(type, other.type)
			&& Objects.equals(id, other.id)
			&& Objects.equals(queryParams, other.queryParams);
	}

	@Override
	public int hashCode() {
		return Objects.hash(organization, type, id, queryParams);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(SCHEME_PREFIX);
		sb.append(organization).append("/").append(type);
		if (media && mediaType != null) {
			sb.append("/").append(mediaType);
		}
		if (id != null) {
			sb.append("/").append(id);
		}
		if (!queryParams.isEmpty()) {
			sb.append("?");
			boolean first = true;
			for (Map.Entry<String, String> entry : queryParams.entrySet()) {
				if (!first) sb.append("&");
				sb.append(entry.getKey()).append("=").append(entry.getValue());
				first = false;
			}
		}
		return sb.toString();
	}

	public static class Builder {
		private String organization;
		private String type;
		private String id;
		private boolean vectorSearch = false;
		private Map<String, String> queryParams = new LinkedHashMap<>();

		public Builder organization(String organization) {
			this.organization = organization;
			return this;
		}

		public Builder type(String type) {
			this.type = type;
			return this;
		}

		public Builder id(String id) {
			this.id = id;
			return this;
		}

		public Builder vectorSearch() {
			this.type = "vector";
			this.id = "search";
			this.vectorSearch = true;
			return this;
		}

		public Builder queryParam(String key, String value) {
			try {
				this.queryParams.put(key, URLEncoder.encode(value, "UTF-8").replace("%20", "+"));
			} catch (UnsupportedEncodingException e) {
				this.queryParams.put(key, value);
			}
			return this;
		}

		public String build() {
			if (organization == null || organization.isEmpty()) {
				throw new IllegalStateException("Organization is required");
			}
			if (type == null || type.isEmpty()) {
				throw new IllegalStateException("Type is required");
			}

			StringBuilder sb = new StringBuilder(SCHEME_PREFIX);
			sb.append(organization).append("/").append(type);
			if (id != null) {
				sb.append("/").append(id);
			}
			if (!queryParams.isEmpty()) {
				sb.append("?");
				boolean first = true;
				for (Map.Entry<String, String> entry : queryParams.entrySet()) {
					if (!first) sb.append("&");
					sb.append(entry.getKey()).append("=").append(entry.getValue());
					first = false;
				}
			}
			return sb.toString();
		}
	}
}
