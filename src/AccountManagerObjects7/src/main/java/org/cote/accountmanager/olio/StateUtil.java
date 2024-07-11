package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioStateRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class StateUtil {
	public static final Logger logger = LogManager.getLogger(StateUtil.class);
	private static final SecureRandom random = new SecureRandom();
	
	public static void queueUpdate(OlioContext context, BaseRecord obj) {
		BaseRecord state = obj;
		if(!obj.getModel().equals(ModelNames.MODEL_CHAR_STATE)) {
			state = obj.get(FieldNames.FIELD_STATE);
		}
		context.queueUpdate(state, new String[] { "currentLocation", "currentEast", "currentNorth" });
	}
	public static boolean move(OlioContext context, BaseRecord animal, DirectionEnumType dir) {
		BaseRecord state = animal.get(FieldNames.FIELD_STATE);
		if(dir == DirectionEnumType.UNKNOWN) {
			logger.warn("Direction is unknown");
			return false;
		}
		if(state == null) {
			logger.warn("State is null");
			return false;
		}
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			logger.warn("Location is null");
			return false;
		}
		List<BaseRecord> cells = GeoLocationUtil.getCells(context, GeoLocationUtil.getParentLocation(context, loc));
		if(cells.size() == 0) {
			logger.warn("Failed to find adjacent cells");
			return false;
		}

		int x = state.get("currentEast");
		int y = state.get("currentNorth");
		
		int rx = (DirectionEnumType.getX(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + x;
		int ry = (DirectionEnumType.getY(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + y;

		moveByOne(context, animal, cells, rx, ry, true, true);
		
		BaseRecord nloc = state.get("currentLocation");
		if(nloc == null) {
			logger.warn("State currentLocation is null");
			return false;
		}
		// logger.info("Moved " + x + ", " + y + " to " + (int)state.get("currentEast") + ", " + (int)state.get("currentNorth"));
		//context.queueUpdate(state, new String[] { "currentLocation", "currentEast", "currentNorth" });
		
		return (
		x != (int)state.get("currentEast")
		||
		y != (int)state.get("currentNorth")
		//	(long)nloc.get(FieldNames.FIELD_ID) == (long)loc.get(FieldNames.FIELD_ID)
		);
		
	}
	
	public static void moveByOne(OlioContext context, BaseRecord animal, List<BaseRecord> cells, int x, int y, boolean allowCrossCell, boolean allowCrossFeature) {
		BaseRecord state = animal.get("state");
		if(state == null) {
			return;
		}

		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			return;
		}
		int xedge = (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int yedge = (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		long id = loc.get(FieldNames.FIELD_ID);
		BaseRecord node = GeoLocationUtil.findCellToEdgePlusOne(context, state.get("currentLocation"), cells, x, y, allowCrossFeature);
		if(node == null) {
			// logger.warn("Failed to find node at: " + x + ", " + y);
			/// failed to find node.  don't move.  just bail
			///
			return;
		}
		else {
			long nid = node.get(FieldNames.FIELD_ID);
			/// different cell
			if(id != nid) {
				/// TODO : Currently, every move will cross a cell
				//logger.info("Cross cell");
				if(!allowCrossCell) {
					return;
				}
				if(!evaluateCanMoveTo(context, animal, node)) {
					return;
				}
				/// logger.warn("Moving from cell " + id + " to " + nid);
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
					y = 0;
				}
				state.setValue("currentLocation", node);
			}
			else {
				if(x < 0 || x > xedge || y < 0 || y > yedge) {
					logger.warn("Banging into border: " + node.get("id") + " " + x + ", " + y);
				}
			}
			if(y >= 0 && y <= yedge) {
				state.setValue("currentNorth", y);
			}
			if(x >= 0 && x <= xedge) {
				state.setValue("currentEast", x);
			}
			// logger.info("Move " + x + ", " + y);
			// context.queue(state.copyRecord(new String[] { FieldNames.FIELD_ID, "currentLocation", "currentEast", "currentNorth" }));
		}
	}
	
	public static boolean evaluateCanMoveTo(OlioContext ctx, BaseRecord animal, BaseRecord cell) {
		boolean canMove = true;
		for(IOlioStateRule rule : ctx.getConfig().getStateRules()) {
			canMove = rule.canMove(ctx, animal, cell);
			if(!canMove) {
				break;
			}
		}
		return canMove;
		
	}
	

	
	public static void agitateLocation(OlioContext context, BaseRecord animal) {
		BaseRecord state = animal.get("state");
		if(state == null) {
			return;
		}
		
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			logger.warn("Location is null");
			return;
		}

		agitateLocation(context, animal, GeoLocationUtil.getCells(context, GeoLocationUtil.getParentLocation(context, loc)));
	}
	public static void agitateLocation(OlioContext context, BaseRecord animal, List<BaseRecord> cells) {
		BaseRecord state = animal.get("state");
		if(state == null) {
			return;
		}
		
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			logger.warn("Location is null");
			return;
		}
		
		/// If the state is currently committed to an action, don't agitate it's location
		///
		List<BaseRecord> acts = state.get("actions");
		if(acts.size() > 0) {
			logger.warn("Don't agitate with current action");
			return;
		}

		int east = state.get("currentEast");
		int north = state.get("currentNorth");
		if(east == -1 || north == -1) {
			/// logger.warn("Set initial location");
			setInitialLocation(context, state);
			return;
		}
		int reast = (random.nextInt(-1,1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + east;
		int rnorth = (random.nextInt(-1,1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + north;

		moveByOne(context, animal, cells, reast, rnorth, true, false);
		// logger.info("Move from " + east + ", " + north + " to " + state.get("currentEast") + ", " + state.get("currentNorth"));
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
	
	public static double getDistance(BaseRecord state1, BaseRecord state2) {
		BaseRecord loc1 = state1.get("currentLocation");
		BaseRecord loc2 = state2.get("currentLocation");
		if(loc1 == null || loc2 == null) {
			logger.error("Null location");
			return 0.0;
		}
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
	
	public static List<BaseRecord> observablePopulation(List<BaseRecord> pop, BaseRecord pov){
		return pop.stream().filter(p -> {
				double dist = StateUtil.getDistance(pov.get("state"), p.get("state"));
				int max = Rules.MAXIMUM_OBSERVATION_DISTANCE * Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
				// logger.info("Distance: " + dist + "::" + max);
				boolean filt =(((long)p.get(FieldNames.FIELD_ID)) != (long)pov.get(FieldNames.FIELD_ID)
				&&
				dist <= max
				);
				return filt;
			}
		).collect(Collectors.toList());
	}

}
