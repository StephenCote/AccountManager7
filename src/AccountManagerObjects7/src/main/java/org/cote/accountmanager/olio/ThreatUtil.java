package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class ThreatUtil {
	public static final Logger logger = LogManager.getLogger(ThreatUtil.class);
	
	public static enum ThreatEnumType {
		NONE,
		ANIMAL_THREAT,
		PERSONAL_THREAT,
		ECONOMIC_THREAT,
		IDEOLOGICAL_THREAT,
		ENVIRONMENTAL_THREAT,
		HEALTH_THREAT,
		POLITICAL_THREAT
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
	
	public static boolean isRelativelyAggressive(AnimalProfile possibleThreat, PersonalityProfile person) {
		/// Do they have any 'fight' in 'em?
		if(InstinctEnumType.compare(possibleThreat.getFight(), InstinctEnumType.STASIS) == ComparatorEnumType.GREATER_THAN) {
			/// Do they 'react' to the person's presence?
		}
		//if(possibleThreat.getFight())
		boolean isAgg = false;
		return isAgg;
	}
	
	public static boolean isToxic(BaseRecord record) {
		List<BaseRecord> items = record.get("store.items");
		return items.stream().filter(i -> "toxin".equals(i.get("name"))).collect(Collectors.toList()).size() > 0;
	}
	
	public static List<BaseRecord> evaluateAnimalThreat(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, BaseRecord person){
		BaseRecord state = person.get("state");
		// BaseRecord stats = person.get("statistics");
		PersonalityProfile pp = group.get(person);
		long locId = state.get("currentLocation.id");
		List<BaseRecord> zoo = realm.get("zoo");
		List<BaseRecord> zpop = zoo.stream().filter(zp -> (zp.get("state.currentLocation") != null && ((long)zp.get("state.currentLocation.id")) == locId)).collect(Collectors.toList());
		Map<BaseRecord, AnimalProfile> amap = ProfileUtil.getAnimalProfileMap(ctx, zpop);
		for(AnimalProfile ap : amap.values()) {
			boolean toxic = isToxic(ap.getRecord());
			boolean isagg = isRelativelyAggressive(ap, pp);
			boolean per = RollUtil.rollPerception(ap.getRecord(), person);
			boolean per2 = RollUtil.rollPerception(person, ap.getRecord());
			logger.info(ap.getName() + (toxic ? " toxic" : "") + (isagg ? " aggressive" : "") + (per ? " noticed" : "") + " -- " + person.get("name"));
		}
		
		List<BaseRecord> tpop = new ArrayList<>();
		logger.info("Evaluate animal threat to " + person.get("name"));
		return tpop;
	}
	
	public static Map<ThreatEnumType,List<BaseRecord>> evaluateImminentThreats(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> group, BaseRecord person) {
		Map<ThreatEnumType,List<BaseRecord>> threats = new HashMap<>();
		List<BaseRecord> anim = evaluateAnimalThreat(ctx, realm, event, group, person);
		
		
		return threats;
	}
}
