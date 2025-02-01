package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.VectorListFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelSchema;

public class VectorChatHistoryListFactory extends VectorListFactory {
	public VectorChatHistoryListFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		BaseRecord systemChar = null;
		BaseRecord userChar = null;
		BaseRecord event = null;
		BaseRecord session = null;
		BaseRecord chatConfig = null;
		BaseRecord promptConfig = null;
		if(parameterList != null) {
			systemChar = parameterList.getParameter("systemCharacter", BaseRecord.class, null);
			userChar = parameterList.getParameter("userCharacter", BaseRecord.class, null);
			event = parameterList.getParameter("event", BaseRecord.class, null);
			session = parameterList.getParameter("session", BaseRecord.class, null);
			chatConfig = parameterList.getParameter("chatConfig", BaseRecord.class, null);
			promptConfig = parameterList.getParameter("promptConfig", BaseRecord.class, null);
		}
		
		BaseRecord vlist = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		BaseRecord vlist2 = null;
		List<BaseRecord> vectors = vlist.get("vectors");
		List<BaseRecord> nvects = new ArrayList<>();
		try {
			vlist2 = RecordFactory.newInstance(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY_LIST);
			for(BaseRecord v : vectors) {
				//BaseRecord chunkModel = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
				BaseRecord v2 = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY, contextUser, v, null);
				v2.set("systemCharacter", systemChar);
				v2.set("userCharacter", userChar);
				v2.set("event", event);
				v2.set("session", session);
				v2.set("chatConfig", chatConfig);
				v2.set("promptConfig", promptConfig);
				nvects.add(v2);
			}
			vlist2.set("vectors", nvects);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return vlist2;
	}

}
