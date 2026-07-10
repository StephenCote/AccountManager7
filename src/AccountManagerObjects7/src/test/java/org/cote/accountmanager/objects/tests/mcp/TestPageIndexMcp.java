package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.mcp.server.Am7ToolProvider;
import org.cote.accountmanager.mcp.server.McpJsonRpc;
import org.cote.accountmanager.mcp.server.McpSession;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// Tier 2 (MCP) verification for the PageIndex integration plan: the four am7_pageindex_* tools on
/// Am7ToolProvider (PageIndexIntegrationPlan.md Tier 2, steps 1-2). A dedicated file rather than extending
/// TestMcpMemory.java (which is CardFox/Verse-vector-specific) — same "mcp" test package, same real
/// McpSession + callTool dispatch pattern, but PageIndex-focused. Never uses the admin user.
///
/// Gated behind PAGEINDEX_LLM (building the index makes live LLM/embedding calls); run single-threaded.
public class TestPageIndexMcp extends BaseTest {

	private static final Pattern NODE_ID_PATTERN = Pattern.compile("nodeId=([0-9a-fA-F\\-]{36})");

	private boolean llmEnabled() {
		return System.getenv("PAGEINDEX_LLM") != null;
	}

	private String text(McpJsonRpc.ToolResult result) {
		assertNotNull("ToolResult is null", result);
		assertFalse("ToolResult reported an error: " + (result.getContent().isEmpty() ? "(no text)" : result.getContent().get(0).getText()), result.isError());
		assertTrue("ToolResult has no content", !result.getContent().isEmpty());
		return result.getContent().get(0).getText();
	}

	/// Extract the nodeId from the FIRST actual node entry line (starts with "[ROOT]"/"[SECTION]"/"[CHUNK]"),
	/// never from the section tool's own "Children of nodeId=<parent>..." header, which also matches the
	/// nodeId= pattern but refers to the parent, not a child.
	private String firstNodeId(String text) {
		for (String line : text.split("\n")) {
			if (line.trim().startsWith("[")) {
				Matcher m = NODE_ID_PATTERN.matcher(line);
				if (m.find()) return m.group(1);
			}
		}
		fail("No node entry line (starting with '[') containing nodeId= found in: " + text);
		return null;
	}

	@Test
	public void TestPageIndexMcpToolsListed() {
		Am7ToolProvider provider = new Am7ToolProvider();
		java.util.List<McpJsonRpc.Tool> tools = provider.listTools(null);
		assertNotNull("Tool list is null", tools);

		boolean hasSearch = false, hasStructure = false, hasSection = false, hasContent = false;
		for (McpJsonRpc.Tool t : tools) {
			if ("am7_pageindex_search".equals(t.getName())) hasSearch = true;
			if ("am7_pageindex_structure".equals(t.getName())) hasStructure = true;
			if ("am7_pageindex_section".equals(t.getName())) hasSection = true;
			if ("am7_pageindex_content".equals(t.getName())) hasContent = true;
		}
		assertTrue("Should list am7_pageindex_search", hasSearch);
		assertTrue("Should list am7_pageindex_structure", hasStructure);
		assertTrue("Should list am7_pageindex_section", hasSection);
		assertTrue("Should list am7_pageindex_content", hasContent);
	}

	@Test
	public void TestPageIndexMcpNavigationAndPbac() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== TestPageIndexMcpNavigationAndPbac ===");

		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/PageIndexMcp");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		BaseRecord testUser2 = mf.getCreateUser(octx.getAdminUser(), "testUser2", octx.getOrganizationId());
		assertNotNull("testUser1 is null", testUser1);
		assertNotNull("testUser2 is null", testUser2);

		String content = "Cliffside Apiary. The apiary keeps forty hives along the southern cliff terraces "
			+ "where wildflowers bloom nearly year-round. A beekeeper harvests honeycomb every autumn and "
			+ "records the yield of each hive in a logbook stored inside the tool shed.";
		BaseRecord doc = getCreateData(testUser1, "mcp-pageindex-test.txt", "text/plain", content.getBytes(), "~/PageIndexMcp", octx.getOrganizationId());
		assertNotNull("Source doc is null", doc);
		String objectId = doc.get(FieldNames.FIELD_OBJECT_ID);

