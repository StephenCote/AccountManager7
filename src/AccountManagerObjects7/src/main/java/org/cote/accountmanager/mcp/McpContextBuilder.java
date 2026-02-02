package org.cote.accountmanager.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;

/**
 * Builds MCP-compliant context blocks for injection into chat messages.
 *
 * Replaces ad-hoc patterns like:
 *   --- BEGIN CITATIONS ---
 *   (Reminder: {...})
 *   (KeyFrame: {...})
 *
 * With unified MCP context blocks:
 *   &lt;mcp:context type="resource" uri="am7://..." ephemeral="true"&gt;
 *   {"schema": "...", "data": {...}}
 *   &lt;/mcp:context&gt;
 */
public class McpContextBuilder {

	private static final Logger logger = LogManager.getLogger(McpContextBuilder.class);

	private List<String> blocks = new ArrayList<>();

	/**
	 * Add a resource context block.
	 *
	 * @param uri       The am7:// URI for the resource
	 * @param schema    The schema URN
	 * @param data      The data map to serialize as JSON
	 * @param ephemeral Whether this context should be filtered from display
	 */
	public void addResource(String uri, String schema, Map<String, Object> data, boolean ephemeral) {
		StringBuilder sb = new StringBuilder();
		sb.append("<mcp:context type=\"resource\"");
		if (uri != null) {
			sb.append(" uri=\"").append(escapeXmlAttr(uri)).append("\"");
		}
		if (ephemeral) {
			sb.append(" ephemeral=\"true\"");
		}
		sb.append(">\n");

		Map<String, Object> body = Map.of("schema", schema, "data", data);
		sb.append(JSONUtil.exportObject(body));
		sb.append("\n</mcp:context>");

		blocks.add(sb.toString());
	}

	/**
	 * Add a reasoning context block (for thought/reasoning traces).
	 *
	 * @param steps The reasoning steps
	 */
	public void addReasoning(List<String> steps) {
		StringBuilder sb = new StringBuilder();
		sb.append("<mcp:context type=\"reasoning\" ephemeral=\"true\">\n");

		Map<String, Object> body = Map.of(
			"schema", "urn:am7:llm:reasoning",
			"steps", steps
		);
		sb.append(JSONUtil.exportObject(body));
		sb.append("\n</mcp:context>");

		blocks.add(sb.toString());
	}

	/**
	 * Add a reminder context block.
	 */
	public void addReminder(String uri, List<Map<String, String>> items) {
		addResource(uri, "urn:am7:narrative:reminder", Map.of("items", items), true);
	}

	/**
	 * Add a keyframe context block.
	 */
	public void addKeyframe(String uri, Map<String, Object> state) {
		addResource(uri, "urn:am7:narrative:keyframe", Map.of("state", state), true);
	}

	/**
	 * Add a metrics context block.
	 */
	public void addMetrics(String uri, Map<String, Object> metricsData) {
		addResource(uri, "urn:am7:biometric:face-analysis", metricsData, true);
	}

	/**
	 * Add an inline media resource tag.
	 */
	public void addMediaResource(String uri, String tags) {
		StringBuilder sb = new StringBuilder();
		sb.append("<mcp:resource uri=\"").append(escapeXmlAttr(uri)).append("\"");
		if (tags != null && !tags.isEmpty()) {
			sb.append(" tags=\"").append(escapeXmlAttr(tags)).append("\"");
		}
		sb.append(" />");
		blocks.add(sb.toString());
	}

	/**
	 * Build all context blocks into a single string.
	 *
	 * @return The concatenated context blocks, or empty string if none added
	 */
	public String build() {
		if (blocks.isEmpty()) {
			return "";
		}
		return String.join("\n", blocks);
	}

	/**
	 * Get the number of context blocks added.
	 */
	public int size() {
		return blocks.size();
	}

	/**
	 * Clear all context blocks.
	 */
	public void clear() {
		blocks.clear();
	}

	private static String escapeXmlAttr(String value) {
		if (value == null) return "";
		return value.replace("&", "&amp;")
			.replace("\"", "&quot;")
			.replace("<", "&lt;")
			.replace(">", "&gt;");
	}
}
