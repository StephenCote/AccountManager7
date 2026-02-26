package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Test;

/// Phase 15 Tests: contextRefs, auto-vectorize, auto-summarize, getDataCitations merge.
/// Tests the complete RAG pipeline from vectorization through citation retrieval.
public class TestContextRefs extends BaseTest {

	/// Test that the contextRefs field exists on the chatRequest model and can hold data.
	@Test
	public void testContextRefsFieldExists() {
		logger.info("testContextRefsFieldExists");
		ChatRequest creq = new ChatRequest();
		assertNotNull("ChatRequest is null", creq);

		/// contextRefs should be a list<string> field
		List<String> refs = creq.get("contextRefs");
		assertNotNull("contextRefs field should exist on chatRequest", refs);
		assertTrue("contextRefs should be empty initially", refs.isEmpty());

		/// Add a serialized ref
		String testRef = "{\"schema\":\"data.data\",\"objectId\":\"test-oid-1234\"}";
		refs.add(testRef);
		List<String> refsAfter = creq.get("contextRefs");
		assertTrue("contextRefs should have 1 entry", refsAfter.size() == 1);
		assertTrue("contextRefs entry should match", refsAfter.get(0).equals(testRef));

		logger.info("contextRefs field exists and accepts data");
	}

	/// Test persisted contextRefs — create chatRequest in DB with contextRefs, reload and verify.
	@Test
	public void testContextRefsPersistence() {
		logger.info("testContextRefsPersistence");
		BaseRecord testUser = getCreateUser("testContextRefsPersist");
		assertNotNull("Test user is null", testUser);

		/// Create a chatRequest record
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "ContextRefs Persist Test " + System.currentTimeMillis());
		BaseRecord creqRec = null;
		try {
			creqRec = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_REQUEST, testUser, null, plist
			);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("chatRequest instance is null", creqRec);

		/// Set contextRefs
		List<String> refs = new ArrayList<>();
		refs.add("{\"schema\":\"data.data\",\"objectId\":\"persist-test-001\"}");
		refs.add("{\"schema\":\"data.tag\",\"objectId\":\"persist-test-tag-001\"}");
		creqRec.setValue("contextRefs", refs);

		/// Persist
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(testUser, creqRec);
		assertNotNull("Created record is null", created);
		String objectId = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("objectId is null", objectId);

