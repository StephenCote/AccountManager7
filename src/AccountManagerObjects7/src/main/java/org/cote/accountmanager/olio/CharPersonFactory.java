package org.cote.accountmanager.olio;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.FactoryBase;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
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
			IPath pu = IOSystem.getActiveContext().getPathUtil();
			try {
				long orgId = contextUser.get(FieldNames.FIELD_ORGANIZATION_ID);
				String gtype = GroupEnumType.DATA.toString();
				String mtype = ModelNames.MODEL_GROUP;
				

				ParameterList plist2 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_STATISTICS).getGroup());
				ParameterList plist3 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_INSTINCT).getGroup());
				ParameterList plist4 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(ModelNames.MODEL_BEHAVIOR).getGroup());
				ParameterList plist5 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(ModelNames.MODEL_PERSONALITY).getGroup());
				ParameterList plist6 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_STATE).getGroup());
				ParameterList plist7 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(OlioModelNames.MODEL_STORE).getGroup());
				ParameterList plist8 = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + RecordFactory.getSchema(ModelNames.MODEL_PROFILE).getGroup());

				BaseRecord stats = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATISTICS, contextUser, null, plist2);
				BaseRecord inst = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_INSTINCT, contextUser, null, plist3);
				BaseRecord beh = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_BEHAVIOR, contextUser, null, plist4);
				BaseRecord pper = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_PERSONALITY, contextUser, null, plist5);
				BaseRecord st = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATE, contextUser, null, plist6);
				BaseRecord sto = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, contextUser, null, plist7);
				BaseRecord pro = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_PROFILE, contextUser, null, plist8);
				
				newRecord.set(OlioFieldNames.FIELD_STATISTICS, stats);
				newRecord.set("instinct", inst);
				newRecord.set("behavior", beh);
				newRecord.set("personality", pper);
				newRecord.set("state", st);
				newRecord.set(FieldNames.FIELD_STORE, sto);
				newRecord.set("profile", pro);

			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}

		
		return newRecord;
	}

}
