package org.cote.rest.services;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CivilUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.ThreatUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin","user"})
@Path("/game")
public class GameService {

	private static final Logger logger = LogManager.getLogger(GameService.class);

	@Context
	ServletContext context;

	/// Submit an interaction between two characters.
	/// JSON body: { "actorId": "uuid", "interactorId": "uuid", "interactionType": "COMBAT" }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/interact")
	@Produces(MediaType.APPLICATION_JSON)
	public Response interact(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String actorId = params.get("actorId");
		String interactorId = params.get("interactorId");
		String interTypeStr = params.get("interactionType");

		if(actorId == null || interactorId == null || interTypeStr == null) {
			return Response.status(400).entity("{\"error\":\"actorId, interactorId, and interactionType are required\"}").build();
		}

		InteractionEnumType interType;
		try {
			interType = InteractionEnumType.valueOf(interTypeStr.toUpperCase());
		} catch(IllegalArgumentException e) {
			return Response.status(400).entity("{\"error\":\"Invalid interactionType: " + interTypeStr + "\"}").build();
		}

		BaseRecord actor = findCharacter(user, actorId);
		BaseRecord interactor = findCharacter(user, interactorId);
		if(actor == null || interactor == null) {
			return Response.status(404).entity("{\"error\":\"Actor or interactor not found\"}").build();
		}

		try {
			BaseRecord interaction = InteractionUtil.resolveInteraction(octx, actor, interactor, interType);
			return Response.status(200).entity(interaction.toFullString()).build();
		} catch(OlioException e) {
			logger.error("Interaction failed: " + e.getMessage());
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Claim a character for player control. Excludes them from automatic evolution.
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/claim/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response claimCharacter(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return Response.status(500).entity("{\"error\":\"Character has no state record\"}").build();
		}

		// Reset state values for a fresh start
		state.setValue("playerControlled", true);
		state.setValue("health", 1.0);
		state.setValue("energy", 1.0);
		state.setValue("hunger", 0.0);
		state.setValue("thirst", 0.0);
		state.setValue("fatigue", 0.0);
		state.setValue("alive", true);
		state.setValue("awake", true);
		Queue.queueUpdate(state, new String[]{"playerControlled", "health", "energy", "hunger", "thirst", "fatigue", "alive", "awake"});
		Queue.processQueue(user);

		return Response.status(200).entity(state.toFullString()).build();
	}

	/// Release a character back to automatic evolution.
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/release/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response releaseCharacter(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return Response.status(500).entity("{\"error\":\"Character has no state record\"}").build();
		}

		state.setValue("playerControlled", false);
		Queue.queueUpdate(state, new String[]{"playerControlled"});
		Queue.processQueue(user);

		return Response.status(200).entity(state.toFullString()).build();
	}

	/// Get the current state of a character (energy, hunger, thirst, fatigue, health, etc.)
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/state/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getCharacterState(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return Response.status(404).entity("{\"error\":\"Character has no state\"}").build();
		}

