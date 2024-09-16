package org.cote.accountmanager.olio.actions;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.Coordinates;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.PointOfInterestEnumType;
import org.cote.accountmanager.olio.PointOfInterestUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class Gather extends CommonAction implements IAction {
	public static final Logger logger = LogManager.getLogger(Gather.class);


	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		BaseRecord realm = context.clock().getRealm();
		if(realm == null) {
			throw new OlioException("Unable to load realm");
		}
		
		BaseRecord cell = actor.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
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
		
		edgeEnd(context, actionResult, quantity);
		
		return actionResult;
	}
	

	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		BaseRecord params = actionResult.get("parameters");

		String itemCategory = params.get("itemCategory");
		int quantity = params.get("quantity");
		
		boolean gathered = false;
		
		List<BaseRecord> dacts = actionResult.get("dependentActions");
		if(dacts.stream().filter(a -> (a.getEnum("type") == ActionResultEnumType.PENDING || a.getEnum("type") == ActionResultEnumType.IN_PROGRESS)).findFirst().isPresent()) {
			// logger.info("Gather action waiting on dependent action");
			return false;
		}
		

		BaseRecord cell = actor.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
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
		
		List<BaseRecord> pois = PointOfInterestUtil.listPointsOfInterest(context, Arrays.asList(new BaseRecord[] {cell}), Arrays.asList(new PointOfInterestEnumType[] {PointOfInterestEnumType.RESOURCE, PointOfInterestEnumType.STASH, PointOfInterestEnumType.HARVESTABLE}));
		if(pois.size() == 0) {
			// logger.info("Scan horizon for other points of interest");
			pois = PointOfInterestUtil.listPointsOfInterest(context, acells, Arrays.asList(new PointOfInterestEnumType[] {PointOfInterestEnumType.RESOURCE, PointOfInterestEnumType.STASH, PointOfInterestEnumType.HARVESTABLE}));	
		}
		// logger.info("Can see " + pois.size() + " points of interest" + (pois.size() > 0 && itemCategory != null ? ", but are there any with " + itemCategory + "?": ""));
		/*
		for(BaseRecord p : pois) {
			BaseRecord bld = p.get("builder");
			if(bld != null) {
				List<BaseRecord> tags = bld.get(FieldNames.FIELD_TAGS);
				String ts = tags.stream().map(t -> (String)t.get(FieldNames.FIELD_NAME)).collect(Collectors.joining(","));
				logger.info(bld.get(FieldNames.FIELD_NAME) + " " + ts);
			}
		}
		*/
		/// Filter to any category
		if(itemCategory != null) {
			
			pois = pois.stream().filter(p -> {
				BaseRecord bld = p.get("builder");
				boolean ret = false;
				if(bld != null) {
					List<BaseRecord> tags = bld.get(FieldNames.FIELD_TAGS);
					ret = tags.stream().filter(t -> itemCategory.equals(t.get(FieldNames.FIELD_NAME))).findFirst().isPresent();
				}
				return ret;
			}).collect(Collectors.toList());
		}
		int cx = actor.get("state.currentEast");
		int cy = actor.get("state.currentNorth");

		List<String> trace = actionResult.get("trace");
		pois = GeoLocationUtil.sortByDistance(pois, "east", "north", cx, cy).stream().filter(p -> !trace.contains(p.get(FieldNames.FIELD_OBJECT_ID))).collect(Collectors.toList());

		BaseRecord realm = context.clock().getRealm();
		List<BaseRecord> zoo = realm.get(OlioFieldNames.FIELD_ZOO);
		List<BaseRecord> zpop = GeoLocationUtil.limitToAdjacent(context, zoo, cell);
		List<BaseRecord> dzpop = zpop.stream().filter(a -> (boolean)a.get("state.alive")).collect(Collectors.toList());
		if(pois.size() == 0 && dzpop.size() == 0) {
			logger.warn("There are no " + (itemCategory != null ? itemCategory + " " : "") + "resource points of interests or dead animals in this part of " + tet.toString() + " around " + cell.get(FieldNames.FIELD_NAME));
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
			return false;
		}
		/// Find animals in the current and adjacent cells
		/*
		String anames = zpop.stream().map(a -> (String)a.get(FieldNames.FIELD_NAME)).collect(Collectors.toSet()).stream().collect(Collectors.joining(", "));
		String adesc = "No animals seem to be nearby.";
		if(anames.length() > 0) {
			adesc ="Some animals are close, including " + anames + ".";
		}
		*/
		String anames = dzpop.stream().map(a -> (String)a.get(FieldNames.FIELD_NAME)).collect(Collectors.toSet()).stream().collect(Collectors.joining(", "));
		String adesc = "No animal remains are nearby.";
		if(anames.length() > 0) {
			adesc ="Some animal remains are close, including " + anames + ".";
		}

		//logger.info("Points of interest: " + pois.size());
		/*
		for(BaseRecord poi : pois) {
			PointOfInterestEnumType type = poi.getEnum(FieldNames.FIELD_TYPE);
			BaseRecord bld = poi.get("builder");
			logger.info("Point of interest (" + type.toString().toLowerCase() + ") " + (bld == null ? "Unknown" : bld.get(FieldNames.FIELD_NAME)));
		}
		*/

		/*
		ZonedDateTime ep = actionResult.get("actionStart");
		ZonedDateTime ee = actionResult.get("actionEnd");
		long remSec = ep.until(ee, ChronoUnit.SECONDS);
		logger.info("Seconds ... " + remSec + " from " + ep + " / " + ee);
		*/
		
		if(pois.size() > 0) {
			/// Choose nearest poi
			BaseRecord poi = pois.get(0);
			
			double dist = GeoLocationUtil.distance(cx, cy, poi.get("east"), poi.get("north"));
			PointOfInterestEnumType type = poi.getEnum(FieldNames.FIELD_TYPE);
			BaseRecord bld = poi.get("builder");
			
			if(dist > Rules.PROXIMATE_CONTACT_DISTANCE) {
				// logger.info("Too far away - " + dist + " meters.  Need to move closer.");
				DirectionEnumType dir = DirectionEnumType.getDirectionFromDegrees(GeoLocationUtil.getAngleBetweenInDegrees(new Coordinates(cx, cy), new Coordinates(poi.get("east"), poi.get("north"))));
				BaseRecord move = Actions.beginMove(context, context.clock().getIncrement(), actor, dir);
				move.setValue("state.currentEast", poi.get("east"));
				move.setValue("state.currentNorth", poi.get("north"));
				actionResult.setValue("actionEnd", move.get("actionEnd"));
				
				edgeEnd(context, actionResult, quantity);
				
				Actions.dependAction(context, actionResult, move);
				return true;
			}

			List<BaseRecord> inv = poi.get("store.inventory");
			if(itemCategory != null) {
				inv = inv.stream().filter(t -> {
					List<BaseRecord> tags = t.get("item.tags");
					return tags.stream().filter(it -> itemCategory.equals(it.get(FieldNames.FIELD_NAME))).findFirst().isPresent();
				}).collect(Collectors.toList());
				
			}
			if(inv.size() > 0) {
				BaseRecord iinv = inv.get(0);
				int count = ItemUtil.countItemInInventory(context, poi, (BaseRecord)iinv.get("item"));
				if(count < quantity || count == 0) {
					logger.info("Not enough (" + count + " < " + quantity + ") at this location.  Checking again - ");
					trace.add(poi.get(FieldNames.FIELD_OBJECT_ID));
					Queue.queueUpdate(actionResult, new String[] {"trace"});
				}
				else {
					logger.info("Gather from point of interest (" + type.toString().toLowerCase() + ") " + (bld == null ? "Unknown" : bld.get(FieldNames.FIELD_NAME)) + " " + dist + " meters away");
	
					boolean withdraw = ItemUtil.withdrawItemFromInventory(context, poi, (BaseRecord)iinv.get("item"), quantity);
					if(withdraw) {
						int minSeconds = actionResult.get("action.minimumTime");
						ActionUtil.addProgressSeconds(actionResult, minSeconds * quantity);
						boolean deposit = ItemUtil.depositItemIntoInventory(context, actor, (BaseRecord)iinv.get("item"), quantity);
						if(deposit) {
							logger.info("Gathered " + quantity + " " + iinv.get("item.name"));
							actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);
							gathered = true;
						}
						else {
							logger.warn("Failed to store " + quantity + " of " + iinv.get("item.name"));
							actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
						}
					}
					else {
						logger.warn("Failed to collect " + quantity + " of " + iinv.get("item.name"));
						logger.warn(iinv.toFullString());
						actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
					}
				}
			}
			else {
				logger.warn("Nothing to collect");
				// logger.warn(poi.toFullString());;
				actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
			}
		}
		
		//logger.info(adesc);

		return gathered;
	}
	
	
}
