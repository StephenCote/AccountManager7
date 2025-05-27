package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

public class TestAgentChat extends BaseTest {

	@Test
	public void TestSchemaPrompt() {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
		q.planMost(false);
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON);
		//logger.info(JSONUtil.exportObject(ms));
		logger.info(getModels());
		logger.info(getSchemaDescription(ms));
	}
	
	
	private String getModels() {
		StringBuilder buff = new StringBuilder();
		ModelNames.MODELS.sort((f1, f2) -> f1.compareTo(f2));
		for (String model : ModelNames.MODELS) {
			ModelSchema ms = RecordFactory.getSchema(model);
			if(ms.isAbs()) continue;
			String inh = ms.getInherits().stream().collect(Collectors.joining(", "));
			buff.append(ms.getName() + (inh.length() > 0 ? " [inherits: " + inh + "]" : ""));
			buff.append(System.lineSeparator());
		}
		//return ModelNames.MODELS.stream().collect(Collectors.joining(System.lineSeparator()));
		return buff.toString();
	}
	
	
	private String getNameTypeStatement(FieldSchema fs) {

		String name = fs.getName();
		FieldEnumType ftype = fs.getFieldType();
		String type = ftype.toString().toLowerCase();
		String arr = "";
		switch (ftype) {
			case BOOLEAN:
				type = "bool";
				break;

			case BLOB:
				type = "byte";
				arr = "[]";
				break;
			case LIST:
				arr = "[]";
				type = fs.getBaseType();
				if(type != null && type.equals(FieldEnumType.MODEL.toString().toLowerCase())) {
					type = fs.getBaseModel();
				}
				break;
			case BYTE:
				logger.warn("Unused");
				break;
			case TIMESTAMP:
			case ZONETIME:
			case CALENDAR:
				type = "datetime";
				break;
				
			case FLEX:
				type = "var";
				break;
			case MODEL:
				type = fs.getBaseModel();
				break;
			case STRING:
				type = "str";
				break;
			case INT:
			case LONG:
			case DOUBLE:
			case ENUM:
			case VECTOR:
				break;
			default:
				logger.error("Unhandled type: " + type.toString());
				break;
		}
		String prim = "";
		if (fs.isPrimaryKey()) {
			prim = "(primary key) ";
		}
		else if(fs.isIdentity()) {
			prim = "(identity) ";
		}
		return prim + type + " " + name + arr;


	}
	
	private String getSchemaDescription(ModelSchema schema) {
		StringBuilder buff = new StringBuilder();
		String sep =  System.lineSeparator();
		buff.append((schema.isEphemeral() ? "Ephemeral " : "") + (schema.isAbs() ? "Abstract " : "") + "Model: " + schema.getName());
		if (schema.getDescription() != null && schema.getDescription().length() > 0) {
			buff.append(sep + "Description: " + schema.getDescription());
		}
		String inh = schema.getInherits().stream().collect(Collectors.joining(", "));
		// String inh = RecordUtil.inheritence(schema).stream().collect(Collectors.joining(", "));
		if (inh != null && inh.length() > 0) {
			buff.append(System.lineSeparator() + "Inherits: " + inh);
		}
		RecordUtil.sortFields(schema);
		if (schema.getFields() != null && schema.getFields().size() > 0) {
			buff.append(sep + "Fields:" + sep);
			for (FieldSchema fld : schema.getFields()) {
				buff.append(getNameTypeStatement(fld) + sep);
				/*
				String ft = "";
				if(fld.isForeign()) {
					if(fld.getBaseModel() == ModelNames.MODEL_SELF) {
						ft = "foreign type: " + fld.getForeignType();
					}
					else {
						ft = fld.getBaseModel() + " (table " + IOSystem.getActiveContext().getDbUtil().getTableName(fld.getBaseModel()) + ")";
					}
				}
				else if(fld.getBaseModel() != null && fld.getBaseModel().length() > 0) {
					ft = "model: " + fld.getBaseModel() + " (table " + IOSystem.getActiveContext().getDbUtil().getTableName(fld.getBaseModel()) + ")";
				}
				String bc = "";
				if (fld.getBaseClass() != null && fld.getBaseClass().length() > 0) {
					bc = " (class: " + fld.getBaseClass() + ")";
				}
				String pref = "";
				if (fld.isVirtual()) {
					pref = "virtual ";
				} else if (fld.isEphemeral()) {
					pref = "ephemeral ";
				} else if (fld.isInternal()) {
					pref = "internal ";
				}
				String key = "";
				if (fld.isPrimaryKey()) {
					key = "(primary key) ";
				}
				String fgn = (ft.length() > 0 ? " " + ft : "");
				buff.append(System.lineSeparator() + key + pref + fld.getName() + " (" + fld.getType() + fgn + ")" + bc);
				*/

			}
		}
		return buff.toString();
	}
	
	/*
	/// Copied from TestChat2 to use/refactor into a utility
	@Test
	public void TestRequestPersistence() {
		logger.info("Test Chat Request Persistence");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Olio LLM Revisions");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord cfg = OlioTestUtil.getOpenAIConfig(testUser1, "Open AI.chat", testProperties);
		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser1, "Gruffy Test");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("Your name is Mr. Gruffypants. You are a ridiculous anthropomorphic character that is extremely gruff, snooty, and moody. Every response should be snarky, critical, and gruff, interspersed with wildly inaccurate mixed methaphors, innuendo and double entendres.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser1, pcfg);
		String chatName = "Gruffy Chat Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);
		
		OpenAIRequest req = ChatUtil.getChatSession(testUser1, chatName, cfg, pcfg);
		//String flds = req.getFields().stream().map(f -> f.getName()).collect(Collectors.joining(", "));
		// logger.info(flds);
		// logger.info(JSONUtil.exportObject(ChatUtil.getPrunedRequest(req), RecordSerializerConfig.getHiddenForeignUnfilteredModule()));
		
		assertNotNull("Request is null", req);
		

		Chat chat = new Chat(testUser1, cfg, pcfg);
		chat.continueChat(req, "Hello, how are you?");
		List<OpenAIMessage> msgs = req.getMessages();
		logger.info("Messages: " + msgs.get(msgs.size() - 1).getContent());
	}
	*/
}
