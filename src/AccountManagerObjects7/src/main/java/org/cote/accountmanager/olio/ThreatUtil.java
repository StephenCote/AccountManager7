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
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class ThreatUtil {
	public static final Logger logger = LogManager.getLogger(ThreatUtil.class);
	
	public static enum ThreatEnumType {
		NONE,
		EXISTENTIAL_THREAT,
		ANIMAL_THREAT,
		ANIMAL_TARGET,
		PERSONAL_THREAT,
		PERSONAL_TARGET,
		ECONOMIC_THREAT,
		ECONOMIC_TARGET,
		IDEOLOGICAL_THREAT,
		IDEOLOGICAL_TARGET,
		ENVIRONMENTAL_THREAT,
		HEALTH_THREAT,
		POLITICAL_THREAT,
		POLITICAL_TARGET
		/*
		ENVIRONMENTAL_SECURITY,
		ECONOMIC_SECURITY,
		FOOD_SECURITY,
		HEALTH_SECURITY,
		PERSONAL_SECURITY,
		COMMUNITY_SECURITY,
		POLITICAL_SECURITY
		*/
	};
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
		/// Do they have any 'fight' in 'em?
		boolean isAgg = false;
		if(!possibleThreat.isAlive()) {
			logger.info("Sheesh, " + possibleThreat.getName() + " isn't even alive");
			return false;
		}
		
		/// Percentage of distance by (very limited) maximum visibility
		double dr = distanceRelativity(possibleThreat.getRecord(), person.getRecord());

		/// do they have any 'fight' in them relative to distance
		boolean fight = InstinctEnumType.compare(possibleThreat.getFight(), InstinctEnumType.AVERAGE, dr) == ComparatorEnumType.GREATER_THAN;
		/// do they have a strong sense of 'protection' relative to distance
		///
		boolean protect = InstinctEnumType.compare(possibleThreat.getProtect(), InstinctEnumType.STRONG, dr) == ComparatorEnumType.GREATER_THAN;
		if(fight || protect) {
			/// Do they see the person (perception)?
			RollEnumType perc = RollUtil.rollStat20(possibleThreat.getRecord(), "perception");
			if(perc == RollEnumType.SUCCESS || perc == RollEnumType.NATURAL_SUCCESS) {
				/// Do they 'react' to the person
				RollEnumType react = RollUtil.rollStat20(possibleThreat.getRecord(), "reaction");
				if(react == RollEnumType.SUCCESS || react == RollEnumType.NATURAL_SUCCESS) {
					isAgg = true;
				}
				else {
					// logger.info(possibleThreat.getName() + " didn't react to 'em");
				}

			}
			else {
				// logger.info(possibleThreat.getName() + " didn't see 'em");
			}

		}
		else {
			// logger.info(possibleThreat.getName() + " has no fight or protective instinct in 'em");
			/*
			if(possibleThreat.getName().equals("wolf")) {
				double d = StateUtil.getDistance(possibleThreat.getRecord().get("state"), person.getRecord().get("state"));
				logger.info(d + " --> " + dr);
				logger.info(JSONUtil.exportObject(possibleThreat));
			}
			*/
		}
		//if(possibleThreat.getFight())

		return isAgg;
	}
	
	public static boolean isToxic(BaseRecord record) {
		List<BaseRecord> items = record.get("store.items");
		return items.stream().filter(i -> "toxin".equals(i.get("name"))).collect(Collectors.toList()).size() > 0;
	}
	
	public static List<AnimalProfile> evaluateAnimalThreat(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, BaseRecord person){
		BaseRecord state = person.get("state");
		// BaseRecord stats = person.get("statistics");
		PersonalityProfile pp = group.get(person);
		long locId = state.get("currentLocation.id");
		List<BaseRecord> zoo = realm.get("zoo");
		List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(ctx, state.get("currentLocation"), Rules.MAXIMUM_OBSERVATION_DISTANCE);
		List<Long> aids = acells.stream().map(c -> ((long)c.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		
		/// Find animals in the current and adjacent cells
		List<BaseRecord> zpop = zoo.stream().filter(zp ->{
			BaseRecord zloc = zp.get("state.currentLocation");
			long zlid = (zloc != null ? zloc.get("id") : 0L);
			return (zlid > 0 && (zlid == locId || aids.contains(zlid)));
		}).collect(Collectors.toList());
		
		List<AnimalProfile> tpop = new ArrayList<>();
		Map<BaseRecord, AnimalProfile> amap = ProfileUtil.getAnimalProfileMap(ctx, zpop);
		for(AnimalProfile ap : amap.values()) {
			boolean toxic = isToxic(ap.getRecord());
			boolean isagg = isRelativelyAggressive(ap, pp);
			ThreatEnumType tet = ThreatEnumType.NONE;
			if(isagg) {
				tet = evaluateStatThreat(ap.getRecord(), person, "perception", ThreatEnumType.ANIMAL_THREAT, ThreatEnumType.ANIMAL_TARGET);
				tpop.add(ap);
				
				if(tet == ThreatEnumType.EXISTENTIAL_THREAT) {
					ap.getFixationTarget().add(person);
					logger.warn(ap.getName() + " is now fixated");
				}
				else if(tet == ThreatEnumType.ANIMAL_TARGET) {
					logger.warn(ap.getName() + " made a spectacle of itself: " + tet.toString());
				}
				else {
					logger.warn(ap.getName() + " is acting aggressive: " + tet.toString());
				}
				
			}
			else {
				// logger.info(ap.getName() + " is not currently aggressive");
			}
		}

		return tpop;
	}
	
	public static List<PersonalityProfile> evaluatePersonalThreat(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, BaseRecord person){
		BaseRecord state = person.get("state");
		PersonalityProfile pp = group.get(person);
		long locId = state.get("currentLocation.id");
		long id = person.get(FieldNames.FIELD_ID);
		List<BaseRecord> pop = ctx.getPopulation(event.get("location"));
		NeedsUtil.agitateLocation(ctx, realm, event, pop, false);

		List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(ctx, state.get("currentLocation"), Rules.MAXIMUM_OBSERVATION_DISTANCE);
		List<Long> aids = acells.stream().map(c -> ((long)c.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		
		/// Find people in the current and adjacent cells
		List<BaseRecord> ppop = pop.stream().filter(zp ->{
			BaseRecord zloc = zp.get("state.currentLocation");
			long zlid = (zloc != null ? zloc.get("id") : 0L);
			return (zlid > 0 && (zlid == locId || aids.contains(zlid)));
		}).collect(Collectors.toList());
		
		List<PersonalityProfile> tpop = new ArrayList<>();
		Map<BaseRecord, PersonalityProfile> amap = ProfileUtil.getProfileMap(ctx, ppop);
		for(PersonalityProfile ap : amap.values()) {
			if(ap.getId() == id) {
				/// skip self
				continue;
			}
			boolean isagg = isRelativelyAggressive(ap, pp);
			ThreatEnumType tet = ThreatEnumType.NONE;
			if(isagg) {
				tet = evaluateStatThreat(ap.getRecord(), person, "perception", ThreatEnumType.ANIMAL_THREAT, ThreatEnumType.ANIMAL_TARGET);
				tpop.add(ap);
				if(tet == ThreatEnumType.EXISTENTIAL_THREAT) {
					ap.getFixationTarget().add(person);
					logger.warn(ap.getName() + " is now fixated");
				}
				else {
					logger.warn(ap.getName() + " is acting aggressive: " + tet.toString());
				}
			}
			else {
				// logger.info(ap.getName() + " is not currently aggressive");
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
		addThreat(threats, ThreatEnumType.EXISTENTIAL_THREAT, anim.stream().filter(a -> a.getFixationTarget().size() > 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		addThreat(threats, ThreatEnumType.ANIMAL_THREAT, anim.stream().filter(a -> a.getFixationTarget().size() == 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		List<PersonalityProfile> people = evaluatePersonalThreat(ctx, realm, event, group, person);
		addThreat(threats, ThreatEnumType.EXISTENTIAL_THREAT, people.stream().filter(a -> a.getFixationTarget().size() > 0).map(a -> a.getRecord()).collect(Collectors.toList()));
		addThreat(threats, ThreatEnumType.PERSONAL_THREAT, people.stream().filter(a -> a.getFixationTarget().size() == 0).map(a -> a.getRecord()).collect(Collectors.toList()));

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
