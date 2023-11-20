package org.cote.accountmanager.provider;

import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.RecordUtil;

public class UrnProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(UrnProvider.class);

	private static final String urnPrefix = "am6";
	private static final String urnSeparator = ":";
	private static final String urnSubSeparator = ".";
	private static final Pattern factoryPattern = Pattern.compile("^am:(\\S[^:]+):(\\S[^:]+)");
	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
	}


	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {

		/*
		if(!model.inherits(ModelNames.MODEL_BASE)) {
			throw new ModelException(String.format(ModelException.INHERITENCE_EXCEPTION, model.getModel(), ModelNames.MODEL_BASE));
		}
		*/
		if(!RecordOperation.CREATE.equals(operation) && !RecordOperation.UPDATE.equals(operation)) {
			return;
		}
		
		String[] fields = RecordUtil.getPossibleFields(model.getModel(), new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_TYPE, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_PARENT_ID});
		IOSystem.getActiveContext().getReader().conditionalPopulate(model, fields);

		StringBuilder buff = new StringBuilder();
		boolean skipName = false;
		buff.append(urnPrefix + urnSeparator + model.getModel());
		if(model.hasField(FieldNames.FIELD_TYPE)) {
			buff.append(urnSubSeparator + model.get(FieldNames.FIELD_TYPE));
		}
		if(model.inherits(ModelNames.MODEL_ORGANIZATION)) {
			buff.append(urnSeparator + getDotPath(model, FieldNames.FIELD_PATH));
			skipName = true;
		}
		else if(model.inherits(ModelNames.MODEL_ORGANIZATION_EXT)) {
			//buff.append(urnSeparator + getDotPath(model, "organizationPath"));
			if((long)model.get(FieldNames.FIELD_ORGANIZATION_ID) == 0L) {
				logger.warn("Skipping urn update on incomplete " + model.getModel() + " model - missing organizationId");
				return;
			}
			long orgId = (long)model.get("organizationId");
			if(orgId == 0L) {
				//throw new ValueException("Organization id is not specified");
				buff.append(urnSeparator + "anonymous");
			}
			else {
				BaseRecord org = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_ORGANIZATION, orgId);
				buff.append(urnSeparator + getDotPath(org, FieldNames.FIELD_PATH));
			}
		}
		if(!model.inherits(ModelNames.MODEL_ORGANIZATION) && model.inherits(FieldNames.FIELD_PATH)) {
			if(model.get(FieldNames.FIELD_PATH) == null) {
				logger.warn("Skipping urn update on incomplete " + model.getModel() + " model - missing path");
				return;
			}
			buff.append(urnSeparator + getDotPath(model, FieldNames.FIELD_PATH));
			skipName = true;
		}
		else if(model.inherits(ModelNames.MODEL_DIRECTORY)) {
			if(model.get(FieldNames.FIELD_GROUP_PATH) == null) {
				logger.warn("Skipping urn update on incomplete " + model.getModel() + " model - missing groupPath");
				logger.warn(model.toString());
				return;
			}
			buff.append(urnSeparator + getDotPath(model, FieldNames.FIELD_GROUP_PATH));
		}
		if(!skipName) {
			if(model.hasField(FieldNames.FIELD_NAME)) {
				String name = model.get(FieldNames.FIELD_NAME);
				if(name == null) {
					buff.append(urnSeparator + UUID.randomUUID().toString());
				}
				else {
					buff.append(urnSeparator + name);
				}
			}
			else {
				if(model.hasField(FieldNames.FIELD_OBJECT_ID)) {
					String oid = model.get(FieldNames.FIELD_OBJECT_ID);
					if(oid == null) {
						buff.append(urnSeparator + UUID.randomUUID().toString());
					}
					else {
						buff.append(urnSeparator + oid);
					}
				}

			}
		}
		String key = getNormalizedString(buff.toString());
		model.set(FieldNames.FIELD_URN, key);

	}
	
	public static String getNormalizedString(String in){
		String outStr = in.toLowerCase().replaceAll("[\\s\\-]",".");
		return outStr.toLowerCase().replaceAll("[^A-Za-z0-9\\.\\:]+","");
	}
	
	private static String getDotPath(BaseRecord rec, String property) throws FieldException {
		if(property == null) property = FieldNames.FIELD_PATH;
		if(!rec.hasField(property)) {
			throw new FieldException(property + " not found");
		}
		String path = rec.get(property);
		if(path == null) {
			logger.error(property + " was null");
			return "null";
		}
		return path.substring(1,path.length()).replace('/', '.');
	}

	public static boolean isUrn(String possibleUrn){
		return factoryPattern.matcher(possibleUrn).find();
	}
}
