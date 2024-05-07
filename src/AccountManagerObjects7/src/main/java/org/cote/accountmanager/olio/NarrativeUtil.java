package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.olio.personality.DarkTriadUtil;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.personality.OCEANUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class NarrativeUtil {
	public static final Logger logger = LogManager.getLogger(NarrativeUtil.class);
	
	private static boolean describePatterns = true;
	
	public static boolean isDescribePatterns() {
		return describePatterns;
	}
	
	public static void setDescribePatterns(boolean describePatterns) {
		NarrativeUtil.describePatterns = describePatterns;
	}

	public static String getDarkTriadDescription(PersonalityProfile prof) {
		StringBuilder desc = new StringBuilder();
		
		StringBuilder desc2 = new StringBuilder();
		
		String gender = prof.getGender();
		String pro = ("male".equals(gender) ? "He" : "She");
		if(prof.isMachiavellian()) {
			desc.append("may be callous, lack morality, or be motivated by self-interest");
		}
		
		if(prof.isNarcissist()) {
			if(desc.length() > 0) desc.append(", ");
			desc.append("may show grandiosity, entitlement, dominance, or superiority");
		}
		if(prof.isPsychopath()) {
			if(desc.length() > 0) desc.append(", and");
			desc.append("may show low levels of empathy and high levels of impulsivity and thrill-seeking");
		}
		desc2.append(pro + " is " + DarkTriadUtil.getDarkTriadName(prof.getDarkTriadKey()));
		desc2.append(".");
		if(desc.length() > 0) {
			desc2.append(" " + pro + " " + desc.toString() + ".");
		}
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
	public static String describeArmament(PersonalityProfile pp) {
		StringBuilder buff = new StringBuilder();
		if(pp.getRecord().get("store.items")  == null) {
			return buff.toString();
		}
		List<BaseRecord> items = ((List<BaseRecord>)pp.getRecord().get("store.items")).stream().filter(w -> ("weapon".equals(w.get("category")) || "armor".equals(w.get("category")))).collect(Collectors.toList());
		if(items.size() == 0) {
			buff.append("unarmed");
		}
		else {
			buff.append("armed with");
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

	public static String describeOutfit(PersonalityProfile pp) {
		return describeOutfit(pp, false);
	}
	public static String describeOutfit(PersonalityProfile pp, boolean includeOuterArms) {
			
	
		StringBuilder buff = new StringBuilder();
		List<BaseRecord> appl = pp.getRecord().get("store.apparel");
		if(appl == null || appl.size() == 0) {
			buff.append("naked");
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
			buff.append("wearing");
			String andl = "";
			// for(BaseRecord w: wearl) {
			for(int i = 0; i < wearl.size(); i++) {
				BaseRecord w = wearl.get(i);
				BaseRecord q = null;
				List<BaseRecord> qs = w.get("qualities");
				if(qs.size() > 0) {
					q = qs.get(0);
				}
				double opac = 1.0;
				double shin = 0.0;
				double smoo = 0.0;
				if(q != null) {
					opac = q.get("opacity");
					shin = q.get("glossiness");
					smoo = q.get("smoothness");
				}
				WearLevelEnumType lvle = w.getEnum("level");
				int lvl = WearLevelEnumType.valueOf(lvle);
				if(!includeOuterArms && lvl >= WearLevelEnumType.valueOf(WearLevelEnumType.OUTER)) {
					continue;
				}
				
				String col = getColor(w, "color");
				// (w.get("color.name") != null ? " " + ((String)w.get("color.name")).toLowerCase() : "");
				if(col != null) {
					col = col.replaceAll("\\([^()]*\\)", "");
				}
				String pat = (w.get("pattern.name") != null ? " " + ((String)w.get("pattern.name")).toLowerCase().replace(" pattern", "") : "");
				String fab = (w.get("fabric") != null ? " " + ((String)w.get("fabric")).toLowerCase() : "");
				List<String> locs = w.get("location");
				String loc = (locs.size() > 0 ? " " + locs.get(0) : "");
				String name = w.get("name");
				if(name.contains("pierc")) {
					name = "pierced" + loc + " ring";
					pat = "";
				}
				String opacs = "";
				if(opac > 0.0 && opac <= 0.25) {
					opacs = " see-through";
				}
				String shins = "";
				if(shin >= 0.7) {
					shins = " shiny";
				}

				buff.append(andl + shins + opacs + col + (describePatterns ? pat : "") + fab + " " + name);
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
		String athreat = "";
		if(athr != ThreatEnumType.NONE) {
			//athreat = " and is a " + athr.toString().replace("_", " ").toLowerCase() + " threat to ";
			athreat = " is threatening";
		}
		String ithreat = "";
		if(ithr != ThreatEnumType.NONE) {
			//ithreat = " and is a " + ithr.toString().replace("_", " ").toLowerCase() + " threat to ";
			ithreat = " is threatening";
		}
		
		String actorGen = inter.get("actor.gender");
		String pro1 = ("male".equals(actorGen) ? "him" : "her");
		String pro12 = ("male".equals(actorGen) ? "he" : "she");
		String iactorGen = inter.get("interactor.gender");
		String pro2 = ("male".equals(iactorGen) ? "him" : "her");
		String pro22 = ("male".equals(iactorGen) ? "he" : "she");

		
		
		/*
		buff.append(aname + " acts like a " + arol.toString().replace("_", " ").toLowerCase() + athreat + iname + " due to " + area.toString().replace("_", " ").toLowerCase() + ".");
		buff.append(" " + iname + " reacts like a " + irol.toString().replace("_", " ").toLowerCase() + ithreat + aname + " due to " + irea.toString().replace("_", " ").toLowerCase() + ".");
		buff.append(" This " + type.toString().replace("_", " ").toLowerCase() + " interaction has a " + aout.toString().replace("_", " ").toLowerCase() + " outcome for " + aname + ", and a " + iout.toString().replace("_", " ").toLowerCase() + " outcome for " + iname + ".");
		*/
		buff.append(aname + athreat + " " + getRoleDescription(arol) + " " + iname + " and " + getOutcomeDescription(aout) + " to " + getInteractionDescription(type) + " " + pro2 + " because " + pro12 + " " + getReasonDescription(area) + ".");
		//buff.append(" " + iname + ithreat + " " + getRoleDescription(irol) + " " + aname + " and " + getOutcomeDescription(iout) + " to respond to " + aname + "'s attempt to " + getInteractionDescription(type) + " " + pro2 + " because " + pro22 + " " + getReasonDescription(irea));
		buff.append(" " + iname + ithreat + " " + getRoleDescription(irol) + " " + aname +  " and " + pro22 + " " + getOutcomeDescription(iout) + " because " + pro22 + " " + getReasonDescription(irea));
		buff.append(".");
		return buff.toString();
	}
	
	public static String getOutcomeDescription(OutcomeEnumType type) {
		String desc = "";
		switch(type) {
			case VERY_FAVORABLE:
				desc = "wildly succeeds";
				break;
			case FAVORABLE:
				desc = "succeeds";
				break;
			case EQUILIBRIUM:
				desc = "attempts without success";
				break;
			case UNFAVORABLE:
				desc = "fails";
				break;
			case VERY_UNFAVORABLE:
				desc = "miserably fails";
				break;
		}
		return desc;
	}
	
	public static String getRoleDescription(CharacterRoleEnumType type) {
		String desc = "is hanging around with";
		switch(type) {
			case ACQUAINTENCE:
				desc = "wants to get to know";
				break;
			case ANTAGONIST:
				desc = "works in opposition to";
				break;
			case ANTIHERO:
				desc = "behaves unexpectedly to";
				break;
			case CONFIDANT:
				desc = "wants to confide in";
				break;
			case CONTAGONIST:
				desc = "plots against";
				break;
			case DEUTERAGONIST:
				desc = "is ready to back-up";
				break;
			case ENEMY_INTEREST:
				desc = "is enemies with";
				break;
			case FOIL:
				desc = "challenges beliefs held by";
				break;
			case GUIDE:
				desc = "wants to chaperone";
				break;
			case HENCHMAN:
				desc = "contributes to any villainy of";
				break;
			case INDETERMINATE:
				break;
			case LOVE_INTEREST:
				desc = "wants an intimate relationship with";
				break;
			case STRANGER:
				desc = "doesn't know";
				break;
			case FRIEND_INTEREST:
				desc = "wants to become friends with";
				break;
			case COMPANION:
				desc = "is a friend and companion with";
				break;
			case PROTAGONIST:
				desc = "wants to do good works with";
				break;
			case TEMPTRESS:
				desc = "wants to use her mind and body to tempt";
				break;
		}
		return desc;
	}
	public static String getReasonDescription(ReasonEnumType type) {
		String desc = "no reason";
		switch(type) {
			case AGE:
				desc = "noticed a difference in age";
				break;
			case ALOOFNESS:
				desc = "doesn't want to engage";
				break;
			case AMORALITY:
				desc = "has no moral compunction";
				break;
			case ATTRACTION:
				desc = "is physically attracted";
				break;
			case ATTRACTIVE_NARCISSISM:
				desc = "is vane and is physically attracted";
				break;
			case AVARICE:
				desc = "is extremely greedy";
				break;
			case COERCION:
				desc = "wants to coerce";
				break;
			case COMMERCE:
				desc = "wants to conduct business";
				break;
			case COMMUNITY:
				desc = "wants to build a stronger community";
				break;
			case COMPANIONSHIP:
				desc = "wants to be good friends";
				break;
			case CONFIDENCE:
				desc = "is confident";
				break;
			case COWARDICE:
				desc = "is a coward";
				break;
			case EXTRAVERSION:
				desc = "likes being around people";
				break;
			case FRIENDSHIP:
				desc = "is a friend";
				break;
			case GENEROSITY:
				desc = "is generous";
				break;
			case GUARDIANSHIP:
				desc = "feels protective";
				break;
			case HOSTILITY:
				desc = "is hostile";
				break;
			case IMMATURITY:
				desc = "is immature";
				break;
			case INSTINCT:
				desc = "is driven by instinct";
				break;
			case INTIMACY:
				desc = "feels intimate";
				break;
			case INTIMIDATION:
				desc = "wants to intimidate";
				break;
			case INTRAVERSION:
				desc = "doesn't like being around people";
				break;
			case LESS_ATTRACTIVE:
				desc = "feels less attractive";
				break;
			case MACHIAVELLIANISM:
				desc = "wants to manipulate the situation";
				break;
			case MATURITY:
				desc = "has to show experience";
				break;
			case MORALITY:
				desc = "has a strong sense of morality";
				break;
			case NARCISSISM:
				desc = "is vane";
				break;
			case NONE:
				desc ="isn't really trying";
				break;
			case PEER_PRESSURE:
				desc = "feels peer pressure";
				break;
			case POLITICAL:
				desc = "is politically motivated";
				break;
			case PSYCHOPATHY:
				desc = "is a crazy psycho";
				break;
			case REVENGE:
				desc = "wants revenge";
				break;
			case REVULSION:
				desc = "is revolted";
				break;
			case SANITY:
				desc = "is being sensible";
				break;
			case SENILITY:
				desc = "is senile";
				break;
			case SENSUALITY:
				desc = "is sensual";
				break;
			case SPIRITUALITY:
				desc = "is spiritual";
				break;
			case SOCIALIZE:
				desc = "is social";
				break;
		}
		return desc;
	}
	public static String getInteractionDescription(InteractionEnumType type) {
		String desc = "loiter with";
		switch(type) {
			case ACCOMMODATE:
				desc = "accommodate";
				break;
			case ALLY:
				desc = "form an alliance with";
				break;
			case BARTER:
				desc = "trade with";
				break;
			case BEFRIEND:
				desc = "be friends with";
				break;
			case BETRAY:
				desc = "double cross";
				break;
			case BREAK_UP:
				desc = "end the relationship with";
				break;
			case COERCE:
				desc = "convince";
				break;
			case COMBAT:
				desc = "fight with";
				break;
			case COMMERCE:
				desc = "buy or sell with";
				break;
			case COMMUNICATE:
				desc = "talk to";
				break;
			case COMPETE:
				desc = "prove their better than";
				break;
			case CONFLICT:
				desc = "enflame conflict with";
				break;
			case CONGREGATE:
				desc = "get together with";
				break;
			case COOPERATE:
				desc = "work together with";
				break;
			case CORRESPOND:
				desc = "write a letter to";
				break;
			case CRITICIZE:
				desc = "give positive or negative feedback to";
				break;
			case DATE:
				desc = "attempt a romantic relationship";
				break;
			case DEBATE:
				desc = "challenge the ideas and beliefs of";
				break;
			case DEFEND:
				desc = "protect themselves against";
				break;
			case ENTERTAIN:
				desc = "entertain";
				break;
			case EXCHANGE:
				desc = "trade with";
				break;
			case EXPRESS_GRATITUDE:
				desc = "give thanks to";
				break;
			case EXPRESS_INDIFFERENCE:
				desc = "try to ignore";
				break;
			case INTIMATE:
				desc = "become intimate with";
				break;
			case MENTOR:
				desc = "teach or coach";
				break;
			case NEGOTIATE:
				desc = "come to agreeable terms with";
				break;
			case OPPOSE:
				desc = "stand in opposition against";
				break;
			case PEER_PRESSURE:
				desc = "pressure and convince";
				break;
			case RECREATE:
				desc = "take a break and relax with";
				break;
			case RELATE:
				desc = "continue building a relationship with";
				break;
			case ROMANCE:
				desc = "engage in a romantic moment with";
				break;
			case SHUN:
				desc = "ostracize";
				break;
			case SEPARATE:
				desc ="put some distance between them and";
				break;
			case SOCIALIZE:
				desc = "spend time socializing with";
				break;
			case THREATEN:
				desc = "make verbal or physical threats against";
				break;
		}
		return desc;
	}
	private static String getColor(BaseRecord rec, String field) {
		BaseRecord col = rec.get(field);
		String clr = null;
		if(col != null && col.hasField(FieldNames.FIELD_NAME)) {
			clr = col.get(FieldNames.FIELD_NAME);
			clr = clr.toLowerCase().replaceAll("\\([^()]*\\)", "");
			/*
			int idx = clr.indexOf('(');
			if(idx > -1) {
				clr = clr.substring(0, idx);
			}
			*/
		}
		return clr;
	}
	public static String describe(OlioContext ctx, BaseRecord person) {
		return describe(ctx, person, false);
	}
	public static String describe(OlioContext ctx, BaseRecord person, boolean includeOuterArms) {
		StringBuilder buff = new StringBuilder();
		PersonalityProfile pp = ProfileUtil.getProfile(ctx, person);

		String name = person.get(FieldNames.FIELD_NAME);
		String fname = person.get("firstName");
		int age = person.get("age");

		String hairColor = getColor(person, "hairColor");
		String hairStyle = person.get("hairStyle");
		String eyeColor =  getColor(person, "eyeColor");
		
		String gender = person.get("gender");
		String pro = ("male".equals(gender) ? "he" : "she");
		String cpro = pro.substring(0,1).toUpperCase() + pro.substring(1);
		String pos = ("male".equals(gender) ? "his" : "her");
		
		boolean uarm = NeedsUtil.isUnarmed(person);
		
		String raceDesc = getRaceDescription(person.get("race"));
		buff.append(fname + " is " + getIsPrettySmart(pp) + ", physically is " + getIsPrettyRipped(pp) + ", has " + pp.getWisdom().toString().toLowerCase() + " wisdom, magic-wise " + getIsPrettyMagic(pp) + ", and is a " + getLooksPrettyUgly(pp) + " looking " + age + " year old " + raceDesc + " " + ("male".equals(gender) ? "man" : "woman") + ".");
		buff.append(" " + cpro + " is " + pp.getMbti().getDescription() + ".");
		buff.append(" Morally, " + pro + " " + getActsLikeSatan(pp) + ".");
		buff.append(" " + getDarkTriadDescription(pp));
		
		buff.append(" " + cpro + " has " + eyeColor + " eyes and " + hairColor + " " + hairStyle + " hair.");
		// buff.append(" " + cpro + " is a '" + pp.getMbti().getName() + "' and is " + pp.getMbti().getDescription() + ".");
		buff.append(" " + cpro + " " + describeOutfit(pp, includeOuterArms) + ".");
		if(includeOuterArms) {
			buff.append(" " + cpro + " " + describeArmament(pp) + ".");
		}
		return buff.toString();
	}
	
	public static String describeStatistics(PersonalityProfile pp) {
		StringBuilder buff = new StringBuilder();
		buff.append(getIsPrettySmart(pp) + ", physically is " + getIsPrettyRipped(pp) + " and " + getIsPrettyEndurable(pp) + ", has " + pp.getWisdom().toString().toLowerCase() + " wisdom, magic-wise " + getIsPrettyMagic(pp) + ", " + getIsPrettyLucky(pp) + ", and is " + getLooksPrettyUgly(pp) + " looking.");
		return buff.toString();
	}
	
	public static BaseRecord getNarrative(PersonalityProfile pp) {
		BaseRecord nar = null;
		try {
			nar = RecordFactory.newInstance(ModelNames.MODEL_NARRATIVE);
			nar.set("name", pp.getRecord().get("firstName"));
			nar.set("fullName", pp.getRecord().get("name"));
			nar.set("physicalDescription", describePhysical(pp));
			nar.set("outfitDescription", describeOutfit(pp));
			nar.set("armamentDescription", describeArmament(pp));
			nar.set("statisticsDescription", describeStatistics(pp));
			nar.set("alignmentDescription", getActsLikeSatan(pp));
			nar.set("darkTriadDescription", getDarkTriadDescription(pp));
			nar.set("mbtiDescription", pp.getMbti().getDescription());
			nar.set("sloanDescription", pp.getSloanDescription());
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		return nar;
		
	}
	public static String describePhysical(PersonalityProfile pp) {
		StringBuilder buff = new StringBuilder();

		int age = pp.getAge();

		String hairColor =  getColor(pp.getRecord(), "hairColor");
		String hairStyle = pp.getRecord().get("hairStyle");
		String eyeColor =  getColor(pp.getRecord(), "eyeColor");
		
		String gender = pp.getGender();
		
		String raceDesc = getRaceDescription(pp.getRace());
		buff.append(age + " year old " + raceDesc + " " + ("male".equals(gender) ? "man" : "woman") + " with " + eyeColor + " eyes and " + hairColor + " " + hairStyle + " hair.");
		return buff.toString();
	}
	
	public static String getIsPrettyMagic(PersonalityProfile prof) {
		
		String desc = "indescribable";
		HighEnumType charm = prof.getMagic();
		if(HighEnumType.compare(charm, HighEnumType.DIMINISHED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "has none";
		}
		else if(HighEnumType.compare(charm, HighEnumType.MODEST, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "has fleeting sensations of something beyond";
		}
		else if(HighEnumType.compare(charm, HighEnumType.FAIR, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "has innate perception and ability";
		}
		else if(HighEnumType.compare(charm, HighEnumType.ELEVATED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "is very capable and knows how to use it";
		}
		else if(HighEnumType.compare(charm, HighEnumType.STRONG, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "is a legitimate wizard";
		}
		else if(HighEnumType.compare(charm, HighEnumType.EXTENSIVE, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "is an extremely gifted wizard";
		}
		else {
			desc = "is a sorceror supreme";
		}
		return desc;
	}

	public static String getIsPrettySmart(PersonalityProfile prof) {
	
		String desc = "indescribable";
		HighEnumType charm = prof.getIntelligence();
		if(HighEnumType.compare(charm, HighEnumType.DIMINISHED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "retarded";
		}
		else if(HighEnumType.compare(charm, HighEnumType.MODEST, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "dumb as a box of rocks";
		}
		else if(HighEnumType.compare(charm, HighEnumType.FAIR, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "schooled in the essentials";
		}
		else if(HighEnumType.compare(charm, HighEnumType.ELEVATED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "fairly smart";
		}
		else if(HighEnumType.compare(charm, HighEnumType.STRONG, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "really smart";
		}
		else if(HighEnumType.compare(charm, HighEnumType.EXTENSIVE, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "extremely smart";
		}
		else {
			// desc = "pulchritudinous"
			desc = "a real genius";
		}
		return desc;
	}
	
	public static String getIsPrettyRipped(PersonalityProfile prof) {
		String desc = "indescribable";
		HighEnumType charm = prof.getPhysicalStrength();
		if(HighEnumType.compare(charm, HighEnumType.DIMINISHED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "extremely weak";
		}
		else if(HighEnumType.compare(charm, HighEnumType.MODEST, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "weak";
		}
		else if(HighEnumType.compare(charm, HighEnumType.FAIR, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "able";
		}
		else if(HighEnumType.compare(charm, HighEnumType.ELEVATED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "athletic";
		}
		else if(HighEnumType.compare(charm, HighEnumType.STRONG, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "olympian";
		}
		else if(HighEnumType.compare(charm, HighEnumType.EXTENSIVE, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			desc = "carved in stone";
		}
		else {
			desc = "an Adonis";
		}
		return desc;
	}

	public static String getHighScale7(HighEnumType lvl, List<String> scale) {
		String desc = "indescribable";
		int score = -1;
		if(scale.size() != 7) {
			logger.error("Expected a seven point scale");
			return desc;
		}
		if(HighEnumType.compare(lvl, HighEnumType.DIMINISHED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			score = 0;
		}
		else if(HighEnumType.compare(lvl, HighEnumType.MODEST, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			score = 1;
		}
		else if(HighEnumType.compare(lvl, HighEnumType.FAIR, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			score = 2;
		}
		else if(HighEnumType.compare(lvl, HighEnumType.ELEVATED, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			score = 3;
		}
		else if(HighEnumType.compare(lvl, HighEnumType.STRONG, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			score = 4;
		}
		else if(HighEnumType.compare(lvl, HighEnumType.EXTENSIVE, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
			score = 5;
		}
		else {
			score = 6;
		}
		if(score >= 0 && score < scale.size()) {
			return scale.get(score);
		}
		return desc;
	}
	private static List<String> lucky7 = Arrays.asList(new String[] {
		"is catastrophically unlucky",
		"is severely unlucky",
		"is moderately unlucky",
		"is mildly unlucky",
		"is a little bit lucky",
		"is very lucky",
		"is extraordinarily lucky"
	});

	public static String getIsPrettyLucky(PersonalityProfile prof) {
		return getHighScale7(prof.getLuck(), lucky7);
	}
	
	private static List<String> physEd7 = Arrays.asList(new String[] {
		"is a couch potato",
		"can walk without getting winded",
		"could run a 5K race",
		"is a half-marathoner",
		"is an marathoner",
		"is a triathelete",
		"is an ultra-marathoner"
	});	
	public static String getIsPrettyEndurable(PersonalityProfile prof) {
		return getHighScale7(prof.getPhysicalEndurance(), physEd7);
	}
	
	
	
	public static String getOthersActLikeSatan(AlignmentEnumType align) {
		String desc = "indescribable";
		switch(align) {
			case CHAOTICEVIL:
				desc = "have no respect for rules, people's lives, or anything except their own selfish and cruel desire";
				break;
			case NEUTRALEVIL:
				desc = "are selfish, will turn on their own allies, and will harm others if it's a benefit";
				break;
			case LAWFULEVIL:
				desc = "see well-ordered systems as necessary to fulfill their personal needs and desires";
				break;
			case CHAOTICNEUTRAL:
				desc = "follow their own heart, shirk rules and traditions, and their freedoms come before good or evil";
				break;
			case NEUTRAL:
				desc = "do not identify as being good or evil";
				break;
			case LAWFULNEUTRAL:
				desc = "strongly believe in lawful concepts such as honor, in addition to personal codes";
				break;
			case CHAOTICGOOD:
				desc = "do what is needed to bring about change for the good, and dislike bureaucracy";
				break;
			case NEUTRALGOOD:
				desc = "act altruistically with regard for law, rules, and traditions";
				break;
			case LAWFULGOOD:
				desc = "always act with honor and a sense of duty";
				break;
			default:
				break;
		}
		return desc;
	}
	public static String getActsLikeSatan(PersonalityProfile prof) {
		String desc = "indescribable";
		String gender = prof.getGender();
		String pos = ("male".equals(gender) ? "his" : "her");
		
		switch(prof.getAlignment()) {
			case CHAOTICEVIL:
				desc = "has no respect for rules, people's lives, or anything except " + pos + " own selfish and cruel desire";
				break;
			case NEUTRALEVIL:
				desc = "is selfish, will turn on " + pos + " own allies, and will harm others if it's a benefit";
				break;
			case LAWFULEVIL:
				desc = "sees well-ordered systems as necessary to fulfill " + pos + " personal needs and desires";
				break;
			case CHAOTICNEUTRAL:
				desc = "follows " + pos + " own heart, shirks rules and traditions, and " + pos + " freedom comes before good or evil";
				break;
			case NEUTRAL:
				desc = "does not identify as being good or evil";
				break;
			case LAWFULNEUTRAL:
				desc = "strongly believes in lawful concepts such as honor, in addition to " + pos + " own personal code";
				break;
			case CHAOTICGOOD:
				desc = "does what is needed to bring about change for the good, and dislikes bureaucracy";
				break;
			case NEUTRALGOOD:
				desc = "acts altruistically with regard for law, rules, and traditions";
				break;
			case LAWFULGOOD:
				desc = "always acts with honor and a sense of duty";
				break;
			default:
				break;
		}
		return desc;
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
		PersonalityProfile pp = ProfileUtil.getProfile(ctx, pov);

		BaseRecord state = pov.get("state");
		BaseRecord store = pov.get("store");
		BaseRecord cell = state.get("currentLocation");
		List<Long> gids = group.stream().map(r -> ((long)r.get(FieldNames.FIELD_ID))).collect(Collectors.toList());

		String name = pov.get(FieldNames.FIELD_NAME);
		String fname = pov.get("firstName");
		int age = pov.get("age");
		
		
		String hairColor =  getColor(pov, "hairColor");
		String hairStyle = pov.get("hairStyle");
		String eyeColor =  getColor(pov, "eyeColor");
		
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
		buff.append(" " + pro + " is a '" + pp.getMbti().getName() + "' and is " + pp.getMbti().getDescription() + ".");
		buff.append(" " + getDarkTriadDescription(pp));
		buff.append(" " + pro + " has " + eyeColor + " eyes and " + hairColor + " " + hairStyle + " hair.");
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
