package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.record.RecordFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class GameUtil {
	public static final Logger logger = LogManager.getLogger(GameUtil.class);

	public static BaseRecord findCharacter(String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		OlioUtil.planMost(q);
		q.setCache(false);  // Bypass cache to ensure full plan is applied
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	public static BaseRecord claimCharacter(BaseRecord person) {
		if (person == null) {
			return null;
		}
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			return null;
		}

		state.setValue("playerControlled", true);
		state.setValue("health", 1.0);
		state.setValue("energy", 1.0);
		state.setValue("hunger", 0.0);
		state.setValue("thirst", 0.0);
		state.setValue("fatigue", 0.0);
		state.setValue("alive", true);
		state.setValue("awake", true);
		Queue.queueUpdate(state, new String[]{"playerControlled", "health", "energy", "hunger", "thirst", "fatigue", "alive", "awake"});
		Queue.processQueue();
		return state;
	}

	public static BaseRecord releaseCharacter(BaseRecord person) {
		if (person == null) {
			return null;
		}
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			return null;
		}

		state.setValue("playerControlled", false);
		Queue.queueUpdate(state, new String[]{"playerControlled"});
		Queue.processQueue();
		return state;
	}

	public static Map<String, Object> getSituation(OlioContext octx, BaseRecord person) {
		if (octx == null || person == null) {
			return null;
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			return null;
		}

		// Refresh state from database to get latest currentEast/currentNorth values
		long stateId = state.get(FieldNames.FIELD_ID);
		if (stateId > 0) {
			Query stateQuery = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_STATE, FieldNames.FIELD_ID, stateId);
			stateQuery.setCache(false);
			stateQuery.planMost(false);
			BaseRecord freshState = IOSystem.getActiveContext().getSearch().findRecord(stateQuery);
			if (freshState != null) {
				// Update the in-memory state with fresh values from DB
				state.setValue(FieldNames.FIELD_CURRENT_EAST, (int) freshState.get(FieldNames.FIELD_CURRENT_EAST));
				state.setValue(FieldNames.FIELD_CURRENT_NORTH, (int) freshState.get(FieldNames.FIELD_CURRENT_NORTH));
			}
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLoc == null) {
			return null;
		}
		// Ensure eastings/northings are populated and marked for serialization
		IOSystem.getActiveContext().getReader().populate(currentLoc, new String[] {FieldNames.FIELD_EASTINGS, FieldNames.FIELD_NORTHINGS, FieldNames.FIELD_TERRAIN_TYPE, FieldNames.FIELD_GEOTYPE});
		Integer east = currentLoc.get(FieldNames.FIELD_EASTINGS);
		Integer north = currentLoc.get(FieldNames.FIELD_NORTHINGS);
		try {
			// Re-set values to ensure they're included in JSON output
			if (east != null) currentLoc.set(FieldNames.FIELD_EASTINGS, east);
			if (north != null) currentLoc.set(FieldNames.FIELD_NORTHINGS, north);
		} catch (Exception e) {
			logger.warn("Failed to set location coordinates: " + e.getMessage());
		}

		List<BaseRecord> realms = octx.getRealms();
		BaseRecord realm = realms.isEmpty() ? null : realms.get(0);
		if (realm == null) {
			return null;
		}

		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		List<BaseRecord> adjacentCells = GeoLocationUtil.getAdjacentCells(octx, currentLoc, 3);
		// Ensure eastings/northings/terrainType are populated and marked for serialization
		for (BaseRecord cell : adjacentCells) {
			IOSystem.getActiveContext().getReader().populate(cell, new String[] {FieldNames.FIELD_EASTINGS, FieldNames.FIELD_NORTHINGS, FieldNames.FIELD_TERRAIN_TYPE});
			try {
				cell.set(FieldNames.FIELD_EASTINGS, cell.get(FieldNames.FIELD_EASTINGS));
				cell.set(FieldNames.FIELD_NORTHINGS, cell.get(FieldNames.FIELD_NORTHINGS));
				cell.set(FieldNames.FIELD_TERRAIN_TYPE, cell.get(FieldNames.FIELD_TERRAIN_TYPE));
			} catch (Exception e) {
				logger.warn("Failed to set cell coordinates: " + e.getMessage());
			}
		}
		List<BaseRecord> nearbyPeople = GeoLocationUtil.limitToAdjacent(octx, pop, currentLoc);
		// Ensure name, objectId, gender, age and location are populated for nearby people
		for (BaseRecord p : nearbyPeople) {
			IOSystem.getActiveContext().getReader().populate(p, new String[] {
				FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GENDER, FieldNames.FIELD_AGE
			});
			try {
				// Re-set to ensure serialization
				p.set(FieldNames.FIELD_NAME, p.get(FieldNames.FIELD_NAME));
				p.set(FieldNames.FIELD_OBJECT_ID, p.get(FieldNames.FIELD_OBJECT_ID));
				p.set(FieldNames.FIELD_GENDER, p.get(FieldNames.FIELD_GENDER));
				p.set(FieldNames.FIELD_AGE, p.get(FieldNames.FIELD_AGE));
			} catch (Exception e) {
				logger.warn("Failed to set person fields: " + e.getMessage());
			}
			BaseRecord pState = p.get(FieldNames.FIELD_STATE);
			if (pState != null) {
				BaseRecord pLoc = pState.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
				if (pLoc != null) {
					IOSystem.getActiveContext().getReader().populate(pLoc, new String[] {FieldNames.FIELD_EASTINGS, FieldNames.FIELD_NORTHINGS});
					try {
						pLoc.set(FieldNames.FIELD_EASTINGS, pLoc.get(FieldNames.FIELD_EASTINGS));
						pLoc.set(FieldNames.FIELD_NORTHINGS, pLoc.get(FieldNames.FIELD_NORTHINGS));
					} catch (Exception e) {
						// ignore
					}
				}
			}
		}

		Map<BaseRecord, PersonalityProfile> profiles = new HashMap<>();
		PersonalityProfile playerProfile = ProfileUtil.getProfile(octx, person);
		profiles.put(person, playerProfile);
		for (BaseRecord p : nearbyPeople) {
			if (!profiles.containsKey(p)) {
				profiles.put(p, ProfileUtil.getProfile(octx, p));
			}
		}

		BaseRecord epoch = octx.clock() != null ? octx.clock().getEpoch() : null;
		Map<ThreatEnumType, List<BaseRecord>> threats = null;
		if (epoch != null) {
			try {
				threats = ThreatUtil.evaluateImminentThreats(octx, realm, epoch, profiles, playerProfile);
			} catch (Exception te) {
				logger.warn("Failed to evaluate threats: {}", te.getMessage());
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("character", person);
		result.put("state", state);

		// Convert location to simple map to ensure all fields are serialized
		Map<String, Object> locMap = new HashMap<>();
		locMap.put("name", currentLoc.get(FieldNames.FIELD_NAME));
		locMap.put("terrainType", currentLoc.get(FieldNames.FIELD_TERRAIN_TYPE));
		locMap.put("eastings", currentLoc.get(FieldNames.FIELD_EASTINGS));
		locMap.put("northings", currentLoc.get(FieldNames.FIELD_NORTHINGS));
		locMap.put("geoType", currentLoc.get(FieldNames.FIELD_GEOTYPE));
		result.put("location", locMap);

		// Convert nearby people to simple maps
		List<Map<String, Object>> peopleList = new ArrayList<>();
		for (BaseRecord p : nearbyPeople) {
			Map<String, Object> pm = new HashMap<>();
			pm.put("name", p.get(FieldNames.FIELD_NAME));
			pm.put("objectId", p.get(FieldNames.FIELD_OBJECT_ID));
			pm.put("gender", p.get(FieldNames.FIELD_GENDER));
			pm.put("age", p.get(FieldNames.FIELD_AGE));
			// Get location coords from state
			BaseRecord pState = p.get(FieldNames.FIELD_STATE);
			if (pState != null) {
				BaseRecord pLoc = pState.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
				if (pLoc != null) {
					Map<String, Object> pLocMap = new HashMap<>();
					pLocMap.put("eastings", pLoc.get(FieldNames.FIELD_EASTINGS));
					pLocMap.put("northings", pLoc.get(FieldNames.FIELD_NORTHINGS));
					pm.put("currentLocation", pLocMap);
				}
			}
			peopleList.add(pm);
		}
		result.put("nearbyPeople", peopleList);

		// Convert adjacent cells to simple maps
		List<Map<String, Object>> cellList = new ArrayList<>();
		for (BaseRecord cell : adjacentCells) {
			Map<String, Object> cm = new HashMap<>();
			cm.put("name", cell.get(FieldNames.FIELD_NAME));
			cm.put("terrainType", cell.get(FieldNames.FIELD_TERRAIN_TYPE));
			cm.put("eastings", cell.get(FieldNames.FIELD_EASTINGS));
			cm.put("northings", cell.get(FieldNames.FIELD_NORTHINGS));
			cellList.add(cm);
		}
		result.put("adjacentCells", cellList);

		List<Map<String, Object>> threatList = new ArrayList<>();
		if (threats != null) {
			for (Map.Entry<ThreatEnumType, List<BaseRecord>> entry : threats.entrySet()) {
				for (BaseRecord src : entry.getValue()) {
					Map<String, Object> t = new HashMap<>();
					t.put("type", entry.getKey().toString());
					t.put("source", src.get(FieldNames.FIELD_OBJECT_ID));
					t.put("sourceName", src.get(FieldNames.FIELD_NAME));
					String modelName = src.getSchema();
					t.put("modelType", modelName);
					if (OlioModelNames.MODEL_ANIMAL.equals(modelName)) {
						t.put("isAnimal", true);
						t.put("animalType", src.get(FieldNames.FIELD_TYPE));
						t.put("groupType", src.get("groupName"));
					} else {
						t.put("isAnimal", false);
					}
					threatList.add(t);
				}
			}
		}
		result.put("threats", threatList);

		Map<String, Object> needs = new HashMap<>();
		needs.put("hunger", getDoubleOrDefault(state, "hunger", 0.0));
		needs.put("thirst", getDoubleOrDefault(state, "thirst", 0.0));
		needs.put("fatigue", getDoubleOrDefault(state, "fatigue", 0.0));
		needs.put("health", getDoubleOrDefault(state, "health", 1.0));
		needs.put("energy", getDoubleOrDefault(state, "energy", 1.0));
		result.put("needs", needs);

		// Add player's position within the cell explicitly
		Map<String, Object> position = new HashMap<>();
		int currentEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int currentNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);
		position.put("currentEast", currentEast);
		position.put("currentNorth", currentNorth);
		position.put("cellEastings", currentLoc.get(FieldNames.FIELD_EASTINGS));
		position.put("cellNorthings", currentLoc.get(FieldNames.FIELD_NORTHINGS));
		result.put("position", position);

		// Add state snapshot for client-side sync of nested state references
		// This allows clients to update their cached player.state without full refetch
		// Values come directly from the state record - model already defines defaults
		Map<String, Object> stateSnapshot = new HashMap<>();
		stateSnapshot.put("currentEast", currentEast);
		stateSnapshot.put("currentNorth", currentNorth);
		stateSnapshot.put("energy", state.get("energy"));
		stateSnapshot.put("health", state.get("health"));
		stateSnapshot.put("hunger", state.get("hunger"));
		stateSnapshot.put("thirst", state.get("thirst"));
		stateSnapshot.put("fatigue", state.get("fatigue"));
		stateSnapshot.put("alive", state.get("alive"));
		stateSnapshot.put("awake", state.get("awake"));
		stateSnapshot.put("playerControlled", state.get("playerControlled"));
		result.put("stateSnapshot", stateSnapshot);

		// Add clock/time information
		Clock clock = octx.clock();
		if (clock != null) {
			Map<String, Object> clockData = new HashMap<>();
			if (clock.getCurrent() != null) {
				clockData.put("current", clock.getCurrent().toString());
				clockData.put("currentDate", clock.getCurrent().toLocalDate().toString());
				clockData.put("currentTime", clock.getCurrent().toLocalTime().toString());
				clockData.put("hour", clock.getCurrent().getHour());
				clockData.put("dayOfYear", clock.getCurrent().getDayOfYear());
			}
			if (clock.getStart() != null) {
				clockData.put("start", clock.getStart().toString());
			}
			if (clock.getEnd() != null) {
				clockData.put("end", clock.getEnd().toString());
			}
			clockData.put("remainingHours", clock.remainingHours());
			clockData.put("remainingDays", clock.remainingDays());
			clockData.put("cycle", clock.getCycle());

			// Add epoch info
			if (epoch != null) {
				Map<String, Object> epochData = new HashMap<>();
				epochData.put("name", epoch.get(FieldNames.FIELD_NAME));
				epochData.put("objectId", epoch.get(FieldNames.FIELD_OBJECT_ID));
				epochData.put("type", epoch.get(FieldNames.FIELD_TYPE));
				clockData.put("epoch", epochData);
			}

			// Add current event info if available (realm-specific)
			BaseRecord currentEvent = clock.getEvent();
			if (currentEvent != null) {
				Map<String, Object> eventData = new HashMap<>();
				eventData.put("name", currentEvent.get(FieldNames.FIELD_NAME));
				eventData.put("objectId", currentEvent.get(FieldNames.FIELD_OBJECT_ID));
				eventData.put("type", currentEvent.get(FieldNames.FIELD_TYPE));
				clockData.put("event", eventData);
			}

			// Add current increment info if available
			BaseRecord currentIncrement = clock.getIncrement();
			if (currentIncrement != null) {
				Map<String, Object> incData = new HashMap<>();
				incData.put("name", currentIncrement.get(FieldNames.FIELD_NAME));
				incData.put("objectId", currentIncrement.get(FieldNames.FIELD_OBJECT_ID));
				incData.put("type", currentIncrement.get(FieldNames.FIELD_TYPE));
				clockData.put("increment", incData);
			}

			result.put("clock", clockData);
		}

		// Add player's interactions - query using IOSystem
		List<Map<String, Object>> interactionList = new ArrayList<>();
		try {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION);
			q.field("actor", person);
			q.setRequestRange(0, 20);
			q.planField(OlioFieldNames.FIELD_ACTOR, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID});
			q.planField(OlioFieldNames.FIELD_INTERACTOR, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID});
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			for (BaseRecord inter : qr.getResults()) {
				Map<String, Object> interMap = new HashMap<>();
				interMap.put("objectId", inter.get(FieldNames.FIELD_OBJECT_ID));
				interMap.put("type", inter.get(FieldNames.FIELD_TYPE));
				interMap.put("state", inter.get(FieldNames.FIELD_STATE));
				interMap.put("description", inter.get(FieldNames.FIELD_DESCRIPTION));

				BaseRecord actor = inter.get("actor");
				if (actor != null) {
					interMap.put("actorName", actor.get(FieldNames.FIELD_NAME));
					interMap.put("actorId", actor.get(FieldNames.FIELD_OBJECT_ID));
				}
				BaseRecord interactor = inter.get("interactor");
				if (interactor != null) {
					interMap.put("interactorName", interactor.get(FieldNames.FIELD_NAME));
					interMap.put("interactorId", interactor.get(FieldNames.FIELD_OBJECT_ID));
				}
				interactionList.add(interMap);
			}
		} catch (Exception e) {
			logger.warn("Failed to get interactions: {}", e.getMessage());
		}
		result.put("interactions", interactionList);

		return result;
	}

	public static boolean moveCharacter(OlioContext octx, BaseRecord person, DirectionEnumType direction, double distance) throws OlioException {
		if (octx == null) {
			throw new OlioException("Context is null");
		}
		if (person == null) {
			throw new OlioException("Character is null");
		}
		if (direction == null || direction == DirectionEnumType.UNKNOWN) {
			throw new OlioException("Invalid direction");
		}

		// Validate character has required state for movement
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			throw new OlioException("Character has no state - cannot move");
		}
		BaseRecord currentLocation = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLocation == null) {
			throw new OlioException("Character has no current location - cannot move");
		}

		if (distance <= 0) {
			distance = 1.0;
		}

		// Log position BEFORE move
		int beforeEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int beforeNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);
		logger.info("moveCharacter BEFORE: direction={} east={} north={}", direction, beforeEast, beforeNorth);

		BaseRecord epoch = octx.clock() != null ? octx.clock().getEpoch() : null;

		BaseRecord actionResult = Actions.beginMove(octx, epoch, person, direction, distance);
		if (actionResult == null) {
			throw new OlioException("Failed to create move action");
		}

		// Route through Overwatch to enable preemption, reactions, and interaction spawning
		try {
			ActionResultEnumType result = octx.getOverwatch().processOne(actionResult);
			logger.info("moveCharacter Overwatch result: {}", result);
			if (result == ActionResultEnumType.FAILED || result == ActionResultEnumType.ERROR) {
				throw new OlioException("Movement blocked or failed");
			}
		} catch (OverwatchException e) {
			throw new OlioException("Movement failed: " + e.getMessage());
		}

		// Log position AFTER move - refetch to see persisted values
		BaseRecord freshPerson = findCharacter(person.get(FieldNames.FIELD_OBJECT_ID));
		if (freshPerson != null) {
			BaseRecord freshState = freshPerson.get(FieldNames.FIELD_STATE);
			if (freshState != null) {
				int afterEast = freshState.get(FieldNames.FIELD_CURRENT_EAST);
				int afterNorth = freshState.get(FieldNames.FIELD_CURRENT_NORTH);
				logger.info("moveCharacter AFTER: east={} north={} (changed: {})",
					afterEast, afterNorth, (afterEast != beforeEast || afterNorth != beforeNorth));
			}
		}

		return true;
	}

	/**
	 * Move towards a target position using proper angle-based direction calculation.
	 * Uses GeoLocationUtil to calculate the angle between current and target positions,
	 * then moves in the appropriate direction (supports 8-directional movement).
	 *
	 * @param octx The Olio context
	 * @param person The character to move
	 * @param targetCellEast Target cell eastings (0-9 within kident)
	 * @param targetCellNorth Target cell northings (0-9 within kident)
	 * @param targetPosEast Target position within cell (0-99 meters from west edge)
	 * @param targetPosNorth Target position within cell (0-99 meters from south edge)
	 * @param distance Number of meters to move (default 1)
	 * @return true if movement succeeded
	 */
	/**
	 * Move a character towards a target position by a specified distance.
	 *
	 * This method ensures fresh values by explicitly populating FK fields from the database.
	 */
	public static boolean moveTowardsPosition(OlioContext octx, BaseRecord person,
			int targetCellEast, int targetCellNorth, int targetPosEast, int targetPosNorth, double distance) throws OlioException {

		if (octx == null || person == null) {
			throw new OlioException("Context and person are required");
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			throw new OlioException("Character has no state");
		}

		BaseRecord currentLocation = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLocation == null) {
			throw new OlioException("Character has no current location");
		}

		// Explicitly populate the FK field values from DB to ensure fresh data after cell crossings
		// The FK ID is correct but the in-memory eastings/northings may be stale from query joins
		IOSystem.getActiveContext().getReader().populate(currentLocation,
			new String[] {FieldNames.FIELD_EASTINGS, FieldNames.FIELD_NORTHINGS});

		int currentCellEast = currentLocation.get(FieldNames.FIELD_EASTINGS);
		int currentCellNorth = currentLocation.get(FieldNames.FIELD_NORTHINGS);
		int currentPosEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int currentPosNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);

		logger.debug("moveTowardsPosition: cell[{},{}] pos[{},{}]",
			currentCellEast, currentCellNorth, currentPosEast, currentPosNorth);

		// Calculate absolute coordinates (cell * 100 + position within cell)
		int currentX = currentCellEast * 100 + currentPosEast;
		int currentY = currentCellNorth * 100 + currentPosNorth;
		int targetX = targetCellEast * 100 + targetPosEast;
		int targetY = targetCellNorth * 100 + targetPosNorth;

		// Check if already at destination
		if (currentX == targetX && currentY == targetY) {
			logger.info("Already at destination");
			return true;
		}

		// Calculate angle using same approach as WalkTo
		double deltaX = targetX - currentX;
		double deltaY = targetY - currentY;
		double angle = Math.atan2(deltaY, deltaX);
		// Rotate 90 degrees to match compass orientation (North = 0)
		angle += Math.PI / 2;
		double degrees = Math.toDegrees(angle);
		if (degrees < 0) degrees += 360;

		DirectionEnumType direction = DirectionEnumType.getDirectionFromDegrees(degrees);
		if (direction == DirectionEnumType.UNKNOWN) {
			throw new OlioException("Could not determine direction to target");
		}

		logger.info("moveTowardsPosition: from [{},{}] to [{},{}], angle={}, direction={}",
			currentX, currentY, targetX, targetY, degrees, direction);

		// Use existing moveCharacter with calculated direction
		return moveCharacter(octx, person, direction, distance);
	}

	/**
	 * Calculate the distance between current position and target position in meters.
	 *
	 * This method ensures fresh values by explicitly populating FK fields from the database.
	 */
	public static double getDistanceToPosition(BaseRecord person,
			int targetCellEast, int targetCellNorth, int targetPosEast, int targetPosNorth) {

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) return -1;

		BaseRecord currentLocation = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLocation == null) return -1;

		// Explicitly populate the FK field values from DB to ensure fresh data after cell crossings
		// The FK ID is correct but the in-memory eastings/northings may be stale from query joins
		IOSystem.getActiveContext().getReader().populate(currentLocation,
			new String[] {FieldNames.FIELD_EASTINGS, FieldNames.FIELD_NORTHINGS});

		int currentCellEast = currentLocation.get(FieldNames.FIELD_EASTINGS);
		int currentCellNorth = currentLocation.get(FieldNames.FIELD_NORTHINGS);
		int currentPosEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int currentPosNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);

		logger.debug("getDistanceToPosition: cell[{},{}] pos[{},{}]",
			currentCellEast, currentCellNorth, currentPosEast, currentPosNorth);

		int currentX = currentCellEast * 100 + currentPosEast;
		int currentY = currentCellNorth * 100 + currentPosNorth;
		int targetX = targetCellEast * 100 + targetPosEast;
		int targetY = targetCellNorth * 100 + targetPosNorth;

		return Math.sqrt(Math.pow(targetX - currentX, 2) + Math.pow(targetY - currentY, 2));
	}

	public static boolean consumeItem(OlioContext octx, BaseRecord person, String itemName, int quantity) {
		if (octx == null || person == null || itemName == null) {
			return false;
		}

		if (quantity <= 0) {
			quantity = 1;
		}

		try {
			BaseRecord epoch = octx.clock() != null ? octx.clock().getEpoch() : null;
			BaseRecord actionResult = Actions.beginConsume(octx, epoch, person, itemName, quantity);
			if (actionResult == null) {
				return false;
			}

			// Route through Overwatch to enable preemption, reactions, and interaction spawning
			ActionResultEnumType result = octx.getOverwatch().processOne(actionResult);
			return result != ActionResultEnumType.FAILED && result != ActionResultEnumType.ERROR;
		} catch (OlioException | OverwatchException e) {
			logger.error("Failed to consume item: {}", e.getMessage());
			return false;
		}
	}

	public static int advanceTurn(OlioContext octx, List<BaseRecord> population) {
		if (octx == null || population == null) {
			return 0;
		}

		int updated = 0;
		for (BaseRecord person : population) {
			BaseRecord state = person.get(FieldNames.FIELD_STATE);
			if (state == null) continue;

			boolean playerControlled = state.get("playerControlled");
			boolean alive = state.get("alive");
			if (!playerControlled || !alive) continue;

			double hunger = state.get("hunger");
			double thirst = state.get("thirst");
			double fatigue = state.get("fatigue");
			boolean awake = state.get("awake");

			state.setValue("hunger", Math.min(1.0, hunger + 0.02));
			state.setValue("thirst", Math.min(1.0, thirst + 0.04));
			if (awake) {
				state.setValue("fatigue", Math.min(1.0, fatigue + 0.06));
			}

			if (hunger >= 1.0 || thirst >= 1.0) {
				double health = state.get("health");
				double penalty = 0.0;
				if (hunger >= 1.0) penalty += 0.05;
				if (thirst >= 1.0) penalty += 0.1;
				state.setValue("health", Math.max(0.0, health - penalty));
			}

			Queue.queueUpdate(state, new String[]{"hunger", "thirst", "fatigue", "health"});
			updated++;
		}

		Queue.processQueue();
		return updated;
	}

	public static Map<String, Object> investigate(OlioContext octx, BaseRecord person) {
		if (octx == null || person == null) {
			return null;
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			return null;
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLoc == null) {
			return null;
		}

		BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		int perception = stats != null ? stats.get("perception") : 10;

		List<BaseRecord> realms = octx.getRealms();
		BaseRecord realm = realms.isEmpty() ? null : realms.get(0);
		if (realm == null) {
			return null;
		}

		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		List<BaseRecord> nearbyPeople = GeoLocationUtil.limitToAdjacent(octx, pop, currentLoc);

		double fatigue = state.get("fatigue");
		state.setValue("fatigue", Math.min(1.0, fatigue + 0.02));
		Queue.queueUpdate(state, new String[]{"fatigue"});
		Queue.processQueue();

		Map<String, Object> result = new HashMap<>();
		result.put("location", currentLoc);
		result.put("terrain", currentLoc.get("terrainType"));
		result.put("perception", perception);

		List<String> discoveries = new ArrayList<>();
		String terrain = currentLoc.get("terrainType");
		discoveries.add("You carefully examine the " + (terrain != null ? terrain.toLowerCase() : "area") + " around you.");

		if (nearbyPeople.size() > 0) {
			int detected = Math.min(nearbyPeople.size(), perception / 5 + 1);
			discoveries.add("You notice " + detected + " " + (detected == 1 ? "person" : "people") + " nearby.");
		} else {
			discoveries.add("The area appears to be deserted.");
		}

		List<BaseRecord> pois = PointOfInterestUtil.listPointsOfInterest(octx, currentLoc);
		if (pois != null && pois.size() > 0) {
			discoveries.add("You spot " + pois.size() + " point" + (pois.size() > 1 ? "s" : "") + " of interest nearby.");
		}

		result.put("discoveries", discoveries);
		result.put("nearbyCount", nearbyPeople.size());

		return result;
	}

	public static Map<String, Object> resolveAction(OlioContext octx, BaseRecord person, BaseRecord target, InteractionEnumType actionType) throws OlioException {
		if (octx == null || person == null || target == null || actionType == null) {
			return null;
		}

		double chosenPriority = getActionPriority(actionType);

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		BaseRecord currentLoc = state != null ? state.get(OlioFieldNames.FIELD_CURRENT_LOCATION) : null;

		List<BaseRecord> realms = octx.getRealms();
		BaseRecord realm = realms.isEmpty() ? null : realms.get(0);

		boolean interrupted = false;
		String interruptType = null;
		BaseRecord interruptSource = null;

		if (realm != null && currentLoc != null) {
			List<BaseRecord> zoo = realm.get(OlioFieldNames.FIELD_ZOO);
			if (zoo != null) {
				List<BaseRecord> nearbyAnimals = GeoLocationUtil.limitToAdjacent(octx, zoo, currentLoc);
				SecureRandom rand = new SecureRandom();
				for (BaseRecord animal : nearbyAnimals) {
					double threatPriority = 0.8;
					double interruptChance = (threatPriority - chosenPriority) * 0.9;
					if (rand.nextDouble() < interruptChance) {
						interrupted = true;
						interruptType = "ANIMAL_THREAT";
						interruptSource = animal;
						break;
					}
				}
			}
		}

		Map<String, Object> response = new HashMap<>();

		if (interrupted) {
			response.put("interrupted", true);
			response.put("interruptType", interruptType);
			response.put("interruptSource", interruptSource != null ? interruptSource.get(FieldNames.FIELD_NAME) : null);
			response.put("result", null);
		} else {
			BaseRecord interaction = InteractionUtil.resolveInteraction(octx, person, target, actionType);
			response.put("interrupted", false);
			response.put("result", interaction);
		}

		return response;
	}

	public static double getActionPriority(InteractionEnumType type) {
		switch (type) {
			case COMBAT: return 0.9;
			case CONFLICT: return 0.85;
			case DEFEND: return 0.8;
			case THREATEN: return 0.6;
			case NEGOTIATE: return 0.5;
			case BARTER: return 0.4;
			case COMMERCE: return 0.4;
			case COMMUNICATE: return 0.3;
			case HELP: return 0.35;
			case INVESTIGATE: return 0.2;
			case SOCIALIZE: return 0.25;
			default: return 0.3;
		}
	}

	public static boolean isCharacterInWorld(OlioContext octx, String objectId) {
		if (octx == null || objectId == null) {
			return false;
		}

		List<BaseRecord> realms = octx.getRealms();
		for (BaseRecord realm : realms) {
			List<BaseRecord> pop = octx.getRealmPopulation(realm);
			for (BaseRecord p : pop) {
				if (objectId.equals(p.get(FieldNames.FIELD_OBJECT_ID))) {
					return true;
				}
			}
		}
		return false;
	}

	public static Map<String, Object> getNewGameData(OlioContext octx) {
		if (octx == null) {
			return null;
		}

		List<BaseRecord> realms = octx.getRealms();
		if (realms.isEmpty()) {
			return null;
		}
		BaseRecord realm = realms.get(0);

		List<BaseRecord> pop = octx.getRealmPopulation(realm);

		Map<String, Object> result = new HashMap<>();
		result.put("realmName", realm.get(FieldNames.FIELD_NAME));
		result.put("realmId", realm.get(FieldNames.FIELD_OBJECT_ID));
		result.put("characters", pop);
		result.put("totalPopulation", pop.size());

		return result;
	}

	private static double getDoubleOrDefault(BaseRecord rec, String field, double defaultValue) {
		try {
			Object val = rec.get(field);
			if (val == null) return defaultValue;
			if (val instanceof Double) return (Double) val;
			if (val instanceof Number) return ((Number) val).doubleValue();
		} catch (Exception e) {
			// ignore
		}
		return defaultValue;
	}

	public static BaseRecord interact(OlioContext octx, BaseRecord actor, BaseRecord interactor, InteractionEnumType interType) throws OlioException {
		if (octx == null || actor == null || interactor == null || interType == null) {
			return null;
		}
		return InteractionUtil.resolveInteraction(octx, actor, interactor, interType);
	}

	public static BaseRecord getCharacterState(BaseRecord person) {
		if (person == null) {
			return null;
		}
		return person.get(FieldNames.FIELD_STATE);
	}

	public static BaseRecord getActionStatus(BaseRecord person) {
		if (person == null) {
			return null;
		}
		List<BaseRecord> actions = person.get("state.actions");
		if (actions == null || actions.isEmpty()) {
			return null;
		}
		return actions.get(actions.size() - 1);
	}

	public static BaseRecord generateOutfit(OlioContext octx, BaseRecord person, int techTier, String climate) {
		if (octx == null || person == null) {
			return null;
		}
		if (climate == null) {
			climate = "TEMPERATE";
		}
		return ApparelUtil.contextApparel(octx, person, techTier, CivilUtil.getClimateForTerrain(climate));
	}

	public static Map<String, Object> adoptCharacter(OlioContext octx, BaseRecord user, BaseRecord person) {
		if (octx == null || user == null || person == null) {
			return null;
		}

		List<BaseRecord> realms = octx.getRealms();
		if (realms.isEmpty()) {
			return null;
		}
		BaseRecord realm = realms.get(0);

		BaseRecord popGroup = realm.get(OlioFieldNames.FIELD_POPULATION);
		if (popGroup == null) {
			return null;
		}

		long popGroupId = popGroup.get(FieldNames.FIELD_ID);

		moveRecordToGroup(person, popGroupId);

		BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		if (stats != null) {
			moveRecordToGroup(stats, popGroupId);
		}

		BaseRecord instinct = person.get("instinct");
		if (instinct != null) {
			moveRecordToGroup(instinct, popGroupId);
		}

		BaseRecord personality = person.get("personality");
		if (personality != null) {
			moveRecordToGroup(personality, popGroupId);
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			try {
				state = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATE);
				person.set(FieldNames.FIELD_STATE, state);
			} catch (Exception e) {
				logger.error("Failed to create state: {}", e.getMessage());
				return null;
			}
		} else {
			moveRecordToGroup(state, popGroupId);
		}

		BaseRecord store = person.get("store");
		if (store != null) {
			moveRecordToGroup(store, popGroupId);

			List<BaseRecord> apparelList = store.get("apparel");
			if (apparelList != null) {
				for (BaseRecord apparel : apparelList) {
					moveRecordToGroup(apparel, popGroupId);
					List<BaseRecord> wearables = apparel.get("wearables");
					if (wearables != null) {
						for (BaseRecord wear : wearables) {
							moveRecordToGroup(wear, popGroupId);
						}
					}
				}
			}

			List<BaseRecord> items = store.get("items");
			if (items != null) {
				for (BaseRecord item : items) {
					moveRecordToGroup(item, popGroupId);
				}
			}
		}

		BaseRecord profile = person.get("profile");
		if (profile != null) {
			moveRecordToGroup(profile, popGroupId);
			BaseRecord portrait = profile.get("portrait");
			if (portrait != null) {
				moveRecordToGroup(portrait, popGroupId);
			}
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLoc == null) {
			BaseRecord origin = realm.get(OlioFieldNames.FIELD_ORIGIN);
			if (origin != null) {
				state.setValue(OlioFieldNames.FIELD_CURRENT_LOCATION, origin);
				Queue.queueUpdate(state, new String[] { OlioFieldNames.FIELD_CURRENT_LOCATION });
			}
		}

		Queue.processQueue();

		IOSystem.getActiveContext().getMemberUtil().member(user, popGroup, person, null, true);

		Map<String, Object> result = new HashMap<>();
		result.put("adopted", true);
		result.put("characterId", person.get(FieldNames.FIELD_OBJECT_ID));
		result.put("realmName", realm.get(FieldNames.FIELD_NAME));
		result.put("groupPath", popGroup.get(FieldNames.FIELD_PATH));

		return result;
	}

	private static void moveRecordToGroup(BaseRecord rec, long groupId) {
		if (rec == null) return;
		try {
			Long currentGroupId = rec.get(FieldNames.FIELD_GROUP_ID);
			if (currentGroupId != null && currentGroupId.equals(groupId)) return;
			rec.set(FieldNames.FIELD_GROUP_ID, groupId);
			Queue.queueUpdate(rec, new String[] { FieldNames.FIELD_GROUP_ID });
		} catch (Exception e) {
			logger.warn("Failed to move record to group: {}", e.getMessage());
		}
	}

	public static List<Map<String, Object>> listSaves(BaseRecord user) {
		List<Map<String, Object>> saves = new ArrayList<>();
		try {
			BaseRecord saveDir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/GameSaves", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			if (saveDir == null) {
				return saves;
			}

			Query q = QueryUtil.createQuery("data.data", FieldNames.FIELD_GROUP_ID, saveDir.get(FieldNames.FIELD_ID));
			q.setRequestRange(0, 100);
			q.field(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);

			if (qr != null && qr.getResults() != null) {
				for (BaseRecord save : qr.getResults()) {
					Map<String, Object> s = new HashMap<>();
					s.put("objectId", save.get(FieldNames.FIELD_OBJECT_ID));
					s.put("name", save.get(FieldNames.FIELD_NAME));
					s.put("createdDate", save.get(FieldNames.FIELD_CREATED_DATE));
					s.put("modifiedDate", save.get(FieldNames.FIELD_MODIFIED_DATE));
					saves.add(s);
				}
			}
		} catch (Exception e) {
			logger.error("Failed to list saves: {}", e.getMessage());
		}
		return saves;
	}

	public static Map<String, Object> saveGame(BaseRecord user, String saveName, String characterId, Object eventLog) {
		if (saveName == null || saveName.isEmpty()) {
			saveName = "Save " + System.currentTimeMillis();
		}

		try {
			BaseRecord saveDir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/GameSaves", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));

			Map<String, Object> saveData = new HashMap<>();
			saveData.put("characterId", characterId);
			saveData.put("timestamp", System.currentTimeMillis());
			saveData.put("eventLog", eventLog);

			BaseRecord saveRec = IOSystem.getActiveContext().getFactory().newInstance("data.data");
			saveRec.set(FieldNames.FIELD_NAME, saveName);
			saveRec.set(FieldNames.FIELD_GROUP_ID, saveDir.get(FieldNames.FIELD_ID));
			saveRec.set(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			saveRec.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			saveRec.set("dataBytesStore", JSONUtil.exportObject(saveData).getBytes("UTF-8"));

			IOSystem.getActiveContext().getRecordUtil().createRecord(saveRec);

			Map<String, Object> result = new HashMap<>();
			result.put("saved", true);
			result.put("saveId", saveRec.get(FieldNames.FIELD_OBJECT_ID));
			result.put("name", saveName);
			return result;
		} catch (Exception e) {
			logger.error("Failed to save game: {}", e.getMessage());
			return null;
		}
	}

	public static Map<String, Object> loadGame(String objectId) {
		try {
			Query q = QueryUtil.createQuery("data.data", FieldNames.FIELD_OBJECT_ID, objectId);
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);

			if (qr == null || qr.getResults() == null || qr.getResults().length == 0) {
				return null;
			}

			BaseRecord save = qr.getResults()[0];
			IOSystem.getActiveContext().getReader().populate(save, new String[] {"dataBytesStore"});
			byte[] data = save.get("dataBytesStore");
			if (data == null) {
				return null;
			}

			String saveJson = new String(data, "UTF-8");
			Map<String, Object> result = new HashMap<>();
			result.put("loaded", true);
			result.put("name", save.get(FieldNames.FIELD_NAME));
			result.put("saveData", JSONUtil.importObject(saveJson, Map.class));

			return result;
		} catch (Exception e) {
			logger.error("Failed to load game: {}", e.getMessage());
			return null;
		}
	}

	public static boolean deleteGame(String objectId) {
		try {
			Query q = QueryUtil.createQuery("data.data", FieldNames.FIELD_OBJECT_ID, objectId);
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);

			if (qr == null || qr.getResults() == null || qr.getResults().length == 0) {
				return false;
			}

			IOSystem.getActiveContext().getRecordUtil().deleteRecord(qr.getResults()[0]);
			return true;
		} catch (Exception e) {
			logger.error("Failed to delete game: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Load or create the interaction evaluation prompt configuration.
	 * Looks for "Interaction Evaluation" in ~/Chat, or loads from resource file.
	 */
	public static BaseRecord getInteractionEvalPromptConfig(BaseRecord user) {
		String promptName = "Interaction Evaluation";
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_NAME, promptName);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.planMost(false);
		BaseRecord existing = IOSystem.getActiveContext().getSearch().findRecord(q);

		if (existing != null) {
			return existing;
		}

		// Load from resource file
		String resourceJson = ResourceUtil.getInstance().getResource("olio/llm/interactionEvalPrompt.json");
		if (resourceJson == null || resourceJson.isEmpty()) {
			logger.warn("Interaction evaluation prompt resource not found, using default");
			resourceJson = getDefaultInteractionEvalPrompt();
		}

		BaseRecord template = JSONUtil.importObject(resourceJson, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if (template == null) {
			logger.error("Failed to parse interaction evaluation prompt template");
			return null;
		}

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, promptName);
			BaseRecord rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, template, plist);
			return IOSystem.getActiveContext().getAccessPoint().create(user, rec);
		} catch (FactoryException e) {
			logger.error("Failed to create interaction eval prompt config: {}", e.getMessage());
			return null;
		}
	}

	private static String getDefaultInteractionEvalPrompt() {
		return "{\n" +
			"  \"name\": \"Interaction Evaluation\",\n" +
			"  \"system\": [\n" +
			"    \"You are an INTERACTION ANALYST evaluating a conversation between two characters.\",\n" +
			"    \"Analyze the conversation and determine the outcome.\",\n" +
			"    \"RESPOND WITH STRUCTURED JSON ONLY - no additional text.\"\n" +
			"  ],\n" +
			"  \"assistant\": [\"I will analyze this interaction.\"],\n" +
			"  \"user\": [\"Evaluate the conversation.\"]\n" +
			"}";
	}

	/**
	 * Conclude a chat session by evaluating the conversation using an LLM
	 * and creating an interaction record based on the results.
	 *
	 * @param octx The Olio context
	 * @param user The user record
	 * @param actor The actor (player character)
	 * @param target The target (NPC or other character)
	 * @param chatMessages List of chat messages in format [{role: "user"|"assistant", content: "..."}]
	 * @param chatConfig The chat configuration used for the conversation
	 * @return Map containing the evaluation results and created interaction
	 */
	public static Map<String, Object> concludeChat(OlioContext octx, BaseRecord user, BaseRecord actor, BaseRecord target,
			List<Map<String, String>> chatMessages, BaseRecord chatConfig) {

		if (octx == null || user == null || actor == null || target == null || chatMessages == null || chatMessages.isEmpty()) {
			logger.warn("concludeChat: Missing required parameters");
			return null;
		}

		Map<String, Object> result = new HashMap<>();

		// Build the conversation transcript
		StringBuilder transcript = new StringBuilder();
		String actorName = actor.get(FieldNames.FIELD_FIRST_NAME);
		String targetName = target.get(FieldNames.FIELD_FIRST_NAME);

		for (Map<String, String> msg : chatMessages) {
			String role = msg.get("role");
			String content = msg.get("content");
			String speaker = "assistant".equals(role) ? targetName : actorName;
			transcript.append(speaker).append(": ").append(content).append("\n");
		}

		// Build context for the evaluation
		String setting = chatConfig != null ? chatConfig.get("setting") : "unknown location";
		String actorGender = actor.get(FieldNames.FIELD_GENDER);
		int actorAge = actor.get(FieldNames.FIELD_AGE);
		String targetGender = target.get(FieldNames.FIELD_GENDER);
		int targetAge = target.get(FieldNames.FIELD_AGE);

		// Build the evaluation prompt
		String evalPrompt = buildEvaluationPrompt(actorName, actorAge, actorGender,
				targetName, targetAge, targetGender, setting, transcript.toString());

		// Get the evaluation prompt config
		BaseRecord evalPromptConfig = getInteractionEvalPromptConfig(user);
		if (evalPromptConfig == null) {
			logger.error("Failed to get interaction evaluation prompt config");
			result.put("error", "Failed to load evaluation prompt configuration");
			return result;
		}

		// Create a simple chat request for evaluation
		OpenAIRequest evalReq = new OpenAIRequest();
		String model = chatConfig != null ? chatConfig.get("analyzeModel") : null;
		if (model == null && chatConfig != null) {
			model = chatConfig.get("model");
		}
		if (model == null) {
			model = "gpt-4o-mini"; // default fallback
		}
		evalReq.setModel(model);
		evalReq.setStream(false);

		// Build system message from prompt config
		List<String> systemParts = evalPromptConfig.get("system");
		String systemPrompt = systemParts != null ? String.join("\n", systemParts) : "Evaluate this interaction.";

		Chat chat = new Chat();
		chat.newMessage(evalReq, "Ready to analyze.", Chat.userRole);
		chat.newMessage(evalReq, "I will analyze the conversation and provide structured JSON.", Chat.assistantRole);
		chat.newMessage(evalReq, evalPrompt, Chat.userRole);

		// Execute the evaluation
		OpenAIResponse evalResp = chat.chat(evalReq);

		if (evalResp == null || evalResp.getMessage() == null) {
			logger.error("Failed to get evaluation response from LLM");
			result.put("error", "LLM evaluation failed");
			return result;
		}

		String llmResponse = evalResp.getMessage().getContent();
		result.put("llmResponse", llmResponse);

		// Parse the LLM JSON response
		Map<String, Object> evalResult = parseEvaluationResponse(llmResponse);
		result.put("evaluation", evalResult);

		// Create the interaction record
		BaseRecord interaction = createInteractionFromEvaluation(octx, user, actor, target, evalResult);
		if (interaction != null) {
			result.put("interaction", interaction);
			result.put("interactionId", interaction.get(FieldNames.FIELD_OBJECT_ID));

			// Attach to current increment/event if available
			BaseRecord epoch = octx.clock() != null ? octx.clock().getEpoch() : null;
			if (epoch != null) {
				attachInteractionToEvent(octx, interaction, epoch);
			}
		}

		result.put("success", interaction != null);
		return result;
	}

	private static String buildEvaluationPrompt(String actorName, int actorAge, String actorGender,
			String targetName, int targetAge, String targetGender, String setting, String transcript) {

		return "CHARACTERS:\n" +
			"- Actor: " + actorName + " (" + actorAge + " year old " + actorGender + ")\n" +
			"- Target: " + targetName + " (" + targetAge + " year old " + targetGender + ")\n\n" +
			"SETTING: " + setting + "\n\n" +
			"CONVERSATION TRANSCRIPT:\n" + transcript + "\n\n" +
			"Evaluate this conversation and respond with JSON:\n" +
			"{\n" +
			"  \"outcome\": \"POSITIVE|NEGATIVE|NEUTRAL|MIXED\",\n" +
			"  \"relationshipChange\": {\n" +
			"    \"direction\": \"IMPROVED|WORSENED|UNCHANGED\",\n" +
			"    \"magnitude\": 0.0 to 1.0\n" +
			"  },\n" +
			"  \"actorOutcome\": \"FAVORABLE|UNFAVORABLE|EQUILIBRIUM\",\n" +
			"  \"targetOutcome\": \"FAVORABLE|UNFAVORABLE|EQUILIBRIUM\",\n" +
			"  \"interactionType\": \"COMMUNICATE|SOCIALIZE|NEGOTIATE|COMMERCE|CONFLICT|HELP\",\n" +
			"  \"trustChange\": -1.0 to 1.0,\n" +
			"  \"summary\": \"Brief 1-2 sentence summary\"\n" +
			"}";
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseEvaluationResponse(String llmResponse) {
		Map<String, Object> evalResult = new HashMap<>();

		try {
			// Extract JSON from response (handle markdown code blocks)
			String jsonStr = llmResponse;
			if (jsonStr.contains("```json")) {
				int start = jsonStr.indexOf("```json") + 7;
				int end = jsonStr.indexOf("```", start);
				if (end > start) {
					jsonStr = jsonStr.substring(start, end).trim();
				}
			} else if (jsonStr.contains("```")) {
				int start = jsonStr.indexOf("```") + 3;
				int end = jsonStr.indexOf("```", start);
				if (end > start) {
					jsonStr = jsonStr.substring(start, end).trim();
				}
			}

			evalResult = JSONUtil.importObject(jsonStr, Map.class);
			if (evalResult == null) {
				evalResult = new HashMap<>();
				evalResult.put("parseError", "Failed to parse JSON response");
				evalResult.put("rawResponse", llmResponse);
			}
		} catch (Exception e) {
			logger.warn("Failed to parse evaluation response: {}", e.getMessage());
			evalResult.put("parseError", e.getMessage());
			evalResult.put("rawResponse", llmResponse);
		}

		return evalResult;
	}

	@SuppressWarnings("unchecked")
	private static BaseRecord createInteractionFromEvaluation(OlioContext octx, BaseRecord user,
			BaseRecord actor, BaseRecord target, Map<String, Object> evalResult) {

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Interactions");
			BaseRecord interaction = IOSystem.getActiveContext().getFactory().newInstance(
					OlioModelNames.MODEL_INTERACTION, user, null, plist);

			// Set actor/interactor
			interaction.set("actorType", actor.getSchema());
			interaction.set("actor", actor);
			interaction.set("interactorType", target.getSchema());
			interaction.set("interactor", target);

			// Set description from summary
			String summary = (String) evalResult.get("summary");
			if (summary != null && summary.length() > 128) {
				summary = summary.substring(0, 125) + "...";
			}
			interaction.set("description", summary);

			// Set interaction type
			String interTypeStr = (String) evalResult.get("interactionType");
			InteractionEnumType interType = InteractionEnumType.COMMUNICATE;
			if (interTypeStr != null) {
				try {
					interType = InteractionEnumType.valueOf(interTypeStr.toUpperCase());
				} catch (IllegalArgumentException e) {
					// Use default
				}
			}
			interaction.set(FieldNames.FIELD_TYPE, interType);

			// Set outcomes
			String actorOutcomeStr = (String) evalResult.get("actorOutcome");
			OutcomeEnumType actorOutcome = OutcomeEnumType.EQUILIBRIUM;
			if (actorOutcomeStr != null) {
				try {
					actorOutcome = OutcomeEnumType.valueOf(actorOutcomeStr.toUpperCase());
				} catch (IllegalArgumentException e) {
					// Use default
				}
			}
			interaction.set("actorOutcome", actorOutcome);

			String targetOutcomeStr = (String) evalResult.get("targetOutcome");
			OutcomeEnumType targetOutcome = OutcomeEnumType.EQUILIBRIUM;
			if (targetOutcomeStr != null) {
				try {
					targetOutcome = OutcomeEnumType.valueOf(targetOutcomeStr.toUpperCase());
				} catch (IllegalArgumentException e) {
					// Use default
				}
			}
			interaction.set("interactorOutcome", targetOutcome);

			// Set state based on overall outcome
			String outcomeStr = (String) evalResult.get("outcome");
			ActionResultEnumType state = ActionResultEnumType.SUCCEEDED;
			if ("NEGATIVE".equals(outcomeStr)) {
				state = ActionResultEnumType.FAILED;
			} else if ("MIXED".equals(outcomeStr)) {
				state = ActionResultEnumType.PENDING;
			}
			interaction.set(FieldNames.FIELD_STATE, state);

			// Set timestamps
			interaction.set("interactionStart", new java.util.Date());
			interaction.set("interactionEnd", new java.util.Date());

			// Create the record
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, interaction);
			return created;

		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to create interaction record: {}", e.getMessage());
			return null;
		}
	}

	private static void attachInteractionToEvent(OlioContext octx, BaseRecord interaction, BaseRecord epoch) {
		try {
			// Get current event from epoch if available
			BaseRecord currentEvent = epoch.get("currentEvent");
			if (currentEvent == null) {
				// Try to get or create an event for the current increment
				List<BaseRecord> realms = octx.getRealms();
				if (!realms.isEmpty()) {
					BaseRecord realm = realms.get(0);
					// For now, just log - full event attachment requires more context
					logger.info("Interaction created for realm: {}", (String)realm.get(FieldNames.FIELD_NAME));
				}
			}

			// TODO: Attach interaction to chatConfig.interactions list if available
			// This would require passing the chatConfig and updating it

		} catch (Exception e) {
			logger.warn("Failed to attach interaction to event: {}", e.getMessage());
		}
	}

	/**
	 * Generate patches for tracking changes to nested object properties.
	 * Used to help clients sync their cached objects with server state.
	 *
	 * @param basePath The base path for the patches (e.g., "state")
	 * @param fields Map of field names to their new values
	 * @return List of patch objects with path and value
	 */
	public static List<Map<String, Object>> generatePatches(String basePath, Map<String, Object> fields) {
		List<Map<String, Object>> patches = new ArrayList<>();
		if (fields == null || fields.isEmpty()) {
			return patches;
		}

		String prefix = (basePath != null && !basePath.isEmpty()) ? basePath + "." : "";
		for (Map.Entry<String, Object> entry : fields.entrySet()) {
			Map<String, Object> patch = new HashMap<>();
			patch.put("path", prefix + entry.getKey());
			patch.put("value", entry.getValue());
			patches.add(patch);
		}
		return patches;
	}

	/**
	 * Generic method to extract a nested object by path and create sync data.
	 * Works for any nested foreign model reference on a character.
	 *
	 * @param person The character record
	 * @param path Dot-separated path to nested object (e.g., "state", "store.apparel", "profile.portrait")
	 * @param fields Optional list of specific fields to include (null = all primitive fields)
	 * @return Map containing the extracted data keyed by path segments
	 */
	public static Map<String, Object> extractNestedSync(BaseRecord person, String path, String[] fields) {
		Map<String, Object> result = new HashMap<>();
		if (person == null || path == null || path.isEmpty()) {
			return result;
		}

		String[] pathParts = path.split("\\.");
		Object current = person;

		// Navigate to the nested object
		for (String part : pathParts) {
			if (current == null) {
				return result;
			}
			if (current instanceof BaseRecord) {
				current = ((BaseRecord) current).get(part);
			} else {
				return result;
			}
		}

		if (current == null) {
			return result;
		}

		// Extract data based on type
		if (current instanceof BaseRecord) {
			BaseRecord rec = (BaseRecord) current;
			Map<String, Object> data = extractRecordFields(rec, fields);
			result.put(pathParts[pathParts.length - 1], data);
		} else if (current instanceof List) {
			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) current;
			List<Map<String, Object>> dataList = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof BaseRecord) {
					dataList.add(extractRecordFields((BaseRecord) item, fields));
				}
			}
			result.put(pathParts[pathParts.length - 1], dataList);
		}

		return result;
	}

	/**
	 * Extract specified fields from a record into a Map.
	 * If fields is null, extracts all primitive (non-foreign) fields.
	 */
	private static Map<String, Object> extractRecordFields(BaseRecord rec, String[] fields) {
		Map<String, Object> data = new HashMap<>();
		if (rec == null) {
			return data;
		}

		// Always include identity fields if they exist on the model
		// Use hasField() to check before accessing to avoid FieldException
		data.put("schema", rec.getSchema());
		if (rec.hasField(FieldNames.FIELD_ID)) {
			data.put("id", rec.get(FieldNames.FIELD_ID));
		}
		if (rec.hasField(FieldNames.FIELD_OBJECT_ID)) {
			data.put("objectId", rec.get(FieldNames.FIELD_OBJECT_ID));
		}
		if (rec.hasField(FieldNames.FIELD_NAME)) {
			data.put("name", rec.get(FieldNames.FIELD_NAME));
		}

		if (fields != null) {
			// Extract only specified fields
			for (String field : fields) {
				try {
					if (rec.hasField(field)) {
						Object val = rec.get(field);
						if (val != null && !(val instanceof BaseRecord) && !(val instanceof List)) {
							data.put(field, val);
						}
					}
				} catch (Exception e) {
					// Skip invalid field names
				}
			}
		} else {
			// Extract all set primitive fields from the record
			// getFields() returns List<FieldType>, need to get name from each
			for (org.cote.accountmanager.model.field.FieldType ft : rec.getFields()) {
				try {
					String fieldName = ft.getName();
					Object val = rec.get(fieldName);
					if (val != null && !(val instanceof BaseRecord) && !(val instanceof List)) {
						data.put(fieldName, val);
					}
				} catch (Exception e) {
					// Skip
				}
			}
		}

		return data;
	}

	/**
	 * Create comprehensive sync data for a character, including multiple nested paths.
	 * This is the generic replacement for createStateSyncData that works with any nested object.
	 *
	 * @param person The character record
	 * @param paths Array of dot-separated paths to sync (e.g., ["state", "store", "statistics"])
	 * @param includePatches Whether to generate patch array for client application
	 * @return Map containing sync data for each path and optionally patches
	 */
	public static Map<String, Object> createSyncData(BaseRecord person, String[] paths, boolean includePatches) {
		Map<String, Object> result = new HashMap<>();
		List<Map<String, Object>> allPatches = new ArrayList<>();

		if (person == null || paths == null) {
			return result;
		}

		for (String path : paths) {
			Map<String, Object> extracted = extractNestedSync(person, path, null);

			// Add to result with path as key
			String key = path.replace(".", "_"); // "state.currentLocation" -> "state_currentLocation"
			if (!extracted.isEmpty()) {
				// Get the innermost key (e.g., "currentLocation" from "state.currentLocation")
				String[] parts = path.split("\\.");
				String innerKey = parts[parts.length - 1];
				Object data = extracted.get(innerKey);

				result.put(key, data);

				// Generate patches for this path
				if (includePatches && data instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> dataMap = (Map<String, Object>) data;
					List<Map<String, Object>> pathPatches = generatePatches(path, dataMap);
					allPatches.addAll(pathPatches);
				} else if (includePatches) {
					// Single value patch
					Map<String, Object> patch = new HashMap<>();
					patch.put("path", path);
					patch.put("value", data);
					allPatches.add(patch);
				}
			}
		}

		if (includePatches && !allPatches.isEmpty()) {
			result.put("patches", allPatches);
		}

		return result;
	}

	/**
	 * Common sync paths for Olio characters - useful constants for endpoints
	 */
	public static final String[] SYNC_PATHS_STATE = { "state" };
	public static final String[] SYNC_PATHS_FULL = { "state", "statistics", "instinct", "store", "profile" };
	public static final String[] SYNC_PATHS_COMBAT = { "state", "statistics" };
	public static final String[] SYNC_PATHS_SOCIAL = { "state", "profile" };

	/**
	 * Create a state snapshot for client sync, optionally including patches
	 * for specific property paths. This is the specialized version for state.
	 *
	 * @param state The character state record
	 * @param currentLoc The current location record
	 * @param includePatches Whether to include patches array
	 * @return Map containing stateSnapshot and optionally patches
	 */
	public static Map<String, Object> createStateSyncData(BaseRecord state, BaseRecord currentLoc, boolean includePatches) {
		Map<String, Object> result = new HashMap<>();

		if (state == null) {
			return result;
		}

		int currentEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int currentNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);

		// State snapshot for direct object updates
		// Values come directly from the state record - model already defines defaults
		Map<String, Object> stateSnapshot = new HashMap<>();
		stateSnapshot.put("currentEast", currentEast);
		stateSnapshot.put("currentNorth", currentNorth);
		stateSnapshot.put("energy", state.get("energy"));
		stateSnapshot.put("health", state.get("health"));
		stateSnapshot.put("hunger", state.get("hunger"));
		stateSnapshot.put("thirst", state.get("thirst"));
		stateSnapshot.put("fatigue", state.get("fatigue"));
		stateSnapshot.put("alive", state.get("alive"));
		stateSnapshot.put("awake", state.get("awake"));
		stateSnapshot.put("playerControlled", state.get("playerControlled"));
		result.put("stateSnapshot", stateSnapshot);

		// Location data
		if (currentLoc != null) {
			Map<String, Object> locMap = new HashMap<>();
			locMap.put("name", currentLoc.get(FieldNames.FIELD_NAME));
			locMap.put("terrainType", currentLoc.get(FieldNames.FIELD_TERRAIN_TYPE));
			locMap.put("eastings", currentLoc.get(FieldNames.FIELD_EASTINGS));
			locMap.put("northings", currentLoc.get(FieldNames.FIELD_NORTHINGS));
			locMap.put("geoType", currentLoc.get(FieldNames.FIELD_GEOTYPE));
			result.put("location", locMap);
		}

		// Generate patches if requested
		if (includePatches) {
			List<Map<String, Object>> patches = generatePatches("state", stateSnapshot);
			if (currentLoc != null) {
				Map<String, Object> locPatch = new HashMap<>();
				locPatch.put("path", "state.currentLocation");
				locPatch.put("value", result.get("location"));
				patches.add(locPatch);
			}
			result.put("patches", patches);
		}

		return result;
	}

	/**
	 * Simple chat method for game conversations between characters.
	 * The target character (NPC) responds to the player's message.
	 *
	 * @param octx The Olio context
	 * @param actor The player character
	 * @param target The NPC character to talk to
	 * @param message The player's message
	 * @return Map containing the response
	 */
	public static Map<String, Object> chat(OlioContext octx, BaseRecord actor, BaseRecord target, String message) {
		return chat(octx, actor, target, message, null);
	}

	/**
	 * Chat method for game conversations with optional chat configuration.
	 * The target character (NPC) responds to the player's message.
	 *
	 * @param octx The Olio context
	 * @param actor The player character
	 * @param target The NPC character to talk to
	 * @param message The player's message
	 * @param chatConfig Optional chat configuration with LLM settings
	 * @return Map containing the response
	 */
	public static Map<String, Object> chat(OlioContext octx, BaseRecord actor, BaseRecord target, String message, BaseRecord chatConfig) {
		Map<String, Object> result = new HashMap<>();

		if (octx == null || actor == null || target == null || message == null || message.trim().isEmpty()) {
			result.put("error", "Missing required parameters");
			return result;
		}

		String actorName = actor.get(FieldNames.FIELD_FIRST_NAME);
		String targetName = target.get(FieldNames.FIELD_FIRST_NAME);
		String targetGender = target.get(FieldNames.FIELD_GENDER);
		int targetAge = target.get(FieldNames.FIELD_AGE);

		// Build a simple prompt
		String systemPrompt = "You are " + targetName + ", a " + targetAge + " year old " + targetGender + " character in a survival/adventure setting. " +
				"Respond naturally and briefly (1-3 sentences) as this character would. " +
				"Stay in character. Do not break the fourth wall.";

		// Create chat with config if provided
		Chat chat;
		if (chatConfig != null) {
			chat = new Chat(null, chatConfig, null);
		} else {
			chat = new Chat();
		}

		// Create chat request - use model from config or default
		OpenAIRequest req = new OpenAIRequest();
		String model = (chatConfig != null) ? chatConfig.get("model") : null;
		if (model == null || model.isEmpty()) {
			model = "gpt-4o-mini";
		}
		req.setModel(model);

		chat.newMessage(req, systemPrompt, "system");
		chat.newMessage(req, actorName + " says: " + message, "user");

		try {
			OpenAIResponse resp = chat.chat(req);
			if (resp != null && resp.getMessage() != null) {
				result.put("response", resp.getMessage());
				result.put("speaker", targetName);
			} else {
				result.put("response", "*" + targetName + " doesn't respond*");
				result.put("speaker", targetName);
			}
		} catch (Exception e) {
			logger.error("Chat failed: " + e.getMessage());
			result.put("error", "Chat service unavailable");
		}

		return result;
	}

	/**
	 * Create an NPC-initiated chat request to a player.
	 * This stores an interaction record that the client can query.
	 *
	 * @param octx The Olio context
	 * @param npc The NPC initiating the chat
	 * @param player The player character being contacted
	 * @param reason Optional reason/greeting for the chat request
	 * @return The created interaction record, or null on failure
	 */
	public static BaseRecord createNpcChatRequest(OlioContext octx, BaseRecord npc, BaseRecord player, String reason) {
		if (octx == null || npc == null || player == null) {
			logger.warn("Missing required parameters for NPC chat request");
			return null;
		}

		try {
			BaseRecord interaction = RecordFactory.newInstance(OlioModelNames.MODEL_INTERACTION);
			interaction.set(FieldNames.FIELD_TYPE, InteractionEnumType.COMMUNICATE);
			interaction.set("state", ActionResultEnumType.PENDING);
			interaction.set("actor", npc);
			interaction.set("actorType", OlioModelNames.MODEL_CHAR_PERSON);
			interaction.set("interactor", player);
			interaction.set("interactorType", OlioModelNames.MODEL_CHAR_PERSON);
			interaction.set("description", reason != null ? reason : "wants to talk");
			interaction.set("interactionStart", ZonedDateTime.now());

			// Store in the world's Interactions group
			BaseRecord world = octx.getWorld();
			BaseRecord interGroup = world.get("interactions");
			if (interGroup != null) {
				interaction.set(FieldNames.FIELD_GROUP_ID, interGroup.get(FieldNames.FIELD_ID));
			}

			IOSystem.getActiveContext().getRecordUtil().createRecord(interaction);
			return interaction;
		} catch (Exception e) {
			logger.error("Failed to create NPC chat request: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get pending chat requests for a player character.
	 *
	 * @param octx The Olio context
	 * @param player The player character
	 * @return List of pending interaction records where NPCs want to chat
	 */
	public static List<BaseRecord> getPendingChatRequests(OlioContext octx, BaseRecord player) {
		List<BaseRecord> pending = new ArrayList<>();
		if (octx == null || player == null) {
			return pending;
		}

		try {
			BaseRecord world = octx.getWorld();
			BaseRecord interGroup = world.get("interactions");
			if (interGroup == null) {
				return pending;
			}

			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION);
			q.field(FieldNames.FIELD_GROUP_ID, interGroup.get(FieldNames.FIELD_ID));
			q.field("interactor.id", player.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_TYPE, InteractionEnumType.COMMUNICATE);
			q.field("state", ActionResultEnumType.PENDING);
			q.planMost(true);

			pending = Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q));
		} catch (Exception e) {
			logger.error("Failed to get pending chat requests: " + e.getMessage());
		}

		return pending;
	}

	/**
	 * Dismiss or accept a pending chat request.
	 *
	 * @param interaction The interaction to update
	 * @param accepted True if accepted, false if dismissed
	 * @return True if updated successfully
	 */
	public static boolean resolveChatRequest(BaseRecord interaction, boolean accepted) {
		if (interaction == null) {
			return false;
		}

		try {
			interaction.set("state", accepted ? ActionResultEnumType.SUCCEEDED : ActionResultEnumType.FAILED);
			interaction.set("interactionEnd", ZonedDateTime.now());
			Queue.queueUpdate(interaction, new String[]{"state", "interactionEnd"});
			Queue.processQueue();
			return true;
		} catch (Exception e) {
			logger.error("Failed to resolve chat request: " + e.getMessage());
			return false;
		}
	}
}
