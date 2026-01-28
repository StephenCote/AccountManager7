package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.rules.IOlioStateRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ErrorUtil;

public class StateUtil {
	public static final Logger logger = LogManager.getLogger(StateUtil.class);
	private static final SecureRandom random = new SecureRandom();
	
	public static void queueUpdateLocation(OlioContext context, BaseRecord obj) {
		queueUpdateLocation(context, obj, false);
	}

	public static void queueUpdateLocation(OlioContext context, BaseRecord obj, boolean includeLocation) {
		updateLocationImmediate(context, obj, includeLocation);
	}

	/// Immediately updates the state's location in the database without using the queue.
	/// This avoids race conditions where stale data in other queued records could overwrite fresh updates.
	public static void updateLocationImmediate(OlioContext context, BaseRecord obj, boolean includeLocation) {
		BaseRecord state = obj;
		if(state == null) {
			logger.warn("State is null");
			ErrorUtil.printStackTrace();
			return;
		}
		if(!obj.getSchema().equals(OlioModelNames.MODEL_CHAR_STATE)) {
			state = obj.get(FieldNames.FIELD_STATE);
		}
		long stateId = state.get(FieldNames.FIELD_ID);
		int east = state.get(FieldNames.FIELD_CURRENT_EAST);
		int north = state.get(FieldNames.FIELD_CURRENT_NORTH);
		BaseRecord loc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		long locId = loc != null ? loc.get(FieldNames.FIELD_ID) : 0L;
		logger.info("Immediate location update - stateHash=" + System.identityHashCode(state) + ", stateId=" + stateId + ", locId=" + locId + ", E=" + east + ", N=" + north + ", includeLocation=" + includeLocation);
		if(stateId == 0L) {
			logger.error("State has no ID - update will fail!");
			ErrorUtil.printStackTrace();
			return;
		}

		// Create a copy with only the fields we want to update
		String[] fields;
		if(includeLocation) {
			fields = new String[] { FieldNames.FIELD_ID, OlioFieldNames.FIELD_CURRENT_LOCATION, FieldNames.FIELD_CURRENT_EAST, FieldNames.FIELD_CURRENT_NORTH };
		} else {
			fields = new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_CURRENT_EAST, FieldNames.FIELD_CURRENT_NORTH };
		}
		BaseRecord updateRec = state.copyRecord(fields);

