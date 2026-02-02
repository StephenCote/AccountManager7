package org.cote.accountmanager.mcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Parses MCP context blocks from message content.
 *
 * Handles two formats:
 * 1. Block contexts: &lt;mcp:context type="..." uri="..." ephemeral="true"&gt;body&lt;/mcp:context&gt;
 * 2. Inline resources: &lt;mcp:resource uri="..." tags="..." /&gt;
 */
public class McpContextParser {

	private static final Logger logger = LogManager.getLogger(McpContextParser.class);

	private static final Pattern BLOCK_PATTERN = Pattern.compile(
		"<mcp:context\\s+([^>]*)>(.*?)</mcp:context>",
		Pattern.DOTALL
	);

	private static final Pattern INLINE_PATTERN = Pattern.compile(
		"<mcp:resource\\s+((?:[^/]|/(?!>))*?)\\s*/>"
	);

	private static final Pattern ATTR_PATTERN = Pattern.compile(
		"(\\w+)\\s*=\\s*\"([^\"]*)\""
	);

	/**
	 * Parse all MCP context blocks from content.
	 *
	 * @param content The message content to parse
	 * @return List of parsed McpContext objects, in order of appearance
	 */
	public static List<McpContext> parse(String content) {
		List<McpContext> contexts = new ArrayList<>();
		if (content == null || content.isEmpty()) {
			return contexts;
		}

		// Parse block contexts
		Matcher blockMatcher = BLOCK_PATTERN.matcher(content);
		while (blockMatcher.find()) {
			McpContext ctx = new McpContext();
			ctx.setStart(blockMatcher.start());
			ctx.setEnd(blockMatcher.end());
			ctx.setInline(false);

			String attrs = blockMatcher.group(1);
			String body = blockMatcher.group(2).trim();
			ctx.setBody(body);

			parseAttributes(attrs, ctx);
			contexts.add(ctx);
		}

		// Parse inline resources
		Matcher inlineMatcher = INLINE_PATTERN.matcher(content);
		while (inlineMatcher.find()) {
			McpContext ctx = new McpContext();
			ctx.setStart(inlineMatcher.start());
			ctx.setEnd(inlineMatcher.end());
			ctx.setInline(true);

			String attrs = inlineMatcher.group(1);
			parseAttributes(attrs, ctx);

			contexts.add(ctx);
		}

		// Sort by position
		contexts.sort((a, b) -> Integer.compare(a.getStart(), b.getStart()));

		return contexts;
	}

	/**
	 * Remove all MCP context blocks from content, returning clean text.
	 *
	 * @param content The content to strip
	 * @return Content with all MCP blocks removed
	 */
	public static String stripAll(String content) {
		if (content == null) return null;
		String result = BLOCK_PATTERN.matcher(content).replaceAll("");
		result = INLINE_PATTERN.matcher(result).replaceAll("");
		return result;
	}

	/**
	 * Remove only ephemeral MCP context blocks from content.
	 *
	 * @param content The content to filter
	 * @return Content with ephemeral blocks removed
	 */
	public static String stripEphemeral(String content) {
		if (content == null) return null;
		List<McpContext> contexts = parse(content);
		StringBuilder result = new StringBuilder(content);

		// Process in reverse order to preserve positions
		for (int i = contexts.size() - 1; i >= 0; i--) {
			McpContext ctx = contexts.get(i);
			if (ctx.isEphemeral()) {
				result.replace(ctx.getStart(), ctx.getEnd(), "");
			}
		}
		return result.toString();
	}

	private static void parseAttributes(String attrs, McpContext ctx) {
		Matcher attrMatcher = ATTR_PATTERN.matcher(attrs);
		while (attrMatcher.find()) {
			String name = attrMatcher.group(1);
			String value = attrMatcher.group(2);
			switch (name) {
				case "type":
					ctx.setType(value);
					break;
				case "uri":
					ctx.setUri(value);
					break;
				case "ephemeral":
					ctx.setEphemeral("true".equalsIgnoreCase(value));
					break;
				case "tags":
					ctx.setTags(Arrays.asList(value.split("\\s*,\\s*")));
					break;
			}
		}
	}
}
