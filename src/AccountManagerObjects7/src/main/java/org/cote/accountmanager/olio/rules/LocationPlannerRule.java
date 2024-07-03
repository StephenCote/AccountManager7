package org.cote.accountmanager.olio.rules;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.DensityEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class LocationPlannerRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(LocationPlannerRule.class);
	
	@Override
	public void generateRegion(OlioContext context, BaseRecord rootEvent, BaseRecord event) {
		BaseRecord location = event.get(FieldNames.FIELD_LOCATION);
		if(location == null) {
			logger.error("Unable to plan without a location");
			return;
		}
		//logger.info(location.toFullString());

		List<BaseRecord> pop = context.getPopulation(location);
		if(pop.isEmpty()){
			logger.error("Unable to plan location with an empty population");
			return;
		}
		
		// logger.info("Planning for " + location.get(FieldNames.FIELD_NAME) + " " + pop.size());	
		Map<String,List<BaseRecord>> demographicMap = context.getDemographicMap(location);
		for(BaseRecord p : pop) {
			OlioUtil.setDemographicMap(context.getOlioUser(), demographicMap, event, p);
		}
		
		DensityEnumType dens = DensityEnumType.valueOf(pop.size());
		int val = DensityEnumType.getValue(dens);
		// logger.info("Density: " + dens.toString());
		String enc = standardVillage;
		if(val > 250) {
			enc = standardHamlet;
		}
		
		GeoLocationUtil.prepareCells(context, location);
		
	}
	private String campFire = "Field|Campfire";
	private String standardVillage = "Village|Village Square|Hut";
	private String standardHamlet = "Hamlet|Town Square|Main Street,SideStreet|House|Living Room,Bedroom";



	@Override
	public void pregenerate(OlioContext context) {
		// TODO Auto-generated method stub
		
	}
	@Override
	public void postgenerate(OlioContext context) {
		// TODO Auto-generated method stub
		
	}
	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		// TODO Auto-generated method stub
		return null;
	}
	@Override
	public BaseRecord generate(OlioContext context) {
		// TODO Auto-generated method stub
		return null;
	}


}
