package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.FactoryBase;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
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
				//newRecord.set(FieldNames.FIELD_STORE, sto);
				newRecord.set(FieldNames.FIELD_PROFILE, pro);
				
				buildNestedGroupRecord(contextUser, newRecord, newRecord.get(FieldNames.FIELD_STORE), RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON).getFieldSchema(FieldNames.FIELD_STORE), null);
				
				logger.info(newRecord.toFullString());

			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return newRecord;
	}
	
	public BaseRecord buildNestedGroupRecord(BaseRecord contextUser, BaseRecord newRecord, BaseRecord template, FieldSchema fieldSchema, String groupName) throws FactoryException, FieldException, ValueException, ModelNotFoundException {
		long orgId = contextUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(fieldSchema.getBaseModel() == null) {
			logger.error("Field schema " + fieldSchema.getName() + " does not define a base model");
			return null;
		}
		ModelSchema ms = RecordFactory.getSchema(fieldSchema.getBaseModel());
		if(groupName == null) {
			groupName = ms.getGroup();
		}
		if(groupName == null) {
			logger.error("Field schema " + fieldSchema.getName() + " does not define a group name");
			return null;
		}
		
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/" + groupName);
		BaseRecord fieldRec = IOSystem.getActiveContext().getFactory().newInstance(fieldSchema.getBaseModel(), contextUser, template.get(fieldSchema.getName()), plist);
		if(fieldSchema.getFieldType() == FieldEnumType.MODEL) {
			newRecord.set(fieldSchema.getName(), fieldRec);
		}
		if(template != null){
			for(FieldSchema fs : ms.getFields()) {
				if(!template.hasField(fs.getName()) || !fs.isForeign() || fs.isVirtual() || fs.isEphemeral() || fs.isReferenced()) {
	                continue;
	            }
				if(fs.getFieldType() == FieldEnumType.MODEL) {
					BaseRecord temp = template.get(fs.getName());
					buildNestedGroupRecord(contextUser, fieldRec, temp, fs, null);
				}
				
				else if (fs.getFieldType() == FieldEnumType.LIST) {
					List<BaseRecord> nlst = new ArrayList<>();
					List<BaseRecord> lst = template.get(fs.getName());
					for(BaseRecord cr : lst) {
						BaseRecord ncr = buildNestedGroupRecord(contextUser, cr, cr, fs, null);
						if(ncr != null) {
							nlst.add(ncr);
						}
					}
					newRecord.set(fs.getName(), nlst);
				}
			}
		}
		return fieldRec;
		
	}

}
