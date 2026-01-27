package org.cote.rest.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.DirectionEnumType;
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

		BaseRecord state = GameUtil.getCharacterState(person);
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
		if(distance <= 0) {
			distance = 1.0;
		}

		try {
			boolean success = GameUtil.moveCharacter(octx, person, dir, distance);
			if(!success) {
				return Response.status(400).entity("{\"error\":\"Movement failed - possibly blocked\"}").build();
			}
		} catch(OlioException e) {
			logger.error("Move action failed: " + e.getMessage());
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}

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
}
