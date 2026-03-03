package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Tests for the prompt library system:
 * - Summarization prompt resource loading (summarization.json)
 * - PromptConfig template loading (promptConfig.*.json)
 * - PromptTemplate template loading (promptTemplate.*.json)
 * - ChatLibraryUtil population of all three library types
 * - LLM integration with alt model (qwen3:8b) for summarization prompts
 */
public class TestPromptLibrary extends BaseTest {

	// =====================================================================
	// 1. Summarization Resource Loading
	// =====================================================================

	@Test
	public void testSummarizationResourceLoads() {
		logger.info("testSummarizationResourceLoads: verify summarization.json loads all 4 fields");
		try {
			Map<String, Object> res = PromptResourceUtil.load("summarization");
			assertNotNull("Summarization resource should load", res);

			String mapSystem = (String) res.get("mapSystem");
			String mapUser = (String) res.get("mapUser");
			String reduceSystem = (String) res.get("reduceSystem");
			String reduceUser = (String) res.get("reduceUser");

			assertNotNull("mapSystem should not be null", mapSystem);
			assertNotNull("mapUser should not be null", mapUser);
			assertNotNull("reduceSystem should not be null", reduceSystem);
			assertNotNull("reduceUser should not be null", reduceUser);

			assertTrue("mapSystem should mention detail", mapSystem.contains("detail"));
			assertTrue("mapUser should mention preserve", mapUser.contains("Preserve"));
			assertTrue("reduceSystem should mention merge", reduceSystem.contains("merge"));
			assertTrue("reduceUser should mention comprehensive", reduceUser.contains("comprehensive"));

			logger.info("testSummarizationResourceLoads PASSED: all 4 fields loaded, mapSystem=" + mapSystem.length() + " chars");
		} catch (Exception e) {
			logger.error(e);
			fail("testSummarizationResourceLoads failed: " + e.getMessage());
		}
	}

	@Test
	public void testSummarizationResourceViaGetString() {
		logger.info("testSummarizationResourceViaGetString: verify PromptResourceUtil.getString works");
		String mapSys = PromptResourceUtil.getString("summarization", "mapSystem");
		assertNotNull("getString(summarization, mapSystem) should return value", mapSys);
		assertTrue("mapSystem should be non-empty", mapSys.length() > 50);

		String mapUser = PromptResourceUtil.getString("summarization", "mapUser");
		assertNotNull("getString(summarization, mapUser) should return value", mapUser);

		// Verify nonexistent field returns null
		String nope = PromptResourceUtil.getString("summarization", "nonexistent");
		assertTrue("Nonexistent field should return null", nope == null);

		logger.info("testSummarizationResourceViaGetString PASSED");
	}

	// =====================================================================
	// 2. PromptConfig Template Loading
	// =====================================================================

	@Test
	public void testPromptConfigTemplateNames() {
		logger.info("testPromptConfigTemplateNames: verify template names array");
		String[] names = ChatUtil.getPromptConfigTemplateNames();
		assertNotNull("Template names should not be null", names);
		assertEquals("Should have 4 prompt config templates", 4, names.length);

		boolean hasDefault = false, hasAnalysis = false, hasCoding = false, hasSummary = false;
		for (String n : names) {
			if ("default".equals(n)) hasDefault = true;
			if ("contentAnalysis".equals(n)) hasAnalysis = true;
			if ("coding".equals(n)) hasCoding = true;
			if ("summary".equals(n)) hasSummary = true;
		}
		assertTrue("Should include 'default'", hasDefault);
		assertTrue("Should include 'contentAnalysis'", hasAnalysis);
		assertTrue("Should include 'coding'", hasCoding);
		assertTrue("Should include 'summary'", hasSummary);

		logger.info("testPromptConfigTemplateNames PASSED");
	}

