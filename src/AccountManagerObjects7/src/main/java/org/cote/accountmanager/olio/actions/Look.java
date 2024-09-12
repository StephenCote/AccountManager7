package org.cote.accountmanager.olio.actions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.Coordinates;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.PointOfInterestEnumType;
import org.cote.accountmanager.olio.PointOfInterestUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class Look  extends CommonAction implements IAction {
	public static final Logger logger = LogManager.getLogger(Look.class);

	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor)
			throws OlioException {
		// TODO Auto-generated method stub
		
		BaseRecord cell = actor.get("state.currentLocation");
		if(cell == null) {
			throw new OlioException("Missing current location");
		}
		
		return actionResult;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor)
			throws OlioException {
		
		List<String> res = actionResult.get("results");
		
		BaseRecord cell = actor.get("state.currentLocation");
		
		if(cell == null) {
			logger.error("Current location is null");
			/*
			BaseRecord state = actor.get("state");
			logger.error(state.toFullString());
			*/
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.ERROR);
			return false;
			
			
		}
		List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(context, cell, Rules.MAXIMUM_OBSERVATION_DISTANCE);

		TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
		Set<String> stets = acells.stream().filter(c -> TerrainEnumType.valueOf((String)c.get("terrainType")) != tet).map(c -> ((String)c.get("terrainType")).toLowerCase()).collect(Collectors.toSet());
		String tdesc = "an expanse of " + tet.toString().toLowerCase();
		if(stets.size() > 0) {
			tdesc = "a patch of " + tet.toString().toLowerCase() + " near " + stets.stream().collect(Collectors.joining(","));
		}
		res.add(tdesc);
		
		List<BaseRecord> acellsx = new ArrayList<>();
		acellsx.addAll(acells);
		acellsx.add(cell);
		
		int cx = actor.get("state.currentEast");
		int cy = actor.get("state.currentNorth");

		List<BaseRecord> pois = PointOfInterestUtil.listPointsOfInterest(context, Arrays.asList(new BaseRecord[] {cell}), Arrays.asList(new PointOfInterestEnumType[] {PointOfInterestEnumType.RESOURCE, PointOfInterestEnumType.STASH, PointOfInterestEnumType.HARVESTABLE}));
		pois = GeoLocationUtil.sortByDistance(pois, "east", "north", cx, cy);
		for(BaseRecord poi : pois) {
			res.add("POI: " + poi.get("name") + " " + getDistDirStatement(actor, poi, "east", "north"));
		}
		
		BaseRecord realm = context.clock().getRealm();
		List<BaseRecord> zoo = realm.get("zoo");
		List<BaseRecord> zpop = GeoLocationUtil.limitToAdjacent(context, zoo, cell);
		logger.info("Animal population: " + zoo.size() + " [visible = " + zpop.size() + "]");
		if(zpop.size() == 0) {
			res.add("Animal: No animals nearby");
		}
		for(BaseRecord an : zpop) {
			res.add("Animal: " + an.get(FieldNames.FIELD_NAME));
		}
		
		actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);

		return true;
	}
	
	protected String getDistDirStatement(BaseRecord actor, BaseRecord targ, String fieldX, String fieldY) {
		int cx = actor.get("state.currentEast");
		int cy = actor.get("state.currentNorth");
		double dist = GeoLocationUtil.distance(cx, cy, targ.get(fieldX), targ.get(fieldY));
		DirectionEnumType dir = DirectionEnumType.getDirectionFromDegrees(GeoLocationUtil.getAngleBetweenInDegrees(new Coordinates(cx, cy), new Coordinates(targ.get(fieldX), targ.get(fieldY))));
		return dist + " meters " + dir.toString();

	}


	
	

}
