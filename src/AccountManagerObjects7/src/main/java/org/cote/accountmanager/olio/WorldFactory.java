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
		BaseRecord dir = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		return dir;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		if(parameterList != null) {
			String name = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_NAME, String.class, null);
			String path = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_PATH, String.class, null);
			if(name != null && path != null && contextUser != null) {
				IPath pu = IOSystem.getActiveContext().getPathUtil();
				try {
					long orgId = contextUser.get(FieldNames.FIELD_ORGANIZATION_ID);
					String gtype = GroupEnumType.DATA.toString();
					String mtype = ModelNames.MODEL_GROUP;
					newRecord.set(OlioFieldNames.FIELD_ADDRESSES, pu.makePath(contextUser, mtype, path + "/" + name + "/Addresses", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_CONTACTS, pu.makePath(contextUser, mtype, path + "/" + name + "/Contacts", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_POPULATION, pu.makePath(contextUser, mtype, path + "/" + name + "/Population", gtype, orgId));
					newRecord.set(FieldNames.FIELD_LOCATIONS, pu.makePath(contextUser, mtype, path + "/" + name + "/Locations", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_EVENTS, pu.makePath(contextUser, mtype, path + "/" + name + "/Events", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_WORDS, pu.makePath(contextUser, mtype, path + "/" + name + "/Words", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_TRAITS, pu.makePath(contextUser, mtype, path + "/" + name + "/Traits", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_COLORS, pu.makePath(contextUser, mtype, path + "/" + name + "/Colors", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_NAMES, pu.makePath(contextUser, mtype, path + "/" + name + "/Names", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_SURNAMES, pu.makePath(contextUser, mtype, path + "/" + name + "/Surnames", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_OCCUPATIONS, pu.makePath(contextUser, mtype, path + "/" + name + "/Occupations", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_DICTIONARY, pu.makePath(contextUser, mtype, path + "/" + name + "/Dictionary", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_APPAREL, pu.makePath(contextUser, mtype, path + "/" + name + "/Apparel", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_WEARABLES, pu.makePath(contextUser, mtype, path + "/" + name + "/Wearables", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_QUALITIES, pu.makePath(contextUser, mtype, path + "/" + name + "/Qualities", gtype, orgId));
					newRecord.set(FieldNames.FIELD_PATTERNS, pu.makePath(contextUser, mtype, path + "/" + name + "/Patterns", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_STATISTICS, pu.makePath(contextUser, mtype, path + "/" + name + "/Statistics", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_INSTINCTS, pu.makePath(contextUser, mtype, path + "/" + name + "/Instincts", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_BEHAVIORS, pu.makePath(contextUser, mtype, path + "/" + name + "/Behaviors", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_STATES, pu.makePath(contextUser, mtype, path + "/" + name + "/States", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_ACTIONS, pu.makePath(contextUser, mtype, path + "/" + name + "/Actions", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_STORES, pu.makePath(contextUser, mtype, path + "/" + name + "/Stores", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_ACTION_RESULTS, pu.makePath(contextUser, mtype, path + "/" + name + "/ActionResults", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_SCHEDULES, pu.makePath(contextUser, mtype, path + "/" + name + "/Schedules", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_PERSONALITIES, pu.makePath(contextUser, mtype, path + "/" + name + "/Personalities", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_BUILDERS, pu.makePath(contextUser, mtype, path + "/" + name + "/Builders", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_ITEMS, pu.makePath(contextUser, mtype, path + "/" + name + "/Items", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_TAGS_GROUP, pu.makePath(contextUser, mtype, path + "/" + name + "/Tags", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_ANIMALS, pu.makePath(contextUser, mtype, path + "/" + name + "/Animals", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_REALMS_GROUP, pu.makePath(contextUser, mtype, path + "/" + name + "/Realms", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_INVENTORIES, pu.makePath(contextUser, mtype, path + "/" + name + "/Inventories", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_INTERACTIONS, pu.makePath(contextUser, mtype, path + "/" + name + "/Interactions", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_NARRATIVES, pu.makePath(contextUser, mtype, path + "/" + name + "/Narratives", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_PROFILES, pu.makePath(contextUser, mtype, path + "/" + name + "/Profiles", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_GALLERY, pu.makePath(contextUser, mtype, path + "/" + name + "/Gallery", gtype, orgId));
					newRecord.set(OlioFieldNames.FIELD_POINTS_OF_INTEREST, pu.makePath(contextUser, mtype, path + "/" + name + "/Points of Interest", gtype, orgId));
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
			}

		}
		return newRecord;
	}

}
