package org.cote.accountmanager.olio.llm;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ChatUtil {
	
	private static final Logger logger = LogManager.getLogger(ChatUtil.class);

	public static boolean saveSession(BaseRecord user, OllamaRequest req, String sessionName) {
		
		BaseRecord dat = getSessionData(user, sessionName);
		boolean upd = false;
		try {
			if(dat == null) {
				BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
				dat = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
				IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, dat, sessionName, dir.get(FieldNames.FIELD_PATH), user.get(FieldNames.FIELD_ORGANIZATION_ID));
				dat.set(FieldNames.FIELD_CONTENT_TYPE,  "text/plain");
				dat = IOSystem.getActiveContext().getAccessPoint().create(user, dat);
			}
			//ByteModelUtil.setValueString(dat, JSONUtil.exportObject(req));
			dat.set(FieldNames.FIELD_BYTE_STORE, JSONUtil.exportObject(req).getBytes());
			if(IOSystem.getActiveContext().getAccessPoint().update(user, dat) != null) {
				upd = true;
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return upd;
	}
	
	public static Query getSessionDataQuery(BaseRecord user, String sessionName) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, sessionName);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.planMost(false);
		return q;
	}
	
	public static BaseRecord getSessionData(BaseRecord user, String sessionName) {
		//q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_BYTE_STORE});
		return IOSystem.getActiveContext().getSearch().findRecord(getSessionDataQuery(user, sessionName));
	}
	public static OllamaRequest getSession(BaseRecord user, String sessionName) {
		
		BaseRecord dat = getSessionData(user, sessionName);
		OllamaRequest req = null;
		if(dat != null) {
			//req = JSONUtil.importObject(ByteModelUtil.getValueString(dat), OllamaRequest.class);
			try {
				req = JSONUtil.importObject(ByteModelUtil.getValueString(dat), OllamaRequest.class);
			} catch (ValueException | FieldException e) {
				logger.error(e);
				e.printStackTrace();
			}

		}
		return req;
	}
	
	public static String getSessionName(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, String name) {
		return
		(
		//CryptoUtil.getDigestAsString(
			user.get(FieldNames.FIELD_NAME)
			+ "-" + chatConfig.get(FieldNames.FIELD_NAME) 
			+ "-" + promptConfig.get(FieldNames.FIELD_NAME)
			+ "-" + name
		//)
		);
	}
	public static BaseRecord getCreateChatConfig(BaseRecord user, String name) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID}));
		OlioUtil.planMost(q);
		OlioUtil.limitSubplanFields(q.plan(), OlioModelNames.MODEL_CHAT_CONFIG, "event");

		BaseRecord dat = IOSystem.getActiveContext().getSearch().findRecord(q);
		
		if(dat == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);
			try {
				dat = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			} catch (Exception e) {
				logger.error(e);
			}

			dat = IOSystem.getActiveContext().getAccessPoint().create(user, dat);
		}
		return dat;
	}
	public static BaseRecord getDefaultPrompt() {
		return JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/llm/prompt.config.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}
	public static BaseRecord getCreatePromptConfig(BaseRecord user, String name) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID}));
		q.planMost(false);
		BaseRecord dat = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(dat == null) {
			BaseRecord template = getDefaultPrompt();
			dat = newPromptConfig(user, name, template);
			dat = IOSystem.getActiveContext().getAccessPoint().create(user, dat);
		}
		return dat;
	}
	protected static BaseRecord newPromptConfig(BaseRecord user, String name, BaseRecord template) {
		BaseRecord rec = null;
		boolean error = false;
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, template, plist);
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		return rec;
	}
}
