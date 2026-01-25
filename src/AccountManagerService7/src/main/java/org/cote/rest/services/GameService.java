package org.cote.rest.services;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
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

		state.setValue("playerControlled", true);
		Queue.queueUpdate(state, new String[]{"playerControlled"});
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
}