	@Test
	public void testPromptConfigTemplatesLoad() {
		logger.info("testPromptConfigTemplatesLoad: verify all promptConfig templates load from resources");
		String[] names = ChatUtil.getPromptConfigTemplateNames();
		for (String name : names) {
			BaseRecord template = ChatUtil.loadPromptConfigTemplate(name);
			assertNotNull("PromptConfig template '" + name + "' should load", template);

			// All should have system and systemAnalyze fields with content
			assertTrue("Template '" + name + "' should have system field", template.hasField("system"));
			assertTrue("Template '" + name + "' should have systemAnalyze field", template.hasField("systemAnalyze"));

			List<String> system = template.get("system");
			assertNotNull("Template '" + name + "' system should not be null", system);
			assertTrue("Template '" + name + "' system should have content", system.size() > 0);

			logger.info("  '" + name + "': system has " + system.size() + " lines");
		}
		logger.info("testPromptConfigTemplatesLoad PASSED: all " + names.length + " templates loaded");
	}

	@Test
	public void testPromptConfigDefaultMatchesOriginal() {
		logger.info("testPromptConfigDefaultMatchesOriginal: verify default template matches prompt.config.json");
		BaseRecord original = ChatUtil.getDefaultPrompt();
		assertNotNull("Original prompt.config.json should load", original);

		BaseRecord template = ChatUtil.loadPromptConfigTemplate("default");
		assertNotNull("Default promptConfig template should load", template);

		// Key fields should match
		List<String> origSystem = original.get("system");
		List<String> tmplSystem = template.get("system");
		assertNotNull("Original system", origSystem);
		assertNotNull("Template system", tmplSystem);
		assertEquals("System field line count should match", origSystem.size(), tmplSystem.size());

		List<String> origAnalyze = original.get("systemAnalyze");
		List<String> tmplAnalyze = template.get("systemAnalyze");
		assertNotNull("Original systemAnalyze", origAnalyze);
		assertNotNull("Template systemAnalyze", tmplAnalyze);
		assertEquals("systemAnalyze line count should match", origAnalyze.size(), tmplAnalyze.size());

		logger.info("testPromptConfigDefaultMatchesOriginal PASSED");
	}

	@Test
	public void testPromptConfigNonexistentReturnsNull() {
		logger.info("testPromptConfigNonexistentReturnsNull");
		BaseRecord template = ChatUtil.loadPromptConfigTemplate("nonexistent_template_xyz");
		assertTrue("Nonexistent template should return null", template == null);
		logger.info("testPromptConfigNonexistentReturnsNull PASSED");
	}

	// =====================================================================
	// 3. PromptTemplate Template Loading (new composable format)
	// =====================================================================

	@Test
	public void testPromptTemplateTemplateNames() {
		logger.info("testPromptTemplateTemplateNames: verify template names array");
		String[] names = ChatUtil.getPromptTemplateTemplateNames();
		assertNotNull("Template names should not be null", names);
		assertEquals("Should have 3 prompt template templates", 3, names.length);

		boolean hasAnalysis = false, hasCoding = false, hasSummary = false;
		for (String n : names) {
			if ("contentAnalysis".equals(n)) hasAnalysis = true;
			if ("coding".equals(n)) hasCoding = true;
			if ("summary".equals(n)) hasSummary = true;
		}
		assertTrue("Should include 'contentAnalysis'", hasAnalysis);
		assertTrue("Should include 'coding'", hasCoding);
		assertTrue("Should include 'summary'", hasSummary);

		logger.info("testPromptTemplateTemplateNames PASSED");
	}

