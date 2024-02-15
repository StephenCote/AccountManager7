package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class ThreatUtil {
	public static final Logger logger = LogManager.getLogger(ThreatUtil.class);
	
	public static double distanceRelativity(BaseRecord rec1, BaseRecord rec2) {
		int maxDist = Rules.MAXIMUM_OBSERVATION_DISTANCE * Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		double dist = StateUtil.getDistance(rec1.get("state"), rec2.get("state"));
		if(dist <= 0) {
			logger.warn("Zero or negative distance detected");
			
		}
		double perc = 1.0 - (dist / maxDist);
		if(perc < 0) perc = 0;
		return perc;
	}



	public static boolean isRelativelyAggressive(AnimalProfile possibleThreat, PersonalityProfile person) {

		boolean isAgg = false;
		if(!possibleThreat.isAlive()) {
			/// logger.info("Sheesh, " + possibleThreat.getName() + " isn't even alive");
			return false;
		}
		
		/// Percentage of distance by (very limited) maximum visibility
		double dr = distanceRelativity(possibleThreat.getRecord(), person.getRecord());

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
		List<BaseRecord> items = record.get("store.items");
		return items.stream().filter(i -> "toxin".equals(i.get("name"))).collect(Collectors.toList()).size() > 0;
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
	
	public static List<AnimalProfile> evaluateAnimalThreat(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, BaseRecord person){
		BaseRecord state = person.get("state");
		// BaseRecord stats = person.get("statistics");
		PersonalityProfile pp = group.get(person);
		List<BaseRecord> zoo = realm.get("zoo");
		NeedsUtil.agitateLocation(ctx, realm, event, zoo, false, true);
		
		/// Find animals in the current and adjacent cells
		List<BaseRecord> zpop = GeoLocationUtil.limitToAdjacent(ctx, zoo, state.get("currentLocation"));
		List<AnimalProfile> tpop = new ArrayList<>();
		Map<BaseRecord, AnimalProfile> amap = ProfileUtil.getAnimalProfileMap(ctx, zpop);
		for(AnimalProfile ap : amap.values()) {
			// boolean toxic = isToxic(ap.getRecord());
			ThreatEnumType tet = evaluateAggressive(ap, pp, ThreatEnumType.ANIMAL_THREAT, ThreatEnumType.ANIMAL_TARGET);
			if(tet != ThreatEnumType.NONE) {
				tpop.add(ap);
			}
		}
		return tpop;
	}

	
	public static List<PersonalityProfile> evaluatePersonalThreat(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, BaseRecord person){
		BaseRecord state = person.get("state");
		PersonalityProfile pp = group.get(person);
		long id = person.get(FieldNames.FIELD_ID);
		
		/// Exclude the primary party from the general populace for purposes of agitating and assessing threat with the remainder of the population
		///
		List<Long> gids = group.keySet().stream().map(r -> ((long)r.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		List<BaseRecord> pop = ctx.getPopulation(event.get("location")).stream().filter(r -> !gids.contains(r.get(FieldNames.FIELD_ID))).toList();
		
		NeedsUtil.agitateLocation(ctx, realm, event, pop, false, true);

		/// Find people in the current and adjacent cells
		List<BaseRecord> ppop = GeoLocationUtil.limitToAdjacent(ctx, pop, state.get("currentLocation"));

		List<PersonalityProfile> tpop = new ArrayList<>();
		Map<BaseRecord, PersonalityProfile> amap = ProfileUtil.getProfileMap(ctx, ppop);
		for(PersonalityProfile ap : amap.values()) {
			if(ap.getId() == id) {
				/// skip self
				continue;
			}
			ThreatEnumType tet = evaluateAggressive(ap, pp, ThreatEnumType.PERSONAL_THREAT, ThreatEnumType.PERSONAL_TARGET);
			if(tet != ThreatEnumType.NONE) {
				tpop.add(ap);
			}
		}

		return tpop;
	}
	
	private static ThreatEnumType evaluateStatThreat(BaseRecord rec1, BaseRecord rec2, String statistic, ThreatEnumType threat, ThreatEnumType antiThreat) {
		ThreatEnumType tet = ThreatEnumType.NONE;
		RollEnumType per = RollUtil.rollStat20(rec1, statistic);
		if(per == RollEnumType.CATASTROPHIC_FAILURE) {
			logger.warn(rec1.get("name") + " #" + rec1.get("id") + " made itself a target by catastrophically failing a " + statistic + " roll");
			tet = antiThreat;
		}
		else if(per == RollEnumType.NATURAL_SUCCESS) {
			logger.warn(rec1.get("name") + " #" + rec1.get("id") + " made itself an existential threat by naturally succeeding a " + statistic + " roll");
			tet = ThreatEnumType.EXISTENTIAL_THREAT;
		}
		if(per == RollEnumType.SUCCESS) {
			tet = threat;
		}

		return tet;
	}
	
	public static Map<ThreatEnumType,List<BaseRecord>> evaluateImminentThreats(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, BaseRecord person) {
		Map<ThreatEnumType,List<BaseRecord>> threats = new HashMap<>();
		
		List<AnimalProfile> anim = evaluateAnimalThreat(ctx, realm, event, group, person);
		addThreat(threats, ThreatEnumType.EXISTENTIAL_THREAT, anim.stream().filter(a -> a.getFixations().size() > 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		addThreat(threats, ThreatEnumType.ANIMAL_THREAT, anim.stream().filter(a -> a.getFixations().size() == 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		List<PersonalityProfile> people = evaluatePersonalThreat(ctx, realm, event, group, person);
		addThreat(threats, ThreatEnumType.EXISTENTIAL_THREAT, people.stream().filter(a -> a.getFixations().size() > 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		addThreat(threats, ThreatEnumType.PERSONAL_THREAT, people.stream().filter(a -> a.getFixations().size() == 0).map(a -> a.getRecord()).collect(Collectors.toList()));

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
}
