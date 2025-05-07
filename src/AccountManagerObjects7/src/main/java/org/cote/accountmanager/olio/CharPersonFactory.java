package org.cote.accountmanager.olio;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.FactoryBase;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ParameterUtil;

public class CharPersonFactory extends FactoryBase {
	public CharPersonFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		BaseRecord dir = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		return dir;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON);
		String path = "~/" + ms.getGroup();
		String name = null;
		if(parameterList != null) {
			path = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_PATH, String.class, path);
			name = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_NAME, String.class, null);
		}
		if(contextUser != null) {
			try {

				ParameterList plist2 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_STATISTICS).getGroup());
				ParameterList plist3 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_INSTINCT).getGroup());
				ParameterList plist4 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(ModelNames.MODEL_BEHAVIOR).getGroup());
				ParameterList plist5 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(ModelNames.MODEL_PERSONALITY).getGroup());
				ParameterList plist6 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_STATE).getGroup());
				ParameterList plist7 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_STORE).getGroup());
				ParameterList plist8 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(ModelNames.MODEL_PROFILE).getGroup());

				BaseRecord stats = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATISTICS, contextUser, newRecord.get(OlioFieldNames.FIELD_STATISTICS), plist2);
				BaseRecord inst = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_INSTINCT, contextUser, newRecord.get(OlioFieldNames.FIELD_INSTINCT), plist3);
				BaseRecord beh = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_BEHAVIOR, contextUser, newRecord.get(FieldNames.FIELD_BEHAVIOR), plist4);
				BaseRecord pper = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_PERSONALITY, contextUser, newRecord.get(FieldNames.FIELD_PERSONALITY), plist5);
				BaseRecord st = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATE, contextUser, newRecord.get(FieldNames.FIELD_STATE), plist6);
				BaseRecord sto = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, contextUser, newRecord.get(FieldNames.FIELD_STORE), plist7);
				BaseRecord pro = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_PROFILE, contextUser, newRecord.get(FieldNames.FIELD_PROFILE), plist8);
				
				newRecord.set(OlioFieldNames.FIELD_STATISTICS, stats);
				newRecord.set(OlioFieldNames.FIELD_INSTINCT, inst);
				newRecord.set(FieldNames.FIELD_BEHAVIOR, beh);
				newRecord.set(FieldNames.FIELD_PERSONALITY, pper);
				newRecord.set(FieldNames.FIELD_STATE, st);
				newRecord.set(FieldNames.FIELD_STORE, sto);
				newRecord.set(FieldNames.FIELD_PROFILE, pro);
				
				// buildNestedGroupRecord(contextUser, newRecord, newRecord.get(OlioFieldNames.FIELD_STATISTICS), ms.getFieldSchema(OlioFieldNames.FIELD_STATISTICS), null);
				// buildNestedGroupRecord(contextUser, newRecord, newRecord.get(FieldNames.FIELD_STORE), ms.getFieldSchema(FieldNames.FIELD_STORE), null);
				
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return newRecord;
	}


}
