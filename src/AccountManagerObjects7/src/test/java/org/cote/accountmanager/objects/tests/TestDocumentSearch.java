package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Test;

public class TestDocumentSearch extends BaseTest {

	/*
	private String scrivenerProjectPath = "C:\\Users\\swcot\\OneDrive\\Documents\\Scrivner\\The Verse.scriv";
	
	@Test
	public void TestScrivener() {
		logger.info("Test scan scrivener...");

		OrganizationContext testOrgContext = getTestOrganization("/Development/Scrivener");
		assertNotNull("Test org is null", testOrgContext);
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("Test user is null", testUser1);
		ScrivenerAssistant sa = new ScrivenerAssistant(testUser1);
		try {
			sa.parseProject(scrivenerProjectPath);
		} catch (NullPointerException | ParserConfigurationException | SAXException | IOException | XPathExpressionException e) {
			logger.error(e);
			e.printStackTrace();
		}
		DocumentWatcher dw = new DocumentWatcher(sa, scrivenerProjectPath);

	}
	*/
	/*
	@Test
	public void TestVectorAccessPoint() {
		logger.info("Test vectorize access point method...");

		VectorUtil vu = new VectorUtil(LLMServiceEnumType.valueOf(testProperties.getProperty("test.embedding.type").toUpperCase()), testProperties.getProperty("test.embedding.server"), testProperties.getProperty("test.embedding.authorizationToken"));
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Scrivener");
		assertNotNull("Test org is null", testOrgContext);
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("Test user is null", testUser1);
		
		String path = "./media/HarlotsEight_Vol1_SM.docx";
		BaseRecord doc = getCreateDocument(testUser1, path);
		assertNotNull("Test data was null", doc);
		
		if(vu.countVectorStore(doc) == 0) {
			try {
				ioContext.getAccessPoint().vectorize(testUser1, doc.getSchema(), doc.get(FieldNames.FIELD_OBJECT_ID), ChunkEnumType.CHAPTER, 200);
			}
			catch(Exception e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		
		List<BaseRecord> vecs = vu.find(doc, "What are the names of Mark's kids?", 10, 0.6);
		assertTrue("Expected at least one result", vecs.size() > 0);
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_ORGANIZATION_ID, testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE, doc);
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, doc.getSchema());
		q.planMost(false, Arrays.asList(new String[] {FieldNames.FIELD_EMBEDDING}));
		BaseRecord[] chunks = ioContext.getSearch().findRecords(q);
		
		assertTrue("Expected to return some chunks", chunks.length > 0);
		logger.info("Chunks: " + chunks.length);
	
	}
	*/
	
	@Test
	public void TestDocumentSummarizer() {
		
		VectorUtil vu = new VectorUtil(LLMServiceEnumType.valueOf(testProperties.getProperty("test.embedding.type").toUpperCase()), testProperties.getProperty("test.embedding.server"), testProperties.getProperty("test.embedding.authorizationToken"));
		OrganizationContext testOrgContext = getTestOrganization("/Development/Summarizer");
		assertNotNull("Test org is null", testOrgContext);
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("Test user is null", testUser1);
		
		String path = "./media/Vore.docx";
		BaseRecord doc = getCreateDocument(testUser1, path);
		
		if(vu.countVectorStore(doc) == 0) {
			try {
				ioContext.getAccessPoint().vectorize(testUser1, doc.getSchema(), doc.get(FieldNames.FIELD_OBJECT_ID), ChunkEnumType.WORD, 250);
			}
			catch(Exception e) {
				logger.error(e);
				e.printStackTrace();
			}
		}

		BaseRecord cfg = null;
		try {
			cfg = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("Config is null", cfg);
		
		cfg.setValue("serviceType", LLMServiceEnumType.OPENAI);
		cfg.setValue("apiVersion", testProperties.getProperty("test.llm.openai.version"));
		cfg.setValue("serverUrl", testProperties.getProperty("test.llm.openai.server"));
		cfg.setValue("model", "gpt-4o");
		cfg.setValue("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken"));

		BaseRecord summary = ChatUtil.createSummary(testUser1, null, null, doc, true);
		assertNotNull("Summary is null", summary);
	}
	
	@Test
	public void TestEmbeddingToolAvailable() {
		String msg = "This is a test";
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.valueOf(testProperties.getProperty("test.embedding.type").toUpperCase()), testProperties.getProperty("test.embedding.server"), testProperties.getProperty("test.embedding.authorizationToken"));
		assertTrue("Expected a heartbeat", eu.heartbeat());
		
		// logger.info(StatementUtil.getDeleteOrphanTemplate(null));
	}


}




