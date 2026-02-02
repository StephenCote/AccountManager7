package org.cote.accountmanager.mcp;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Filters MCP context blocks from message content, categorizing them
 * and optionally removing ephemeral blocks from the display content.
 *
 * Replaces ad-hoc filtering functions like:
 *   pruneOut(cnt, "--- CITATION", "END CITATIONS ---")
 *   pruneTag(cnt, "think")
 *   pruneToMark(cnt, "(Reminder")
 */
public class McpContextFilter {

	private static final Logger logger = LogManager.getLogger(McpContextFilter.class);

	private boolean showEphemeral = false;
	private boolean renderResources = false;

	public McpContextFilter() {}

	public McpContextFilter(boolean showEphemeral, boolean renderResources) {
		this.showEphemeral = showEphemeral;
		this.renderResources = renderResources;
	}

	/**
	 * Filter content, categorizing MCP blocks and optionally stripping ephemeral content.
	 *
	 * @param content The raw message content
	 * @return McpFilterResult with cleaned content and categorized contexts
	 */
	public McpFilterResult filter(String content) {
		McpFilterResult result = new McpFilterResult();

		if (content == null || content.isEmpty()) {
			result.setContent(content != null ? content : "");
			return result;
		}

		List<McpContext> contexts = McpContextParser.parse(content);

		// Categorize each context
		for (McpContext ctx : contexts) {
			categorize(ctx, result);
		}

		// Build filtered content
		if (showEphemeral) {
			result.setContent(content);
		} else {
			StringBuilder filtered = new StringBuilder(content);
			// Remove in reverse order to preserve positions
			for (int i = contexts.size() - 1; i >= 0; i--) {
				McpContext ctx = contexts.get(i);
				if (ctx.isEphemeral() || (!renderResources && ctx.isInline())) {
					filtered.replace(ctx.getStart(), ctx.getEnd(), "");
				} else if (renderResources && ctx.isInline()) {
					String rendered = renderInlineResource(ctx);
					filtered.replace(ctx.getStart(), ctx.getEnd(), rendered);
				}
			}
			result.setContent(filtered.toString());
		}

		return result;
	}

	/**
	 * Categorize a context block based on its URI and type.
	 */
	private void categorize(McpContext ctx, McpFilterResult result) {
		String uri = ctx.getUri();
		String type = ctx.getType();

		if (uri != null && uri.contains("/citations/")) {
			result.getCitations().add(ctx);
		} else if (uri != null && uri.contains("/reminder/")) {
			result.getReminders().add(ctx);
		} else if (uri != null && uri.contains("/keyframe/")) {
			result.getKeyframes().add(ctx);
		} else if (uri != null && uri.contains("/metrics/")) {
			result.getMetrics().add(ctx);
		} else if ("reasoning".equals(type)) {
			result.getReasoning().add(ctx);
		} else if (ctx.isInline() || (uri != null && uri.contains("/media/"))) {
			result.getMedia().add(ctx);
		}
	}

	/**
	 * Render an inline resource as HTML.
	 */
	private String renderInlineResource(McpContext ctx) {
		String uri = ctx.getUri();
		if (uri != null && uri.contains("/media/")) {
			return "<img data-mcp-uri=\"" + uri + "\" src=\"\" />";
		}
		return "";
	}

	public boolean isShowEphemeral() { return showEphemeral; }
	public void setShowEphemeral(boolean showEphemeral) { this.showEphemeral = showEphemeral; }

	public boolean isRenderResources() { return renderResources; }
	public void setRenderResources(boolean renderResources) { this.renderResources = renderResources; }
}
