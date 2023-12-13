package org.cote.accountmanager.olio.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class GenericLocationInitializationRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(GenericLocationInitializationRule.class);
	
	private String rootLocationName = null;
	private String[] locationNames = null;
	public GenericLocationInitializationRule(String rootLocationName, String[] locationNames) {
		this.rootLocationName = rootLocationName;
		this.locationNames = locationNames;
	}
	
	@Override
	public void pregenerate(OlioContext context) {
		long id = context.getUniverse().get("locations.id");
		if(IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id)) > 0) {
			logger.info("Locations already setup");
			return;
		}
		String path = context.getUniverse().get("locations.path");
		if(path == null) {
			logger.error("Path is not specified");
			return;
		}
		int geonameid = 1;
		BaseRecord world = GeoLocationUtil.createLocation(context, null, rootLocationName, geonameid++);
		for(String s : locationNames) {
			GeoLocationUtil.createLocation(context, world, s, geonameid++);
		}
		
	}

	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		List<BaseRecord> recs = new ArrayList<>();
		long id = context.getUniverse().get("locations.id");

		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id);
		pq.field(FieldNames.FIELD_PARENT_ID, 0L);
		BaseRecord world = IOSystem.getActiveContext().getSearch().findRecord(pq);
		if(world == null){
			logger.error("No world locations found");
			return new BaseRecord[0];
		}
		recs.add(world);
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id);
		lq.field(FieldNames.FIELD_PARENT_ID, world.get(FieldNames.FIELD_ID));
		lq.setRequestRange(0L, context.getConfig().getBaseLocationCount());
		recs.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(lq)));
		logger.info("Returning " + recs.size() + " locs");
		return recs.toArray(new BaseRecord[0]);
	}

	
}
