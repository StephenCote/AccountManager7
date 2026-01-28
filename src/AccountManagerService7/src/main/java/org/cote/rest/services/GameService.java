package org.cote.rest.services;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
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

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String actorId = params.get("actorId");
		String interactorId = params.get("interactorId");
		InteractionEnumType interType = params.getEnum("interactionType");

		if(actorId == null || interactorId == null || interType == null) {
			return Response.status(400).entity("{\"error\":\"actorId, interactorId, and interactionType are required\"}").build();
		}

		BaseRecord actor = GameUtil.findCharacter(actorId);
		BaseRecord interactor = GameUtil.findCharacter(interactorId);
		if(actor == null || interactor == null) {
			return Response.status(404).entity("{\"error\":\"Actor or interactor not found\"}").build();
		}

		try {
			BaseRecord interaction = GameUtil.interact(octx, actor, interactor, interType);
			if(interaction == null) {
				return Response.status(500).entity("{\"error\":\"Interaction failed\"}").build();
			}
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
		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = GameUtil.claimCharacter(person);
		if(state == null) {
			return Response.status(500).entity("{\"error\":\"Character has no state record\"}").build();
		}

		return Response.status(200).entity(state.toFullString()).build();
	}

	/// Release a character back to automatic evolution.
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/release/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response releaseCharacter(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = GameUtil.releaseCharacter(person);
		if(state == null) {
			return Response.status(500).entity("{\"error\":\"Character has no state record\"}").build();
		}

		return Response.status(200).entity(state.toFullString()).build();
	}

	/// Get the current state of a character (energy, hunger, thirst, fatigue, health, etc.)
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/state/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getCharacterState(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord state = GameUtil.getCharacterState(person);
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
		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord lastAction = GameUtil.getActionStatus(person);
		if(lastAction == null) {
			return Response.status(200).entity("{\"status\":\"idle\",\"actions\":[]}").build();
		}

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

		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
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

		boolean consumed = GameUtil.consumeItem(octx, person, itemName, quantity);
		if(!consumed) {
			return Response.status(400).entity("{\"error\":\"Could not consume item\"}").build();
		}

		// Return state with sync data for client-side cache update
		BaseRecord state = GameUtil.getCharacterState(person);
		if(state != null) {
			BaseRecord currentLoc = state.get("currentLocation");
			Map<String, Object> syncData = GameUtil.createStateSyncData(state, currentLoc, true);
			syncData.put("consumed", true);
			syncData.put("itemName", itemName);
			syncData.put("quantity", quantity);
			return Response.status(200).entity(JSONUtil.exportObject(syncData)).build();
		}
		return Response.status(200).entity("{\"status\":\"consumed\"}").build();
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

		List<BaseRecord> realms = octx.getRealms();
		if(realms.isEmpty()) {
			return Response.status(500).entity("{\"error\":\"No realm available\"}").build();
		}

		List<BaseRecord> population = octx.getRealmPopulation(realms.get(0));
		int updated = GameUtil.advanceTurn(octx, population);

		return Response.status(200).entity("{\"updated\":" + updated + "}").build();
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

		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		Map<String, Object> result = GameUtil.getSituation(octx, person);
		if(result == null) {
			return Response.status(500).entity("{\"error\":\"Failed to get situation - character may have no state or location\"}").build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
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

		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String targetId = params.get("targetId");
		String actionTypeStr = params.get("actionType");
		if(targetId == null || actionTypeStr == null) {
			return Response.status(400).entity("{\"error\":\"targetId and actionType required\"}").build();
		}

		BaseRecord target = GameUtil.findCharacter(targetId);
		if(target == null) {
			return Response.status(404).entity("{\"error\":\"Target not found\"}").build();
		}

		InteractionEnumType actionType;
		try {
			actionType = InteractionEnumType.valueOf(actionTypeStr.toUpperCase());
		} catch(IllegalArgumentException e) {
			return Response.status(400).entity("{\"error\":\"Invalid actionType\"}").build();
		}

		try {
			Map<String, Object> response = GameUtil.resolveAction(octx, person, target, actionType);
			if(response == null) {
				return Response.status(500).entity("{\"error\":\"Failed to resolve action\"}").build();
			}
			return Response.status(200).entity(JSONUtil.exportObject(response)).build();
		} catch(OlioException e) {
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Move character in a direction using the walk action system.
	/// JSON body: { "direction": "NORTH", "distance": 1.0 }
	/// Distance is optional and defaults to 1.0 meters
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

		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null || !params.containsKey("direction")) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String dirStr = (String) params.get("direction");
		DirectionEnumType dir = DirectionEnumType.valueOf(dirStr);
		if(dir == null || dir == DirectionEnumType.UNKNOWN) {
			return Response.status(400).entity("{\"error\":\"direction required (NORTH, SOUTH, EAST, WEST)\"}").build();
		}

		Object distObj = params.get("distance");
		Double distance = (distObj instanceof Number) ? ((Number) distObj).doubleValue() : 1.0;
		logger.info("GameService.moveCharacter: received distance=" + distObj + ", parsed=" + distance);
		if(distance <= 0) {
			distance = 1.0;
		}

		try {
			GameUtil.moveCharacter(octx, person, dir, distance);
		} catch(OlioException e) {
			logger.warn("Move action failed: " + e.getMessage());
			return Response.status(400).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}

		return getSituation(objectId, request);
	}

	/// Move towards a target position using proper angle-based direction calculation.
	/// Supports diagonal movement along the slope to the destination.
	/// JSON body: { "targetCellEast": 5, "targetCellNorth": 5, "targetPosEast": 50, "targetPosNorth": 50, "fullMove": true }
	/// Set fullMove=true to move all the way to destination in a single call (server-side loop)
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/moveTo/{objectId:[0-9A-Za-z\\-]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response moveToPosition(@PathParam("objectId") String objectId, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		// Target cell coordinates (within kident grid, 0-9)
		int targetCellEast = getIntParam(params, "targetCellEast", -1);
		int targetCellNorth = getIntParam(params, "targetCellNorth", -1);
		// Target position within cell (meters from edge, 0-99)
		int targetPosEast = getIntParam(params, "targetPosEast", 50);
		int targetPosNorth = getIntParam(params, "targetPosNorth", 50);
		// Full move - move all the way to destination (server-side loop)
		boolean fullMove = getBoolParam(params, "fullMove", false);
		// Distance to move per step (meters) - only used if fullMove is false
		double stepDistance = getDoubleParam(params, "distance", 10.0);

		if(targetCellEast < 0 || targetCellNorth < 0) {
			return Response.status(400).entity("{\"error\":\"Target cell coordinates required\"}").build();
		}

		// Calculate remaining distance to target
		double remainingDistance = GameUtil.getDistanceToPosition(person,
			targetCellEast, targetCellNorth, targetPosEast, targetPosNorth);

		if(remainingDistance <= 0) {
			// Already at destination
			Map<String, Object> result = new HashMap<>();
			result.put("arrived", true);
			result.put("remainingDistance", 0);
			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		}

		boolean arrived = false;
		String abortReason = null;

		try {
			if(fullMove) {
				// Server-side movement loop - move until arrived
				double chunkSize = 10.0;  // Move 10m at a time
				int maxIterations = 10000;  // Safety limit
				int iterations = 0;
				double previousDistance = remainingDistance;
				int stallCount = 0;  // Circuit breaker: count iterations without progress

				while(iterations++ < maxIterations) {
					// Re-fetch person to get updated state/location after cell crossings
					person = GameUtil.findCharacter(objectId);
					if(person == null) {
						abortReason = "Character lost during movement";
						break;
					}

					remainingDistance = GameUtil.getDistanceToPosition(person,
						targetCellEast, targetCellNorth, targetPosEast, targetPosNorth);

					if(remainingDistance <= 1.0) {
						arrived = true;
						break;
					}

					// Circuit breaker: detect if we're not making progress
					// Allow small tolerance for floating point comparison
					if(remainingDistance >= previousDistance - 0.5) {
						stallCount++;
						logger.warn("Movement stall detected: iteration={} prev={} curr={} stallCount={}",
							iterations, previousDistance, remainingDistance, stallCount);
						if(stallCount >= 3) {
							abortReason = "Movement stuck (no progress after " + stallCount + " attempts)";
							break;
						}
					} else {
						// Making progress, reset stall counter
						stallCount = 0;
					}
					previousDistance = remainingDistance;

					double moveDistance = Math.min(chunkSize, remainingDistance);
					GameUtil.moveTowardsPosition(octx, person, targetCellEast, targetCellNorth, targetPosEast, targetPosNorth, moveDistance);
				}

				if(iterations >= maxIterations) {
					abortReason = "Movement timeout";
				}

				// Final re-fetch to ensure we return current state
				person = GameUtil.findCharacter(objectId);
			} else {
				// Single step movement (legacy behavior)
				double moveDistance = Math.min(stepDistance, remainingDistance);
				GameUtil.moveTowardsPosition(octx, person, targetCellEast, targetCellNorth, targetPosEast, targetPosNorth, moveDistance);

				// Re-fetch to get updated state
				person = GameUtil.findCharacter(objectId);
				if(person != null) {
					remainingDistance = GameUtil.getDistanceToPosition(person,
						targetCellEast, targetCellNorth, targetPosEast, targetPosNorth);
					arrived = remainingDistance <= 1.0;
				}
			}
		} catch(OlioException e) {
			logger.warn("MoveTo action failed: " + e.getMessage());
			return Response.status(400).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}

		// Return situation with remaining distance and status
		// The situation includes threats - client can check and handle accordingly
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found after movement\"}").build();
		}
		try {
			Map<String, Object> situationMap = GameUtil.getSituation(octx, person);
			situationMap.put("remainingDistance", GameUtil.getDistanceToPosition(person,
				targetCellEast, targetCellNorth, targetPosEast, targetPosNorth));
			situationMap.put("arrived", arrived);
			if(abortReason != null) {
				situationMap.put("abortReason", abortReason);
			}
			return Response.status(200).entity(JSONUtil.exportObject(situationMap)).build();
		} catch(Exception e) {
			return getSituation(objectId, request);
		}
	}

	private boolean getBoolParam(Map<String, Object> params, String key, boolean defaultValue) {
		Object val = params.get(key);
		if(val == null) return defaultValue;
		if(val instanceof Boolean) return (Boolean) val;
		return Boolean.parseBoolean(val.toString());
	}

	private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
		Object val = params.get(key);
		if(val == null) return defaultValue;
		if(val instanceof Number) return ((Number) val).intValue();
		try { return Integer.parseInt(val.toString()); } catch(Exception e) { return defaultValue; }
	}

	private double getDoubleParam(Map<String, Object> params, String key, double defaultValue) {
		Object val = params.get(key);
		if(val == null) return defaultValue;
		if(val instanceof Number) return ((Number) val).doubleValue();
		try { return Double.parseDouble(val.toString()); } catch(Exception e) { return defaultValue; }
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

		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		Map<String, Object> result = GameUtil.investigate(octx, person);
		if(result == null) {
			return Response.status(500).entity("{\"error\":\"Investigation failed - character may have no state or location\"}").build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// Chat with an NPC character.
	/// JSON body: { "actorId": "uuid", "targetId": "uuid", "chatConfigId": "uuid", "message": "hello" }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/chat")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response chat(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String actorId = (String) params.get("actorId");
		String targetId = (String) params.get("targetId");
		String chatConfigId = (String) params.get("chatConfigId");
		String message = (String) params.get("message");

		if(actorId == null || targetId == null || message == null) {
			return Response.status(400).entity("{\"error\":\"actorId, targetId, and message required\"}").build();
		}

		BaseRecord actor = GameUtil.findCharacter(actorId);
		BaseRecord target = GameUtil.findCharacter(targetId);

		if(actor == null || target == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		// Load chat config if provided
		BaseRecord chatConfig = null;
		if(chatConfigId != null && !chatConfigId.isEmpty()) {
			chatConfig = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigId);
			if(chatConfig == null) {
				logger.warn("Chat config not found: " + chatConfigId);
			}
		}

		Map<String, Object> result = GameUtil.chat(octx, actor, target, message, chatConfig);
		if(result.containsKey("error")) {
			return Response.status(500).entity(JSONUtil.exportObject(result)).build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// Get pending NPC chat requests for a player character.
	/// JSON body: { "playerId": "uuid" }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/chat/pending")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPendingChatRequests(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String playerId = (String) params.get("playerId");
		if(playerId == null) {
			return Response.status(400).entity("{\"error\":\"playerId required\"}").build();
		}

		BaseRecord player = GameUtil.findCharacter(playerId);
		if(player == null) {
			return Response.status(404).entity("{\"error\":\"Player not found\"}").build();
		}

		List<BaseRecord> pending = GameUtil.getPendingChatRequests(octx, player);

		// Build response with actor details
		List<Map<String, Object>> results = new ArrayList<>();
		for(BaseRecord interaction : pending) {
			Map<String, Object> item = new HashMap<>();
			BaseRecord actor = interaction.get("actor");
			if(actor != null) {
				item.put("npcId", actor.get("objectId"));
				item.put("npcName", actor.get("name"));
				item.put("npcFirstName", actor.get("firstName"));
			}
			item.put("interactionId", interaction.get("objectId"));
			item.put("reason", interaction.get("description"));
			item.put("timestamp", interaction.get("interactionStart"));
			results.add(item);
		}

		Map<String, Object> response = new HashMap<>();
		response.put("pending", results);
		return Response.status(200).entity(JSONUtil.exportObject(response)).build();
	}

	/// Create an NPC-initiated chat request (for testing or admin use).
	/// JSON body: { "npcId": "uuid", "playerId": "uuid", "reason": "greeting message" }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/chat/request")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response createNpcChatRequest(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String npcId = (String) params.get("npcId");
		String playerId = (String) params.get("playerId");
		String reason = (String) params.get("reason");

		if(npcId == null || playerId == null) {
			return Response.status(400).entity("{\"error\":\"npcId and playerId required\"}").build();
		}

		BaseRecord npc = GameUtil.findCharacter(npcId);
		BaseRecord player = GameUtil.findCharacter(playerId);

		if(npc == null || player == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		BaseRecord interaction = GameUtil.createNpcChatRequest(octx, npc, player, reason);
		if(interaction == null) {
			return Response.status(500).entity("{\"error\":\"Failed to create chat request\"}").build();
		}

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("interactionId", interaction.get("objectId"));
		return Response.status(200).entity(JSONUtil.exportObject(response)).build();
	}

	/// Resolve a pending chat request (accept or dismiss).
	/// JSON body: { "interactionId": "uuid", "accepted": true }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/chat/resolve")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response resolveChatRequest(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		@SuppressWarnings("unchecked")
		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String interactionId = (String) params.get("interactionId");
		Boolean accepted = (Boolean) params.get("accepted");

		if(interactionId == null || accepted == null) {
			return Response.status(400).entity("{\"error\":\"interactionId and accepted required\"}").build();
		}

		BaseRecord interaction = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, OlioModelNames.MODEL_INTERACTION, interactionId);
		if(interaction == null) {
			return Response.status(404).entity("{\"error\":\"Interaction not found\"}").build();
		}

		boolean success = GameUtil.resolveChatRequest(interaction, accepted);

		Map<String, Object> response = new HashMap<>();
		response.put("success", success);
		return Response.status(200).entity(JSONUtil.exportObject(response)).build();
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

		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String characterId = params.get("characterId");
		if(characterId == null) {
			return Response.status(400).entity("{\"error\":\"characterId required\"}").build();
		}

		BaseRecord person = GameUtil.findCharacter(characterId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		int techTier = 2;
		Object tierObj = params.get("techTier");
		if(tierObj instanceof Number) {
			techTier = ((Number)tierObj).intValue();
		}

		String climate = params.get("climate");

		BaseRecord apparel = GameUtil.generateOutfit(octx, person, techTier, climate);
		if(apparel == null) {
			return Response.status(500).entity("{\"error\":\"Failed to generate apparel\"}").build();
		}

		return Response.status(200).entity(apparel.toFullString()).build();
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

		BaseRecord person = GameUtil.findCharacter(objectId);
		if(person == null) {
			return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
		}

		Map<String, Object> result = GameUtil.adoptCharacter(octx, user, person);
		if(result == null) {
			return Response.status(500).entity("{\"error\":\"Failed to adopt character\"}").build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
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

		Map<String, Object> result = GameUtil.getNewGameData(octx);
		if(result == null) {
			return Response.status(500).entity("{\"error\":\"No realm available\"}").build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
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

		boolean inWorld = GameUtil.isCharacterInWorld(octx, objectId);

		Map<String, Object> result = new HashMap<>();
		result.put("inWorld", inWorld);
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

		List<Map<String, Object>> saves = GameUtil.listSaves(user);
		Map<String, Object> result = new HashMap<>();
		result.put("saves", saves);
		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
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
		Object eventLog = params.get("eventLog");

		Map<String, Object> result = GameUtil.saveGame(user, saveName, characterId, eventLog);
		if(result == null) {
			return Response.status(500).entity("{\"error\":\"Failed to save game\"}").build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// Load game state
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/load/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response loadGame(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		Map<String, Object> result = GameUtil.loadGame(objectId);
		if(result == null) {
			return Response.status(404).entity("{\"error\":\"Save not found or empty\"}").build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// Delete a saved game
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/deleteSave/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteSave(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		boolean deleted = GameUtil.deleteGame(objectId);
		if(!deleted) {
			return Response.status(404).entity("{\"error\":\"Save not found\"}").build();
		}

		Map<String, Object> result = new HashMap<>();
		result.put("deleted", true);
		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// Conclude a chat session and create an interaction record based on LLM evaluation.
	/// JSON body: {
	///   "actorId": "uuid",
	///   "targetId": "uuid",
	///   "chatConfigId": "uuid" (optional),
	///   "messages": [{"role": "user|assistant", "content": "..."}]
	/// }
	///
	@RolesAllowed({"user"})
	@POST
	@Path("/concludeChat")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response concludeChat(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		if(octx == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize context\"}").build();
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> params = JSONUtil.importObject(json, Map.class);
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String actorId = (String) params.get("actorId");
		String targetId = (String) params.get("targetId");
		@SuppressWarnings("unchecked")
		List<Map<String, String>> messages = (List<Map<String, String>>) params.get("messages");

		if(actorId == null || targetId == null || messages == null || messages.isEmpty()) {
			return Response.status(400).entity("{\"error\":\"actorId, targetId, and messages are required\"}").build();
		}

		BaseRecord actor = GameUtil.findCharacter(actorId);
		if(actor == null) {
			return Response.status(404).entity("{\"error\":\"Actor not found\"}").build();
		}

		BaseRecord target = GameUtil.findCharacter(targetId);
		if(target == null) {
			return Response.status(404).entity("{\"error\":\"Target not found\"}").build();
		}

		// Optionally get chat config for model settings
		BaseRecord chatConfig = null;
		String chatConfigId = (String) params.get("chatConfigId");
		if(chatConfigId != null) {
			chatConfig = GameUtil.findCharacter(chatConfigId); // This should be a chatConfig lookup
		}

		Map<String, Object> result = GameUtil.concludeChat(octx, user, actor, target, messages, chatConfig);
		if(result == null) {
			return Response.status(500).entity("{\"error\":\"Failed to evaluate chat\"}").build();
		}

		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// Get a terrain tile image.
	/// Valid terrain types: cave, clear, desert, dunes, forest, glacier, grass, hill, hills,
	/// jungle, lake, marsh, mountain, oasis, ocean, plains, plateau, pond, river, savanna,
	/// shelter, shoreline, stream, swamp, tundra, valley
	/// Note: PermitAll to allow image loading without auth (tiles are public static resources)
	///
	@jakarta.annotation.security.PermitAll
	@GET
	@Path("/tile/{terrain:[a-z]+}")
	@Produces("image/png")
	public Response getTile(@PathParam("terrain") String terrain, @Context HttpServletRequest request) {
		// Get tile path from context parameter, default to relative path
		String tilePath = context.getInitParameter("tile.path");
		if(tilePath == null || tilePath.isEmpty()) {
			tilePath = "./media/tiles";
		}

		// Sanitize terrain name - only allow lowercase letters
		if(!terrain.matches("^[a-z]+$")) {
			return Response.status(400).entity("Invalid terrain name").build();
		}

		// Map "unknown" terrain to "clear" as fallback
		if("unknown".equals(terrain)) {
			terrain = "clear";
		}

		String filePath = tilePath + File.separator + terrain + ".png";
		byte[] imageData = FileUtil.getFile(filePath);

		if(imageData == null || imageData.length == 0) {
			// Fallback to clear tile if specific terrain not found
			logger.warn("Tile not found: " + filePath + ", falling back to clear");
			filePath = tilePath + File.separator + "clear.png";
			imageData = FileUtil.getFile(filePath);
		}

		if(imageData == null || imageData.length == 0) {
			logger.error("Fallback tile also not found: " + filePath);
			return Response.status(404).entity("Tile not found").build();
		}

		return Response.ok(imageData, "image/png")
			.header("Cache-Control", "public, max-age=86400") // Cache for 24 hours
			.build();
	}
}
