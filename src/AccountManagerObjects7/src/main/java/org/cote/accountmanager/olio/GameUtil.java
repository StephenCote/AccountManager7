package org.cote.accountmanager.olio;

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
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;

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

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLoc == null) {
			return null;
		}

		List<BaseRecord> realms = octx.getRealms();
		BaseRecord realm = realms.isEmpty() ? null : realms.get(0);
		if (realm == null) {
			return null;
		}

		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		List<BaseRecord> adjacentCells = GeoLocationUtil.getAdjacentCells(octx, currentLoc, 3);
		List<BaseRecord> nearbyPeople = GeoLocationUtil.limitToAdjacent(octx, pop, currentLoc);

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
		result.put("location", currentLoc);
		result.put("nearbyPeople", nearbyPeople);
		result.put("adjacentCells", adjacentCells);

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

		return result;
	}

	public static boolean moveCharacter(OlioContext octx, BaseRecord person, DirectionEnumType direction, double distance) throws OlioException {
		if (octx == null || person == null || direction == null || direction == DirectionEnumType.UNKNOWN) {
			return false;
		}

		if (distance <= 0) {
			distance = 1.0;
		}

		BaseRecord epoch = octx.clock() != null ? octx.clock().getEpoch() : null;

		BaseRecord actionResult = Actions.beginMove(octx, epoch, person, direction, distance);
		if (actionResult == null) {
			throw new OlioException("Failed to create move action");
		}

		boolean success = Actions.executeAction(octx, actionResult);
		if (!success) {
			return false;
		}

		Actions.concludeAction(octx, actionResult, person, null);
		Queue.processQueue();

		return true;
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

			boolean success = Actions.executeAction(octx, actionResult);
			if (!success) {
				return false;
			}

			Actions.concludeAction(octx, actionResult, person, null);
			Queue.processQueue();
			return true;
		} catch (OlioException e) {
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
}
