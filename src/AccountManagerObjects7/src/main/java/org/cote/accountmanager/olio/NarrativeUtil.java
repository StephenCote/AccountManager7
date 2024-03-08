package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.personality.DarkTriadUtil;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.personality.OCEANUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class NarrativeUtil {
	public static final Logger logger = LogManager.getLogger(NarrativeUtil.class);
	
	public static String getDarkTriadDescription(PersonalityProfile prof) {
		//StringBuilder desc = new StringBuilder();
		
		StringBuilder desc2 = new StringBuilder();
		/*
		if(prof.isMachiavellian()) {
			desc.append("is machiavellian and may be callous, lack morality, and/or are motivated by self-interest");
		}
		
		if(prof.isNarcissist()) {
			if(desc.length() > 0) desc.append("; ");
			desc.append("is narcissistic and may show grandiosity, entitlement, dominance, and/or superiority");
		}
		if(prof.isPsychopath()) {
			if(desc.length() > 0) desc.append("; ");
			desc.append("is a psychopath and may show low levels of empathy and high levels of impulsivity and thrill-seeking");
		}
		*/
		String gender = prof.getGender();
		String pro = ("male".equals(gender) ? "he" : "she");
		/// prof.getRecord().get("firstName")
		desc2.append("Personality-wise, " + pro + " is " + DarkTriadUtil.getDarkTriadName(prof.getDarkTriadKey()));
		desc2.append(".");
		// desc2.append(" (" + prof.getDarkTriadKey() + ").");
		/*
		if(desc.length() > 0) {
			desc2.append(desc.toString() + ".");
		}
		else {
			desc2.append("does not reflect dark traits.");
		}
		*/
		return desc2.toString();
	}
	public static String getRaceDescription(List<String> races) {
		StringBuilder desc = new StringBuilder();
		for(String rc: races) {
			RaceEnumType ret = RaceEnumType.valueOf(rc);
			if(desc.length() > 0) desc.append(" and ");
			desc.append(RaceEnumType.valueOf(ret));
		}
		return desc.toString();
	}
	public static String describeArmament(OlioContext ctx, BaseRecord person) {
		StringBuilder buff = new StringBuilder();
		List<BaseRecord> items = ((List<BaseRecord>)person.get("store.items")).stream().filter(w -> ("weapon".equals(w.get("category")) || "armor".equals(w.get("category")))).collect(Collectors.toList());
		if(items.size() == 0) {
			buff.append("is unarmed");
		}
		else {
			buff.append("is armed with");
			String andl = "";
			//for(BaseRecord w: items) {
			for(int i = 0; i < items.size(); i++) {
				BaseRecord w = items.get(i);
				String mat = "plastic";
				List<String> mats = w.get("materials");
				if(mats.size() > 0) mat = mats.get(0);
				buff.append(andl + " " + mat + " " + w.get("name"));
				andl = ", " + (i == items.size() - 2 ? "and" : "");
			}
		}

		return buff.toString();
	}

	public static String describeOutfit(OlioContext ctx, BaseRecord person) {
		return describeOutfit(ctx, person, false);
	}
	public static String describeOutfit(OlioContext ctx, BaseRecord person, boolean includeOuterArms) {
			
	
		StringBuilder buff = new StringBuilder();
		List<BaseRecord> appl = person.get("store.apparel");
		if(appl.size() == 0) {
			buff.append("is naked");
		}
		else {
			BaseRecord app = null;
			Optional<BaseRecord> oapp = appl.stream().filter(a -> ((boolean)a.get("inuse"))).findFirst();
			if(oapp.isPresent()) {
				app = oapp.get();
			}
			else {
				app = appl.get(0);
			}
			List<BaseRecord> wearl = app.get("wearables");
			wearl.sort((f1, f2) -> WearLevelEnumType.compareTo(WearLevelEnumType.valueOf((String)f1.get("level")), WearLevelEnumType.valueOf((String)f2.get("level"))));
			buff.append("is wearing");
			String andl = "";
			// for(BaseRecord w: wearl) {
			for(int i = 0; i < wearl.size(); i++) {
				BaseRecord w = wearl.get(i);
				WearLevelEnumType lvle = w.getEnum("level");
				int lvl = WearLevelEnumType.valueOf(lvle);
				if(!includeOuterArms && lvl >= WearLevelEnumType.valueOf(WearLevelEnumType.OUTER)) {
					continue;
				}

				String col = (w.get("color") != null ? " " + ((String)w.get("color")).toLowerCase() : "");
				if(col != null) {
					col = col.replaceAll("\\([^()]*\\)", "");
				}
				String fab = (w.get("fabric") != null ? " " + ((String)w.get("fabric")).toLowerCase() : "");
				List<String> locs = w.get("location");
				String loc = (locs.size() > 0 ? " " + locs.get(0) : "");
				String name = w.get("name");
				if(name.contains("pierc")) {
					name = "pierced" + loc + " ring";
				}
				buff.append(andl + col + fab + " " + name);
				//andl = ", and";
				andl = "," + (i == wearl.size() - 2 ? " and" : "");
			}
		}
		return buff.toString();
		
	}
	
	public static String describeInteraction(BaseRecord inter) {
		String aname = inter.get("actor.firstName");
		InteractionEnumType type = inter.getEnum("type");
		AlignmentEnumType aalign = inter.getEnum("actorAlignment");
		ThreatEnumType athr = inter.getEnum("actorThreat");
		CharacterRoleEnumType arol = inter.getEnum("actorRole");
		ReasonEnumType area = inter.getEnum("actorReason");
		OutcomeEnumType aout = inter.getEnum("actorOutcome");
		String iname = inter.get("interactor.firstName");
		AlignmentEnumType ialign = inter.getEnum("interactorAlignment");
		ThreatEnumType ithr = inter.getEnum("interactorThreat");
		CharacterRoleEnumType irol = inter.getEnum("interactorRole");
		ReasonEnumType irea = inter.getEnum("interactorReason");
		OutcomeEnumType iout = inter.getEnum("interactorOutcome");
		StringBuilder  buff = new StringBuilder();
		buff.append(aname + " acts like a " + arol.toString().replace("_", " ").toLowerCase() + " and is a " + athr.toString().replace("_", " ").toLowerCase() + " to " +  iname + " due to " + area.toString().replace("_", " ").toLowerCase() + ".");
		buff.append(" " + iname + " reacts like a " + irol.toString().replace("_", " ").toLowerCase() + " being a " + ithr.toString().replace("_", " ").toLowerCase() + " to " +  aname + " due to " + irea.toString().replace("_", " ").toLowerCase() + ".");
		buff.append(" This " + type.toString().replace("_", " ").toLowerCase() + " interaction has a " + aout.toString().replace("_", " ").toLowerCase() + " outcome for " + aname + ", and a " + iout.toString().replace("_", " ").toLowerCase() + " outcome for " + iname + ".");
		return buff.toString();
	}
	public static String describe(OlioContext ctx, BaseRecord person) {
		return describe(ctx, person, false);
	}
	public static String describe(OlioContext ctx, BaseRecord person, boolean includeOuterArms) {
		StringBuilder buff = new StringBuilder();
		PersonalityProfile pp = ProfileUtil.analyzePersonality(ctx, person);

		String name = person.get(FieldNames.FIELD_NAME);
		String fname = person.get("firstName");
		int age = person.get("age");

		String hairColor = person.get("hairColor");
		String hairStyle = person.get("hairStyle");
		String eyeColor = person.get("eyeColor");
		
		String gender = person.get("gender");
		String pro = ("male".equals(gender) ? "he" : "she");
		String cpro = pro.substring(0,1).toUpperCase() + pro.substring(1);
		String pos = ("male".equals(gender) ? "his" : "her");
		
		boolean uarm = NeedsUtil.isUnarmed(person);
		
		String raceDesc = getRaceDescription(person.get("race"));
		buff.append(fname + " is a " + getLooksPrettyUgly(pp) + " looking " + age + " year old " + raceDesc + " " + ("male".equals(gender) ? "man" : "woman") + ".");
		buff.append(" " + cpro + " has " + eyeColor + " eyes and " + hairColor + " " + hairStyle + " hair.");
		// buff.append(" " + cpro + " is a '" + pp.getMbti().getName() + "' and is " + pp.getMbti().getDescription() + ".");
		buff.append(" " + cpro + " is " + pp.getMbti().getDescription() + ".");
		buff.append(" " + getDarkTriadDescription(pp));
		buff.append(" " + cpro + " " + describeOutfit(ctx, person, includeOuterArms) + ".");
		if(includeOuterArms) {
			buff.append(" " + cpro + " " + describeArmament(ctx, person) + ".");
		}
		return buff.toString();
	}
	
	public static String getLooksPrettyUgly(PersonalityProfile prof) {
		/// TODO: Aesthetic/Appearance determination is currently simplified to the charisma statistic
		/// However, it could be better described as: charisma + symmetry + physical genetics + personality + fitness/health
		/// personality, and fitness and health can be pulled from the other stats; at the moment there isn't a way to capture symmetry.
		/// physical genetics could be determined given a suitable ancestry (eg: (charisma + symmetric + personality + fitness/health) / 4 -> physical genetics)
		
		String desc = "indescribable";
		HighEnumType charm = prof.getCharisma();
		if(HighEnumType.compare(charm, HighEnumType.DIMINISHED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "hideous";
		}
		else if(HighEnumType.compare(charm, HighEnumType.MODEST, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "homely";
		}
		else if(HighEnumType.compare(charm, HighEnumType.FAIR, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "bland";
		}
		else if(HighEnumType.compare(charm, HighEnumType.ELEVATED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "comely";
		}
		else if(HighEnumType.compare(charm, HighEnumType.STRONG, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "pretty";
		}
		else if(HighEnumType.compare(charm, HighEnumType.EXTENSIVE, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "beautiful";
		}
		else {
			// desc = "pulchritudinous"
			desc = "gorgeous";
		}
		return desc;
	}
	public static String describeAsthetics(PersonalityProfile prof) {
		StringBuilder buff = new StringBuilder();
		
		return buff.toString();
	}

	public static String lookaround(OlioContext ctx, BaseRecord realm, BaseRecord event, BaseRecord increment, List<BaseRecord> group, BaseRecord pov, Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> threatMap) {
		StringBuilder buff = new StringBuilder();
		PersonalityProfile pp = ProfileUtil.analyzePersonality(ctx, pov);

		BaseRecord state = pov.get("state");
		BaseRecord store = pov.get("store");
		BaseRecord cell = state.get("currentLocation");
		List<Long> gids = group.stream().map(r -> ((long)r.get(FieldNames.FIELD_ID))).collect(Collectors.toList());

		String name = pov.get(FieldNames.FIELD_NAME);
		String fname = pov.get("firstName");
		int age = pov.get("age");
		
		
		String hairColor = pov.get("hairColor");
		String hairStyle = pov.get("hairStyle");
		String eyeColor = pov.get("eyeColor");
		
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
		
		String raceDesc = getRaceDescription(pov.get("race"));
		buff.append(fname + " is a " + age + " year old " + raceDesc + " " + ("male".equals(gender) ? "man" : "woman") + ".");
		buff.append(" " + pro + " has " + eyeColor + " eyes and " + hairColor + " " + hairStyle + " hair.");
		buff.append(" " + pro + " is a '" + pp.getMbti().getName() + "' and is " + pp.getMbti().getDescription() + ".");
		buff.append(" " + getDarkTriadDescription(pp));
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
		String anames = apop.stream().map(a -> (String)a.get("name")).collect(Collectors.toSet()).stream().collect(Collectors.joining(", "));
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
			PersonalityProfile pp2 = ProfileUtil.analyzePersonality(ctx, p);
			String compatKey = OCEANUtil.getCompatibilityKey(pov.get("personality"), p.get("personality"));
			CompatibilityEnumType mbtiCompat = MBTIUtil.getCompatibility(pov.get("personality.mbtiKey"), p.get("personality.mbtiKey"));
			buff.append("\n" + p.get("firstName") + " " + getRaceDescription(p.get("race")) + " (" + p.get("age") + " year old " + p.get("gender") + "): " + compatKey + " / " + mbtiCompat.toString() + " / " + getDarkTriadDescription(pp2));
		}
		
		return buff.toString();
	}
	
}
