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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ParameterUtil;

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
					// logger.info("Create world: " + path + "/" + name);
					long orgId = contextUser.get(FieldNames.FIELD_ORGANIZATION_ID);
					String gtype = GroupEnumType.DATA.toString();
					String mtype = ModelNames.MODEL_GROUP;
					newRecord.set("addresses", pu.makePath(contextUser, mtype, path + "/" + name + "/Addresses", gtype, orgId));
					newRecord.set("contacts", pu.makePath(contextUser, mtype, path + "/" + name + "/Contacts", gtype, orgId));
					newRecord.set("population", pu.makePath(contextUser, mtype, path + "/" + name + "/Population", gtype, orgId));
					newRecord.set("locations", pu.makePath(contextUser, mtype, path + "/" + name + "/Locations", gtype, orgId));
					newRecord.set("events", pu.makePath(contextUser, mtype, path + "/" + name + "/Events", gtype, orgId));
					newRecord.set("words", pu.makePath(contextUser, mtype, path + "/" + name + "/Words", gtype, orgId));
					newRecord.set("traits", pu.makePath(contextUser, mtype, path + "/" + name + "/Traits", gtype, orgId));
					newRecord.set("colors", pu.makePath(contextUser, mtype, path + "/" + name + "/Colors", gtype, orgId));
					newRecord.set("names", pu.makePath(contextUser, mtype, path + "/" + name + "/Names", gtype, orgId));
					newRecord.set("surnames", pu.makePath(contextUser, mtype, path + "/" + name + "/Surnames", gtype, orgId));
					newRecord.set("occupations", pu.makePath(contextUser, mtype, path + "/" + name + "/Occupations", gtype, orgId));
					newRecord.set("dictionary", pu.makePath(contextUser, mtype, path + "/" + name + "/Dictionary", gtype, orgId));
					newRecord.set("apparel", pu.makePath(contextUser, mtype, path + "/" + name + "/Apparel", gtype, orgId));
					newRecord.set("wearables", pu.makePath(contextUser, mtype, path + "/" + name + "/Wearables", gtype, orgId));
					newRecord.set("qualities", pu.makePath(contextUser, mtype, path + "/" + name + "/Qualities", gtype, orgId));
					newRecord.set("patterns", pu.makePath(contextUser, mtype, path + "/" + name + "/Patterns", gtype, orgId));
					newRecord.set("statistics", pu.makePath(contextUser, mtype, path + "/" + name + "/Statistics", gtype, orgId));
					newRecord.set("instincts", pu.makePath(contextUser, mtype, path + "/" + name + "/Instincts", gtype, orgId));
					newRecord.set("behaviors", pu.makePath(contextUser, mtype, path + "/" + name + "/Behaviors", gtype, orgId));
					newRecord.set("states", pu.makePath(contextUser, mtype, path + "/" + name + "/States", gtype, orgId));
					newRecord.set("actions", pu.makePath(contextUser, mtype, path + "/" + name + "/Actions", gtype, orgId));
					newRecord.set("stores", pu.makePath(contextUser, mtype, path + "/" + name + "/Stores", gtype, orgId));
					newRecord.set("actionResults", pu.makePath(contextUser, mtype, path + "/" + name + "/ActionResults", gtype, orgId));
					newRecord.set("schedules", pu.makePath(contextUser, mtype, path + "/" + name + "/Schedules", gtype, orgId));
					newRecord.set("personalities", pu.makePath(contextUser, mtype, path + "/" + name + "/Personalities", gtype, orgId));
					newRecord.set("builders", pu.makePath(contextUser, mtype, path + "/" + name + "/Builders", gtype, orgId));
					newRecord.set("items", pu.makePath(contextUser, mtype, path + "/" + name + "/Items", gtype, orgId));
					newRecord.set("tagsGroup", pu.makePath(contextUser, mtype, path + "/" + name + "/Tags", gtype, orgId));
					newRecord.set("animals", pu.makePath(contextUser, mtype, path + "/" + name + "/Animals", gtype, orgId));
					newRecord.set("realmsGroup", pu.makePath(contextUser, mtype, path + "/" + name + "/Realms", gtype, orgId));
					newRecord.set("inventories", pu.makePath(contextUser, mtype, path + "/" + name + "/Inventories", gtype, orgId));
					newRecord.set("interactions", pu.makePath(contextUser, mtype, path + "/" + name + "/Interactions", gtype, orgId));
					newRecord.set("narratives", pu.makePath(contextUser, mtype, path + "/" + name + "/Narratives", gtype, orgId));
					newRecord.set("profiles", pu.makePath(contextUser, mtype, path + "/" + name + "/Profiles", gtype, orgId));
					newRecord.set("pointsOfInterest", pu.makePath(contextUser, mtype, path + "/" + name + "/Points of Interest", gtype, orgId));
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
			}

		}
		return newRecord;
	}

}