		boolean built = IOSystem.getActiveContext().getAccessPoint().pageIndex(testUser1, "data.data", objectId);
		assertTrue("AccessPoint.pageIndex returned false", built);

		Am7ToolProvider provider = new Am7ToolProvider();
		McpSession session1 = new McpSession("test-session-1", testUser1);
		McpSession session2 = new McpSession("test-session-2", testUser2);

		/// --- am7_pageindex_structure (owner) ---
		Map<String, Object> structureArgs = new HashMap<>();
		structureArgs.put("type", "data.data");
		structureArgs.put("objectId", objectId);
		String structureText = text(provider.callTool(session1, "am7_pageindex_structure", structureArgs));
		logger.info("[MCP] structure:\n" + structureText);
		assertTrue("[MCP] structure should mention ROOT", structureText.contains("[ROOT]"));
		String rootNodeId = firstNodeId(structureText);

		/// --- am7_pageindex_section (owner) — children of root ---
		Map<String, Object> sectionArgs = new HashMap<>();
		sectionArgs.put("nodeId", rootNodeId);
		String sectionText = text(provider.callTool(session1, "am7_pageindex_section", sectionArgs));
		logger.info("[MCP] section:\n" + sectionText);
		assertTrue("[MCP] section should list at least one child", sectionText.contains("[CHUNK]") || sectionText.contains("[SECTION]"));
		String childNodeId = firstNodeId(sectionText);

		/// --- am7_pageindex_content (owner) — resolve down to a CHUNK leaf ---
		Map<String, Object> contentArgs = new HashMap<>();
		contentArgs.put("nodeId", childNodeId);
		McpJsonRpc.ToolResult contentResult = provider.callTool(session1, "am7_pageindex_content", contentArgs);
		if (contentResult.isError()) {
			/// childNodeId resolved to a SECTION, not a CHUNK — descend one more level via its own children.
			logger.info("[MCP] first child was not a CHUNK (" + contentResult.getContent().get(0).getText() + "); descending further");
			Map<String, Object> subSectionArgs = new HashMap<>();
			subSectionArgs.put("nodeId", childNodeId);
			String subSectionText = text(provider.callTool(session1, "am7_pageindex_section", subSectionArgs));
			childNodeId = firstNodeId(subSectionText);
			contentArgs.put("nodeId", childNodeId);
			contentResult = provider.callTool(session1, "am7_pageindex_content", contentArgs);
		}
		String contentText = text(contentResult);
		logger.info("[MCP] content: " + contentText);
		assertTrue("[MCP] content should be non-empty leaf text", contentText.trim().length() > 0);

		/// --- am7_pageindex_search (owner) — content-specific query ---
		Map<String, Object> searchArgs = new HashMap<>();
		searchArgs.put("type", "data.data");
		searchArgs.put("objectId", objectId);
		searchArgs.put("query", "How often is honeycomb harvested and where is the yield recorded?");
		String searchText = text(provider.callTool(session1, "am7_pageindex_search", searchArgs));
		logger.info("[MCP] search:\n" + searchText);
		String lc = searchText.toLowerCase();
		assertTrue("[MCP] search should surface the autumn/logbook passage", lc.contains("autumn") || lc.contains("logbook"));

		/// --- NEGATIVE: testUser2 (same org, no access to testUser1's doc) is denied by PBAC ---
		McpJsonRpc.ToolResult deniedStructure = provider.callTool(session2, "am7_pageindex_structure", structureArgs);
		String deniedText = text(deniedStructure);
		logger.info("[MCP][NEGATIVE] structure as testUser2: " + deniedText);
		assertFalse("[MCP][NEGATIVE] denied user must not see the real structure", deniedText.contains("[ROOT]"));

		McpJsonRpc.ToolResult deniedSearch = provider.callTool(session2, "am7_pageindex_search", searchArgs);
		String deniedSearchText = text(deniedSearch);
		logger.info("[MCP][NEGATIVE] search as testUser2: " + deniedSearchText);
		assertTrue("[MCP][NEGATIVE] denied user should get 0 results", deniedSearchText.contains("Found 0 PageIndex result"));
	}
}
