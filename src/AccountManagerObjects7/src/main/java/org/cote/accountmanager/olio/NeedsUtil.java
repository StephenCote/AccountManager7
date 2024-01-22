package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.PersonalityProfile.PhysiologicalNeeds;
import org.cote.accountmanager.olio.ThreatUtil.ThreatEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class NeedsUtil {

	public static final Logger logger = LogManager.getLogger(NeedsUtil.class);
	private static final SecureRandom random = new SecureRandom();
	private static final int partyMin = 3;
	private static final int partyMax = 10;
	private static final int minPartyAge = 14;
	private static final int maxPartyAge = 40;
	private static final int maxPartyCheck = 5;
	
	
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

	protected static List<BaseRecord> localWildlife(BaseRecord realm, BaseRecord location){
		long id = location.get(FieldNames.FIELD_ID);
		List<BaseRecord> zoo = realm.get("zoo");
		return zoo.stream().filter(zp -> (zp.get("state.currentLocation") != null && ((long)zp.get("state.currentLocation.id")) == id)).collect(Collectors.toList());
	}
	protected static void agitateLocation(OlioContext ctx, BaseRecord realm, BaseRecord event, List<BaseRecord> pop, boolean cluster) {
		BaseRecord eloc = event.get("location");
		List<BaseRecord> acells = GeoLocationUtil.getCells(ctx, eloc);
		BaseRecord rloc = null;
		
		try {
			for(BaseRecord p: pop) {
				boolean blup = false;
				boolean bloc = false;
				
				String name = p.get(FieldNames.FIELD_NAME);
				BaseRecord state = p.get("state");
				BaseRecord location = state.get("currentLocation");
				if(location == null) {
					if(rloc == null) {
						rloc = acells.get(random.nextInt(acells.size()));
					}
					location = rloc;
					if(!cluster) {
						rloc = null;
					}
					state.set("currentLocation", rloc);
					blup = true;
				}
				if(StateUtil.agitateLocation(ctx, state)) {
					bloc = true;
				}
				String geoType = location.get("geoType");
				if(geoType.equals("feature")) {
					logger.warn("Feature placement detected: Move " + name);
				}
				else {
					List<String> upf = new ArrayList<>();
					if(blup) {
						upf.add("currentLocation");
					}
					if(bloc) {
						upf.add("currentEast");
						upf.add("currentNorth");
					}
					if(upf.size() > 0) {
						upf.add(FieldNames.FIELD_ID);
						ctx.queue(state.copyRecord(upf.toArray(new String[0])));
					}
				}
			}
			ctx.processQueue();
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	protected static Map<BaseRecord, Map<ThreatEnumType,List<BaseRecord>>> agitate(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> map) {
		BaseRecord eloc = event.get("location");
		Map<BaseRecord, Map<ThreatEnumType, List<BaseRecord>>> tmap = new HashMap<>();
		try {
			agitateLocation(ctx, realm, event, Arrays.asList(map.keySet().toArray(new BaseRecord[0])), true);
			for(BaseRecord p: map.keySet()) {

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
						else {
							Map<ThreatEnumType, List<BaseRecord>> threats = ThreatUtil.evaluateImminentThreats(ctx, realm, event, map, p);
							if(threats.keySet().size() > 0) {
								tmap.put(p, threats);
							}
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
	
	public static List<BaseRecord> recommend(OlioContext ctx, BaseRecord locationEpoch, BaseRecord increment){
		List<BaseRecord> acts = new ArrayList<>();
		return acts;
	}

	public static List<BaseRecord> recommend(OlioContext ctx, BaseRecord locationEpoch, BaseRecord increment, List<BaseRecord> group){
		Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, group);
		PersonalityGroupProfile pgp = ProfileUtil.getGroupProfile(map);
		logger.info("Calculating recommendation ....");
		BaseRecord realm = ctx.getRealm(locationEpoch.get("location"));
		Map<BaseRecord, Map<ThreatEnumType, List<BaseRecord>>> tmap = agitate(ctx, realm, increment, map);
		if(tmap.keySet().size() > 0) {
			logger.warn("TODO: Evaluate initial threats");
		}
		MapUtil.printLocationMap(ctx, locationEpoch.get(FieldNames.FIELD_LOCATION), realm, group);

		List<BaseRecord> acts = new ArrayList<>();
		
		return acts;
	}
	
	public static List<BaseRecord> getCreateParty(OlioContext ctx, BaseRecord locationEpoch){
		List<BaseRecord> party = new ArrayList<>();
		BaseRecord loc = locationEpoch.get("location");
		IOSystem.getActiveContext().getReader().populate(loc, new String[] { FieldNames.FIELD_NAME });
		String partyName = loc.get(FieldNames.FIELD_NAME) + " Party";
		BaseRecord grp = null;
		try {
			BaseRecord realm = ctx.getRealm(locationEpoch.get("location"));
			grp = OlioUtil.getCreatePopulationGroup(ctx, partyName);
			if(realm.get("principalGroup") == null) {
				realm.set("principalGroup", grp);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(realm.copyRecord(new String[] {FieldNames.FIELD_ID, "principalGroup", FieldNames.FIELD_ORGANIZATION_ID}));
			}
			party = OlioUtil.listGroupPopulation(ctx, grp);
			if(party.size() == 0) {
				List<BaseRecord> lpop = ctx.getPopulation(loc);
				int len = random.nextInt(partyMin, partyMax);
				Set<Long> partSet = new HashSet<>();
				logger.info("Creating a party of " + len + " from " + lpop.size());
				for(int i = 0; i < len; i++) {
					BaseRecord per = lpop.get(random.nextInt(lpop.size()));
					long id = per.get(FieldNames.FIELD_ID);
					int age = per.get("age");
					int check = 0;
					while(partSet.contains(id) || age < minPartyAge || age > maxPartyAge) {
						per = lpop.get(random.nextInt(lpop.size()));
						id = per.get(FieldNames.FIELD_ID);
						age = per.get("age");
						check++;
						if(check > maxPartyCheck) {
							break;
						}
					}
					if(!partSet.contains(id)) {
						partSet.add(id);
						if(!IOSystem.getActiveContext().getMemberUtil().member(ctx.getUser(), grp, per, null, true)) {
							logger.error("Failed to add member");
						}
					}
				}
				party = OlioUtil.listGroupPopulation(ctx, grp);
			}
			logger.info(partyName + " size = " + party.size());
			
		} catch (FieldException | ValueException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
		}
		return party;
	}
	
}
