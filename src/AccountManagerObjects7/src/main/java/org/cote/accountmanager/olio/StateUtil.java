package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class StateUtil {
	public static final Logger logger = LogManager.getLogger(StateUtil.class);
	private static final SecureRandom random = new SecureRandom();
	public static BaseRecord findCell(OlioContext ctx, BaseRecord state, List<BaseRecord> cells, int x, int y) {
		BaseRecord loc = state.get("currentLocation");
		BaseRecord cell = loc;
		if(cells.size() == 0) {
			logger.error("Empty cell list");
			return null;
		}
		BaseRecord par = GeoLocationUtil.getParentLocation(ctx, loc);
		int xedge = (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int yedge = (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int eastings = loc.get("eastings");
		int northings = loc.get("northings");
		if(x < 0 || x > xedge || y < 0 || y > yedge){
			/// cross cell
			if(x < 0) eastings -=1;
			else if(x > xedge) eastings += 1;
			if(y < 0) northings -=1;
			else if(y > xedge) northings += 1;
			if(eastings < 0 || eastings > (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) || northings < 0 || northings > (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1)) {
				logger.info("Cross feature!");
			}
			else {
				final int veast = eastings;
				final int vnorth = northings;
				Optional<BaseRecord> ocell = cells.stream().filter(c -> {
					int ceast = c.get("eastings");
					int cnorth = c.get("northings");
					return (ceast == veast && cnorth == vnorth);
				}).findFirst();
				if(ocell.isPresent()) {
					cell = ocell.get();
				}
				else {
					logger.error("Couldn't find it at " + veast + ", " + vnorth);
				}
			}
		}
		else {
			/// return current location
			return loc;
		}
		return cell;
		
	}
	public static void move(OlioContext context, BaseRecord state, List<BaseRecord> cells, int x, int y, boolean allowCrossCell, boolean allowCrossFeature) {
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			return;
		}
		/// int eastings = loc.get("eastings");
		/// int northings = loc.get("northings");
		int xedge = (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int yedge = (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		long id = loc.get(FieldNames.FIELD_ID);
		BaseRecord node = findCell(context, state, cells, x, y);
		if(node == null) {
			logger.warn("Failed to find node at: " + x + ", " + y);
		}
		else {
			long nid = node.get(FieldNames.FIELD_ID);
			/// different cell
			if(id != nid) {
				if(x < 0) {
					x = xedge;
				}
				else if(x > xedge) {
					x = 0;
				}
				if(y < 0) {
					y = yedge;
				}
				else if(y > yedge) {
					x = 0;
				}
				state.setValue("currentLocation", node);
			}
			state.setValue("currentEast", x);
			state.setValue("currentNorth", y);
			// context.queue(state.copyRecord(new String[] { FieldNames.FIELD_ID, "currentLocation", "currentEast", "currentNorth" }));
		}
		


		
	}
	public static void agitateLocation(OlioContext context, BaseRecord state) {
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			return;
		}
		agitateLocation(context, state, GeoLocationUtil.getCells(context, GeoLocationUtil.getParentLocation(context, loc)));
	}
	public static void agitateLocation(OlioContext context, BaseRecord state, List<BaseRecord> cells) {
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			return;
		}
		int east = state.get("currentEast");
		int north = state.get("currentNorth");
		if(east == -1 || north == -1) {
			setInitialLocation(context, state);
			return;
		}
		int reast = (random.nextInt(-1,1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + east;
		int rnorth = (random.nextInt(-1,1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + north;
		
		move(context, state, cells, reast, rnorth, true, false);
		
	}
	public static boolean setInitialLocation(OlioContext context, BaseRecord state) {
		if(state.get("currentLocation") == null) {
			return false;
		}
		// List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(context, state.get("currentLocation"), 1);
		int east = state.get("currentEast");
		int north = state.get("currentNorth");
		boolean bal = false;
		if(east == -1 || north == -1) {
			try {
				state.set("currentEast", random.nextInt(0, Rules.MAP_EXTERIOR_CELL_WIDTH) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
				state.set("currentNorth", random.nextInt(0, Rules.MAP_EXTERIOR_CELL_HEIGHT) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
				bal = true;
			}
			catch(ModelNotFoundException | FieldException | ValueException e) {
				logger.error(e);
			}
		}
		return bal;
	}
	
	protected static double getDistance(BaseRecord state1, BaseRecord state2) {
		BaseRecord loc1 = state1.get("currentLocation");
		BaseRecord loc2 = state2.get("currentLocation");
		
		int eastings1 = ((int)loc1.get("eastings")) * (Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
		int northings1 = ((int)loc1.get("northings")) * (Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
		int eastings2 = ((int)loc2.get("eastings")) * (Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
		int northings2 = ((int)loc2.get("eastings")) * (Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
		
		int x1 = state1.get("currentEast");
		int y1 = state1.get("currentNorth");
		int x2 = state2.get("currentEast");
		int y2 = state2.get("currentNorth");
		return GeoLocationUtil.distance(x1 + eastings1, y1 + northings1, x2 + eastings2, y2 + northings2);
	}
	

}