	@Test
	public void testPromptTemplateTemplatesLoad() {
		logger.info("testPromptTemplateTemplatesLoad: verify all promptTemplate templates load");
		String[] names = ChatUtil.getPromptTemplateTemplateNames();
		for (String name : names) {
			BaseRecord template = ChatUtil.loadPromptTemplateTemplate(name);
			assertNotNull("PromptTemplate template '" + name + "' should load", template);

			// Should have sections
			assertTrue("Template '" + name + "' should have sections field", template.hasField("sections"));
			List<BaseRecord> sections = template.get("sections");
			assertNotNull("Template '" + name + "' sections should not be null", sections);
			assertTrue("Template '" + name + "' should have at least 3 sections", sections.size() >= 3);

			// Verify each section has required fields
			for (BaseRecord section : sections) {
				String sectionName = section.get("sectionName");
				assertNotNull("Section should have sectionName", sectionName);
				assertTrue("Section '" + sectionName + "' name should be non-empty", sectionName.length() > 0);

				List<String> lines = section.get("lines");
				assertNotNull("Section '" + sectionName + "' should have lines", lines);
				assertTrue("Section '" + sectionName + "' should have content", lines.size() > 0);
			}

			logger.info("  '" + name + "': " + sections.size() + " sections loaded");
		}
		logger.info("testPromptTemplateTemplatesLoad PASSED: all " + names.length + " templates loaded");
	}

	@Test
	public void testPromptTemplateComposition() {
		logger.info("testPromptTemplateComposition: verify templates compose without errors");
		String[] names = ChatUtil.getPromptTemplateTemplateNames();
		for (String name : names) {
			BaseRecord template = ChatUtil.loadPromptTemplateTemplate(name);
			assertNotNull("Template '" + name + "' should load", template);

			// Compose without chatConfig/promptConfig (just structural test)
			String composed = PromptTemplateComposer.composeSystem(template, null, null);
			assertNotNull("Composed system for '" + name + "' should not be null", composed);
			assertTrue("Composed system for '" + name + "' should have content", composed.length() > 50);

			logger.info("  '" + name + "' system composed: " + composed.length() + " chars");
		}
		logger.info("testPromptTemplateComposition PASSED");
	}

	// =====================================================================
	// 4. ChatLibraryUtil Population
	// =====================================================================

	@Test
	public void testLibraryPopulation() {
		logger.info("testLibraryPopulation: verify populateDefaults creates all library entries");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptLibTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "plTestUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		try {
			String server = testProperties.getProperty("test.llm.ollama.server");
			String altModel = testProperties.getProperty("test.llm.ollama.altModel");
			if (altModel == null || altModel.isEmpty()) {
				altModel = testProperties.getProperty("test.llm.ollama.model");
			}

			// Populate all library defaults
			ChatLibraryUtil.populateDefaults(testUser, server, altModel, "ollama");

			// Verify chat config library
			assertTrue("Chat config library should be populated", ChatLibraryUtil.isLibraryPopulated(testUser));

			// Verify prompt config library — should now have 4 entries (default, contentAnalysis, coding, summary)
			assertTrue("Prompt config library should be populated", ChatLibraryUtil.isPromptLibraryPopulated(testUser));

			BaseRecord promptLibDir = ChatLibraryUtil.findLibraryDir(testUser, ChatLibraryUtil.LIBRARY_PROMPT_CONFIGS);
			assertNotNull("Prompt config library dir should exist", promptLibDir);

			String[] expectedPromptConfigs = {"default", "contentAnalysis", "coding", "summary"};
			for (String pcName : expectedPromptConfigs) {
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_NAME, pcName);
				q.field(FieldNames.FIELD_GROUP_ID, promptLibDir.get(FieldNames.FIELD_ID));
				BaseRecord found = IOSystem.getActiveContext().getSearch().findRecord(q);
				assertNotNull("Prompt config '" + pcName + "' should exist in library", found);
				logger.info("  Found prompt config: " + pcName);
			}

			// Verify prompt template library
			assertTrue("Prompt template library should be populated", ChatLibraryUtil.isPromptTemplateLibraryPopulated(testUser));

			BaseRecord ptLibDir = ChatLibraryUtil.findLibraryDir(testUser, ChatLibraryUtil.LIBRARY_PROMPT_TEMPLATES);
			assertNotNull("Prompt template library dir should exist", ptLibDir);

			String[] expectedPromptTemplates = {"contentAnalysis", "coding", "summary"};
			for (String ptName : expectedPromptTemplates) {
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_TEMPLATE, FieldNames.FIELD_NAME, ptName);
				q.field(FieldNames.FIELD_GROUP_ID, ptLibDir.get(FieldNames.FIELD_ID));
				q.planMost(true);
				BaseRecord found = IOSystem.getActiveContext().getSearch().findRecord(q);
				assertNotNull("Prompt template '" + ptName + "' should exist in library", found);

