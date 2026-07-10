package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// Tier 2 (chat RAG) verification for the PageIndex integration plan: the opt-in `usePageIndex` chatConfig
/// flag gating ChatUtil.getDataCitations' PageIndex branch (PageIndexIntegrationPlan.md Tier 2, step 3).
/// Modeled on TestContextRefs.java. Never uses the admin user; a non-admin test user is the actor.
///
/// The source doc here is PageIndex-only (never vectorized), so the two tests are a genuine differential:
/// flag off -> 0 citations (no vector store exists, PageIndex branch is skipped); flag on -> real
/// PageIndex-derived citations appear. That proves the flag actually gates the injection rather than the
/// citations coming from some other path.
///
/// Gated behind PAGEINDEX_LLM (building the index makes live LLM/embedding calls); run single-threaded.
public class TestPageIndexCitations extends BaseTest {

	private boolean llmEnabled() {
		return System.getenv("PAGEINDEX_LLM") != null;
	}

	private BaseRecord buildIndexedDoc(BaseRecord testUser, OrganizationContext octx, String name, String content) {
		BaseRecord doc = getCreateData(testUser, name, "text/plain", content.getBytes(), "~/PageIndexCitations", octx.getOrganizationId());
		assertNotNull("Source doc is null", doc);
		boolean built = IOSystem.getActiveContext().getAccessPoint().pageIndex(testUser, "data.data", doc.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("AccessPoint.pageIndex returned false", built);
		return doc;
	}

	private ChatRequest buildChatRequest(BaseRecord chatConfig, BaseRecord promptConfig, BaseRecord doc, String message) {
		ChatRequest creq = new ChatRequest();
		creq.setChatConfig(chatConfig);
		creq.setPromptConfig(promptConfig);
		creq.setMessage(message);
		String docRef = "{\"schema\":\"" + doc.getSchema() + "\",\"objectId\":\"" + doc.get(FieldNames.FIELD_OBJECT_ID) + "\"}";
		List<String> contextRefs = new ArrayList<>();
		contextRefs.add(docRef);
		creq.setValue("contextRefs", contextRefs);
		return creq;
	}

	@Test
	public void testPageIndexCitationsFlagOffYieldsNone() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== testPageIndexCitationsFlagOffYieldsNone ===");

		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/PageIndexCitations");
		BaseRecord testUser = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull("Test user is null", testUser);

		BaseRecord doc = buildIndexedDoc(testUser, octx, "citations-flag-off.txt",
			"Beacon Relay Station. The relay station boosts long-range radio signals between the coastal "
			+ "towns and the inland farming cooperative. A technician inspects the antenna mast every "
			+ "Tuesday and logs the signal strength in a paper ledger kept in the equipment shed.");

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "PageIndexCitations FlagOff Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PageIndexCitations FlagOff Prompt");
		assertNotNull("chatConfig is null", chatConfig);
		assertNotNull("promptConfig is null", promptConfig);

		/// Explicitly false (default) — the PageIndex branch must not fire.
		chatConfig.setValue("usePageIndex", false);
		chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
		assertNotNull("chatConfig update returned null", chatConfig);

		ChatRequest creq = buildChatRequest(chatConfig, promptConfig, doc, "How often is the signal strength logged?");
		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, creq);
		assertNotNull("getOpenAIRequest returned null", oreq);

		List<String> citations = ChatUtil.getDataCitations(testUser, oreq, creq);
		assertNotNull("Citations list is null", citations);
		logger.info("[FLAG-OFF] citations=" + citations.size() + " (expected 0 — doc was never vectorized, only PageIndex-built)");
		assertTrue("[FLAG-OFF] expected 0 citations with usePageIndex=false and no vector store, got " + citations.size(),
			citations.isEmpty());
	}

	@Test
	public void testPageIndexCitationsFlagOnYieldsPageIndexContent() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== testPageIndexCitationsFlagOnYieldsPageIndexContent ===");

		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/PageIndexCitations");
		BaseRecord testUser = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull("Test user is null", testUser);

		BaseRecord doc = buildIndexedDoc(testUser, octx, "citations-flag-on.txt",
			"Beacon Relay Station. The relay station boosts long-range radio signals between the coastal "
			+ "towns and the inland farming cooperative. A technician inspects the antenna mast every "
			+ "Tuesday and logs the signal strength in a paper ledger kept in the equipment shed.");

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "PageIndexCitations FlagOn Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PageIndexCitations FlagOn Prompt");
		assertNotNull("chatConfig is null", chatConfig);
		assertNotNull("promptConfig is null", promptConfig);

		chatConfig.setValue("usePageIndex", true);
		chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
		assertNotNull("chatConfig update returned null", chatConfig);

		String question = "How often is the signal strength logged, and where?";
		ChatRequest creq = buildChatRequest(chatConfig, promptConfig, doc, question);
		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, creq);
		assertNotNull("getOpenAIRequest returned null", oreq);

		List<String> citations = ChatUtil.getDataCitations(testUser, oreq, creq);
		assertNotNull("Citations list is null", citations);
		logger.info("[FLAG-ON] citations=" + citations.size());
		assertTrue("[FLAG-ON] expected at least 1 PageIndex-derived citation with usePageIndex=true, got " + citations.size(),
			!citations.isEmpty());

		/// Content-specific: the answer ("Tuesday", "ledger") lives in the one PageIndex leaf chunk.
		boolean matched = false;
		for (String cit : citations) {
			String lc = cit.toLowerCase();
			if (lc.contains("tuesday") || lc.contains("ledger")) {
				matched = true;
				logger.info("[FLAG-ON] matched citation: " + cit.substring(0, Math.min(300, cit.length())));
				break;
			}
		}
		assertTrue("[FLAG-ON] no citation contained the expected PageIndex content (\"Tuesday\"/\"ledger\")", matched);
	}
}
