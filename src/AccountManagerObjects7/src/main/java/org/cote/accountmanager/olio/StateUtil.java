package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;

public class StateUtil {
	public static final Logger logger = LogManager.getLogger(StateUtil.class);
	private static final SecureRandom random = new SecureRandom();
	
	public static boolean agitateLocation(OlioContext context, BaseRecord state) {
		if(state.get("currentLocation") == null) {
			return false;
		}
		// List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(context, state.get("currentLocation"), 1);
		int east = state.get("currentEast");
		int north = state.get("currentNorth");
		boolean bal = false;
		if(east == 0 || north == 0) {
			try {
				state.set("currentEast", random.nextInt(1, Rules.MAP_EXTERIOR_CELL_WIDTH) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
				state.set("currentNorth", random.nextInt(1, Rules.MAP_EXTERIOR_CELL_HEIGHT) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
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
