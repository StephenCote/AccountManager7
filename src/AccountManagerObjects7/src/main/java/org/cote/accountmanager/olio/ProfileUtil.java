package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.PersonalityProfile.EsteemNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.LoveNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.PhysiologicalNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.SafetyNeeds;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.LocationEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

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
	private static final String[] personalityFields = new String[]{"openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism"};
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
	private static Map<String, Sloan> sloanDef = new ConcurrentHashMap<>();
	protected static Map<String, Sloan> getSloanDef() {
		if(sloanDef.keySet().size() == 0) {
			String[] sloanJson = JSONUtil.importObject(ResourceUtil.getResource("./olio/sloan.json"), String[].class);
			for(String s: sloanJson) {
				String[] pairs = s.split("\\|");
				String dis = "";
				if(pairs.length > 4) dis = pairs[4];
				sloanDef.put(pairs[0], new Sloan(pairs[0], pairs[1], pairs[2], pairs[3], dis));
			}
		}
		return sloanDef;
	}
	
	private static Map<String, MBTI> mbtiDef = new ConcurrentHashMap<>();
	protected static Map<String, MBTI> getMBTIDef(){
		if(mbtiDef.keySet().size() == 0) {
			String[] mbtiJson = JSONUtil.importObject(ResourceUtil.getResource("./olio/mbti.json"), String[].class);
			for(String s: mbtiJson) {
				String[] pairs = s.split("\\|");
				mbtiDef.put(pairs[0], new MBTI(pairs[0], pairs[1], pairs[2]));
			}			
		}
		return mbtiDef;
	}
	
	private static final String SLOAN_SOCIAL_KEY = "social";
	private static final String SLOAN_RESERVED_KEY = "reserved";
	private static final String SLOAN_LIMBIC_KEY = "limbic";
	private static final String SLOAN_CALM_KEY = "calm";
	private static final String SLOAN_ORGANIZED_KEY = "organized";
	private static final String SLOAN_UNSTRUCTURED_KEY = "unstructured";
	private static final String SLOAN_ACCOMMODATING_KEY = "accommodating";
	private static final String SLOAN_EGOCENTRIC_KEY = "egocentric";
	private static final String SLOAN_NONCURIOUS_KEY = "non-curious";
	private static final String SLOAN_INQUISITIVE_KEY = "inquisitive";
	protected static String getSloanKey(BaseRecord rec) {
		double ext = rec.get(personalityFields[2]);
		double neu = rec.get(personalityFields[4]);
		double con = rec.get(personalityFields[1]);
		double agr = rec.get(personalityFields[3]);
		double ope = rec.get(personalityFields[0]);
		StringBuilder bld = new StringBuilder();
		if(ext > 0.5) {
			bld.append(SLOAN_SOCIAL_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_RESERVED_KEY.substring(0,1));			
		}
		if(neu > 0.5) {
			bld.append(SLOAN_LIMBIC_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_CALM_KEY.substring(0,1));			
		}
		if(con > 0.5) {
			bld.append(SLOAN_ORGANIZED_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_UNSTRUCTURED_KEY.substring(0,1));			
		}
		if(agr > 0.5) {
			bld.append(SLOAN_ACCOMMODATING_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_EGOCENTRIC_KEY.substring(0,1));			
		}
		if(ope > 0.5) {
			bld.append(SLOAN_INQUISITIVE_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_NONCURIOUS_KEY.substring(0,1));			
		}
		return bld.toString();
	}
	
	public static String getSloanCardinal(BaseRecord rec) {
		return Collections.max(Arrays.asList(personalityFields), Comparator.comparing(c -> (double)rec.get(c)));
	}
	
	
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
	
	public static AnimalProfile updateAnimalProfile(OlioContext octx, BaseRecord animal) {
		long id = animal.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			logger.error("Invalid identifier");
			return null;
		}
		if(!animalProfiles.containsKey(id)) {
			return getAnimalProfile(octx, animal);
		}
		AnimalProfile prof = animalProfiles.get(id);
		updateAnimalProfile(octx.getWorld(), animal, prof);
		animalProfiles.put(id,  prof);
		return prof;
	}
	
	protected static int countPhysiologicalNeed(Map<BaseRecord, PersonalityProfile> map, PhysiologicalNeeds need) {
		return map.values().stream()
		  .map(c -> c.getPhysiologicalNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}

	protected static int countSafetyNeed(Map<BaseRecord, PersonalityProfile> map, SafetyNeeds need) {
		return map.values().stream()
		  .map(c -> c.getSafetyNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}
	
	protected static int countEsteemNeed(Map<BaseRecord, PersonalityProfile> map, EsteemNeeds need) {
		return map.values().stream()
		  .map(c -> c.getEsteemNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}
	
	protected static int countLoveNeed(Map<BaseRecord, PersonalityProfile> map, LoveNeeds need) {
		return map.values().stream()
		  .map(c -> c.getLoveNeeds().contains(need) ? 1 : 0)
		  .reduce(0, Integer::sum);
	}
	
	
	public static PersonalityGroupProfile getGroupProfile(Map<BaseRecord, PersonalityProfile> map){
		PersonalityGroupProfile pgp = new PersonalityGroupProfile();
		
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeeds.FOOD, countPhysiologicalNeed(map, PhysiologicalNeeds.FOOD));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeeds.WATER, countPhysiologicalNeed(map, PhysiologicalNeeds.WATER));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeeds.REPRODUCTION, countPhysiologicalNeed(map, PhysiologicalNeeds.REPRODUCTION));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeeds.SHELTER, countPhysiologicalNeed(map, PhysiologicalNeeds.SHELTER));
		pgp.getPhysiologicalNeeds().put(PhysiologicalNeeds.CLOTHING, countPhysiologicalNeed(map, PhysiologicalNeeds.CLOTHING));
		
		pgp.getSafetyNeeds().put(SafetyNeeds.EMPLOYMENT, countSafetyNeed(map, SafetyNeeds.EMPLOYMENT));
		pgp.getSafetyNeeds().put(SafetyNeeds.HEALTH, countSafetyNeed(map, SafetyNeeds.HEALTH));
		pgp.getSafetyNeeds().put(SafetyNeeds.PROPERTY, countSafetyNeed(map, SafetyNeeds.PROPERTY));
		pgp.getSafetyNeeds().put(SafetyNeeds.RESOURCES, countSafetyNeed(map, SafetyNeeds.RESOURCES));
		pgp.getSafetyNeeds().put(SafetyNeeds.SECURITY, countSafetyNeed(map, SafetyNeeds.SECURITY));
		
		pgp.getLoveNeeds().put(LoveNeeds.CONNECTION, countLoveNeed(map, LoveNeeds.CONNECTION));
		pgp.getLoveNeeds().put(LoveNeeds.FAMILY, countLoveNeed(map, LoveNeeds.FAMILY));
		pgp.getLoveNeeds().put(LoveNeeds.FRIENDSHIP, countLoveNeed(map, LoveNeeds.FRIENDSHIP));
		pgp.getLoveNeeds().put(LoveNeeds.INTIMACY, countLoveNeed(map, LoveNeeds.INTIMACY));
		
		pgp.getEsteemNeeds().put(EsteemNeeds.FREEDOM, countEsteemNeed(map, EsteemNeeds.FREEDOM));
		pgp.getEsteemNeeds().put(EsteemNeeds.RECOGNITION, countEsteemNeed(map, EsteemNeeds.RECOGNITION));
		pgp.getEsteemNeeds().put(EsteemNeeds.RESPECT, countEsteemNeed(map, EsteemNeeds.RESPECT));
		pgp.getEsteemNeeds().put(EsteemNeeds.SELF_ESTEEM, countEsteemNeed(map, EsteemNeeds.SELF_ESTEEM));
		pgp.getEsteemNeeds().put(EsteemNeeds.STATUS, countEsteemNeed(map, EsteemNeeds.STATUS));
		pgp.getEsteemNeeds().put(EsteemNeeds.STRENGTH, countEsteemNeed(map, EsteemNeeds.STRENGTH));
		
		return pgp;
	}
	public static Map<BaseRecord, PersonalityProfile> getProfileMap(OlioContext octx, List<BaseRecord> persons){
		Map<BaseRecord, PersonalityProfile> map = new HashMap<>();
		for(BaseRecord per : persons) {
			map.put(per, getProfile(octx, per));
		}
		return map;
	}
	
	public static Map<BaseRecord, AnimalProfile> getAnimalProfileMap(OlioContext octx, List<BaseRecord> animals){
		Map<BaseRecord, AnimalProfile> map = new HashMap<>();
		for(BaseRecord per : animals) {
			map.put(per, getAnimalProfile(octx, per));
		}
		return map;
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
	
	public static AnimalProfile getAnimalProfile(OlioContext octx, BaseRecord animal) {
		
		long id = animal.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			logger.error("Invalid identifier");
			return null;
		}
		
		if(animalProfiles.containsKey(id)) {
			return animalProfiles.get(id);
		}
		
		AnimalProfile prof = analyzeAnimal(octx, animal);
		
		if(prof != null) {
			animalProfiles.put(id, prof);
		}
		
		return prof;
	}
	private static void checkPopulation(BaseRecord animal) {
		IOSystem.getActiveContext().getReader().populate(animal, new String[] {"statistics", "instinct", "store"});
		BaseRecord stats = animal.get("statistics");
		BaseRecord inst = animal.get("instinct");
		BaseRecord sto = animal.get("store");
		IOSystem.getActiveContext().getReader().populate(inst);
		IOSystem.getActiveContext().getReader().populate(sto);
		IOSystem.getActiveContext().getReader().populate(stats);
		
	}
	public static AnimalProfile analyzeAnimal(OlioContext octx, BaseRecord animal) {
		checkPopulation(animal);
		AnimalProfile prof = createAnimalProfile(octx.getWorld(), animal);
		return prof;
	}
	
	public static PersonalityProfile analyzePersonality(OlioContext octx, BaseRecord person) {
		checkPopulation(person);
		IOSystem.getActiveContext().getReader().populate(person, new String[] {"personality"});
		BaseRecord per = person.get("personality");
		IOSystem.getActiveContext().getReader().populate(per);
		PersonalityProfile prof = createProfile(octx.getWorld(), person);
		return prof;
	}
	
	protected static AnimalProfile createAnimalProfile(BaseRecord world, BaseRecord animal) {
		AnimalProfile prof = new AnimalProfile();
		prof.setId(animal.get(FieldNames.FIELD_ID));
		updateAnimalProfile(world, animal, prof);
		return prof;
	}
	
	protected static PersonalityProfile createProfile(BaseRecord world, BaseRecord person) {
		PersonalityProfile prof = new PersonalityProfile();
		prof.setId(person.get(FieldNames.FIELD_ID));
		updateProfile(world, person, prof);
		return prof;
	}
	protected static void updateAnimalProfile(BaseRecord world, BaseRecord animal, AnimalProfile prof) {
		prof.setName(animal.get(FieldNames.FIELD_NAME));
		prof.setRecord(animal);
		prof.setGender(animal.get("gender"));
		prof.setAge(animal.get("age"));
		prof.setAlive(animal.get("state.alive"));
		
		BaseRecord inst = animal.get("instinct");
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
		
		BaseRecord stats = animal.get("statistics");
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
		prof.setPerception(HighEnumType.valueOf(((int)stats.get("perception")*5)/d1));

	}
	protected static void updateProfile(BaseRecord world, BaseRecord person, PersonalityProfile prof) {
		updateAnimalProfile(world, person, prof);

		List<BaseRecord> parts = person.get("partners");
		List<BaseRecord> deps = person.get("dependents");
		prof.setMarried(parts.size() > 0);
		prof.setChildren(deps.size() > 0);

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
		
		Sloan sloan = getSloan(per.get("sloanKey"));
		MBTI mbti = getMBTI(per.get("mbtiKey"));
		if(sloan != null) {
			prof.setSloanDescription(sloan.getDescription());
		}
		else {
			// logger.warn("Sloan is null");
		}
		if(mbti != null) {
			prof.setMbtiTitle(mbti.getName());
			prof.setMbtiDescription(mbti.getDescription());
		}
		else {
			// logger.warn("MBTI is null");
		}

		analyzePhysiologicalNeeds(person, prof);
	}
	public static MBTI getMBTI(String key) {
		Map<String, MBTI> mbtiMap = getMBTIDef();
		if(key != null && mbtiMap.containsKey(key)) {
			return mbtiMap.get(key);
		}
		else {
			logger.warn("Invalid mbti key '" + key + "'");
		}

		return null;
	}
	public static Sloan getSloan(String key) {
		Map<String, Sloan> sloanMap = getSloanDef();
		if(key != null && sloanMap.containsKey(key)) {
			return sloanMap.get(key);
		}
		else {
			logger.warn("Invalid sloan key '" + key + "'");
		}
		return null;
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
		
		List<BaseRecord> partners = person.get("partners");
		int marker = 0;
		if(partners.size() == 0) {
			prof.getLoveNeeds().add(LoveNeeds.INTIMACY);
			marker++;
		}

		List<BaseRecord> siblings = person.get("siblings");
		List<BaseRecord> dependents = person.get("dependents");
		if(partners.size() == 0 && siblings.size() == 0 && dependents.size() == 0) {
			prof.getLoveNeeds().add(LoveNeeds.FAMILY);
			marker++;
		}
		
		List<BaseRecord> social = person.get("socialRing");
		if(social.size() == 0) {
			prof.getLoveNeeds().add(LoveNeeds.FRIENDSHIP);
			marker++;
		}
		
		if(marker == 0) {
			prof.getLoveNeeds().add(LoveNeeds.CONNECTION);
		}
		
		//// Security calculation
		//// Finance + Personal
		prof.getSafetyNeeds().add(SafetyNeeds.SECURITY);
		
		//// Employment calculation - use scheduled activity count
		prof.getSafetyNeeds().add(SafetyNeeds.EMPLOYMENT);
		
		//// simple calculation - if the person has nothing
		if(items.size() == 0) {
			prof.getSafetyNeeds().add(SafetyNeeds.RESOURCES);
		}
		
		List<BaseRecord> locs = store.get("locations");
		if(locs.size() == 0) {
			prof.getSafetyNeeds().add(SafetyNeeds.PROPERTY);
		}
		
		BaseRecord state = person.get("state");
		double health = state.get("health");
		if(health < 0.25) {
			prof.getSafetyNeeds().add(SafetyNeeds.HEALTH);
		}
		
		/// TODO: Initial esteem calculations

	}
	
}
class MBTI {
	private String key = null;
	private String name = null;
	private String description = null;
	public MBTI(String key, String name, String description) {
		this.key = key;
		this.name = name;
		this.description = description;
	}
	public String getKey() {
		return key;
	}
	public String getName() {
		return name;
	}
	public String getDescription() {
		return description;
	}
	public void setKey(String key) {
		this.key = key;
	}
	public void setName(String name) {
		this.name = name;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	
}
class Sloan {
	private String key = null;
	private String mbtiKey = null;
	private String description = null;
	private List<String> favoredCareers = null;
	private List<String> disfavoredCareers = null;
	public Sloan(String key, String mbtiKey, String description, String favored, String disfavored) {
		this.key = key;
		this.mbtiKey = mbtiKey;
		this.description = description;
		this.favoredCareers = Arrays.asList(favored.split(",")).stream().map(s -> s.trim()).collect(Collectors.toList());
		this.disfavoredCareers = Arrays.asList(disfavored.split(",")).stream().map(s -> s.trim()).collect(Collectors.toList());
	}
	public String getKey() {
		return key;
	}
	public String getDescription() {
		return description;
	}
	public List<String> getFavoredCareers() {
		return favoredCareers;
	}
	public List<String> getDisfavoredCareers() {
		return disfavoredCareers;
	}

	public String getMbtiKey() {
		return mbtiKey;
	}
	public void setMbtiKey(String mbtiKey) {
		this.mbtiKey = mbtiKey;
	}
	public void setKey(String key) {
		this.key = key;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public void setFavoredCareers(List<String> favoredCareers) {
		this.favoredCareers = favoredCareers;
	}
	public void setDisfavoredCareers(List<String> disfavoredCareers) {
		this.disfavoredCareers = disfavoredCareers;
	}
	
	
	
}