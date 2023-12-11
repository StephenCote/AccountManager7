package org.cote.accountmanager.olio.rules;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class CustomLocationInitializationRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(CustomLocationInitializationRule.class);
	
	private String rootLocationName = null;
	private String[] locationNames = null;
	public CustomLocationInitializationRule(String rootLocationName, String[] locationNames) {
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

	
}
