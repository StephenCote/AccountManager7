package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.ThreatUtil.ThreatEnumType;
import org.cote.accountmanager.olio.personality.CompatibilityEnumType;
import org.cote.accountmanager.olio.personality.MBTIUtil;
import org.cote.accountmanager.olio.personality.OCEANUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class NarrativeUtil {
	public static final Logger logger = LogManager.getLogger(NarrativeUtil.class);
	
	public static String lookaround(OlioContext ctx, BaseRecord realm, BaseRecord event, BaseRecord increment, List<BaseRecord> group, BaseRecord pov, Map<BaseRecord, Map<ThreatEnumType, List<BaseRecord>>> threatMap) {
		StringBuilder buff = new StringBuilder();
		PersonalityProfile pp = ProfileUtil.analyzePersonality(ctx, pov);

		BaseRecord state = pov.get("state");
		BaseRecord store = pov.get("store");
		BaseRecord cell = state.get("currentLocation");
		List<Long> gids = group.stream().map(r -> ((long)r.get(FieldNames.FIELD_ID))).collect(Collectors.toList());

		String name = pov.get(FieldNames.FIELD_NAME);
		String fname = pov.get("firstName");
		int age = pov.get("age");
		
		String gender = pov.get("gender");
		String pro = ("male".equals(gender) ? "he" : "she");
		String pos = ("male".equals(gender) ? "him" : "her");
		boolean nak = NeedsUtil.isNaked(pov);
		boolean fod = NeedsUtil.needsFood(pov);
		boolean dri = NeedsUtil.needsWater(pov);
		List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(ctx, cell, Rules.MAXIMUM_OBSERVATION_DISTANCE);
		//Set<TerrainEnumType> stets = acells.stream().map(c -> TerrainEnumType.valueOf((String)c.get("terrainType"))).collect(Collectors.toSet());
		TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
		Set<String> stets = acells.stream().filter(c -> TerrainEnumType.valueOf((String)c.get("terrainType")) != tet).map(c -> ((String)c.get("terrainType")).toLowerCase()).collect(Collectors.toSet());
		

		buff.append(fname + " is a " + age + " year old " + ("male".equals(gender) ? "man" : "woman") + ".");
		buff.append(" " + pro + " is a '" + pp.getMbtiTitle() + "' and is " + pp.getMbtiDescription() + ".");
		buff.append(" " + pro + " is");
		if(stets.size() > 0) {
			buff.append(" standing on a patch of " + tet.toString().toLowerCase() + " near " + stets.stream().collect(Collectors.joining(",")) + ".");
		}
		else {
			buff.append(" standing in an expanse of " + tet.toString().toLowerCase() + ".");
		}
		
		if(threatMap.keySet().size() > 0) {
			buff.append(" " + pro + " is threatened by some people or animals.");
		}
		
		List<String> needs = new ArrayList<>();
		if(nak) needs.add("clothing");
		if(fod) needs.add("food");
		if(dri) needs.add("water");
		if(needs.size() > 0) {
			buff.append(" " + pro + " needs " + needs.stream().collect(Collectors.joining(", ")));
			if(needs.size() >= 3) {
				buff.append(" (" + pro + " is naked and afraid)");
			}
			buff.append(".");
		}
		else {
			buff.append(" " + pro + " seems to be managing.");
		}


		String names = group.stream().filter(p -> !fname.equals(p.get("firstName"))).map(p -> ((String)p.get("firstName") + " (" + p.get("age") + " year old " + p.get("gender") + ")")).collect(Collectors.joining(", "));
		buff.append(" " + pro + " is accompanied by " + names + ".");
		
		List<BaseRecord> fpop = GeoLocationUtil.limitToAdjacent(ctx, ctx.getPopulation(event.get("location")).stream().filter(r -> !gids.contains(r.get(FieldNames.FIELD_ID))).toList(), cell);
		List<BaseRecord> apop = GeoLocationUtil.limitToAdjacent(ctx, realm.get("zoo"), cell);
		String anames = apop.stream().map(a -> (String)a.get("name")).collect(Collectors.joining(", "));
		if(fpop.size() > 0) {
			buff.append(" There are " + fpop.size() +" strangers nearby.");
		}
		else {
			buff.append(" No one else seems to be around.");
		}
		if(anames.length() > 0) {
			buff.append(" Some animals are close, including " + anames + ".");
		}
		else {
			buff.append(" There don't seem to be any animals.");
		}
		
		buff.append("\n" + fname + " (" + gender + ") compatibility with group:");
		long id = pov.get("id");
		for(BaseRecord p : group) {
			if(id == (long)p.get("id")) {
				continue;
			}
			String compatKey = OCEANUtil.getCompatibilityKey(pov.get("personality"), p.get("personality"));
			CompatibilityEnumType mbtiCompat = MBTIUtil.getCompatibility(pov.get("personality.mbtiKey"), p.get("personality.mbtiKey"));
			buff.append("\n" + p.get("firstName") + " (" + p.get("gender") + "): " + compatKey + " / " + mbtiCompat.toString());
		}
		
		return buff.toString();
	}
	
}
