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
	


}