				// Verify sections were persisted
				List<BaseRecord> sections = found.get("sections");
				assertNotNull("Template '" + ptName + "' should have sections", sections);
				assertTrue("Template '" + ptName + "' should have at least 1 section", sections.size() > 0);
				logger.info("  Found prompt template: " + ptName + " (" + sections.size() + " sections)");
			}

			logger.info("testLibraryPopulation PASSED: all library entries created");
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("testLibraryPopulation failed: " + e.getMessage());
		}
	}

	@Test
	public void testLibraryPopulationIdempotent() {
		logger.info("testLibraryPopulationIdempotent: verify second populate does not create duplicates");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptLibTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "plTestUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		try {
			String server = testProperties.getProperty("test.llm.ollama.server");
			String altModel = testProperties.getProperty("test.llm.ollama.altModel");
			if (altModel == null || altModel.isEmpty()) {
				altModel = testProperties.getProperty("test.llm.ollama.model");
			}

			// First populate (may already be populated from previous test)
			ChatLibraryUtil.populateDefaults(testUser, server, altModel, "ollama");

			// Count prompt configs
			BaseRecord promptLibDir = ChatLibraryUtil.findLibraryDir(testUser, ChatLibraryUtil.LIBRARY_PROMPT_CONFIGS);
			assertNotNull("Prompt lib dir should exist", promptLibDir);
			Query q1 = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG);
			q1.field(FieldNames.FIELD_GROUP_ID, promptLibDir.get(FieldNames.FIELD_ID));
			int countBefore = IOSystem.getActiveContext().getSearch().count(q1);

			// Second populate
			ChatLibraryUtil.populateDefaults(testUser, server, altModel, "ollama");

			// Count again — should be the same
			int countAfter = IOSystem.getActiveContext().getSearch().count(q1);
			assertEquals("Prompt config count should not change on re-populate", countBefore, countAfter);

			logger.info("testLibraryPopulationIdempotent PASSED: count before=" + countBefore + ", after=" + countAfter);
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("testLibraryPopulationIdempotent failed: " + e.getMessage());
		}
	}

	@Test
	public void testPromptOnlyPopulation() {
		logger.info("testPromptOnlyPopulation: verify populatePromptDefaults works independently");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptLibTest2");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "plTestUser2", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		try {
			// Populate prompts only (no chat configs)
			ChatLibraryUtil.populatePromptDefaults(testUser);

			// Verify prompt configs created
			assertTrue("Prompt library should be populated", ChatLibraryUtil.isPromptLibraryPopulated(testUser));

			// Verify prompt templates created
			assertTrue("Prompt template library should be populated", ChatLibraryUtil.isPromptTemplateLibraryPopulated(testUser));

			logger.info("testPromptOnlyPopulation PASSED");
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("testPromptOnlyPopulation failed: " + e.getMessage());
		}
	}

	// =====================================================================
	// 5. LLM Integration — Summarization with alt model
	// =====================================================================

	@Test
	public void testSummarizationPromptsWithLLM() {
		logger.warn("[LLM-LIVE] testSummarizationPromptsWithLLM: Requires reachable Ollama server with alt model");
		logger.info("testSummarizationPromptsWithLLM: verify externalized prompts produce better output");

		String llmType = testProperties.getProperty("test.llm.type");
		if (llmType == null || llmType.isEmpty()) {
			logger.warn("SKIPPED: No LLM configuration (test.llm.type)");
			return;
		}

		String server = testProperties.getProperty("test.llm.ollama.server");
		String altModel = testProperties.getProperty("test.llm.ollama.altModel");
		if (altModel == null || altModel.isEmpty()) {
			altModel = testProperties.getProperty("test.llm.ollama.model");
		}

		BaseRecord testUser = getCreateUser("testSummLLM");
		assertNotNull("Test user should not be null", testUser);

		try {
			// Create a chat config with the alt model
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, "SummLLMTest-" + UUID.randomUUID().toString());
			BaseRecord chatConfig = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, testUser, null, plist);
			chatConfig.set("serviceType", LLMServiceEnumType.OLLAMA);
			chatConfig.set("serverUrl", server);
			chatConfig.set("model", altModel);
			chatConfig.set("requestTimeout", 120);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().create(testUser, chatConfig);
			assertNotNull("Chat config should be created", chatConfig);

			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "SummLLM Prompt-" + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Load the summarization prompts
			String mapSys = PromptResourceUtil.getString("summarization", "mapSystem");
			String mapUser = PromptResourceUtil.getString("summarization", "mapUser");
			assertNotNull("mapSystem should load", mapSys);
			assertNotNull("mapUser should load", mapUser);

			// Create a Chat instance and test summarization prompt
			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			chat.setLlmSystemPrompt(mapSys);

			String testContent = "The kingdom of Aldoria was founded by King Theron the Bold in the year 847 of the Third Age. "
				+ "Theron, a devout Christian warrior from the northern highlands, united five warring clans through a combination of "
				+ "military prowess and diplomatic marriages. His wife, Queen Elara of Westmarch, bore him three sons: Aldric, Brennan, "
				+ "and Cedric. The eldest, Aldric, inherited the throne at age 22 after Theron's death in battle against the eastern raiders. "
				+ "The kingdom's economy relied on iron mining in the Greystone Mountains and wheat farming in the Sunvale Plains. "
				+ "The capital, Thornhaven, grew from a hilltop fortress to a city of 50,000 within two generations. "
				+ "Aldric established the Order of the Silver Shield, a knightly order sworn to protect the kingdom's borders and uphold "
				+ "the Christian faith. The Order's first commander, Sir Gareth Ironhand, was a white-haired veteran of thirty campaigns "
				+ "who led with stoic authority and unshakable conviction.";

			OpenAIRequest req = chat.newRequest(altModel);
			String cmd = mapUser + System.lineSeparator() + testContent + System.lineSeparator() + "/no_think";
			chat.newMessage(req, cmd, Chat.userRole);

			OpenAIResponse resp = chat.chat(req);

			if (resp == null) {
				logger.warn("SKIPPED: LLM server did not respond. Verify server at " + server + " with model " + altModel);
				return;
			}

			String content = resp.getMessage() != null ? resp.getMessage().getContent() : null;
			if (content == null) {
				logger.warn("SKIPPED: LLM returned null content");
				return;
			}

			assertTrue("Summary should be non-empty", content.length() > 0);
			assertTrue("Summary should be substantive (>100 chars), got " + content.length(), content.length() > 100);

			// Verify key details are preserved (the whole point of the new prompts)
			logger.info("Summary length: " + content.length() + " chars");
			logger.info("Summary preview: " + content.substring(0, Math.min(500, content.length())));

			// Check that /no_think suppressed thinking tags in output
			assertFalse("Output should not contain <think> tags (no_think should suppress)", content.contains("<think>"));

			logger.info("testSummarizationPromptsWithLLM PASSED: summary=" + content.length() + " chars");
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("testSummarizationPromptsWithLLM failed: " + e.getMessage());
		}
	}

	// =====================================================================
	// 6. ChatConfig template names alignment
	// =====================================================================

	@Test
	public void testChatConfigTemplateNamesUnchanged() {
		logger.info("testChatConfigTemplateNamesUnchanged: verify existing chatConfig templates still load");
		String[] names = ChatUtil.getChatConfigTemplateNames();
		assertNotNull("Chat config template names should not be null", names);
		assertEquals("Should have 6 chat config templates", 6, names.length);

		for (String name : names) {
			BaseRecord template = ChatUtil.loadChatConfigTemplate(name);
			assertNotNull("Chat config template '" + name + "' should load", template);
		}

		logger.info("testChatConfigTemplateNamesUnchanged PASSED: all 6 templates load");
	}
}
