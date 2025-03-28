package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Test;
public class TestVectorStore extends BaseTest {
	
	private boolean resetStore = true;
	
	
	@Test
	public void TestOlioVectorList() {
		logger.info("Test olio vector list");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/Vector");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord data = getCreateData(testUser1, "Test data 1", "text/plain", "This is the example text".getBytes(), "~/Data", testOrgContext.getOrganizationId());
		assertNotNull("Data is null", data);
		IOSystem.getActiveContext().getReader().populate(data, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_VECTOR_REFERENCE, data);
		plist.parameter(FieldNames.FIELD_CHUNK, ChunkEnumType.WORD);
		plist.parameter(FieldNames.FIELD_CHUNK_COUNT, 10);
		
		BaseRecord vlist = null;
		try {
			vlist = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY_LIST, testUser1, null, plist);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("List is null", vlist);
		List<BaseRecord> vectors = vlist.get(FieldNames.FIELD_VECTORS);
		assertTrue("Expected at least one vector", vectors.size() > 0);
		logger.info(vectors.get(0).toFullString());
	}
	
	@Test
	public void TestVectorList() {
		logger.info("Test vector list");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/Vector");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord data = getCreateData(testUser1, "Test data 1", "text/plain", "This is the example text".getBytes(), "~/Data", testOrgContext.getOrganizationId());
		assertNotNull("Data is null", data);
		IOSystem.getActiveContext().getReader().populate(data, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_VECTOR_REFERENCE, data);
		plist.parameter(FieldNames.FIELD_CHUNK, ChunkEnumType.WORD);
		plist.parameter(FieldNames.FIELD_CHUNK_COUNT, 10);
		
		BaseRecord vlist = null;
		try {
			vlist = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_VECTOR_LIST, testUser1, null, plist);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("List is null", vlist);
		List<BaseRecord> vectors = vlist.get(FieldNames.FIELD_VECTORS);
		assertTrue("Expected at least one vector", vectors.size() > 0);
		logger.info(vectors.get(0).toFullString());
	}
	
	@Test
	public void TestPDF() {
		VectorUtil vu = new VectorUtil(LLMServiceEnumType.valueOf(testProperties.getProperty("test.embedding.type").toUpperCase()), testProperties.getProperty("test.embedding.server"), testProperties.getProperty("test.embedding.authorizationToken"));
		logger.info("Test VectorStore with PDF");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/Vector");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord pdf1 = getCreateDocument(testUser1, "./media/CardFox.pdf");
		assertNotNull("Document is null", pdf1);
		IOSystem.getActiveContext().getReader().populate(pdf1, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		
		int count = vu.countVectorStore(pdf1);
		if(resetStore && count > 0) {
			vu.deleteVectorStore(pdf1);
			count = 0;
		}
		if(count == 0) {
		List<BaseRecord> chunks = new ArrayList<>();
		try {
			 chunks = vu.createVectorStore(pdf1, ChunkEnumType.WORD, 250);
			logger.info("Retrieved " + chunks.size());
			assertTrue("Expected chunks", chunks.size() > 0);
			IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));

		} catch (FieldException | WriterException e) {
			logger.error(e);
		}
		}
		List<BaseRecord> store = vu.getVectorStore(pdf1);
		assertTrue("Expected the store", store.size() > 0);
		
		List<BaseRecord> findStore = vu.find(pdf1, "Where is the casino?", 5, 60);
		logger.info("Found: " + findStore.size());
		for(BaseRecord s : findStore) {
			logger.info("Score: " + s.get(FieldNames.FIELD_SCORE));
			logger.info("Content: " + s.get(FieldNames.FIELD_CONTENT));
		}
	}
	
	@Test
	public void TestVectorChunk() {
		
		logger.info("Test VectorChunk with DOCX");
		logger.info("NOTE: Currently depends on uncommitted example content");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/Vector");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String path = "./media/The Verse.docx";
		BaseRecord doc = getCreateDocument(testUser1, path);
		IOSystem.getActiveContext().getReader().populate(doc, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		VectorUtil vu = new VectorUtil(LLMServiceEnumType.valueOf(testProperties.getProperty("test.embedding.type").toUpperCase()), testProperties.getProperty("test.embedding.server"), testProperties.getProperty("test.embedding.authorizationToken"));
		int count = vu.countVectorStore(doc);
		if(resetStore && count > 0) {
			vu.deleteVectorStore(doc);
			count = 0;
		}
		if(count == 0) {
			List<BaseRecord> chunks = new ArrayList<>();
			try {
				 chunks = vu.createVectorStore(doc, ChunkEnumType.CHAPTER, 20);
				logger.info("Retrieved " + chunks.size());
				assertTrue("Expected chunks", chunks.size() > 0);
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
	
			} catch (FieldException | WriterException e) {
				logger.error(e);
			}
		}
		List<BaseRecord> store = vu.getVectorStore(doc);
		assertTrue("Expected the store", store.size() > 0);
		
		List<BaseRecord> findStore = vu.find(doc, "Who is Mark Lucean?", 10, 60);
		logger.info("Found: " + findStore.size());
		for(BaseRecord s : findStore) {
			logger.info("Score: " + s.get(FieldNames.FIELD_SCORE));
			logger.info("Content: " + s.get(FieldNames.FIELD_CONTENT));
		}
	
	}

	
	
	
}
