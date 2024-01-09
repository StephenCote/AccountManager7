package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.PersonalityProfile.PhysiologicalNeeds;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.LocationEnumType;

public class PersonalityUtil {
	public static final Logger logger = LogManager.getLogger(PersonalityUtil.class);
	private static SecureRandom rand = new SecureRandom();

	/*
	 Open
	 	Ideas (curious)
		Fantasy (imaginative)
		Aesthetics (artistic)
		Actions (wide interests)
		Feelings (excitable)
		Values (unconventional)
	  Con
	  	Competence (efficient)
		Order (organized)
		Dutifulness (not careless)
		Achievement striving (thorough)
		Self-discipline (not lazy)
		Deliberation (not impulsive)
	   Extra
		Gregariousness (sociable)
		Assertiveness (forceful)
		Activity (energetic)
		Excitement-seeking (adventurous)
		Positive emotions (enthusiastic)
		Warmth (outgoing) 
       Agree
		Trust (forgiving)
		Straightforwardness (not demanding)
		Altruism (warm)
		Compliance (not stubborn)
		Modesty (not show-off)
		Tender-mindedness (sympathetic) 
	   Nuero
		Anxiety (tense)
		Angry hostility (irritable)
		Depression (not contented)
		Self-consciousness (shy)
		Impulsiveness (moody)
		Vulnerability (not self-confident) 
	 */
	/*
	 * Hierarchy of Needs
	 * Self-actualization
	 * Esteem (respect, self-esteem, status, recognition, strength, freedom)
	 * Love and belonging (friendship, intimacy, family, sense of connection)
	 * Safety needs (personal security, employment, resources, health, property
	 * Physiological needs (air, water, food, shelter, sleep, clothing, reproduction)
	 */
	private static final String[] personalityFields = new String[]{"openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism"};
	/*
	Openness - [inventive/curiosity to consistent/cautious]
	Conscientiousness - [organized/efficient to extravagant/careless]
	Extraversion - [outgoing/energetic to solitary/reserved]
	Agreeableness - [friendly/compassionate to critical/rational/detached]
	Neuroticism - [resilient/confident to sensitive/nervous]
	 */
	public static void rollPersonality(BaseRecord rec) {
		DecimalFormat df = new DecimalFormat("#.##");
		df.setRoundingMode(RoundingMode.HALF_EVEN);

		try {
			for(String s : personalityFields) {
				double val =Double.parseDouble(df.format(rand.nextDouble()));
				rec.set(s, val);
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	
	private static Map<Long, PersonalityProfile> profiles = new ConcurrentHashMap<>();
	
	public static PersonalityProfile updateProfile(OlioContext octx, BaseRecord person) {
		long id = person.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			logger.error("Invalid identifier");
			return null;
		}
		if(!profiles.containsKey(id)) {
			return getProfile(octx, person);
		}
		PersonalityProfile prof = profiles.get(id);
		updateProfile(octx.getWorld(), person, prof);
		profiles.put(id,  prof);
		return prof;

	}
	
	public static PersonalityProfile getProfile(OlioContext octx, BaseRecord person) {
		
		long id = person.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			logger.error("Invalid identifier");
			return null;
		}
		
		if(profiles.containsKey(id)) {
			return profiles.get(id);
		}
		
		PersonalityProfile prof = analyzePersonality(octx, person);
		
		if(prof != null) {
			profiles.put(id, prof);
		}
		
		return prof;
	}
	
	public static PersonalityProfile analyzePersonality(OlioContext octx, BaseRecord person) {
		IOSystem.getActiveContext().getReader().populate(person, new String[] {"personality", "statistics"});
		BaseRecord per = person.get("personality");
		BaseRecord stats = person.get("statistics");
		BaseRecord inst = person.get("instinct");
		BaseRecord sto = person.get("store");
		IOSystem.getActiveContext().getReader().populate(per);
		IOSystem.getActiveContext().getReader().populate(inst);
		IOSystem.getActiveContext().getReader().populate(sto);
		IOSystem.getActiveContext().getReader().populate(stats);
		PersonalityProfile prof = createProfile(octx.getWorld(), person);
		/// logger.info("Analyzing " + person.get(FieldNames.FIELD_NAME));
		return prof;
	}
	
	
	protected static PersonalityProfile createProfile(BaseRecord world, BaseRecord person) {
		PersonalityProfile prof = new PersonalityProfile();
		prof.setId(person.get(FieldNames.FIELD_ID));
		updateProfile(world, person, prof);
		return prof;
	}
	protected static void updateProfile(BaseRecord world, BaseRecord person, PersonalityProfile prof) {
		prof.setName(person.get(FieldNames.FIELD_NAME));
		prof.setRecord(person);
		prof.setGender(person.get("gender"));
		prof.setAge(person.get("age"));
		List<BaseRecord> parts = person.get("partners");
		List<BaseRecord> deps = person.get("dependents");
		prof.setMarried(parts.size() > 0);
		prof.setChildren(deps.size() > 0);
		try {
			prof.setAlive(!CharacterUtil.isDeceased(person));
		} catch (ModelException e) {
			logger.error(e);
		}

		prof.setEvents(Arrays.asList(EventUtil.getEvents(world, person, new String[]{"actors", "participants", "observers", "influencers"}, EventEnumType.UNKNOWN)));
		Optional<BaseRecord> dopt = prof.getEvents().stream().filter(e -> EventEnumType.DIVORCE.toString().equals(((String)e.get(FieldNames.FIELD_TYPE)).toUpperCase())).findFirst();
		if(dopt.isPresent()) {
			prof.setDivorced(true);
		}

		BaseRecord per = person.get("personality");
		prof.setOpen(VeryEnumType.valueOf((double)per.get("openness")));
		prof.setConscientious(VeryEnumType.valueOf((double)per.get("conscientiousness")));
		prof.setExtraverted(VeryEnumType.valueOf((double)per.get("extraversion")));
		prof.setAgreeable(VeryEnumType.valueOf((double)per.get("agreeableness")));
		prof.setNeurotic(VeryEnumType.valueOf((double)per.get("neuroticism")));
		
		BaseRecord inst = person.get("instinct");
		
		prof.setSleep(InstinctEnumType.valueOf((double)inst.get("sleep")));
		prof.setFight(InstinctEnumType.valueOf((double)inst.get("fight")));
		prof.setFlight(InstinctEnumType.valueOf((double)inst.get("flight")));
		prof.setFeed(InstinctEnumType.valueOf((double)inst.get("feed")));
		prof.setDrink(InstinctEnumType.valueOf((double)inst.get("drink")));
		prof.setMate(InstinctEnumType.valueOf((double)inst.get("mate")));
		prof.setHerd(InstinctEnumType.valueOf((double)inst.get("herd")));
		prof.setHygiene(InstinctEnumType.valueOf((double)inst.get("hygiene")));
		prof.setCooperate(InstinctEnumType.valueOf((double)inst.get("cooperate")));
		prof.setResist(InstinctEnumType.valueOf((double)inst.get("resist")));
		prof.setAdapt(InstinctEnumType.valueOf((double)inst.get("adapt")));
		prof.setLaugh(InstinctEnumType.valueOf((double)inst.get("laugh")));
		prof.setCry(InstinctEnumType.valueOf((double)inst.get("cry")));
		prof.setProtect(InstinctEnumType.valueOf((double)inst.get("protect")));
		
		BaseRecord stats = person.get("statistics");
		double d1 = 100.0;
		prof.setPhysicalStrength(HighEnumType.valueOf(((int)stats.get("physicalStrength")*5)/d1));
		prof.setPhysicalEndurance(HighEnumType.valueOf(((int)stats.get("physicalEndurance")*5)/d1));
		prof.setManualDexterity(HighEnumType.valueOf(((int)stats.get("manualDexterity")*5)/d1));
		prof.setAgility(HighEnumType.valueOf(((int)stats.get("agility")*5)/d1));
		prof.setSpeed(HighEnumType.valueOf(((int)stats.get("speed")*5)/d1));
		prof.setMentalStrength(HighEnumType.valueOf(((int)stats.get("mentalStrength")*5)/d1));
		
		prof.setMentalEndurance(HighEnumType.valueOf(((int)stats.get("mentalEndurance")*5)/d1));
		prof.setIntelligence(HighEnumType.valueOf(((int)stats.get("intelligence")*5)/d1));
		prof.setCharisma(HighEnumType.valueOf(((int)stats.get("charisma")*5)/d1));
		prof.setCreativity(HighEnumType.valueOf(((int)stats.get("creativity")*5)/d1));
		prof.setSpirituality(HighEnumType.valueOf(((int)stats.get("spirituality")*5)/d1));
		prof.setWisdom(HighEnumType.valueOf(((int)stats.get("wisdom")*5)/d1));
		prof.setHealth(HighEnumType.valueOf(((int)stats.get("health")*5)/d1));
		prof.setMaximumHealth(HighEnumType.valueOf(((int)stats.get("maximumHealth")*5)/d1));
		prof.setSave(HighEnumType.valueOf((double)stats.get("save")));
		prof.setReaction(HighEnumType.valueOf(((int)stats.get("reaction")*5)/d1));
		prof.setScience(HighEnumType.valueOf(((int)stats.get("science")*5)/d1));
		prof.setMagic(HighEnumType.valueOf(((int)stats.get("magic")*5)/d1));
		prof.setLuck(HighEnumType.valueOf(((int)stats.get("luck")*5)/d1));

		analyzePhysiologicalNeeds(person, prof);
	}
	
	protected static void analyzePhysiologicalNeeds(BaseRecord person, PersonalityProfile prof) {
		/// do they have clothes?
		BaseRecord store = person.get("store");
		BaseRecord cit = person.get(FieldNames.FIELD_CONTACT_INFORMATION);
		List<BaseRecord> apparel = store.get("apparel");
		if(apparel.size() == 0) {
			prof.getPhysiologicalNeeds().add(PhysiologicalNeeds.CLOTHING);
		}
		
		List<BaseRecord> items = store.get("items");
		List<BaseRecord> water = items.stream().filter(i -> "water".equals(i.get("category"))).collect(Collectors.toList());
		if(water.size() == 0) {
			prof.getPhysiologicalNeeds().add(PhysiologicalNeeds.WATER);
		}
		
		List<BaseRecord> food = items.stream().filter(i -> "food".equals(i.get("category"))).collect(Collectors.toList());
		if(food.size() == 0) {
			prof.getPhysiologicalNeeds().add(PhysiologicalNeeds.FOOD);
		}
		if(cit != null) {
			List<BaseRecord> addrl = cit.get("addresses");
			List<BaseRecord> home = addrl.stream().filter(a -> LocationEnumType.HOME.toString().equals(a.get("locationType"))).collect(Collectors.toList());
			if(home.size() == 0) {
				prof.getPhysiologicalNeeds().add(PhysiologicalNeeds.SHELTER);	
			}
		}

	}
	
}

