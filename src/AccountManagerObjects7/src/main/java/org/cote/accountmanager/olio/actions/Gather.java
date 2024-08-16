package org.cote.accountmanager.olio.actions;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NeedsUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.PointOfInterestEnumType;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class Gather extends CommonAction implements IAction {
	public static final Logger logger = LogManager.getLogger(Gather.class);


	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		BaseRecord realm = context.getRealm(context.getCurrentLocation());
		if(realm == null) {
			throw new OlioException("Unable to load realm");
		}
		
		BaseRecord cell = actor.get("state.currentLocation");
		if(cell == null) {
			throw new OlioException("Missing current location");
		}
		BaseRecord params = actionResult.get("parameters");
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		String itemCategory = params.get("itemCategory");
		if(itemCategory == null) {
			throw new OlioException("Item category required");
		}

		int quantity = params.get("quantity");
		if(quantity <= 0) {
			quantity = 1;
			params.setValue("quantity", 1);
		}
		
		int minSeconds = actionResult.get("action.minimumTime");
		ActionUtil.edgeSecondsUntilEnd(actionResult, minSeconds * quantity);
		context.queueUpdate(actionResult, new String[]{"actionEnd"});

		return actionResult;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		BaseRecord params = actionResult.get("parameters");

		String itemCategory = params.get("itemCategory");
		int quantity = params.get("quantity");
		
		boolean gathered = false;

		BaseRecord cell = actor.get("state.currentLocation");
		List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(context, cell, Rules.MAXIMUM_OBSERVATION_DISTANCE);

		TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
		Set<String> stets = acells.stream().filter(c -> TerrainEnumType.valueOf((String)c.get("terrainType")) != tet).map(c -> ((String)c.get("terrainType")).toLowerCase()).collect(Collectors.toSet());
		String tdesc = "an expanse of " + tet.toString().toLowerCase();
		if(stets.size() > 0) {
			tdesc = "a patch of " + tet.toString().toLowerCase() + " near " + stets.stream().collect(Collectors.joining(","));
		}
		/// add current cell to adjacent list
		//acells.add(cell);
		//logger.info("Gathering in " + tdesc + " across " + acells.size() + " cells from " + cell.get(FieldNames.FIELD_NAME));
		/// Look for points of interest with resource or harvestable builders
		///
		
		List<BaseRecord> pois = GeoLocationUtil.listPointsOfInterest(context, Arrays.asList(new BaseRecord[] {cell}), Arrays.asList(new PointOfInterestEnumType[] {PointOfInterestEnumType.RESOURCE, PointOfInterestEnumType.STASH, PointOfInterestEnumType.HARVESTABLE}));
		if(pois.size() == 0) {
			logger.info("Scan horizon for other points of interest");
			pois = GeoLocationUtil.listPointsOfInterest(context, acells, Arrays.asList(new PointOfInterestEnumType[] {PointOfInterestEnumType.RESOURCE, PointOfInterestEnumType.STASH, PointOfInterestEnumType.HARVESTABLE}));	
		}
		BaseRecord realm = context.getRealm(context.getCurrentLocation());
		List<BaseRecord> zoo = realm.get("zoo");
		List<BaseRecord> zpop = GeoLocationUtil.limitToAdjacent(context, zoo, cell);
		List<BaseRecord> dzpop = zpop.stream().filter(a -> (boolean)a.get("state.alive")).collect(Collectors.toList());
		if(pois.size() == 0 && dzpop.size() == 0) {
			logger.warn("There are no resource points of interests or dead animals");
		}
		/// Find animals in the current and adjacent cells
		String anames = zpop.stream().map(a -> (String)a.get("name")).collect(Collectors.toSet()).stream().collect(Collectors.joining(", "));
		String adesc = "No animals seem to be nearby.";
		if(anames.length() > 0) {
			adesc ="Some animals are close, including " + anames + ".";
		}
		logger.info("Points of interest: " + pois.size());
		for(BaseRecord poi : pois) {
			PointOfInterestEnumType type = poi.getEnum(FieldNames.FIELD_TYPE);
			BaseRecord bld = poi.get("builder");
			logger.info(type.toString() + " " + (bld == null ? "Unknown" : bld.get(FieldNames.FIELD_NAME)));
		}
		//logger.info(adesc);

		return gathered;
	}
	
	
}
