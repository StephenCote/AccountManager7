package org.cote.accountmanager.olio.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class RandomLocationInitializationRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(RandomLocationInitializationRule.class);
	
	public RandomLocationInitializationRule() {

	}
	
	@Override
	public void pregenerate(OlioContext context) {
	
		
	}

	
	/// Select locationCount + 1 (world) locations randomly from the universe.  The first location will be treated as the world location.
	/// Note: These locations will be copied into the world data
	///
	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		List<BaseRecord> locations = new ArrayList<>();
		Set<String> locSet = new HashSet<>();
		for(int i = 0; i < (context.getConfig().getBaseLocationCount() + 1); i++) {
			BaseRecord loc = GeoLocationUtil.randomLocation(context.getUser(), context.getUniverse());
			if(loc == null) {
				logger.error("Failed to find a random location!");
				return null;
			}
			while(loc != null && locSet.contains(loc.get(FieldNames.FIELD_NAME))) {
				loc = GeoLocationUtil.randomLocation(context.getUser(), context.getUniverse());
			}
			locSet.add(loc.get(FieldNames.FIELD_NAME));
			locations.add(loc);
		}
		return locations.toArray(new BaseRecord[0]);
	}

	
}
