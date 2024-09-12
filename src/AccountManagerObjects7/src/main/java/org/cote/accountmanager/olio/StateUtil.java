package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.rules.IOlioStateRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class StateUtil {
	public static final Logger logger = LogManager.getLogger(StateUtil.class);
	private static final SecureRandom random = new SecureRandom();
	
	public static void queueUpdateLocation(OlioContext context, BaseRecord obj) {
		BaseRecord state = obj;
		if(!obj.getModel().equals(ModelNames.MODEL_CHAR_STATE)) {
			state = obj.get(FieldNames.FIELD_STATE);
		}
		Queue.queueUpdate(state, new String[] { "currentLocation", "currentEast", "currentNorth" });
	}
	
	/*
	 * NOTE About State and Cell positions:
	 * Cells are 100 square meters, parented to 1 square kilometer, and have eastings and northings numbered 0 - 9.
	 * State.currentEast and currentNorth marks the position in the 100 meter square within the cell, and are numbered 0 - 99.
	 */
	
	
	/// NOTE: Does not queue update
	public static boolean moveByOneMeterInCell(OlioContext context, BaseRecord animal, DirectionEnumType dir) {
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
		/*
		int rx = (DirectionEnumType.getX(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + x;
		int ry = (DirectionEnumType.getY(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + y;
		*/
		int rx = DirectionEnumType.getX(dir) + x;
		int ry = DirectionEnumType.getY(dir) + y;
		moveByOne(context, animal, cells, rx, ry, true, true);
		
		BaseRecord nloc = state.get("currentLocation");
		if(nloc == null) {
			logger.warn("State currentLocation is null");
			return false;
		}
		// logger.info("Moved " + x + ", " + y + " to " + (int)state.get("currentEast") + ", " + (int)state.get("currentNorth"));
		//Queue.queueUpdate(state, new String[] { "currentLocation", "currentEast", "currentNorth" });
		
		return (
		x != (int)state.get("currentEast")
		||
		y != (int)state.get("currentNorth")
		//	(long)nloc.get(FieldNames.FIELD_ID) == (long)loc.get(FieldNames.FIELD_ID)
		);
		
	}
	
	/// NOTE: Does not queue update
	private static void moveByOne(OlioContext context, BaseRecord animal, List<BaseRecord> cells, int stateX, int stateY, boolean allowCrossCell, boolean allowCrossFeature) {
		BaseRecord state = animal.get("state");
		if(state == null) {
			return;
		}

		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			return;
		}
		/*
		int xedge = (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int yedge = (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		*/
		int xedge = Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int yedge = Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		
		long id = loc.get(FieldNames.FIELD_ID);
		/*
		/// CellX/Y used to test the boundary within the cell, and if it exceeds the boundary, change the location to the adjacent cell, and set the state position to that corresponding edge
		///
		int cellX = stateX;
		int cellY = stateY;
		
		if(stateX != 0) cellX = (int)Math.floor((double)cellX)/Rules.MAP_EXTERIOR_CELL_WIDTH;
		if(stateY != 0) cellY = (int)Math.floor((double)cellY)/Rules.MAP_EXTERIOR_CELL_HEIGHT;
		*/
		// logger.info("Find cell: " + stateX + ", " + stateY);
		BaseRecord node = GeoLocationUtil.findCellToEdgePlusOne(context, state.get("currentLocation"), cells, stateX, stateY, allowCrossFeature);
		if(node == null) {
			// logger.warn("Failed to find node at: " + x + ", " + y);
			/// failed to find node.  don't move.  just bail
			///
			return;
		}
		else {
			long nid = node.get(FieldNames.FIELD_ID);
			if(!evaluateCanMoveTo(context, animal, node)) {
				return;
			}
			/// different cell
			if(id != nid) {
				/// TODO : Currently, every move will cross a cell
				// logger.info("Cross cell!");
				if(!allowCrossCell) {
					return;
				}

				/// logger.warn("Moving from cell " + id + " to " + nid);
				if(stateX < 0) {
					stateX = xedge - 1;
				}
				else if(stateX >= xedge) {
					stateX = 0;
				}
				if(stateY < 0) {
					stateY = yedge - 1;
				}
				else if(stateY >= yedge) {
					stateY = 0;
				}
				state.setValue("currentLocation", node);
			}
			else {
				if(stateX < 0 || stateX >= xedge || stateY < 0 || stateY >= yedge) {
					logger.warn("Banging into border: " + node.get("id") + " " + stateX + ", " + stateY);
				}
			}
			if(stateY < 0 || stateY >= yedge) {
				logger.error("Failed to navigate intra-cell north to " + stateX + "::" + xedge + ", " + stateY + "::" + yedge);
			}
			if(stateX < 0 || stateX >= xedge) {
				logger.error("Failed to navigate intra-cell east to " + stateX + "::" + xedge + ", " + stateY + "::" + yedge);
			}

			if(stateY >= 0 && stateY < yedge) {
				state.setValue("currentNorth", stateY);
			}
			if(stateX >= 0 && stateX < xedge) {
				state.setValue("currentEast", stateX);
			}
			// logger.info("Moved To: " + cellX + "." + stateX + ", " + cellY + "." + stateY);
			// logger.info("Move " + x + ", " + y);
			// Queue.queue(state.copyRecord(new String[] { FieldNames.FIELD_ID, "currentLocation", "currentEast", "currentNorth" }));
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
	

	
	public static boolean agitateLocation(OlioContext context, BaseRecord animal) {
		BaseRecord state = animal.get("state");
		if(state == null) {
			return false;
		}
		
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			logger.warn("Location is null");
			return false;
		}

		return agitateLocation(context, animal, GeoLocationUtil.getCells(context, GeoLocationUtil.getParentLocation(context, loc)));
	}
	public static boolean agitateLocation(OlioContext context, BaseRecord animal, List<BaseRecord> cells) {
		BaseRecord state = animal.get("state");
		if(state == null) {
			return false;
		}
		
		BaseRecord loc = state.get("currentLocation");
		if(loc == null) {
			logger.warn("Location is null");
			return false;
		}
		
		/// If the state is currently committed to an action, don't agitate it's location
		///
		List<BaseRecord> acts = state.get("actions");
		if(acts.size() > 0) {
			// logger.warn("Don't agitate with current action: " + animal.get("name"));
			return false;
		}

		int east = state.get("currentEast");
		int north = state.get("currentNorth");
		if(east == -1 || north == -1) {
			/// logger.warn("Set initial location");
			setInitialLocation(context, state);
			return true;
		}
		/*
		int reast = (random.nextInt(-1,1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + east;
		int rnorth = (random.nextInt(-1,1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + north;
		*/
		DirectionEnumType dir = GeoLocationUtil.randomDirection();
		try {
			Actions.beginMove(context, context.clock().getIncrement(), animal, dir);
		} catch (OlioException e) {
			logger.error(e);
		}
		/*
		int reast = DirectionEnumType.getX(dir)  + east;
		int rnorth = DirectionEnumType.getY(dir) + north;

		moveByOne(context, animal, cells, reast, rnorth, true, false);
		*/
		return true;
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
				/*
				state.set("currentEast", random.nextInt(0, Rules.MAP_EXTERIOR_CELL_WIDTH - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
				state.set("currentNorth", random.nextInt(0, Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
				*/
				state.set("currentEast", random.nextInt(1, (Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER)) - 1);
				state.set("currentNorth", random.nextInt(1, (Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER)) - 1);

				bal = true;
			}
			catch(ModelNotFoundException | FieldException | ValueException e) {
				logger.error(e);
			}
		}
		return bal;
	}
	

	
	public static List<BaseRecord> observablePopulation(List<BaseRecord> pop, BaseRecord pov){
		return pop.stream().filter(p -> {
				double dist = GeoLocationUtil.getDistanceToState(pov.get("state"), p.get("state"));
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