		// Update directly in the database, bypassing the queue
		boolean success = IOSystem.getActiveContext().getRecordUtil().updateRecord(updateRec);
		logger.info("Immediate location update result: " + success + " for stateId=" + stateId + ", E=" + east + ", N=" + north);
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
			ErrorUtil.printStackTrace();
			return false;
		}
		BaseRecord loc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(loc == null) {
			logger.warn("Location is null");
			return false;
		}
		List<BaseRecord> cells = GeoLocationUtil.getCells(context, GeoLocationUtil.getParentLocation(context, loc));
		if(cells.size() == 0) {
			logger.warn("Failed to find adjacent cells");
			return false;
		}

		int x = state.get(FieldNames.FIELD_CURRENT_EAST);
		int y = state.get(FieldNames.FIELD_CURRENT_NORTH);
		/*
		int rx = (DirectionEnumType.getX(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + x;
		int ry = (DirectionEnumType.getY(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + y;
		*/
		int rx = DirectionEnumType.getX(dir) + x;
		int ry = DirectionEnumType.getY(dir) + y;
		moveByOne(context, animal, cells, rx, ry, true, true);
		
		BaseRecord nloc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(nloc == null) {
			logger.warn("State currentLocation is null");
			return false;
		}
		int newX = state.get(FieldNames.FIELD_CURRENT_EAST);
		int newY = state.get(FieldNames.FIELD_CURRENT_NORTH);
		boolean moved = (x != newX) || (y != newY);
		logger.info("moveByOneMeterInCell: stateHash=" + System.identityHashCode(state) + " original=(" + x + "," + y + ") new=(" + newX + "," + newY + ") moved=" + moved);
		return moved;
	}
	
	/// NOTE: Does not queue update
	private static void moveByOne(OlioContext context, BaseRecord animal, List<BaseRecord> cells, int stateX, int stateY, boolean allowCrossCell, boolean allowCrossFeature) {
		BaseRecord state = animal.get(FieldNames.FIELD_STATE);
		if(state == null) {
			logger.warn("moveByOne: state is null");
			return;
		}

		int beforeEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int beforeNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);
		logger.info("moveByOne: stateHash=" + System.identityHashCode(state) + " BEFORE - E=" + beforeEast + ", N=" + beforeNorth + ", targetX=" + stateX + ", targetY=" + stateY);

		BaseRecord loc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(loc == null) {
			logger.warn("moveByOne: loc is null");
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
		BaseRecord node = GeoLocationUtil.findCellToEdgePlusOne(context, state.get(OlioFieldNames.FIELD_CURRENT_LOCATION), cells, stateX, stateY, allowCrossFeature);
		if(node == null) {
			logger.warn("moveByOne: Failed to find node at " + stateX + ", " + stateY + " - no movement");
			return;
		}
		else {
			long nid = node.get(FieldNames.FIELD_ID);
			if(!evaluateCanMoveTo(context, animal, node)) {
				logger.warn("moveByOne: evaluateCanMoveTo returned false - no movement");
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
				state.setValue(OlioFieldNames.FIELD_CURRENT_LOCATION, node);
			}
			else {
				if(stateX < 0 || stateX >= xedge || stateY < 0 || stateY >= yedge) {
					logger.warn("Banging into border: " + node.get(FieldNames.FIELD_ID) + " " + stateX + ", " + stateY);
				}
			}
			if(stateY < 0 || stateY >= yedge) {
				logger.error("Failed to navigate intra-cell north to " + stateX + "::" + xedge + ", " + stateY + "::" + yedge);
			}
			if(stateX < 0 || stateX >= xedge) {
				logger.error("Failed to navigate intra-cell east to " + stateX + "::" + xedge + ", " + stateY + "::" + yedge);
			}

			if(stateY >= 0 && stateY < yedge) {
				state.setValue(FieldNames.FIELD_CURRENT_NORTH, stateY);
			}
			if(stateX >= 0 && stateX < xedge) {
				state.setValue(FieldNames.FIELD_CURRENT_EAST, stateX);
			}
			int afterEast = state.get(FieldNames.FIELD_CURRENT_EAST);
			int afterNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);
			logger.info("moveByOne: AFTER - E=" + afterEast + ", N=" + afterNorth);
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
		BaseRecord state = animal.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return false;
		}
		
		BaseRecord loc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(loc == null) {
			logger.warn("Location is null");
			return false;
		}

		return agitateLocation(context, animal, GeoLocationUtil.getCells(context, GeoLocationUtil.getParentLocation(context, loc)));
	}
	public static boolean agitateLocation(OlioContext context, BaseRecord animal, List<BaseRecord> cells) {
		BaseRecord state = animal.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return false;
		}
		
		BaseRecord loc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(loc == null) {
			logger.warn("Location is null");
			return false;
		}
		
		/// If the state is currently committed to an action, don't agitate it's location
		///
		List<BaseRecord> acts = state.get(OlioFieldNames.FIELD_ACTIONS);
		if(acts.size() > 0) {
			// logger.warn("Don't agitate with current action: " + animal.get(FieldNames.FIELD_NAME));
			return false;
		}

		int east = state.get(FieldNames.FIELD_CURRENT_EAST);
		int north = state.get(FieldNames.FIELD_CURRENT_NORTH);
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
		// logger.info("Move from " + east + ", " + north + " to " + state.get(FieldNames.FIELD_CURRENT_EAST) + ", " + state.get(FieldNames.FIELD_CURRENT_NORTH));
	}
	public static boolean setInitialLocation(OlioContext context, BaseRecord state) {
		if(state.get(OlioFieldNames.FIELD_CURRENT_LOCATION) == null) {
			return false;
		}
		// List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(context, state.get(OlioFieldNames.FIELD_CURRENT_LOCATION), 1);
		int east = state.get(FieldNames.FIELD_CURRENT_EAST);
		int north = state.get(FieldNames.FIELD_CURRENT_NORTH);
		boolean bal = false;
		if(east == -1 || north == -1) {
			try {
				/*
				state.set(FieldNames.FIELD_CURRENT_EAST, random.nextInt(0, Rules.MAP_EXTERIOR_CELL_WIDTH - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
				state.set(FieldNames.FIELD_CURRENT_NORTH, random.nextInt(0, Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
				*/
				state.set(FieldNames.FIELD_CURRENT_EAST, random.nextInt(1, (Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER)) - 1);
				state.set(FieldNames.FIELD_CURRENT_NORTH, random.nextInt(1, (Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER)) - 1);

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
				double dist = GeoLocationUtil.getDistanceToState(pov.get(FieldNames.FIELD_STATE), p.get(FieldNames.FIELD_STATE));
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
