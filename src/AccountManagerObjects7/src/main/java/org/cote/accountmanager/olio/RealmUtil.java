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
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.MemberUtil;

public class RealmUtil {
	public static final Logger logger = LogManager.getLogger(RealmUtil.class);
	
	public static List<BaseRecord> getRealms(OlioContext context){
		return Arrays.asList(OlioUtil.list(context, OlioModelNames.MODEL_REALM, "realms"));
	}
	
	public static BaseRecord getCreateRealm(OlioContext ctx, BaseRecord origin) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_REALM, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("realmsGroup.id"));
		q.field("origin", origin.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID}));
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

		ParameterList plist = ParameterList.newParameterList("path", world.get("realmsGroup.path"));
		BaseRecord realm = null;
		IOSystem.getActiveContext().getReader().populate(origin, new String[] {FieldNames.FIELD_NAME});
		try {
			realm = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_REALM, ctx.getOlioUser(), null, plist);
			realm.set("origin", origin);
			//realm.set("population", ctx.getPopulationGroup(origin, "Population"));
			realm.set("store", IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("stores.path"))));
			realm.set(FieldNames.FIELD_NAME, "Realm " + origin.get(FieldNames.FIELD_NAME));
			List<BaseRecord> locations = realm.get("locations");
			BaseRecord[] locs = GeoLocationUtil.getLocationsByFeature(origin, origin.get("feature"), 0L);
			locations.addAll(Arrays.asList(locs));
			IOSystem.getActiveContext().getRecordUtil().updateRecord(realm);
			
			BaseRecord part = ParticipationFactory.newParticipation(ctx.getOlioUser(), ctx.getWorld(), "realms", realm);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(part);
			
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return realm;
	}
	
	public static void updateRealmLocations(OlioContext ctx, BaseRecord realm, boolean refresh) {
		BaseRecord origin = realm.get("origin");
		List<BaseRecord> locations = realm.get("locations");
		if(refresh || locations.size() == 0) {
			MemberUtil mu = IOSystem.getActiveContext().getMemberUtil();
			for(BaseRecord l : locations) {
				mu.member(ctx.getOlioUser(), realm, "locations", l, null, false);
			}
			locations.clear();
			BaseRecord[] locs = GeoLocationUtil.getLocationsByFeature(origin, origin.get("feature"), 0L);
			locations.addAll(Arrays.asList(locs));
			for(BaseRecord l : locations) {
				mu.member(ctx.getOlioUser(), realm, "locations", l, null, true);
			}
			if(locations.size() == 0) {
				logger.warn("Failed to find realm locations");
			}
		}
	}
	
	 
}
