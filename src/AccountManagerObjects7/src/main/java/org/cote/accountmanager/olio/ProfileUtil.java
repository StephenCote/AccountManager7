package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.olio.personality.DarkTriadUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.personality.MBTI;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.personality.Sloan;
import org.cote.accountmanager.personality.SloanUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.LocationEnumType;

public class ProfileUtil {
	public static final Logger logger = LogManager.getLogger(ProfileUtil.class);
	private static SecureRandom rand = new SecureRandom();
	private static Map<Long, PersonalityProfile> profiles = new ConcurrentHashMap<>();
	private static Map<Long, AnimalProfile> animalProfiles = new ConcurrentHashMap<>();
	/*
	Openness - [inventive/curiosity to consistent/cautious]
	Conscientiousness - [organized/efficient to extravagant/careless]
	Extraversion - [outgoing/energetic to solitary/reserved]
	Agreeableness - [friendly/compassionate to critical/rational/detached]
	Neuroticism - [resilient/confident to sensitive/nervous]
	 */
	public static final String[] PERSONALITY_FIELDS = new String[]{"openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism"};
	public static final String[] DARK_PERSONALITY_FIELDS = new String[]{"machiavellianism", "narcissism", "psychopathy"};
/*

Primary (Big 5 - OCEAN):
	Openness - [inventive/curiosity to consistent/cautious]
	  	Non-curious (low)
	  	Inquisitive (high)
	Conscientiousness - [organized/efficient to extravagant/careless]
	    Organized (high)
	    Unstructered (low)
	Extraversion - [outgoing/energetic to solitary/reserved]
	    Social (high)
	    Reserved (low)
	Agreeableness - [friendly/compassionate to critical/rational/detached]
	    Accommodating (high)
	    Egocentric (low)
	Neuroticism - [resilient/confident to sensitive/nervous]
	    Limbic (high)
	    Calm (low)
	
SLOAN Coding
  	Extraversion * Neuroticism * Concientiousness * Agreeableness * Openness
		
SLOAN Notation
	Social or Reserved
	Limbic or Calm
	Organized or Unstructured
	Accommodating or Egocentric
	Non-curious or Inquisitive
 */


	
	
	
	
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

	/// NOTE: The baserecord should be fully populated (query without limit) before invoking getProfile
	/// Otherwise, invoking populate on the incomplete models can result in errors or false-alarms when computing some statistics  
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
	
	/// NOTE: The baserecord should be fully populated (query without limit) before invoking getProfile
	/// Otherwise, invoking populate on the incomplete models can result in errors or false-alarms when computing some statistics  
	public static AnimalProfile getAnimalProfile(BaseRecord animal) {
		
		long id = animal.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			logger.error("Invalid identifier");
			return null;
		}
		
		if(animalProfiles.containsKey(id)) {
			return animalProfiles.get(id);
		}
		
		AnimalProfile prof = analyzeAnimal(animal);
		
		if(prof != null) {
			animalProfiles.put(id, prof);
		}
		