		return Response.status(200).entity(state.toFullString()).build();
	}

	/// Get the current action status for a character (what action they're in, time remaining, etc.)
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/status/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getActionStatus(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		List<BaseRecord> actions = person.get("state.actions");
		if(actions == null || actions.isEmpty()) {
			return Response.status(200).entity("{\"status\":\"idle\",\"actions\":[]}").build();
		}

		BaseRecord lastAction = actions.get(actions.size() - 1);
		return Response.status(200).entity(lastAction.toFullString()).build();
	}

	/// Consume an item from the character's store to satisfy needs.
	/// JSON body: { "itemName": "fresh water", "quantity": 1 }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/consume/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response consumeItem(@PathParam("objectId") String objectId, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String itemName = params.get("itemName");
		if(itemName == null) {
			return Response.status(400).entity("{\"error\":\"itemName is required\"}").build();
		}

		int quantity = 1;
		Object qObj = params.get("quantity");
		if(qObj != null && qObj instanceof Number) {
			quantity = ((Number)qObj).intValue();
		}

		// Find the item in the character's inventory
		BaseRecord item = ItemUtil.findStoredItemByName(person, itemName);
		if(item == null) {
			return Response.status(404).entity("{\"error\":\"Item not found in inventory: " + itemName + "\"}").build();
		}

		// Withdraw from inventory
		boolean withdrawn = ItemUtil.withdrawItemFromInventory(octx, person, item, quantity);
		if(!withdrawn) {
			return Response.status(400).entity("{\"error\":\"Could not withdraw item\"}").build();
		}

		// Apply needs satisfaction based on item category
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state != null) {
			String category = item.get(OlioFieldNames.FIELD_CATEGORY);
			if("food".equals(category)) {
				double hunger = state.get("hunger");
				state.setValue("hunger", Math.max(0.0, hunger - (0.3 * quantity)));
				double energy = state.get("energy");
				state.setValue("energy", Math.min(1.0, energy + (0.1 * quantity)));
			} else if("water".equals(category)) {
				double thirst = state.get("thirst");
				state.setValue("thirst", Math.max(0.0, thirst - (0.3 * quantity)));
			}
			Queue.queueUpdate(state, new String[]{"hunger", "thirst", "energy"});
		}

		Queue.processQueue(user);
		return Response.status(200).entity(state != null ? state.toFullString() : "{\"status\":\"consumed\"}").build();
	}

	/// Advance time for player-controlled characters.
	/// This accumulates needs (hunger, thirst, fatigue) as if an increment had passed.
	/// Called as "end turn" from the card game.
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/advance")
	@Produces(MediaType.APPLICATION_JSON)
	public Response advanceTurn(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		// Find all player-controlled characters and accumulate their needs
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
		q.planMost(true);
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		BaseRecord[] results = (qr != null ? qr.getResults() : new BaseRecord[0]);

		int updated = 0;
		for(BaseRecord person : results) {
			BaseRecord state = person.get(FieldNames.FIELD_STATE);
			if(state == null) continue;

			boolean playerControlled = state.get("playerControlled");
			boolean alive = state.get("alive");
			if(!playerControlled || !alive) continue;

			// Accumulate needs per increment
			double hunger = state.get("hunger");
			double thirst = state.get("thirst");
			double fatigue = state.get("fatigue");
			boolean awake = state.get("awake");

			state.setValue("hunger", Math.min(1.0, hunger + 0.02));
			state.setValue("thirst", Math.min(1.0, thirst + 0.04));
			if(awake) {
				state.setValue("fatigue", Math.min(1.0, fatigue + 0.06));
			}

			// Degrade health if critical needs are maxed
			if(hunger >= 1.0 || thirst >= 1.0) {
				double health = state.get("health");
				double penalty = 0.0;
				if(hunger >= 1.0) penalty += 0.05;
				if(thirst >= 1.0) penalty += 0.1;
				state.setValue("health", Math.max(0.0, health - penalty));
			}

			Queue.queueUpdate(state, new String[]{"hunger", "thirst", "fatigue", "health"});
			updated++;
		}

		Queue.processQueue(user);
		return Response.status(200).entity("{\"updated\":" + updated + "}").build();
	}

	private BaseRecord findCharacter(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// Get the full situation for a character - location, nearby grid, threats, people, POIs, needs.
	/// This is the single endpoint the card game uses to get everything it needs.
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/situation/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSituation(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return Response.status(500).entity("{\"error\":\"Character has no state\"}").build();
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(currentLoc == null) {
			return Response.status(500).entity("{\"error\":\"Character has no location\"}").build();
		}

		// Find the realm for this character
		List<BaseRecord> realms = octx.getRealms();
		BaseRecord realm = realms.isEmpty() ? null : realms.get(0);
		if(realm == null) {
			return Response.status(500).entity("{\"error\":\"No realm found\"}").build();
		}

		// Get population and nearby people first (more efficient)
		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		List<BaseRecord> adjacentCells = GeoLocationUtil.getAdjacentCells(octx, currentLoc, 3);
		List<BaseRecord> nearbyPeople = GeoLocationUtil.limitToAdjacent(octx, pop, currentLoc);

		// Only compute profiles for nearby people + player (performance optimization)
		Map<BaseRecord, PersonalityProfile> profiles = new HashMap<>();
		PersonalityProfile playerProfile = ProfileUtil.getProfile(octx, person);
		profiles.put(person, playerProfile);
		for(BaseRecord p : nearbyPeople) {
			if(!profiles.containsKey(p)) {
				profiles.put(p, ProfileUtil.getProfile(octx, p));
			}
		}

		// Evaluate threats (only considers nearby entities)
		BaseRecord epoch = octx.clock() != null ? octx.clock().getEpoch() : null;
		Map<ThreatEnumType, List<BaseRecord>> threats = null;
		if(epoch != null) {
			try {
				threats = ThreatUtil.evaluateImminentThreats(octx, realm, epoch, profiles, playerProfile);
			} catch(Exception te) {
				logger.warn("Failed to evaluate threats: {}", te.getMessage());
			}
		}

		// Build response as a Map
		try {
			Map<String, Object> result = new HashMap<>();
			result.put("character", person);
			result.put("state", state);
			result.put("location", currentLoc);
			result.put("nearbyPeople", nearbyPeople);
			result.put("adjacentCells", adjacentCells);

			// Convert threats map to JSON-friendly format
			List<Map<String, Object>> threatList = new ArrayList<>();
			if(threats != null) {
				for(Map.Entry<ThreatEnumType, List<BaseRecord>> entry : threats.entrySet()) {
					for(BaseRecord src : entry.getValue()) {
						Map<String, Object> t = new HashMap<>();
						t.put("type", entry.getKey().toString());
						t.put("source", src.get(FieldNames.FIELD_OBJECT_ID));
						t.put("sourceName", src.get(FieldNames.FIELD_NAME));
						// Include additional info for animals
						String modelName = src.getSchema();
						t.put("modelType", modelName);
						if(OlioModelNames.MODEL_ANIMAL.equals(modelName)) {
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

			// Get needs (with null-safe defaults)
			Map<String, Object> needs = new HashMap<>();
			needs.put("hunger", getDoubleOrDefault(state, "hunger", 0.0));
			needs.put("thirst", getDoubleOrDefault(state, "thirst", 0.0));
			needs.put("fatigue", getDoubleOrDefault(state, "fatigue", 0.0));
			needs.put("health", getDoubleOrDefault(state, "health", 1.0));
			needs.put("energy", getDoubleOrDefault(state, "energy", 1.0));
			result.put("needs", needs);

			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		} catch(Exception e) {
			logger.error("Failed to build situation response", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Resolve an action for a character. Checks for interrupts from higher-priority threats.
	/// JSON body: { "targetId": "uuid", "actionType": "TALK" }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/resolve/{objectId:[0-9A-Za-z\\-]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response resolveAction(@PathParam("objectId") String objectId, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String targetId = params.get("targetId");
		String actionTypeStr = params.get("actionType");
		if(targetId == null || actionTypeStr == null) {
			return Response.status(400).entity("{\"error\":\"targetId and actionType required\"}").build();
		}

		BaseRecord target = findCharacter(user, targetId);
		if(target == null) {
			return Response.status(404).entity("{\"error\":\"Target not found\"}").build();
		}

		InteractionEnumType actionType;
		try {
			actionType = InteractionEnumType.valueOf(actionTypeStr.toUpperCase());
		} catch(IllegalArgumentException e) {
			return Response.status(400).entity("{\"error\":\"Invalid actionType\"}").build();
		}

		// Calculate chosen action priority
		double chosenPriority = getActionPriority(actionType);

		// Check for interrupts from pending threats
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		BaseRecord currentLoc = state != null ? state.get(OlioFieldNames.FIELD_CURRENT_LOCATION) : null;

		List<BaseRecord> realms = octx.getRealms();
		BaseRecord realm = realms.isEmpty() ? null : realms.get(0);

		boolean interrupted = false;
		String interruptType = null;
		BaseRecord interruptSource = null;

		if(realm != null && currentLoc != null) {
			// Get nearby animals as potential interrupters
			List<BaseRecord> zoo = realm.get(OlioFieldNames.FIELD_ZOO);
			if(zoo != null) {
				List<BaseRecord> nearbyAnimals = GeoLocationUtil.limitToAdjacent(octx, zoo, currentLoc);
				SecureRandom rand = new SecureRandom();
				for(BaseRecord animal : nearbyAnimals) {
					double threatPriority = 0.8; // ANIMAL_THREAT priority
					double interruptChance = (threatPriority - chosenPriority) * 0.9;
					if(rand.nextDouble() < interruptChance) {
						interrupted = true;
						interruptType = "ANIMAL_THREAT";
						interruptSource = animal;
						break;
					}
				}
			}
		}

		try {
			Map<String, Object> response = new HashMap<>();

			if(interrupted) {
				response.put("interrupted", true);
				response.put("interruptType", interruptType);
				response.put("interruptSource", interruptSource != null ? interruptSource.get(FieldNames.FIELD_NAME) : null);
				response.put("result", null);
			} else {
				BaseRecord interaction = InteractionUtil.resolveInteraction(octx, person, target, actionType);
				response.put("interrupted", false);
				response.put("result", interaction);
			}

			return Response.status(200).entity(JSONUtil.exportObject(response)).build();
		} catch(OlioException e) {
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Move character one cell in a direction (N, S, E, W).
	/// JSON body: { "direction": "N" }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/move/{objectId:[0-9A-Za-z\\-]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response moveCharacter(@PathParam("objectId") String objectId, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String direction = params.get("direction");
		if(direction == null) {
			return Response.status(400).entity("{\"error\":\"direction required (N, S, E, W)\"}").build();
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return Response.status(500).entity("{\"error\":\"Character has no state\"}").build();
		}

		// Get current position
		int east = state.get("currentEast");
		int north = state.get("currentNorth");

		// Apply movement
		switch(direction.toUpperCase()) {
			case "N": north++; break;
			case "S": north--; break;
			case "E": east++; break;
			case "W": east--; break;
			default:
				return Response.status(400).entity("{\"error\":\"Invalid direction: " + direction + "\"}").build();
		}

		// Update state
		state.setValue("currentEast", east);
		state.setValue("currentNorth", north);

		// Apply fatigue cost for movement
		double fatigue = state.get("fatigue");
		state.setValue("fatigue", Math.min(1.0, fatigue + 0.01));

		Queue.queueUpdate(state, new String[]{"currentEast", "currentNorth", "fatigue"});
		Queue.processQueue(user);

		// Return updated situation
		return getSituation(objectId, request);
	}

	/// Investigate the current location. Reveals nearby entities based on perception.
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/investigate/{objectId:[0-9A-Za-z\\-]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response investigateLocation(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state == null) {
			return Response.status(500).entity("{\"error\":\"Character has no state\"}").build();
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(currentLoc == null) {
			return Response.status(500).entity("{\"error\":\"Character has no location\"}").build();
		}

		// Get character's perception stat for investigation quality
		BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		int perception = stats != null ? stats.get("perception") : 10;

		// Find realm
		List<BaseRecord> realms = octx.getRealms();
		BaseRecord realm = realms.isEmpty() ? null : realms.get(0);
		if(realm == null) {
			return Response.status(500).entity("{\"error\":\"No realm found\"}").build();
		}

		// Get population and nearby entities
		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		List<BaseRecord> nearbyPeople = GeoLocationUtil.limitToAdjacent(octx, pop, currentLoc);

		// Apply fatigue cost for investigation
		double fatigue = state.get("fatigue");
		state.setValue("fatigue", Math.min(1.0, fatigue + 0.02));
		Queue.queueUpdate(state, new String[]{"fatigue"});
		Queue.processQueue(user);

		// Build investigation result
		Map<String, Object> result = new HashMap<>();
		result.put("location", currentLoc);
		result.put("terrain", currentLoc.get("terrainType"));
		result.put("perception", perception);

		// Description of what was found
		List<String> discoveries = new ArrayList<>();
		String terrain = currentLoc.get("terrainType");
		discoveries.add("You carefully examine the " + (terrain != null ? terrain.toLowerCase() : "area") + " around you.");

		// Report nearby people based on perception
		if(nearbyPeople.size() > 0) {
			int detected = Math.min(nearbyPeople.size(), perception / 5 + 1);
			discoveries.add("You notice " + detected + " " + (detected == 1 ? "person" : "people") + " nearby.");
		} else {
			discoveries.add("The area appears to be deserted.");
		}

		// Check for POIs in the area
		List<BaseRecord> pois = octx.getPointsOfInterest();
		if(pois != null && pois.size() > 0) {
			discoveries.add("You spot some points of interest in the vicinity.");
		}

		result.put("discoveries", discoveries);
		result.put("nearbyCount", nearbyPeople.size());

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// Generate a context-aware outfit for a character.
	/// JSON body: { "characterId": "uuid", "techTier": 2, "climate": "TEMPERATE" }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/outfit/generate")
	@Produces(MediaType.APPLICATION_JSON)
	public Response generateOutfit(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String characterId = params.get("characterId");
		if(characterId == null) {
			return Response.status(400).entity("{\"error\":\"characterId required\"}").build();
		}

		BaseRecord person = findCharacter(user, characterId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		// Get tier and climate from params or defaults
		int techTier = 2;
		Object tierObj = params.get("techTier");
		if(tierObj instanceof Number) {
			techTier = ((Number)tierObj).intValue();
		}

		String climateStr = params.get("climate");
		if(climateStr == null) {
			climateStr = "TEMPERATE";
		}

		// Generate the outfit using string climate
		BaseRecord apparel = ApparelUtil.contextApparel(octx, person, techTier, CivilUtil.getClimateForTerrain(climateStr));
		if(apparel == null) {
			return Response.status(500).entity("{\"error\":\"Failed to generate apparel\"}").build();
		}

		return Response.status(200).entity(apparel.toFullString()).build();
	}

	private double getDoubleOrDefault(BaseRecord rec, String field, double defaultValue) {
		try {
			Object val = rec.get(field);
			if(val == null) return defaultValue;
			if(val instanceof Double) return (Double)val;
			if(val instanceof Number) return ((Number)val).doubleValue();
		} catch(Exception e) {
			// ignore
		}
		return defaultValue;
	}

	private double getActionPriority(InteractionEnumType type) {
		switch(type) {
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

	/// Adopt a character into the Olio world space.
	/// Moves the character to the realm's population group.
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/adopt/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response adoptCharacter(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		// Find the character
		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		// Get the first realm to adopt into
		List<BaseRecord> realms = octx.getRealms();
		if(realms.isEmpty()) {
			return Response.status(500).entity("{\"error\":\"No realm available\"}").build();
		}
		BaseRecord realm = realms.get(0);

		try {
			// Get the population group for the realm
			BaseRecord popGroup = realm.get(OlioFieldNames.FIELD_POPULATION);
			if(popGroup == null) {
				return Response.status(500).entity("{\"error\":\"Realm has no population group\"}").build();
			}

			long popGroupId = popGroup.get(FieldNames.FIELD_ID);

			// Move the character and ALL linked objects to the population group
			moveRecordToGroup(person, popGroupId);

			// Move statistics
			BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
			if(stats != null) {
				moveRecordToGroup(stats, popGroupId);
			}

			// Move instinct
			BaseRecord instinct = person.get("instinct");
			if(instinct != null) {
				moveRecordToGroup(instinct, popGroupId);
			}

			// Move personality
			BaseRecord personality = person.get("personality");
			if(personality != null) {
				moveRecordToGroup(personality, popGroupId);
			}

			// Move state
			BaseRecord state = person.get(FieldNames.FIELD_STATE);
			if(state == null) {
				state = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATE);
				person.set(FieldNames.FIELD_STATE, state);
			} else {
				moveRecordToGroup(state, popGroupId);
			}

			// Move store and its contents (apparel, items, etc.)
			BaseRecord store = person.get("store");
			if(store != null) {
				moveRecordToGroup(store, popGroupId);

				// Move apparel and wearables
				List<BaseRecord> apparelList = store.get("apparel");
				if(apparelList != null) {
					for(BaseRecord apparel : apparelList) {
						moveRecordToGroup(apparel, popGroupId);
						// Move wearables within apparel
						List<BaseRecord> wearables = apparel.get("wearables");
						if(wearables != null) {
							for(BaseRecord wear : wearables) {
								moveRecordToGroup(wear, popGroupId);
							}
						}
					}
				}

				// Move items
				List<BaseRecord> items = store.get("items");
				if(items != null) {
					for(BaseRecord item : items) {
						moveRecordToGroup(item, popGroupId);
					}
				}
			}

			// Move profile and portrait
			BaseRecord profile = person.get("profile");
			if(profile != null) {
				moveRecordToGroup(profile, popGroupId);
				BaseRecord portrait = profile.get("portrait");
				if(portrait != null) {
					moveRecordToGroup(portrait, popGroupId);
				}
			}

			// Set initial location to realm origin if not set
			BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
			if(currentLoc == null) {
				BaseRecord origin = realm.get(OlioFieldNames.FIELD_ORIGIN);
				if(origin != null) {
					state.set(OlioFieldNames.FIELD_CURRENT_LOCATION, origin);
					Queue.queueUpdate(state, new String[] { OlioFieldNames.FIELD_CURRENT_LOCATION });
				}
			}

			// Initialize state values
			if(state.get("health") == null) state.set("health", 1.0);
			if(state.get("energy") == null) state.set("energy", 1.0);
			if(state.get("hunger") == null) state.set("hunger", 0.0);
			if(state.get("thirst") == null) state.set("thirst", 0.0);
			if(state.get("fatigue") == null) state.set("fatigue", 0.0);
			if(state.get("alive") == null) state.set("alive", true);
			if(state.get("awake") == null) state.set("awake", true);

			// Process all queued updates
			Queue.processQueue();

			// Add to population membership if not already there
			IOSystem.getActiveContext().getMemberUtil().member(user, popGroup, person, null, true);

			Map<String, Object> result = new HashMap<>();
			result.put("adopted", true);
			result.put("characterId", person.get(FieldNames.FIELD_OBJECT_ID));
			result.put("realmName", realm.get(FieldNames.FIELD_NAME));
			result.put("groupPath", popGroup.get(FieldNames.FIELD_PATH));

			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		} catch(Exception e) {
			logger.error("Failed to adopt character", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Helper to move a record to a new group
	private void moveRecordToGroup(BaseRecord rec, long groupId) {
		if(rec == null) return;
		try {
			Long currentGroupId = rec.get(FieldNames.FIELD_GROUP_ID);
			if(currentGroupId != null && currentGroupId.equals(groupId)) return; // Already in correct group
			rec.set(FieldNames.FIELD_GROUP_ID, groupId);
			Queue.queueUpdate(rec, new String[] { FieldNames.FIELD_GROUP_ID });
		} catch(Exception e) {
			logger.warn("Failed to move record to group: {}", e.getMessage());
		}
	}

	/// Start a new game session - returns available characters from the world.
	/// Can be used to select a character or get a random one.
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/newGame")
	@Produces(MediaType.APPLICATION_JSON)
	public Response newGame(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		List<BaseRecord> realms = octx.getRealms();
		if(realms.isEmpty()) {
			return Response.status(500).entity("{\"error\":\"No realm available\"}").build();
		}
		BaseRecord realm = realms.get(0);

		try {
			// Get population for this realm
			List<BaseRecord> pop = octx.getRealmPopulation(realm);

			// Build character list with basic info
			// Note: getRealmPopulation uses FULL_PLAN_FILTER which excludes objectId,
			// so we need to populate each record to get the objectId field
			List<Map<String, Object>> characters = new ArrayList<>();
			for(BaseRecord p : pop) {
				// Populate to ensure objectId is available
				IOSystem.getActiveContext().getReader().populate(p, new String[] {FieldNames.FIELD_OBJECT_ID});

				Map<String, Object> charInfo = new HashMap<>();
				charInfo.put("objectId", p.get(FieldNames.FIELD_OBJECT_ID));
				charInfo.put("name", p.get(FieldNames.FIELD_NAME));
				charInfo.put("firstName", p.get("firstName"));
				charInfo.put("lastName", p.get("lastName"));
				charInfo.put("gender", p.get("gender"));
				charInfo.put("age", p.get("age"));

				// Get portrait if available
				BaseRecord profile = p.get("profile");
				if(profile != null) {
					BaseRecord portrait = profile.get("portrait");
					if(portrait != null) {
						// Also populate portrait to get its objectId
						IOSystem.getActiveContext().getReader().populate(portrait, new String[] {FieldNames.FIELD_OBJECT_ID});
						charInfo.put("portraitId", portrait.get(FieldNames.FIELD_OBJECT_ID));
					}
				}
				characters.add(charInfo);
			}

			Map<String, Object> result = new HashMap<>();
			result.put("realmName", realm.get(FieldNames.FIELD_NAME));
			result.put("realmId", realm.get(FieldNames.FIELD_OBJECT_ID));
			result.put("characters", characters);
			result.put("totalPopulation", pop.size());

			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		} catch(Exception e) {
			logger.error("Failed to get new game data", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Check if a character is part of the Olio world population
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/isInWorld/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response isCharacterInWorld(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord person = findCharacter(user, objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		List<BaseRecord> realms = octx.getRealms();
		boolean inWorld = false;
		String realmName = null;

		for(BaseRecord realm : realms) {
			List<BaseRecord> pop = octx.getRealmPopulation(realm);
			for(BaseRecord p : pop) {
				if(objectId.equals(p.get(FieldNames.FIELD_OBJECT_ID))) {
					inWorld = true;
					realmName = realm.get(FieldNames.FIELD_NAME);
					break;
				}
			}
			if(inWorld) break;
		}

		Map<String, Object> result = new HashMap<>();
		result.put("inWorld", inWorld);
		result.put("realmName", realmName);
		result.put("characterId", objectId);

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// List saved games for the current user
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/saves")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listSaves(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		try {
			// Get or create the user's game saves group
			BaseRecord saveDir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/GameSaves", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			if(saveDir == null) {
				return Response.status(200).entity("{\"saves\":[]}").build();
			}

			// Query saves from this group
			Query q = QueryUtil.createQuery("data.data", FieldNames.FIELD_GROUP_ID, saveDir.get(FieldNames.FIELD_ID));
			q.setRequestRange(0, 100);
			q.field(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);

			List<Map<String, Object>> saves = new ArrayList<>();
			if(qr != null && qr.getResults() != null) {
				for(BaseRecord save : qr.getResults()) {
					Map<String, Object> s = new HashMap<>();
					s.put("objectId", save.get(FieldNames.FIELD_OBJECT_ID));
					s.put("name", save.get(FieldNames.FIELD_NAME));
					s.put("createdDate", save.get(FieldNames.FIELD_CREATED_DATE));
					s.put("modifiedDate", save.get(FieldNames.FIELD_MODIFIED_DATE));
					saves.add(s);
				}
			}

			Map<String, Object> result = new HashMap<>();
			result.put("saves", saves);
			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		} catch(Exception e) {
			logger.error("Failed to list saves", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Save game state
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/save")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response saveGame(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String saveName = (String) params.get("name");
		String characterId = (String) params.get("characterId");
		if(saveName == null || saveName.isEmpty()) {
			saveName = "Save " + System.currentTimeMillis();
		}

		try {
			// Get or create the user's game saves group
			BaseRecord saveDir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/GameSaves", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));

			// Create save data
			Map<String, Object> saveData = new HashMap<>();
			saveData.put("characterId", characterId);
			saveData.put("timestamp", System.currentTimeMillis());
			saveData.put("eventLog", params.get("eventLog"));

			// Create data record for save
			BaseRecord saveRec = RecordFactory.newInstance("data.data");
			saveRec.set(FieldNames.FIELD_NAME, saveName);
			saveRec.set(FieldNames.FIELD_GROUP_ID, saveDir.get(FieldNames.FIELD_ID));
			saveRec.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			saveRec.set("dataBytesStore", JSONUtil.exportObject(saveData).getBytes("UTF-8"));

			IOSystem.getActiveContext().getRecordUtil().createRecord(saveRec);

			Map<String, Object> result = new HashMap<>();
			result.put("saved", true);
			result.put("saveId", saveRec.get(FieldNames.FIELD_OBJECT_ID));
			result.put("name", saveName);
			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		} catch(Exception e) {
			logger.error("Failed to save game", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Load game state
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/load/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response loadGame(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		try {
			// Find the save record
			Query q = QueryUtil.createQuery("data.data", FieldNames.FIELD_OBJECT_ID, objectId);
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);

			if(qr == null || qr.getResults() == null || qr.getResults().length == 0) {
				return Response.status(404).entity("{\"error\":\"Save not found\"}").build();
			}

			BaseRecord save = qr.getResults()[0];
			byte[] data = save.get("dataBytesStore");
			if(data == null) {
				return Response.status(500).entity("{\"error\":\"Save data is empty\"}").build();
			}

			String saveJson = new String(data, "UTF-8");
			Map<String, Object> result = new HashMap<>();
			result.put("loaded", true);
			result.put("name", save.get(FieldNames.FIELD_NAME));
			result.put("saveData", JSONUtil.importObject(saveJson, Map.class));

			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		} catch(Exception e) {
			logger.error("Failed to load game", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Delete a saved game
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/deleteSave/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteSave(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		try {
			Query q = QueryUtil.createQuery("data.data", FieldNames.FIELD_OBJECT_ID, objectId);
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);

			if(qr == null || qr.getResults() == null || qr.getResults().length == 0) {
				return Response.status(404).entity("{\"error\":\"Save not found\"}").build();
			}

			IOSystem.getActiveContext().getRecordUtil().deleteRecord(qr.getResults()[0]);

			Map<String, Object> result = new HashMap<>();
			result.put("deleted", true);
			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		} catch(Exception e) {
			logger.error("Failed to delete save", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}
}
