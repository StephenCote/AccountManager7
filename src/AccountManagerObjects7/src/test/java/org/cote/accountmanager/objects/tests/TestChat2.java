package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Test;

public class TestChat2 extends BaseTest {

	/*
	@Test
	public void TestOpenAIModel() {
		logger.info("Test Open AI Model");
		BaseRecord aireq = null;

		try {
			aireq = RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_REQUEST);
			aireq.set("model", "herm-local");
			addMessage(aireq, "system",
					"Your name is Mr. Flufflepants. You are a ridiculous anthropomorphic character that uses wildly inaccurate and mixed methaphors in every answer.");
			addMessage(aireq, "user", "Hi, what's your name?");
			// opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		String ser = JSONUtil.exportObject(aireq, RecordSerializerConfig.getHiddenForeignUnfilteredModule());

		BaseRecord req2 = RecordFactory.importRecord(OlioModelNames.MODEL_OPENAI_REQUEST, ser);
		assertNotNull("Record is null", req2);

		BaseRecord resp = chat(aireq);
		assertNotNull("Response is null", resp);

	}
	*/

	public void addMessage(BaseRecord req, String role, String message) {
		BaseRecord aimsg = null;
		try {
			aimsg = RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_MESSAGE);
			aimsg.set("role", role);
			aimsg.set("content", message);
			List<BaseRecord> msgs = req.get("messages");
			msgs.add(aimsg);

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

	}

	public String getChatURL(BaseRecord rec, LLMServiceEnumType type) {
		String url = null;
		if (type == LLMServiceEnumType.OLLAMA) {
			url = testProperties.getProperty("test.llm.ollama.server") + "/api/chat";
		} else {
			url = testProperties.getProperty("test.llm.openai.server") + "/openai/deployments/" + rec.get("model")
					+ "/chat/completions?api-version=" + testProperties.getProperty("test.llm.openai.version");
		}
		return url;
	}

	private LLMServiceEnumType serviceType = LLMServiceEnumType.OLLAMA;

	public BaseRecord chat(BaseRecord req) {
		if (req == null) {
			return null;
		}
		String ser = JSONUtil.exportObject(req, RecordSerializerConfig.getHiddenForeignUnfilteredModule());
		String url = getChatURL(req, serviceType);
		String authorizationToken = null;
		if (serviceType == LLMServiceEnumType.OPENAI) {
			authorizationToken = testProperties.getProperty("test.llm.openai.authorizationToken");
		}

		return post(OlioModelNames.MODEL_OPENAI_RESPONSE, ClientUtil.getResource(url), authorizationToken, ser,
				MediaType.APPLICATION_JSON_TYPE);
	}

	public BaseRecord post(String modelName, WebTarget resource, String authZ, String json, MediaType responseType) {

		Builder bld = ClientUtil.getRequestBuilder(resource).accept(responseType);

		if (authZ != null) {
			bld.header("api-key", authZ);
		}

		Response response = bld.post(Entity.entity(json, MediaType.APPLICATION_JSON_TYPE));

		BaseRecord outObj = null;
		if (response != null) {
			if (response.getStatus() == 200) {

				String ser = response.readEntity(String.class);
				logger.info(ser);
				outObj = RecordFactory.importRecord(modelName, ser);
			} else {
				logger.warn("Received response: " + response.getStatus());
				logger.warn(response.readEntity(String.class));
			}
		} else {
			logger.warn("Null response");
		}
		return outObj;
	}
	
	private boolean resetStore = false;
	
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
		
		int count = VectorUtil.countVectorStore(doc);
		if(resetStore && count > 0) {
			VectorUtil.deleteVectorStore(doc);
			count = 0;
		}
		if(count == 0) {
			List<BaseRecord> chunks = new ArrayList<>();
			try {
				 chunks = VectorUtil.createVectorStore(doc, ChunkEnumType.CHAPTER, 20);
				logger.info("Retrieved " + chunks.size());
				assertTrue("Expected chunks", chunks.size() > 0);
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
	
			} catch (FieldException | WriterException e) {
				logger.error(e);
			}
		}
		List<BaseRecord> store = VectorUtil.getVectorStore(doc);
		assertTrue("Expected the store", store.size() > 0);
		
		List<BaseRecord> findStore = VectorUtil.find(doc, "Who did Mark have an affair with?", 10, 60);
		logger.info("Found: " + findStore.size());
		for(BaseRecord s : findStore) {
			logger.info("Score: " + s.get(FieldNames.FIELD_SCORE));
			logger.info("Content: " + s.get(FieldNames.FIELD_CONTENT));
		}
		
		
		/*
		String data = null;
		try {
			data = ByteModelUtil.getValueString(doc);
		} catch (ValueException | FieldException e) {
			logger.error(e);
		}
		
		try {
			List<String> chunks = VectorUtil.chunkByChapter(doc.get(FieldNames.FIELD_NAME), path, data, 10);
			logger.info("Chunks: " + chunks.size());
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		*/
		/*

		BaseRecord pdf1 = getCreateDocument(testUser1, "./media/The Verse.docx");
		assertNotNull("Document is null", pdf1);
		IOSystem.getActiveContext().getReader().populate(pdf1, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		
		int count = VectorUtil.countVectorStore(pdf1);
		if(resetStore && count > 0) {
			VectorUtil.deleteVectorStore(pdf1);
			count = 0;
		}
		if(count == 0) {
		List<BaseRecord> chunks = new ArrayList<>();
		try {
			 chunks = VectorUtil.createVectorStore(pdf1, ChunkEnumType.WORD, 250);
			logger.info("Retrieved " + chunks.size());
			assertTrue("Expected chunks", chunks.size() > 0);
			IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));

		} catch (FieldException | WriterException e) {
			logger.error(e);
		}
		}
		List<BaseRecord> store = VectorUtil.getVectorStore(pdf1);
		assertTrue("Expected the store", store.size() > 0);
		
		List<BaseRecord> findStore = VectorUtil.find(pdf1, "Where is the casino?", 5, 60);
		logger.info("Found: " + findStore.size());
		for(BaseRecord s : findStore) {
			logger.info("Score: " + s.get(FieldNames.FIELD_SCORE));
			logger.info("Content: " + s.get(FieldNames.FIELD_CONTENT));
		}
		*/
	}

	

}
