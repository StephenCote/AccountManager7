package org.cote.accountmanager.factory;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.PathUtil;

public class WorldFactory extends FactoryBase {
	public WorldFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		
		// logger.error("*** New directory - this shouldn't be hit for an abstract model");
		BaseRecord dir = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		return dir;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		// logger.info(newRecord.toFullString());
		if(parameterList != null) {
			
			String name = ParameterUtil.getParameter(parameterList, "name", String.class, null);
			String path = ParameterUtil.getParameter(parameterList, "path", String.class, null);
			if(name != null && path != null && contextUser != null) {
				IPath pu = IOSystem.getActiveContext().getPathUtil();
				try {
					long orgId = contextUser.get(FieldNames.FIELD_ORGANIZATION_ID);
					newRecord.set("population", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Population", GroupEnumType.DATA.toString(), orgId));
					newRecord.set("locations", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Locations", GroupEnumType.DATA.toString(), orgId));
					newRecord.set("events", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Events", GroupEnumType.DATA.toString(), orgId));
					newRecord.set("words", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Words", GroupEnumType.DATA.toString(), orgId));
					newRecord.set("names", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Names", GroupEnumType.DATA.toString(), orgId));
					newRecord.set("surnames", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Surnames", GroupEnumType.DATA.toString(), orgId));
					newRecord.set("occupations", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Occupations", GroupEnumType.DATA.toString(), orgId));
					newRecord.set("dictionary", pu.makePath(contextUser, ModelNames.MODEL_GROUP, path + "/" + name + "/Dictionary", GroupEnumType.DATA.toString(), orgId));
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
			}

		}
		return newRecord;
	}
}