		return prof;
	}
	
	public static void rollPersonality(BaseRecord rec) {
		DecimalFormat df = new DecimalFormat("#.##");
		df.setRoundingMode(RoundingMode.HALF_EVEN);

		try {
			for(String s : PERSONALITY_FIELDS) {
				double val = Double.parseDouble(df.format(rand.nextDouble()));
				rec.set(s, val);
			}
			DarkTriadUtil.rollDarkPersonality(rec);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	
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
		updateProfile((octx != null ? octx.getWorld() : null), person, prof);
		profiles.put(id,  prof);
		return prof;
	}
	
	public static AnimalProfile updateAnimalProfile(OlioContext octx, BaseRecord animal) {
		long id = animal.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			logger.error("Invalid identifier");
			return null;
		}
		if(!animalProfiles.containsKey(id)) {
			return getAnimalProfile(animal);
		}
		AnimalProfile prof = animalProfiles.get(id);
		updateAnimalProfile(animal, prof);
		animalProfiles.put(id,  prof);
		return prof;
	}
	
	protected static int countPhysiologicalNeed(Map<BaseRecord, PersonalityProfile> map, PhysiologicalNeedsEnumType need) {
		return map.values().stream()
		  .map(c -> c.getPhysiologicalNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}

	protected static int countSafetyNeed(Map<BaseRecord, PersonalityProfile> map, SafetyNeedsEnumType need) {
		return map.values().stream()
		  .map(c -> c.getSafetyNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}
	
	protected static int countEsteemNeed(Map<BaseRecord, PersonalityProfile> map, EsteemNeedsEnumType need) {
		return map.values().stream()
		  .map(c -> c.getEsteemNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}
	
	protected static int countLoveNeed(Map<BaseRecord, PersonalityProfile> map, LoveNeedsEnumType need) {
		return map.values().stream()
		  .map(c -> c.getLoveNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}

	protected static void countMoney(Map<BaseRecord, PersonalityProfile> map, PersonalityGroupProfile pgp) {
		for(BaseRecord p: map.keySet()) {
			pgp.getRelativeWealth().put(p.get(FieldNames.FIELD_ID), ItemUtil.countMoney(p));
		}
	}
	
	public static PersonalityGroupProfile getGroupProfile(Map<BaseRecord, PersonalityProfile> map){
		PersonalityGroupProfile pgp = new PersonalityGroupProfile();
		
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeedsEnumType.FOOD, countPhysiologicalNeed(map, PhysiologicalNeedsEnumType.FOOD));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeedsEnumType.WATER, countPhysiologicalNeed(map, PhysiologicalNeedsEnumType.WATER));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeedsEnumType.REPRODUCTION, countPhysiologicalNeed(map, PhysiologicalNeedsEnumType.REPRODUCTION));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeedsEnumType.SHELTER, countPhysiologicalNeed(map, PhysiologicalNeedsEnumType.SHELTER));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeedsEnumType.CLOTHING, countPhysiologicalNeed(map, PhysiologicalNeedsEnumType.CLOTHING));
		
		pgp.getSafetyNeeds().put(SafetyNeedsEnumType.EMPLOYMENT, countSafetyNeed(map, SafetyNeedsEnumType.EMPLOYMENT));
		pgp.getSafetyNeeds().put(SafetyNeedsEnumType.HEALTH, countSafetyNeed(map, SafetyNeedsEnumType.HEALTH));
		pgp.getSafetyNeeds().put(SafetyNeedsEnumType.PROPERTY, countSafetyNeed(map, SafetyNeedsEnumType.PROPERTY));
		pgp.getSafetyNeeds().put(SafetyNeedsEnumType.RESOURCES, countSafetyNeed(map, SafetyNeedsEnumType.RESOURCES));
		pgp.getSafetyNeeds().put(SafetyNeedsEnumType.SECURITY, countSafetyNeed(map, SafetyNeedsEnumType.SECURITY));
		
		pgp.getLoveNeeds().put(LoveNeedsEnumType.CONNECTION, countLoveNeed(map, LoveNeedsEnumType.CONNECTION));
		pgp.getLoveNeeds().put(LoveNeedsEnumType.FAMILY, countLoveNeed(map, LoveNeedsEnumType.FAMILY));
		pgp.getLoveNeeds().put(LoveNeedsEnumType.FRIENDSHIP, countLoveNeed(map, LoveNeedsEnumType.FRIENDSHIP));
		pgp.getLoveNeeds().put(LoveNeedsEnumType.INTIMACY, countLoveNeed(map, LoveNeedsEnumType.INTIMACY));
		
		pgp.getEsteemNeeds().put(EsteemNeedsEnumType.FREEDOM, countEsteemNeed(map, EsteemNeedsEnumType.FREEDOM));
		pgp.getEsteemNeeds().put(EsteemNeedsEnumType.RECOGNITION, countEsteemNeed(map, EsteemNeedsEnumType.RECOGNITION));
		pgp.getEsteemNeeds().put(EsteemNeedsEnumType.RESPECT, countEsteemNeed(map, EsteemNeedsEnumType.RESPECT));
		pgp.getEsteemNeeds().put(EsteemNeedsEnumType.SELF_ESTEEM, countEsteemNeed(map, EsteemNeedsEnumType.SELF_ESTEEM));
		pgp.getEsteemNeeds().put(EsteemNeedsEnumType.STATUS, countEsteemNeed(map, EsteemNeedsEnumType.STATUS));
		pgp.getEsteemNeeds().put(EsteemNeedsEnumType.STRENGTH, countEsteemNeed(map, EsteemNeedsEnumType.STRENGTH));
		
		countMoney(map, pgp);
		
		return pgp;
	}
	public static Map<BaseRecord, PersonalityProfile> getProfileMap(OlioContext octx, List<BaseRecord> persons){
		Map<BaseRecord, PersonalityProfile> map = new HashMap<>();
		for(BaseRecord per : persons) {
			map.put(per, getProfile(octx, per));
		}
		return map;
	}
	
	public static Map<BaseRecord, AnimalProfile> getAnimalProfileMap(List<BaseRecord> animals){
		Map<BaseRecord, AnimalProfile> map = new HashMap<>();
		for(BaseRecord per : animals) {
			map.put(per, getAnimalProfile(per));
		}
		return map;
	}
	
	protected static AnimalProfile analyzeAnimal(BaseRecord animal) {
		AnimalProfile prof = createAnimalProfile(animal);
		return prof;
	}
	
	protected static PersonalityProfile analyzePersonality(OlioContext octx, BaseRecord person) {
		PersonalityProfile prof = createProfile((octx != null ? octx.getWorld() : null), person);
		return prof;
	}
	
	protected static AnimalProfile createAnimalProfile(BaseRecord animal) {
		AnimalProfile prof = new AnimalProfile();
		prof.setId(animal.get(FieldNames.FIELD_ID));
		updateAnimalProfile(animal, prof);
		return prof;
	}
	
	protected static PersonalityProfile createProfile(BaseRecord world, BaseRecord person) {
		PersonalityProfile prof = new PersonalityProfile();
		prof.setId(person.get(FieldNames.FIELD_ID));
		updateProfile(world, person, prof);
		return prof;
	}
	protected static void updateAnimalProfile(BaseRecord animal, AnimalProfile prof) {
		prof.setName(animal.get(FieldNames.FIELD_NAME));
		prof.setRecord(animal);
		prof.setGender(animal.get("gender"));
		prof.setAge(animal.get(FieldNames.FIELD_AGE));
		if(animal.get(FieldNames.FIELD_STATE) != null) {
			prof.setAlive(animal.get("state.alive"));
		}
		prof.setAlignment(AlignmentEnumType.valueOf(animal.get(FieldNames.FIELD_ALIGNMENT)));
		BaseRecord inst = animal.get(OlioFieldNames.FIELD_INSTINCT);
		if(inst != null) {
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
		}
		BaseRecord stats = animal.get(OlioFieldNames.FIELD_STATISTICS);
		if(stats != null) {
			double d1 = 100.0;
			prof.setPhysicalStrength(HighEnumType.valueOf(((int)stats.get("physicalStrength")*5)/d1));
			prof.setPhysicalEndurance(HighEnumType.valueOf(((int)stats.get("physicalEndurance")*5)/d1));
			prof.setManualDexterity(HighEnumType.valueOf(((int)stats.get("manualDexterity")*5)/d1));
			prof.setAgility(HighEnumType.valueOf(((int)stats.get("agility")*5)/d1));
			prof.setSpeed(HighEnumType.valueOf(((int)stats.get("speed")*5)/d1));
			prof.setMentalStrength(HighEnumType.valueOf(((int)stats.get("mentalStrength")*5)/d1));
			prof.setAthleticism(HighEnumType.valueOf(((int)stats.get("athleticism")*5)/d1));
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
			prof.setPerception(HighEnumType.valueOf(((int)stats.get("perception")*5)/d1));
		}
	}
	protected static void updateProfile(BaseRecord world, BaseRecord person, PersonalityProfile prof) {
		updateAnimalProfile(person, prof);

		List<BaseRecord> parts = person.get("partners");
		List<BaseRecord> deps = person.get("dependents");
		prof.setMarried(parts.size() > 0);
		prof.setChildren(deps.size() > 0);
		if(world != null) {
			prof.setEvents(Arrays.asList(EventUtil.getEvents(world, person, new String[]{"actors", "participants", "observers", "influencers"}, EventEnumType.UNKNOWN)));
		}
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
		
		prof.setMachiavellian(VeryEnumType.valueOf((double)per.get("machiavellianism")));
		prof.setNarcissist(VeryEnumType.valueOf((double)per.get("narcissism")));
		prof.setPsychopath(VeryEnumType.valueOf((double)per.get("psychopathy")));
		prof.setDarkTriadKey(per.get("darkTriadKey"));
		
		Sloan sloan = SloanUtil.getSloan(per.get("sloanKey"));
		MBTI mbti = MBTIUtil.getMBTI(per.get("mbtiKey"));
		if(sloan != null) {
			prof.setSloanKey(sloan.getKey());
			prof.setSloanDescription(sloan.getDescription());
		}
		else {
			// logger.warn("Sloan is null");
		}
		if(mbti != null) {
			prof.setMbtiKey(mbti.getKey());
			prof.setMbti(mbti);
		}
		else {
			// logger.warn("MBTI is null");
		}

		analyzePhysiologicalNeeds(person, prof);
	}

	protected static void analyzePhysiologicalNeeds(BaseRecord person, PersonalityProfile prof) {
		/// do they have clothes?
		BaseRecord store = person.get(FieldNames.FIELD_STORE);
		BaseRecord cit = person.get(FieldNames.FIELD_CONTACT_INFORMATION);
		if(store != null) {
			List<BaseRecord> apparel = store.get(OlioFieldNames.FIELD_APPAREL);
			if(apparel.size() == 0) {
				prof.getPhysiologicalNeeds().add(PhysiologicalNeedsEnumType.CLOTHING);
			}
			
			List<BaseRecord> items = store.get(OlioFieldNames.FIELD_ITEMS);
			List<BaseRecord> water = items.stream().filter(i -> "water".equals(i.get(OlioFieldNames.FIELD_CATEGORY))).collect(Collectors.toList());
			if(water.size() == 0) {
				prof.getPhysiologicalNeeds().add(PhysiologicalNeedsEnumType.WATER);
			}
			
			List<BaseRecord> food = items.stream().filter(i -> "food".equals(i.get(OlioFieldNames.FIELD_CATEGORY))).collect(Collectors.toList());
			if(food.size() == 0) {
				prof.getPhysiologicalNeeds().add(PhysiologicalNeedsEnumType.FOOD);
			}
			
			//// simple calculation - if the person has nothing
			if(items.size() == 0) {
				prof.getSafetyNeeds().add(SafetyNeedsEnumType.RESOURCES);
			}
			
			List<BaseRecord> locs = store.get(FieldNames.FIELD_LOCATIONS);
			if(locs.size() == 0) {
				prof.getSafetyNeeds().add(SafetyNeedsEnumType.PROPERTY);
			}

		}
		if(cit != null) {
			List<BaseRecord> addrl = cit.get(OlioFieldNames.FIELD_ADDRESSES);
			List<BaseRecord> home = addrl.stream().filter(a -> LocationEnumType.HOME.toString().equals(a.get("locationType"))).collect(Collectors.toList());
			if(home.size() == 0) {
				prof.getPhysiologicalNeeds().add(PhysiologicalNeedsEnumType.SHELTER);	
			}
		}
		
		List<BaseRecord> partners = person.get("partners");
		int marker = 0;
		if(partners.size() == 0) {
			prof.getLoveNeeds().add(LoveNeedsEnumType.INTIMACY);
			marker++;
		}

		List<BaseRecord> siblings = person.get("siblings");
		List<BaseRecord> dependents = person.get("dependents");
		if(partners.size() == 0 && siblings.size() == 0 && dependents.size() == 0) {
			prof.getLoveNeeds().add(LoveNeedsEnumType.FAMILY);
			marker++;
		}
		
		List<BaseRecord> social = person.get("socialRing");
		if(social.size() == 0) {
			prof.getLoveNeeds().add(LoveNeedsEnumType.FRIENDSHIP);
			marker++;
		}
		
		if(marker == 0) {
			prof.getLoveNeeds().add(LoveNeedsEnumType.CONNECTION);
		}
		
		//// Security calculation
		//// Finance + Personal
		prof.getSafetyNeeds().add(SafetyNeedsEnumType.SECURITY);
		
		//// Employment calculation - use scheduled activity count
		prof.getSafetyNeeds().add(SafetyNeedsEnumType.EMPLOYMENT);
		
		
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state != null) {
			double health = state.get("health");
			if(health < 0.25) {
				prof.getSafetyNeeds().add(SafetyNeedsEnumType.HEALTH);
			}
		}
		
		/// TODO: Initial esteem calculations

	}
	
	public static boolean sameProfile(AnimalProfile ap1, AnimalProfile ap2) {
		return ap1.getId() == ap2.getId();
	}
	
}

