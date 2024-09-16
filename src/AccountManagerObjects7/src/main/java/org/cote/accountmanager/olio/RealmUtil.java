package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.MemberUtil;

public class RealmUtil {
	public static final Logger logger = LogManager.getLogger(RealmUtil.class);
	
	public static List<BaseRecord> getRealms(OlioContext context){
		return Arrays.asList(OlioUtil.list(context, OlioModelNames.MODEL_REALM, OlioFieldNames.FIELD_REALMS));
	}
	
	public static BaseRecord getCreateRealm(OlioContext ctx, BaseRecord origin) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_REALM, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get(OlioFieldNames.FIELD_REALMS_GROUP_ID));
		q.field(OlioFieldNames.FIELD_ORIGIN, origin.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID}));
		OlioUtil.planMost(q);
		BaseRecord realm = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(realm == null) {
			realm = createRealm(ctx, origin);
		}
		else {
			updateRealmLocations(ctx, realm, false);
		}
		return realm;
	}
	
	protected static BaseRecord createRealm(OlioContext ctx, BaseRecord origin) {
		BaseRecord world = ctx.getWorld();

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, world.get(OlioFieldNames.FIELD_REALMS_GROUP_PATH));
		BaseRecord realm = null;
		IOSystem.getActiveContext().getReader().populate(origin, new String[] {FieldNames.FIELD_NAME});
		try {
			realm = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_REALM, ctx.getOlioUser(), null, plist);
			realm.set(OlioFieldNames.FIELD_ORIGIN, origin);
			//realm.set(OlioFieldNames.FIELD_POPULATION, ctx.getPopulationGroup(origin, "Population"));
			realm.set(FieldNames.FIELD_STORE, IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get(OlioFieldNames.FIELD_STORES_PATH))));
			realm.set(FieldNames.FIELD_NAME, "Realm " + origin.get(FieldNames.FIELD_NAME));
			List<BaseRecord> locations = realm.get(FieldNames.FIELD_LOCATIONS);
			BaseRecord[] locs = GeoLocationUtil.getLocationsByFeature(origin, origin.get(FieldNames.FIELD_FEATURE), 0L);
			locations.addAll(Arrays.asList(locs));
			IOSystem.getActiveContext().getRecordUtil().updateRecord(realm);
			
			BaseRecord part = ParticipationFactory.newParticipation(ctx.getOlioUser(), ctx.getWorld(), OlioFieldNames.FIELD_REALMS, realm);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(part);
			
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return realm;
	}
	
	public static void updateRealmLocations(OlioContext ctx, BaseRecord realm, boolean refresh) {
		BaseRecord origin = realm.get(OlioFieldNames.FIELD_ORIGIN);
		List<BaseRecord> locations = realm.get(FieldNames.FIELD_LOCATIONS);
		if(refresh || locations.size() == 0) {
			MemberUtil mu = IOSystem.getActiveContext().getMemberUtil();
			for(BaseRecord l : locations) {
				mu.member(ctx.getOlioUser(), realm, FieldNames.FIELD_LOCATIONS, l, null, false);
			}
			locations.clear();
			BaseRecord[] locs = GeoLocationUtil.getLocationsByFeature(origin, origin.get(FieldNames.FIELD_FEATURE), 0L);
			locations.addAll(Arrays.asList(locs));
			for(BaseRecord l : locations) {
				mu.member(ctx.getOlioUser(), realm, FieldNames.FIELD_LOCATIONS, l, null, true);
			}
			if(locations.size() == 0) {
				logger.warn("Failed to find realm locations");
			}
		}
	}
	
	 
}
