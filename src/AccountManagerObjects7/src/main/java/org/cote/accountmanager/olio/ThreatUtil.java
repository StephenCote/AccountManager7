package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class ThreatUtil {
	public static final Logger logger = LogManager.getLogger(ThreatUtil.class);
	
	public static List<BaseRecord> evaluateThreatMap(OlioContext ctx, Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap, BaseRecord increment){
		List<BaseRecord> inters = new ArrayList<>();
		if(tmap.keySet().size() > 0) {
			// logger.warn("TODO: Evaluate initial threats into actions");
			/// Evaluate initial threats into interaction placeholders
			///
			tmap.forEach((pp, threats) -> {
				threats.forEach((tet, al) -> {
					al.forEach(a -> {
						BaseRecord inter = InteractionUtil.newInteraction(ctx, InteractionEnumType.THREATEN, increment, a, tet, pp.getRecord());
						inter.setValue("description", a.get(FieldNames.FIELD_NAME) + " is a " + tet.toString() + " to " + pp.getRecord().get(FieldNames.FIELD_FIRST_NAME));
						inters.add(inter);
						//Queue.queue(inter);
					});
				});
			});
		}
		return inters;
	}
	
	public static boolean isRelativelyAggressive(AnimalProfile possibleThreat, PersonalityProfile person) {

		boolean isAgg = false;
		if(!possibleThreat.isAlive()) {
			/// logger.info("Sheesh, " + possibleThreat.getName() + " isn't even alive");
			return false;
		}
		
		/// Percentage of distance by (very limited) maximum visibility
		double dr = GeoLocationUtil.distanceRelativityToState(possibleThreat.getRecord(), person.getRecord());

		/// Is the animal starving
		boolean starving = InstinctUtil.checkFeedInstinct(possibleThreat, InstinctEnumType.STRONG, dr); 
		
		/// do they have any 'fight' in them relative to distance
		boolean fight = InstinctUtil.checkFightInstinct(possibleThreat, InstinctEnumType.AVERAGE, dr); 

		/// do they have a sense of 'protection' relative to distance
		///
		boolean protect = InstinctUtil.checkProtectInstinct(possibleThreat, InstinctEnumType.STRONG, dr);
		if(fight || protect || starving) {
			/// Do they see the person (perception)?
			// logger.info(possibleThreat.getName() + " has a fight, protect, or feed instinct");
			RollEnumType perc = RollUtil.rollPerception(possibleThreat.getRecord());
			if(perc == RollEnumType.SUCCESS || perc == RollEnumType.NATURAL_SUCCESS) {
				isAgg = true;
				/// Do they 'react' to the person
				/*
				RollEnumType react = RollUtil.rollReaction(possibleThreat.getRecord());
				if(react == RollEnumType.SUCCESS || react == RollEnumType.NATURAL_SUCCESS) {
					isAgg = true;
				}
				else {
					// logger.info(possibleThreat.getName() + " didn't react to 'em");
				}
				*/
			}
			else {
				// logger.info("But, " + possibleThreat.getName() + " didn't react");
			}

		}
		else {
			// logger.info(possibleThreat.getName() + " has no fight or protective instinct in 'em");
		}

		return isAgg;
	}
	
	public static boolean isToxic(BaseRecord record) {
		List<BaseRecord> items = record.get(OlioFieldNames.FIELD_STORE_ITEMS);
		return items.stream().filter(i -> "toxin".equals(i.get(FieldNames.FIELD_NAME))).collect(Collectors.toList()).size() > 0;
	}

	public static ThreatEnumType evaluateAggressive(AnimalProfile ap, PersonalityProfile pp, ThreatEnumType threat, ThreatEnumType antiThreat) {
		boolean isagg = isRelativelyAggressive(ap, pp);
		ThreatEnumType tet = ThreatEnumType.NONE;
		if(isagg) {
			/// roll for reaction to act on the aggression
			tet = evaluateStatThreat(ap.getRecord(), pp.getRecord(), "reaction", threat, antiThreat);
			if(tet == ThreatEnumType.EXISTENTIAL_THREAT) {
				ap.getFixations().add(pp.getRecord());
				logger.warn(ap.getName() + " is now fixated");
			}
			else if(tet == ThreatEnumType.ANIMAL_TARGET || tet == ThreatEnumType.PERSONAL_TARGET) {
				logger.warn(ap.getName() + " made a spectacle of itself: " + tet.toString());
			}
			else {
				// logger.warn(ap.getName() + " is acting aggressive: " + tet.toString());
			}
		}
		else {
			// logger.info(ap.getName() + " is not currently aggressive");
		}
		return tet;
	}
	
	public static Map<ThreatEnumType, List<AnimalProfile>> evaluateAnimalThreat(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, PersonalityProfile person){
		BaseRecord state = person.getRecord().get(FieldNames.FIELD_STATE);
		// BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		//PersonalityProfile pp = group.get(person);
		List<BaseRecord> zoo = realm.get(OlioFieldNames.FIELD_ZOO);
		// logger.info("Agitate animal threats");
		NeedsUtil.agitateLocation(ctx, realm, event, zoo, false, true);
		
		/// Find animals in the current and adjacent cells
		List<BaseRecord> zpop = GeoLocationUtil.sortByDistanceToState(GeoLocationUtil.limitToAdjacent(ctx, zoo, state.get(OlioFieldNames.FIELD_CURRENT_LOCATION)), person.getRecord().get(FieldNames.FIELD_STATE));
		//List<AnimalProfile> tpop = new ArrayList<>();
		Map<ThreatEnumType, List<AnimalProfile>> tpop = new HashMap<>();
		//Map<BaseRecord, AnimalProfile> amap = ProfileUtil.getAnimalProfileMap(zpop);
		//for(AnimalProfile ap : amap.values()) {
		for(BaseRecord apr: zpop) {
			AnimalProfile ap = ProfileUtil.getAnimalProfile(apr);
			// boolean toxic = isToxic(ap.getRecord());
			ThreatEnumType tet = evaluateAggressive(ap, person, ThreatEnumType.ANIMAL_THREAT, ThreatEnumType.ANIMAL_TARGET);
			if(tet != ThreatEnumType.NONE) {
				if(!tpop.containsKey(tet)) {
					tpop.put(tet, new ArrayList<>());
				}
				tpop.get(tet).add(ap);
			}
		}
		return tpop;
	}

	
	public static Map<ThreatEnumType, List<PersonalityProfile>> evaluatePersonalThreat(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, PersonalityProfile person){
		BaseRecord state = person.getRecord().get(FieldNames.FIELD_STATE);

		long id = person.getId();
		
		/// Exclude the primary party from the general populace for purposes of agitating and assessing threat with the remainder of the population
		///
		List<Long> gids = group.keySet().stream().map(r -> ((long)r.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		List<BaseRecord> pop = ctx.getRealmPopulation(realm);
		
		// logger.info("Agitate person threats");
		NeedsUtil.agitateLocation(ctx, realm, event, pop, false, true);

		/// Find people in the current and adjacent cells
		List<BaseRecord> ppop = GeoLocationUtil.limitToAdjacent(ctx, pop, state.get(OlioFieldNames.FIELD_CURRENT_LOCATION));

		Map<ThreatEnumType, List<PersonalityProfile>> tpop = new HashMap<>();
		Map<BaseRecord, PersonalityProfile> amap = ProfileUtil.getProfileMap(ctx, ppop);
		for(PersonalityProfile ap : amap.values()) {
			if(ap.getId() == id) {
				/// skip self
				continue;
			}
			ThreatEnumType tet = evaluateAggressive(ap, person, ThreatEnumType.PERSONAL_THREAT, ThreatEnumType.PERSONAL_TARGET);
			if(tet != ThreatEnumType.NONE) {
				if(!tpop.containsKey(tet)) {
					tpop.put(tet, new ArrayList<>());
				}
				tpop.get(tet).add(ap);
			}
		}

		return tpop;
	}
	
	private static ThreatEnumType evaluateStatThreat(BaseRecord rec1, BaseRecord rec2, String statistic, ThreatEnumType threat, ThreatEnumType antiThreat) {
		ThreatEnumType tet = ThreatEnumType.NONE;
		RollEnumType per = RollUtil.rollStat20(rec1, statistic);
		if(per == RollEnumType.CATASTROPHIC_FAILURE) {
			logger.warn(rec1.get(FieldNames.FIELD_NAME) + " #" + rec1.get(FieldNames.FIELD_ID) + " made itself a target by catastrophically failing a " + statistic + " roll");
			tet = antiThreat;
		}
		else if(per == RollEnumType.NATURAL_SUCCESS) {
			logger.warn(rec1.get(FieldNames.FIELD_NAME) + " #" + rec1.get(FieldNames.FIELD_ID) + " made itself an existential threat by naturally succeeding a " + statistic + " roll");
			tet = ThreatEnumType.EXISTENTIAL_THREAT;
		}
		if(per == RollEnumType.SUCCESS) {
			tet = threat;
		}

		return tet;
	}
	
	public static Map<ThreatEnumType,List<BaseRecord>> evaluateImminentThreats(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, PersonalityProfile person) {
		Map<ThreatEnumType, List<BaseRecord>> threats = new HashMap<>();
		Map<ThreatEnumType, List<AnimalProfile>> amap = evaluateAnimalThreat(ctx, realm, event, group, person);
		for(ThreatEnumType tet : amap.keySet()) {
			addThreat(threats, tet, amap.get(tet).stream().map(a -> a.getRecord()).collect(Collectors.toList()));
		}
		Map<ThreatEnumType, List<PersonalityProfile>> pmap =  evaluatePersonalThreat(ctx, realm, event, group, person);
		for(ThreatEnumType tet : pmap.keySet()) {
			addThreat(threats, tet, pmap.get(tet).stream().map(a -> a.getRecord()).collect(Collectors.toList()));
		}

		/*
		List<AnimalProfile> anim = evaluateAnimalThreat(ctx, realm, event, group, person);
		addThreat(threats, ThreatEnumType.EXISTENTIAL_THREAT, anim.stream().filter(a -> a.getFixations().size() > 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		addThreat(threats, ThreatEnumType.ANIMAL_THREAT, anim.stream().filter(a -> a.getFixations().size() == 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		List<PersonalityProfile> people = evaluatePersonalThreat(ctx, realm, event, group, person);
		addThreat(threats, ThreatEnumType.EXISTENTIAL_THREAT, people.stream().filter(a -> a.getFixations().size() > 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		addThreat(threats, ThreatEnumType.PERSONAL_THREAT, people.stream().filter(a -> a.getFixations().size() == 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		*/
		return threats;
	}
	private static void addThreat(Map<ThreatEnumType,List<BaseRecord>> map, ThreatEnumType threat, List<BaseRecord> recs) {
		if(recs.size() > 0) {
			if(!map.containsKey(threat)) {
				map.put(threat, new ArrayList<>());
			}
			map.get(threat).addAll(recs);
		}
	}
	
	public static Map<PersonalityProfile, Map<ThreatEnumType,List<BaseRecord>>> getThreatMap(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> map) {

		Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = new HashMap<>();
		for(BaseRecord p0 : map.keySet()) {
			PersonalityProfile pp = map.get(p0);
			BaseRecord p = pp.getRecord();

			String name = p.get(FieldNames.FIELD_NAME);
			BaseRecord state = p.get(FieldNames.FIELD_STATE);
			boolean immobile = state.get("immobilized");
			boolean incap = state.get("incapacitated");
			boolean alive = state.get(OlioFieldNames.FIELD_ALIVE);
			boolean awake = state.get("awake");
			
			BaseRecord location = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
			if(location == null) {
				logger.warn("Location is null for " + p0.get(FieldNames.FIELD_NAME));
				continue;
			}
			String geoType = location.get(FieldNames.FIELD_GEOTYPE);
			if(geoType != null && geoType.equals(FieldNames.FIELD_FEATURE)) {
				logger.warn("Feature placement detected: Move " + name);
			}
			else {
				if(alive && awake && !immobile && !incap) {
					if(state.get(OlioFieldNames.FIELD_CURRENT_EVENT) != null) {
						logger.warn("Agitating " + name + " who is currently busy");
					}
					Map<ThreatEnumType, List<BaseRecord>> threats = ThreatUtil.evaluateImminentThreats(ctx, realm, event, map, pp);
					if(threats.keySet().size() > 0) {
						tmap.put(pp, threats);
					}
				}
			}
		}
		Queue.processQueue();
		return tmap;
	}
}
