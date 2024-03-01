package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.PersonalityProfile.EsteemNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.LoveNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.PhysiologicalNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.SafetyNeeds;
import org.cote.accountmanager.olio.personality.DarkTriadUtil;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.personality.PersonalityUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class NeedsUtil {

	public static final Logger logger = LogManager.getLogger(NeedsUtil.class);
	private static final SecureRandom random = new SecureRandom();
	
	
	/*
	 * In the Hierarchy of Needs driven rule system, the order of evaluation is:
	 * (3 Rs of Needs Evaluation) Recommend, Re/Act, Result
	 * Recommend:
	 * 	- Use the epoch alignment as a guide for external/random occurrences, and as a sentiment modifier
	 *  - Iterate through population, subgroup, or individual
	 *  - Calculate initial instinct modifiers, behaviors for the event increment
	 *  - Calculate initial state change for the event increment
	 *  - Create 2 profiles: current profile (what they are), and projected event outcome profile (what they want - char state vs char + event state)
	 *  - For each individual, recommend zero or more actions based on need and 'current' and 'projected' personality
	 *     - Produced actions may be chains of dependencies.  E.G.: To eat while having no food would spawn forage, or hunt.  Hunt would spawn the need for a trap or weapon, etc.
	 *  - Evaluate the list of actions any peer groups the provided group members belong to
	 *     - This may result in immediate actions/results such as attempting to lead or influence choice
	 *  - Evaluate the list against any adjacent or established group that may have influence over the peer group or individual
	 *     - E.G.: If there's a government, or established leader/leadership group
	 *  - Evaluate the list against any authority group that may have or try to exert control over the peer group or individual
	 *  - Evaluate the list again individually for personality influencers
	 *  - Evaluate the list again individually for social group modifiers
	 *  
	 *  - Therefore, the order of recommendation is: individual, peer group, any adjacent peer group, leadership group, any authority group, individual, peer group
	 *  - The result of the recommendation is a list of pending action chains assigned to zero or more people (zero would mean the action isn't performed, but is kept as a marker).
	 *  - The list of actions may now include counter actions from the iterative review
	 *  - If an action is determined to be repeatable, attach any schedules off the event
	 * Re/Act
	 * 	- Actions are started in order.
	 *  - For each action, determine if a counter action was included
	 *  - For each counter action, evaluate a 'decide' action to determine if it conducted.  If so, that action becomes contested 
     *  - For contested actions, determine the starting order (who goes first), and then evaluate each.
     *  - For any participants involved in a contested action, re-evaluate their assigned actions to determine any impacts
	 *  - Evaluate all remaining ActionResult results and modifiers.
	 *  - Evaluate all ActionResults for recommended subsequent actions.
	 *  - Evaluate any subsequent action than can/should be completed in the increment.  The remaining subsequent actions will be picked up at a later increment
	 * Result
	 *  - Evaluate the ActionResults for each individual
	 *  - Evaluate the results for each peer group for peer group / behavior adjustment
	 *  - Evaluate the results for any adjacent peer group
	 *  - Evaluate the results for any leadership or authority group
	 *  - Reevaluate the results for each individual for personality influencers
	 *  - Reevaluate the results for each individual for social group influencers
	 *  
	 * The 3Rs are evaluated after preparatory consideration
	 * 1) Assess Locations
	 *    a) The base location (check stores, populate items/animals, etc)
	 *    b) The current location (check stores, populate items/animals, etc)
	 *    c) The current population (check for personality-driven events/actions)
	 *    c) The current peer groups (check for personality-driven events/actions)
	 *    c) Rules should apply to the current location
	 * 2) Assess Group/Individual
	 *    a) What are the current needs, and which is most pressing?
	 *    b) What is the current relative situation (expressed as a state and description)
	 *    c) Are there any current threats?  People, groups, animals, events (eg: natural)
	 *    d) Eval 3Rs
	 * 3) Post Process
	 *    A) Results from #1 and #2 should be queued as much as possible 
	 *    b) Determine significance, if any, of ActionResults
	 *    c) Evaluate behavior and personality impacts
	 *  
	 */
	
	public static List<BaseRecord> recommend(OlioContext ctx, BaseRecord locationEpoch, BaseRecord increment){
		List<BaseRecord> acts = new ArrayList<>();
		return acts;
	}
	
	public static List<BaseRecord> recommend(OlioContext ctx, BaseRecord locationEpoch, BaseRecord increment, List<BaseRecord> group){
		Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, group);
		PersonalityGroupProfile pgp = ProfileUtil.getGroupProfile(map);

		logger.info("Calculating initial recommendation ....");
		BaseRecord realm = ctx.getRealm(locationEpoch.get("location"));
		/// Agitate map, people, animals, and identify initial threats
		///
		List<BaseRecord> inters = increment.get("interactions");
		Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = agitate(ctx, realm, increment, map, false);
		if(tmap.keySet().size() > 0) {
			// logger.warn("TODO: Evaluate initial threats into actions");
			/// Evaluate initial threats into interaction placeholders
			///
			tmap.forEach((pp, threats) -> {
				threats.forEach((tet, al) -> {
					al.forEach(a -> {
						BaseRecord inter = InteractionUtil.newInteraction(ctx, increment, a, tet, pp.getRecord());
						inter.setValue("description", a.get("name") + " is a " + tet.toString() + " to " + pp.getRecord().get("firstName"));
						inters.add(inter);
						ctx.queue(inter);
					});
				});
			});
		}
		
		/// Evaluate needs 
		List<BaseRecord> actions = evaluateNeeds(ctx, locationEpoch, increment, group, map);
		logger.info("Group actions to delegate: " + actions.size());
		
		/// Delegate actions
		GroupDynamicUtil.delegateActions(ctx, locationEpoch, increment, map, actions);
		ctx.queueUpdate(increment, new String[] {FieldNames.FIELD_ID, "interactions"});
		String nar = NarrativeUtil.lookaround(ctx, realm, increment, increment, group, group.get((new Random()).nextInt(0,group.size())), tmap);
		logger.info(nar);
		
		/*
		MapUtil.printLocationMap(ctx, locationEpoch.get(FieldNames.FIELD_LOCATION), realm, group);
		MapUtil.printRealmMap(ctx, realm);
		*/
		List<BaseRecord> acts = new ArrayList<>();
		
		return acts;
	}


	
	protected static BaseRecord createActionForNeed(OlioContext ctx, PhysiologicalNeeds need) {
		BaseRecord action = null;
		BaseRecord builder = null;
		BaseRecord actionResult = null;
		if(need == PhysiologicalNeeds.SHELTER) {
			List<BaseRecord> builders = Arrays.asList(BuilderUtil.getBuilders(ctx)).stream().filter(b -> ((String)b.get("type")).equals("location")).collect(Collectors.toList());
			List<BaseRecord> actions = Arrays.asList(ActionUtil.getActions(ctx)).stream().filter(b -> ((String)b.get("name")).equals("build")).collect(Collectors.toList());
			action = actions.get(0);
			builder = builders.get(random.nextInt(0, builders.size()));
		}
		else if(need == PhysiologicalNeeds.FOOD) {
			List<BaseRecord> actions = Arrays.asList(ActionUtil.getActions(ctx)).stream().filter(b -> ((String)b.get("name")).equals("gather") || ((String)b.get("name")).equals("hunt")).collect(Collectors.toList());
			action = actions.get(random.nextInt(0, actions.size()));
		}
		else if(need == PhysiologicalNeeds.WATER) {
			List<BaseRecord> actions = Arrays.asList(ActionUtil.getActions(ctx)).stream().filter(b -> ((String)b.get("name")).equals("gather")).collect(Collectors.toList());
			action = actions.get(random.nextInt(0, actions.size()));
		}
		else if(need == PhysiologicalNeeds.REPRODUCTION) {
			/// 
		}
		if(action != null) {
			ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("actionResults.path"));
			try {
				actionResult = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACTION_RESULT, ctx.getUser(), null, plist);
				actionResult.set("action", action);
				actionResult.set("builder", builder);
				actionResult.set("needType", "physiological");
				actionResult.set("need", need.toString().toLowerCase());
			} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}

		}

		return actionResult;
	}
	
	protected static BaseRecord createActionForNeed(OlioContext ctx, SafetyNeeds need) {
		BaseRecord actionResult = null;
		return actionResult;
	}

	protected static BaseRecord createActionForNeed(OlioContext ctx, EsteemNeeds need) {
		BaseRecord actionResult = null;
		return actionResult;
	}

	protected static BaseRecord createActionForNeed(OlioContext ctx, LoveNeeds need) {
		BaseRecord actionResult = null;
		return actionResult;
	}

	
	protected static List<BaseRecord> evaluateNeeds(OlioContext ctx, BaseRecord locationEpoch, BaseRecord increment, List<BaseRecord> group, Map<BaseRecord, PersonalityProfile> map) {
		List<BaseRecord> actions = new ArrayList<>();
		PersonalityGroupProfile pgp = ProfileUtil.getGroupProfile(map);
		List<PhysiologicalNeeds> pneeds = pgp.getPhysiologicalNeedsPriority();
		for(PhysiologicalNeeds need: pneeds) {
			BaseRecord act = createActionForNeed(ctx, need);
			if(act != null) {
				actions.add(act);
			}
		}

		List<SafetyNeeds> sneeds = pgp.getSafetyNeedsPriority();
		for(SafetyNeeds need: sneeds) {
			BaseRecord act = createActionForNeed(ctx, need);
			if(act != null) {
				actions.add(act);
			}
		}
		
		List<LoveNeeds> lneeds = pgp.getLoveNeedsPriority();
		for(LoveNeeds need: lneeds) {
			BaseRecord act = createActionForNeed(ctx, need);
			if(act != null) {
				actions.add(act);
			}
		}
		
		List<EsteemNeeds> eneeds = pgp.getEsteemNeedsPriority();
		for(EsteemNeeds need: eneeds) {
			BaseRecord act = createActionForNeed(ctx, need);
			if(act != null) {
				actions.add(act);
			}
		}
		
		return actions;
	}

	protected static List<BaseRecord> localWildlife(BaseRecord realm, BaseRecord location){
		long id = location.get(FieldNames.FIELD_ID);
		List<BaseRecord> zoo = realm.get("zoo");
		return zoo.stream().filter(zp -> (zp.get("state.currentLocation") != null && ((long)zp.get("state.currentLocation.id")) == id)).collect(Collectors.toList());
	}
	
	protected static void agitateLocation(OlioContext ctx, BaseRecord realm, BaseRecord event, List<BaseRecord> pop, boolean cluster, boolean roam) {
		BaseRecord eloc = event.get("location");
		List<BaseRecord> acells = GeoLocationUtil.getCells(ctx, eloc);
		BaseRecord rloc = null;
		
		try {
			for(BaseRecord p: pop) {
				String name = p.get(FieldNames.FIELD_NAME);
				BaseRecord state = p.get("state");
				if((boolean)state.get("agitated") == true) {
					continue;
				}
				BaseRecord location = state.get("currentLocation");
				if(location == null) {
					if(rloc == null) {
						rloc = acells.get(random.nextInt(acells.size()));
					}
					location = rloc;
					state.set("currentLocation", rloc);
					if(!cluster) {
						rloc = null;
					}
				}
				StateUtil.agitateLocation(ctx, state);
				state.setValue("agitated", true);
				// logger.info("Agitate " + p.get("name") + " " + location.get("name") + ", " + state.get("currentEast") + ", " + state.get("currentNorth"));

				String geoType = location.get("geoType");
				if(geoType.equals("feature")) {
					logger.warn("Feature placement detected: Move " + name);
				}
				else {
					List<String> upf = new ArrayList<>();
					upf.add("currentLocation");
					upf.add("currentEast");
					upf.add("currentNorth");
					/// if not roaming, then persist the agitated state to prevent further location agitation
					if(!roam) {
						upf.add("agitated");
					}
					upf.add(FieldNames.FIELD_ID);
					ctx.queue(state.copyRecord(upf.toArray(new String[0])));
				}
			}
			ctx.processQueue();
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	protected static Map<PersonalityProfile, Map<ThreatEnumType,List<BaseRecord>>> agitate(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> map, boolean roam) {
		BaseRecord eloc = event.get("location");
		Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = new HashMap<>();
		try {
			agitateLocation(ctx, realm, event, Arrays.asList(map.keySet().toArray(new BaseRecord[0])), true, roam);
			for(PersonalityProfile pp: map.values()) {
				BaseRecord p = pp.getRecord();
				String name = p.get(FieldNames.FIELD_NAME);
				BaseRecord state = p.get("state");
				boolean immobile = state.get("immobilized");
				boolean alive = state.get("alive");
				boolean awake = state.get("awake");
				
				BaseRecord location = state.get("currentLocation");

				long id = location.get(FieldNames.FIELD_ID);
				String geoType = location.get("geoType");
				if(geoType.equals("feature")) {
					logger.warn("Feature placement detected: Move " + name);
				}
				else {
					/// People in current location
					/*
					int currLocCount = map.keySet().stream()
					  .map(c -> c.get("state.currentLocation") != null && ((long)c.get("state.currentLocation.id")) == id ? 1 : 0)
					  .reduce(0, Integer::sum);
					*/
					/// logger.info("Agitate " + name + " in " + location.get(FieldNames.FIELD_NAME));
					if(alive && awake && !immobile) {
						if(state.get("currentEvent") != null) {
							logger.warn("Agitating " + name + " who is currently busy");
						}
						Map<ThreatEnumType, List<BaseRecord>> threats = ThreatUtil.evaluateImminentThreats(ctx, realm, event, map, p);
						if(threats.keySet().size() > 0) {
							tmap.put(pp, threats);
						}
					}
				}
			}
			ctx.processQueue();
		}
		catch(Exception e) {
			logger.error(e);
		}
		return tmap;
	}
	

	

	
	public static boolean isNaked(BaseRecord record) {
		List<BaseRecord> items = record.get("store.apparel");
		/// TODO: Need to filter for inuse
		return items.size() == 0;
	}	
	
	public static boolean needsFood(BaseRecord record) {
		List<BaseRecord> items = record.get("store.items");
		List<BaseRecord> food = items.stream().filter(i -> "food".equals(i.get("category"))).collect(Collectors.toList());
		return food.size() == 0;
	}
	
	public static boolean needsWater(BaseRecord record) {
		List<BaseRecord> items = record.get("store.items");
		List<BaseRecord> water = items.stream().filter(i -> "water".equals(i.get("category"))).collect(Collectors.toList());
		return water.size() == 0;
	}
	
}