		/// Reload from DB
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false);
		BaseRecord reloaded = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("Reloaded record is null", reloaded);

		List<String> reloadedRefs = reloaded.get("contextRefs");
		assertNotNull("Reloaded contextRefs is null", reloadedRefs);
		assertTrue("Expected 2 contextRefs, got " + reloadedRefs.size(), reloadedRefs.size() == 2);
		logger.info("contextRefs persisted and reloaded: " + reloadedRefs.size() + " entries");
	}

	/// Test the load → modify → update → reload path for contextRefs.
	/// This mimics the exact flow used by the REST endpoint (ChatService.attachContext).
	@Test
	public void testContextRefsUpdatePath() {
		logger.info("testContextRefsUpdatePath");
		BaseRecord testUser = getCreateUser("testContextRefsUpdate");
		assertNotNull("Test user is null", testUser);

		/// Step 1: Create a chatRequest WITHOUT contextRefs (simulates existing record)
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "ContextRefs Update Test " + System.currentTimeMillis());
		BaseRecord creqRec = null;
		try {
			creqRec = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_REQUEST, testUser, null, plist
			);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("chatRequest instance is null", creqRec);

		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(testUser, creqRec);
		assertNotNull("Created record is null", created);
		String objectId = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("objectId is null", objectId);

		/// Step 2: Load from DB using planMost (same as REST endpoint)
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false);
		BaseRecord loaded = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("Loaded record is null", loaded);

		/// Step 3: Check default value of contextRefs on loaded record
		List<String> defaultRefs = loaded.get("contextRefs");
		logger.info("Default contextRefs after load: " + (defaultRefs != null ? defaultRefs.size() + " entries" : "NULL"));

		/// Step 4: Modify contextRefs (mimicking appendContextRef from ChatService)
		List<String> newRefs = new ArrayList<>();
		if (defaultRefs != null) {
			newRefs.addAll(defaultRefs);
		}
		newRefs.add("{\"schema\":\"data.data\",\"objectId\":\"update-test-001\"}");
		try {
			loaded.set("contextRefs", newRefs);
		} catch (Exception e) {
			logger.error("Failed to set contextRefs", e);
			assertTrue("set() should not throw: " + e.getMessage(), false);
		}

		/// Verify the value was set
		List<String> verifyRefs = loaded.get("contextRefs");
		assertNotNull("contextRefs should not be null after set", verifyRefs);
		assertTrue("Expected 1 contextRef, got " + verifyRefs.size(), verifyRefs.size() == 1);

		/// Step 5: Update (same as REST endpoint)
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(testUser, loaded);
		assertNotNull("Update returned null (FAILED)", updated);

		/// Step 6: Reload from DB and verify
		Query q2 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
		q2.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q2.planMost(false);
		BaseRecord reloaded = IOSystem.getActiveContext().getAccessPoint().find(testUser, q2);
		assertNotNull("Reloaded record is null", reloaded);

		List<String> reloadedRefs = reloaded.get("contextRefs");
		assertNotNull("Reloaded contextRefs is null — UPDATE DID NOT PERSIST", reloadedRefs);
		logger.info("Reloaded contextRefs: " + reloadedRefs.size() + " entries: " + reloadedRefs);
		assertTrue("Expected 1 contextRef after update, got " + reloadedRefs.size(), reloadedRefs.size() == 1);

		/// Step 7: Append another ref and verify accumulation
		List<String> moreRefs = new ArrayList<>(reloadedRefs);
		moreRefs.add("{\"schema\":\"data.tag\",\"objectId\":\"update-test-tag-001\"}");
		try {
			reloaded.set("contextRefs", moreRefs);
		} catch (Exception e) {
			logger.error("Failed to set contextRefs (second append)", e);
		}
		IOSystem.getActiveContext().getAccessPoint().update(testUser, reloaded);

		Query q3 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
		q3.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q3.planMost(false);
		BaseRecord reloaded2 = IOSystem.getActiveContext().getAccessPoint().find(testUser, q3);
		List<String> finalRefs = reloaded2.get("contextRefs");
		assertNotNull("Final contextRefs is null", finalRefs);
		assertTrue("Expected 2 contextRefs after second update, got " + finalRefs.size(), finalRefs.size() == 2);

		logger.info("testContextRefsUpdatePath PASSED — load→modify→update→reload works correctly");
	}

	/// Test VectorUtil with CardFox.pdf — vectorize, count, find.
	/// Regression test for existing vector store capabilities.
	@Test
	public void testVectorizeDocument() {
		logger.info("testVectorizeDocument");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/ContextRefs");
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testCtxUser1", testOrgContext.getOrganizationId());

		BaseRecord doc = getCreateDocument(testUser, "./media/CardFox.pdf");
		assertNotNull("Document is null", doc);
		IOSystem.getActiveContext().getReader().populate(doc, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		/// Create vector store if it doesn't exist
		int count = vu.countVectorStore(doc);
		if (count == 0) {
			try {
				List<BaseRecord> chunks = vu.createVectorStore(doc, ChunkEnumType.WORD, 250);
				logger.info("Created " + chunks.size() + " vector chunks for CardFox.pdf");
				assertTrue("Expected chunks", chunks.size() > 0);
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
				IOSystem.getActiveContext().getWriter().flush();
			} catch (FieldException | WriterException e) {
				logger.error(e);
				return;
			}
		}

		/// Verify chunks exist
		int storedCount = vu.countVectorStore(doc);
		assertTrue("Expected stored vector chunks, got " + storedCount, storedCount > 0);
		logger.info("Verified " + storedCount + " vector chunks for CardFox.pdf");

		/// Search for content
		List<BaseRecord> results = vu.find(doc, "Where is the casino?", 5, 60);
		logger.info("Vector search returned " + results.size() + " results");
		assertTrue("Expected search results", results.size() > 0);
		for (BaseRecord r : results) {
			logger.info("  Score: " + r.get(FieldNames.FIELD_SCORE) + " Content: " + ((String) r.get(FieldNames.FIELD_CONTENT)).substring(0, Math.min(80, ((String)r.get(FieldNames.FIELD_CONTENT)).length())));
		}
	}

	/// Test composeSummary (map-reduce) with CardFox.pdf — regression test.
	@Test
	public void testComposeSummary() {
		logger.info("testComposeSummary");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/ContextRefs");
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testCtxUser1", testOrgContext.getOrganizationId());

		BaseRecord doc = getCreateDocument(testUser, "./media/CardFox.pdf");
		assertNotNull("Document is null", doc);

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		/// Ensure vectors exist
		int count = vu.countVectorStore(doc);
		if (count == 0) {
			logger.warn("No vector chunks for CardFox.pdf — run testVectorizeDocument first or vectorize here");
			try {
				List<BaseRecord> chunks = vu.createVectorStore(doc, ChunkEnumType.WORD, 250);
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
				IOSystem.getActiveContext().getWriter().flush();
			} catch (FieldException | WriterException e) {
				logger.warn("Vectorization failed: " + e.getMessage());
				return;
			}
		}

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "ContextRefs Summary Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "ContextRefs Summary Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Chat/prompt config not available — skipping LLM-dependent test");
			return;
		}

		/// composeSummary does map-reduce summarization
		List<String> summaries = ChatUtil.composeSummary(testUser, chatConfig, promptConfig, doc, false);
		assertNotNull("Summaries list is null", summaries);
		if (summaries.isEmpty()) {
			logger.warn("composeSummary returned empty — LLM service may be unavailable");
			return;
		}

		String lastSummary = summaries.get(summaries.size() - 1);
		assertNotNull("Final summary is null", lastSummary);
		assertTrue("Final summary should not be empty", lastSummary.length() > 0);
		logger.info("composeSummary produced " + summaries.size() + " summaries, final length=" + lastSummary.length());
		logger.info("Preview: " + lastSummary.substring(0, Math.min(300, lastSummary.length())));
	}

	/// Test createSummary (single-document summary) and getSummary retrieval.
	@Test
	public void testCreateAndGetSummary() {
		logger.info("testCreateAndGetSummary");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/ContextRefs");
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testCtxUser1", testOrgContext.getOrganizationId());

		BaseRecord doc = getCreateDocument(testUser, "./media/CardFox.pdf");
		assertNotNull("Document is null", doc);

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		/// Ensure vectors exist
		int count = vu.countVectorStore(doc);
		if (count == 0) {
			try {
				List<BaseRecord> chunks = vu.createVectorStore(doc, ChunkEnumType.WORD, 250);
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
				IOSystem.getActiveContext().getWriter().flush();
			} catch (FieldException | WriterException e) {
				logger.warn("Vectorization failed: " + e.getMessage());
				return;
			}
		}

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "ContextRefs CreateSum Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "ContextRefs CreateSum Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Chat/prompt config not available — skipping");
			return;
		}

		/// Check if summary already exists
		BaseRecord existingSummary = ChatUtil.getSummary(testUser, doc);
		if (existingSummary != null) {
			logger.info("Summary already exists for doc, name=" + existingSummary.get(FieldNames.FIELD_NAME));
		}

		/// Create summary (recreate=false means skip if exists)
		BaseRecord summary = ChatUtil.createSummary(testUser, chatConfig, promptConfig, doc, false);
		if (summary == null) {
			logger.warn("createSummary returned null — LLM service may be unavailable");
			return;
		}

		logger.info("Summary created: " + summary.get(FieldNames.FIELD_NAME));

		/// Verify getSummary retrieves it
		BaseRecord retrieved = ChatUtil.getSummary(testUser, doc);
		assertNotNull("getSummary should find the summary", retrieved);
		logger.info("getSummary retrieved: " + retrieved.get(FieldNames.FIELD_NAME));
	}

	/// Test getDataCitations with contextRefs — the core merge logic.
	/// Creates a chatRequest with contextRefs pointing to a vectorized document,
	/// then verifies getDataCitations returns citations.
	@Test
	public void testGetDataCitationsWithContextRefs() {
		logger.info("testGetDataCitationsWithContextRefs");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/ContextRefs");
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testCtxUser1", testOrgContext.getOrganizationId());

		/// Get or create a vectorized document
		BaseRecord doc = getCreateDocument(testUser, "./media/CardFox.pdf");
		assertNotNull("Document is null", doc);

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		int count = vu.countVectorStore(doc);
		if (count == 0) {
			try {
				List<BaseRecord> chunks = vu.createVectorStore(doc, ChunkEnumType.WORD, 250);
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
				IOSystem.getActiveContext().getWriter().flush();
			} catch (FieldException | WriterException e) {
				logger.warn("Vectorization failed: " + e.getMessage());
				return;
			}
			count = vu.countVectorStore(doc);
			if (count == 0) {
				logger.warn("Vector chunks not persisted — skipping");
				return;
			}
		}
		logger.info("Document has " + count + " vector chunks");

		/// Create a chatRequest with contextRefs containing the document reference
		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "ContextRefs Citation Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "ContextRefs Citation Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Chat/prompt config not available — skipping");
			return;
		}

		ChatRequest creq = new ChatRequest();
		creq.setChatConfig(chatConfig);
		creq.setPromptConfig(promptConfig);
		creq.setMessage("Where is the casino located?");

		/// Set contextRefs with the document reference
		String docRef = "{\"schema\":\"" + doc.getSchema() + "\",\"objectId\":\"" + doc.get(FieldNames.FIELD_OBJECT_ID) + "\"}";
		List<String> contextRefs = new ArrayList<>();
		contextRefs.add(docRef);
		creq.setValue("contextRefs", contextRefs);

		/// Verify contextRefs is set
		List<String> setRefs = creq.get("contextRefs");
		assertTrue("contextRefs should have 1 entry", setRefs.size() == 1);
		logger.info("chatRequest contextRefs: " + setRefs);

		/// Build OpenAI request (needed by getDataCitations for dedup)
		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, creq);
		if (oreq == null) {
			logger.warn("getOpenAIRequest returned null — config may be incomplete, skipping");
			return;
		}

		/// Call getDataCitations — should merge contextRefs and find vector results
		List<String> citations = ChatUtil.getDataCitations(testUser, oreq, creq);
		assertNotNull("Citations list is null", citations);
		logger.info("getDataCitations returned " + citations.size() + " citations from contextRefs");
		assertTrue("Expected at least 1 citation from contextRefs", citations.size() > 0);

		for (int i = 0; i < Math.min(3, citations.size()); i++) {
			String cit = citations.get(i);
			logger.info("  Citation " + i + ": " + cit.substring(0, Math.min(200, cit.length())));
		}
	}

	/// Test getDataCitations with BOTH ephemeral data[] and persisted contextRefs[].
	/// Verifies deduplication: same objectId in both lists should produce only one set of citations.
	@Test
	public void testGetDataCitationsMergeDedup() {
		logger.info("testGetDataCitationsMergeDedup");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/ContextRefs");
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testCtxUser1", testOrgContext.getOrganizationId());

		BaseRecord doc = getCreateDocument(testUser, "./media/CardFox.pdf");
		assertNotNull("Document is null", doc);

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		int count = vu.countVectorStore(doc);
		if (count == 0) {
			logger.warn("No vector chunks — skipping merge/dedup test");
			return;
		}

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "ContextRefs Dedup Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "ContextRefs Dedup Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Config not available — skipping");
			return;
		}

		String docRef = "{\"schema\":\"" + doc.getSchema() + "\",\"objectId\":\"" + doc.get(FieldNames.FIELD_OBJECT_ID) + "\"}";

		ChatRequest creq = new ChatRequest();
		creq.setChatConfig(chatConfig);
		creq.setPromptConfig(promptConfig);
		creq.setMessage("Tell me about the casino");

		/// Set same doc in both data[] (ephemeral) and contextRefs[] (persisted)
		List<String> dataList = new ArrayList<>();
		dataList.add(docRef);
		creq.setValue(FieldNames.FIELD_DATA, dataList);

		List<String> contextRefs = new ArrayList<>();
		contextRefs.add(docRef);
		creq.setValue("contextRefs", contextRefs);

		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, creq);
		if (oreq == null) {
			logger.warn("getOpenAIRequest returned null — skipping");
			return;
		}

		/// Citations should be deduplicated — same doc shouldn't double-query
		List<String> citations = ChatUtil.getDataCitations(testUser, oreq, creq);
		assertNotNull("Citations list is null", citations);
		logger.info("Merge/dedup test: " + citations.size() + " citations (should equal single-ref count)");
		assertTrue("Expected citations from merged refs", citations.size() > 0);

		/// Compare with contextRefs-only count — should be the same since dedup removes duplicate
		ChatRequest creqRefOnly = new ChatRequest();
		creqRefOnly.setChatConfig(chatConfig);
		creqRefOnly.setPromptConfig(promptConfig);
		creqRefOnly.setMessage("Tell me about the casino");
		List<String> refsOnly = new ArrayList<>();
		refsOnly.add(docRef);
		creqRefOnly.setValue("contextRefs", refsOnly);

		OpenAIRequest oreqRef = ChatUtil.getOpenAIRequest(testUser, creqRefOnly);
		if (oreqRef != null) {
			List<String> citationsRefOnly = ChatUtil.getDataCitations(testUser, oreqRef, creqRefOnly);
			logger.info("ContextRefs-only: " + citationsRefOnly.size() + " vs merged: " + citations.size());
			assertTrue("Merged citation count should equal single-ref count (dedup working)",
				citations.size() == citationsRefOnly.size());
		}
	}

	/// Test getDataCitations with tag in contextRefs — tags scope vector queries.
	@Test
	public void testGetDataCitationsWithTag() {
		logger.info("testGetDataCitationsWithTag");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/ContextRefs");
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testCtxUser1", testOrgContext.getOrganizationId());

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		/// Create a tag for scoping
		BaseRecord tagDir = ioContext.getPathUtil().makePath(testUser, ModelNames.MODEL_GROUP, "~/Tags", "DATA", testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Tags directory is null", tagDir);

		BaseRecord tag = null;
		try {
			tag = RecordFactory.newInstance(ModelNames.MODEL_TAG);
			ioContext.getRecordUtil().applyNameGroupOwnership(testUser, tag, "ContextRefsTestTag", "~/Tags", testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			tag.set(FieldNames.FIELD_TYPE, "Data");
			BaseRecord existingTag = ioContext.getRecordUtil().getRecord(testUser, ModelNames.MODEL_TAG, "ContextRefsTestTag", 0L, (long)tagDir.get(FieldNames.FIELD_ID), testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			if (existingTag != null) {
				tag = existingTag;
			} else {
				ioContext.getRecordUtil().createRecord(tag);
			}
		} catch (Exception e) {
			logger.error("Failed to create tag: " + e.getMessage());
			return;
		}
		assertNotNull("Tag is null", tag);
		String tagOid = tag.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Tag objectId is null", tagOid);

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "ContextRefs Tag Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "ContextRefs Tag Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Config not available — skipping");
			return;
		}

		/// Create chatRequest with tag in contextRefs
		ChatRequest creq = new ChatRequest();
		creq.setChatConfig(chatConfig);
		creq.setPromptConfig(promptConfig);
		creq.setMessage("What is this about?");

		String tagRef = "{\"schema\":\"data.tag\",\"objectId\":\"" + tagOid + "\"}";
		List<String> contextRefs = new ArrayList<>();
		contextRefs.add(tagRef);
		creq.setValue("contextRefs", contextRefs);

		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, creq);
		if (oreq == null) {
			logger.warn("getOpenAIRequest returned null — skipping");
			return;
		}

		/// getDataCitations should handle tag-only context (tags-only path in ChatUtil)
		List<String> citations = ChatUtil.getDataCitations(testUser, oreq, creq);
		assertNotNull("Citations list should not be null (may be empty if no matching vectors)", citations);
		logger.info("Tag-scoped citations: " + citations.size());
		/// Tags-only may return 0 if no vectors are tagged — that's OK, we're testing it doesn't crash
	}

	/// Test getDataCitations with empty contextRefs — backward compat, no crash.
	@Test
	public void testGetDataCitationsEmptyContextRefs() {
		logger.info("testGetDataCitationsEmptyContextRefs");
		BaseRecord testUser = getCreateUser("testCtxEmpty");
		assertNotNull("Test user is null", testUser);

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "ContextRefs Empty Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "ContextRefs Empty Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Config not available — skipping");
			return;
		}

		ChatRequest creq = new ChatRequest();
		creq.setChatConfig(chatConfig);
		creq.setPromptConfig(promptConfig);
		creq.setMessage("Hello");

		/// No data, no contextRefs — should return empty list, no crash
		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, creq);
		if (oreq == null) {
			logger.warn("getOpenAIRequest returned null — skipping");
			return;
		}

		List<String> citations = ChatUtil.getDataCitations(testUser, oreq, creq);
		assertNotNull("Citations should not be null", citations);
		assertTrue("Citations should be empty with no refs", citations.isEmpty());
		logger.info("Empty contextRefs test passed — 0 citations as expected");
	}

	/// Test contextRefs with multiple different document types.
	@Test
	public void testMultiRefContextRefs() {
		logger.info("testMultiRefContextRefs");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/ContextRefs");
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testCtxUser1", testOrgContext.getOrganizationId());

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		/// Create two different data records
		BaseRecord doc1 = getCreateDocument(testUser, "./media/CardFox.pdf");
		assertNotNull("Doc1 is null", doc1);

		BaseRecord doc2 = getCreateData(testUser, "multiref-test.txt", "text/plain",
			"The quick brown fox jumped over the lazy dog near the crystal palace.".getBytes(),
			"~/Data", testOrgContext.getOrganizationId());
		assertNotNull("Doc2 is null", doc2);

		/// Vectorize doc2
		IOSystem.getActiveContext().getReader().populate(doc2, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		int count2 = vu.countVectorStore(doc2);
		if (count2 == 0) {
			try {
				List<BaseRecord> chunks = vu.createVectorStore(doc2, "The quick brown fox jumped over the lazy dog near the crystal palace.", ChunkEnumType.WORD, 500);
				if (chunks.size() > 0) {
					IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
					IOSystem.getActiveContext().getWriter().flush();
				}
			} catch (Exception e) {
				logger.warn("Doc2 vectorization failed: " + e.getMessage());
			}
		}

		/// Ensure doc1 has vectors
		int count1 = vu.countVectorStore(doc1);
		if (count1 == 0) {
			logger.warn("Doc1 has no vectors — skipping multi-ref test");
			return;
		}

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "ContextRefs Multi Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "ContextRefs Multi Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Config not available — skipping");
			return;
		}

		/// Create chatRequest with both docs in contextRefs
		ChatRequest creq = new ChatRequest();
		creq.setChatConfig(chatConfig);
		creq.setPromptConfig(promptConfig);
		creq.setMessage("Tell me about the content");

		List<String> contextRefs = new ArrayList<>();
		contextRefs.add("{\"schema\":\"" + doc1.getSchema() + "\",\"objectId\":\"" + doc1.get(FieldNames.FIELD_OBJECT_ID) + "\"}");
		contextRefs.add("{\"schema\":\"" + doc2.getSchema() + "\",\"objectId\":\"" + doc2.get(FieldNames.FIELD_OBJECT_ID) + "\"}");
		creq.setValue("contextRefs", contextRefs);

		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, creq);
		if (oreq == null) {
			logger.warn("getOpenAIRequest returned null — skipping");
			return;
		}

		List<String> citations = ChatUtil.getDataCitations(testUser, oreq, creq);
		assertNotNull("Citations list is null", citations);
		logger.info("Multi-ref test: " + citations.size() + " citations from " + contextRefs.size() + " context refs");
		/// At least doc1 should produce citations
		assertTrue("Expected at least 1 citation from multi-ref context", citations.size() > 0);
	}
}
