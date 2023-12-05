package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;


public class EvolutionUtil {
	public static final Logger logger = LogManager.getLogger(EvolutionUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	private static String[] demographicLabels = new String[]{"Alive","Child","Young Adult","Adult","Available","Senior","Mother","Coupled","Deceased"};
	protected static Map<String,List<BaseRecord>> newDemographicMap(){
		Map<String,List<BaseRecord>> map = new ConcurrentHashMap<>();
		for(String label : demographicLabels){
			map.put(label, new CopyOnWriteArrayList<>());
		}
		return map;
	}
	
	protected static void setDemographicMap(BaseRecord user, Map<String,List<BaseRecord>> map, BaseRecord parentEvent, BaseRecord person) {
		try {
			Date birthDate = person.get("birthDate");
			Date endDate = parentEvent.get("eventEnd");
			int age = (int)((endDate.getTime() - birthDate.getTime()) / OlioUtil.YEAR);
			map.values().stream().forEach(l -> l.removeIf(f -> ((long)person.get(FieldNames.FIELD_ID)) == ((long)f.get(FieldNames.FIELD_ID))));
			
			if(CharacterUtil.isDeceased(person)){
				map.get("Deceased").add(person);
				List<BaseRecord> partners = person.get("partners");
				if(partners.size() > 0) {
					logger.error("***** Deceased " + person.get(FieldNames.FIELD_NAME) + " should have been decoupled and wasn't");
					// decouple(user, person);
				}
			}
			else{
				map.get("Alive").add(person);
	
				if(age <= Rules.MAXIMUM_CHILD_AGE){
					map.get("Child").add(person);
				}
				else if(age >= Rules.SENIOR_AGE){
					map.get("Senior").add(person);
				}
				else if(age < Rules.MINIMUM_ADULT_AGE) {
					map.get("Young Adult").add(person);
				}
				else{
					map.get("Adult").add(person);
				}

				List<BaseRecord> partners = person.get("partners");
				if(!partners.isEmpty()) {
					map.get("Coupled").add(person);
				}
				else if(age >= Rules.MINIMUM_MARRY_AGE && age <= Rules.MAXIMUM_MARRY_AGE && partners.isEmpty()) {
					map.get("Available").add(person);
				}
				if("female".equals(person.get("gender")) && age >= Rules.MINIMUM_ADULT_AGE && age <= Rules.MAXIMUM_FERTILITY_AGE_FEMALE) {
					map.get("Mother").add(person);
				}

			}
		}
		catch(ModelException e) {
			logger.error(e);
		}
	}
	



	
	/// The default config - 1 epoch/increment = 1 year.  Evolve will run through 12 months, and within each month branch out into smaller clusters of events
	protected static void evolvePopulation(BaseRecord user, BaseRecord world, BaseRecord parentEvent, AlignmentEnumType eventAlignment, BaseRecord population, int increment){
		try {

			Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON);
			q.filterParticipation(population, null, ModelNames.MODEL_CHAR_PERSON, null);
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			q.setCache(false);
			
			List<BaseRecord> pop = new ArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
			if(pop.isEmpty()){
				logger.warn("Population is decimated");
				return;
			}
			
			Map<String,List<BaseRecord>> demographicMap = newDemographicMap();
			Map<String, List<BaseRecord>> queue = new HashMap<>();
			logger.info("Mapping ...");
			for(BaseRecord p : pop) {
				setDemographicMap(user, demographicMap, parentEvent, p);
			}

			logger.info("Evolving " + parentEvent.get("location.name") + " with " + pop.size() + " people ...");
			for(int i = 0; i < (increment * 12); i++){
				evolvePopulation(user, world, parentEvent, eventAlignment, population, pop, demographicMap, queue, i);
			}
			
			for(BaseRecord p: pop) {
				int age =  CharacterUtil.getCurrentAge(user, world, p);
				p.set("age",age);
				OlioUtil.queueUpdate(queue, p, new String[] {"age"});
			}
			
			logger.info("Updating population ...");
			queue.forEach((k, v) -> {
				IOSystem.getActiveContext().getRecordUtil().updateRecords(v.toArray(new BaseRecord[0]));
			});
			
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	protected static void evolvePopulation(BaseRecord user, BaseRecord world, BaseRecord parentEvent, AlignmentEnumType eventAlignment, BaseRecord population, List<BaseRecord> pop, Map<String,List<BaseRecord>> map, Map<String, List<BaseRecord>> queue, int iteration){
		
		try {
			List<BaseRecord> additions = new ArrayList<>();
			List<BaseRecord> deaths = new ArrayList<>();
			ParameterList elist = ParameterList.newParameterList("path", world.get("events.path"));
			for(BaseRecord per : pop) {
				if(CharacterUtil.isDeceased(per)) {
					continue;
				}
				long now = ((Date)parentEvent.get("eventStart")).getTime() + (OlioUtil.DAY * iteration);
				long lage = now - ((Date)per.get("birthDate")).getTime();
				//long now = (((Date)parentEvent.get("eventStart")).getTime() - ((Date)per.get("birthDate")).getTime() + (OlioUtil.DAY * iteration));
				int currentAge = (int)(lage/OlioUtil.YEAR);
				String gender = per.get("gender");
				/// If a female is ruled to be a mother, generate the baby
				///
				if("female".equalsIgnoreCase(gender) && rulePersonBirth(eventAlignment, population, per, currentAge)){
					List<BaseRecord> partners = per.get("partners");
					List<BaseRecord> dep1 = per.get("dependents");
					BaseRecord partner = partners.isEmpty() ? null : partners.get(0);
					BaseRecord baby = CharacterUtil.randomPerson(user, world, (Rules.IS_PATRIARCHAL && partner != null ? partner : per).get("lastName"));
					StatisticsUtil.rollStatistics(baby.get("statistics"), 0);
					baby.set("birthDate", new Date(now));
					// queueAdd(queue, baby);
					AddressUtil.addressPerson(user, world, baby, parentEvent.get("location"));
					List<BaseRecord> appl = baby.get("apparel");
					appl.add(ApparelUtil.randomApparel(user, world, baby));
					
					IOSystem.getActiveContext().getRecordUtil().updateRecord(baby);
					dep1.add(baby);
					OlioUtil.queueAdd(queue, ParticipationFactory.newParticipation(user, population, null, baby));
					OlioUtil.queueAdd(queue, ParticipationFactory.newParticipation(user, per, "dependents", baby));
					if(partner != null){
						BaseRecord partp = pop.stream().filter(p -> ((long)p.get(FieldNames.FIELD_ID)) == ((long)partner.get(FieldNames.FIELD_ID))).findFirst().get();
						List<BaseRecord> dep2 = partp.get("dependents");
						dep2.add(baby);
						OlioUtil.queueAdd(queue, ParticipationFactory.newParticipation(user, partp, "dependents", baby));
					}
					
					additions.add(baby);

					EventUtil.addEvent(user, world, parentEvent, EventEnumType.BIRTH, "Birth of " + baby.get(FieldNames.FIELD_NAME), now, new BaseRecord[] {per}, new BaseRecord[] {baby}, (partner != null ? new BaseRecord[] {partner} : null), queue);
				}
				
				if(rulePersonDeath(eventAlignment, population, per, currentAge)){
					//BaseRecord attr = AttributeUtil.
					OlioUtil.addAttribute(queue, per, "deceased", true);
					List<BaseRecord> partners = per.get("partners");
					BaseRecord partner = partners.isEmpty() ? null : partners.get(0);
					if(partner != null) {
						/// Use the population copy of the partner since the partners list may be consulted later in the same evolution cycle
						///
						BaseRecord partp = pop.stream().filter(p -> ((long)p.get(FieldNames.FIELD_ID)) == ((long)partner.get(FieldNames.FIELD_ID))).findFirst().get();
						CharacterUtil.couple(user, per, partp, false);
						// decouple(user, per);
					}
					IOSystem.getActiveContext().getMemberUtil().member(user, population, per, null, false);
					/// TODO: Add user to Cemetery group
					EventUtil.addEvent(user, world, parentEvent, EventEnumType.DEATH, "Death of " + per.get(FieldNames.FIELD_NAME) + " at the age of " + currentAge, now, new BaseRecord[] {per}, null, null, queue);
					deaths.add(per);
				}
			}
			pop.addAll(additions);
			for(BaseRecord per : additions){
				setDemographicMap(user, map, parentEvent, per);
			}
			for(BaseRecord per : deaths){
				setDemographicMap(user, map, parentEvent, per);
			}			
			ruleCouples(user, world, parentEvent, map, queue);
			
			for(BaseRecord p : pop) {
				setDemographicMap(user, map, parentEvent, p);
			}
		}
		catch(ModelException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	


	/*

	*/

	private static boolean rulePersonBirth(AlignmentEnumType eventAlignmentType, BaseRecord populationGroup, BaseRecord mother, int age) {
		boolean outBool = false;
		String gender = mother.get("gender");
		if(!gender.equals("female") || age < Rules.MINIMUM_MARRY_AGE || age > Rules.MAXIMUM_FERTILITY_AGE_FEMALE) {
			return false;
		}
		
		List<BaseRecord> partners = mother.get("partners");
		List<BaseRecord> dependents = mother.get("dependents");
		
		double odds = Rules.ODDS_BIRTH_BASE + (partners.isEmpty() ? Rules.ODDS_BIRTH_SINGLE : Rules.ODDS_BIRTH_MARRIED - (dependents.size() * Rules.ODDS_BIRTH_FAMILY_SIZE));
		double rand = Math.random();
		if(rand < odds){
			outBool = true;
		}
		
		return outBool;
	}


	private static boolean rulePersonDeath(AlignmentEnumType eventAlignmentType, BaseRecord populationGroup, BaseRecord person, int age) {
		boolean outBool = false;
		boolean personIsLeader = false;
		
		double odds = 
			Rules.ODDS_DEATH_BASE + (age < Rules.MAXIMUM_CHILD_AGE ? Rules.ODDS_DEATH_MOD_CHILD : 0.0)
			+ (age > Rules.INITIAL_AVERAGE_DEATH_AGE
				? (age - Rules.INITIAL_AVERAGE_DEATH_AGE) * (personIsLeader ? Rules.ODDS_DEATH_MOD_LEADER : Rules.ODDS_DEATH_MOD_GENERAL) 
				: 0.0
			  )
			+ (age >= Rules.MAXIMUM_AGE ? 1.0 : 0.0)
		;
		double r = rand.nextDouble();
		if(r < odds){
			outBool = true;
		}
		return outBool;
	}
	
	
	/*

	private void ruleImmigration(String sessionId, EventType parentEvent, PersonGroupType population) throws ArgumentException, FactoryException{
		double rand = Math.random();
		double odds = immigrateRate;
		List<PersonType> immigration = new ArrayList<>();
		if(rand < odds){
			/// Single
			
			PersonType person = randomPerson(user,personsDir);
			int age = (int)(CalendarUtil.getTimeSpan(person.getBirthDate(),parentEvent.getEndDate()) / YEAR);
			immigration.add(person);
			if(rand < odds/2){
				PersonType partner = randomPerson(user,personsDir,person.getLastName());
				int page = (int)(CalendarUtil.getTimeSpan(partner.getBirthDate(),parentEvent.getEndDate()) / YEAR);
				immigration.add(partner);
				if(age >= minMarryAge && page >= minMarryAge){
					partner.getPartners().add(person);
					person.getPartners().add(partner);
				}
				else if(age > minMarryAge && page < minMarryAge){
					person.getDependents().add(partner);
				}
				
				if(rand < odds / 4){
					int count = 1 + (int)(Math.random() * 10);
					for(int i = 0; i < count; i++){
						PersonType other = randomPerson(user,personsDir,person.getLastName());
						int cage = (int)(CalendarUtil.getTimeSpan(other.getBirthDate(),parentEvent.getEndDate()) / YEAR);
						immigration.add(other);
						if(cage > age){
							other.getDependents().add(person);
						}
						if(cage > page){
							other.getDependents().add(partner);
						}
						if(cage <= age && cage <= page){
							partner.getDependents().add(other);
							person.getDependents().add(other);
						}
					}
				}
			}
		}
		if(immigration.isEmpty() == false){
			
			for(PersonType person : immigration){

				BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.PERSON, person);
				
				/// Note: When dealing with bulk sessions that involve complex object types, consistency within the session is necessary because the objects may result in dirty write operations, where a transitive dependency (aka the dirty entry) is introduced within the bulk session.
				/// So if, for example, a person is added with contact information here, but without it later, such as in immigration, and the person factory adds it by default, that default entry becomes dirty.  The dirty entry can cause a bulk schema difference on the operation, which results in a null identifier.
				///
				addressPerson(person,parentEvent.getLocation(),sessionId);
				BaseParticipantType bpt = ((GroupParticipationFactory)Factories.getBulkFactory(FactoryEnumType.GROUPPARTICIPATION)).newPersonGroupParticipation(population, person);
				BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.GROUPPARTICIPATION, bpt);

			}
			populationCache.get(population.getId()).addAll(immigration);
			EventType immig = ((EventFactory)Factories.getFactory(FactoryEnumType.EVENT)).newEvent(user, parentEvent);
			immig.setName("Immigration of " + immigration.get(0).getName() + (immigration.size() > 1 ? " and " + (immigration.size() - 1) + " others" : ""));
			immig.setEventType(EventEnumType.INGRESS);
			immig.getActors().addAll(immigration);
			immig.setLocation(parentEvent.getLocation());
			BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.EVENT, immig);
		}

	}
	*/
 	private static Map<String,List<BaseRecord>> getPotentialPartnerMap(Map<String, List<BaseRecord>> map){
		Map<String,List<BaseRecord>> potentials = new HashMap<>();
		potentials.put("male", new ArrayList<>());
		potentials.put("female", new ArrayList<>());
		for(BaseRecord person : map.get("Available")){
			List<BaseRecord> partners = person.get("partners");
			String gender = person.get("gender");
			if(!partners.isEmpty()){
				continue;
			}
			potentials.get(gender).add(person);
		}
		return potentials;
	}
	private static void ruleCouples(BaseRecord user, BaseRecord world, BaseRecord parentEvent, Map<String, List<BaseRecord>> map, Map<String, List<BaseRecord>> queue) {

		Map<String,List<BaseRecord>> pots = getPotentialPartnerMap(map);

		Set<BaseRecord> eval = new HashSet<>();
		List<BaseRecord> remap = new ArrayList<>();
		List<BaseRecord> coupled = map.get("Coupled");
		for(BaseRecord per : coupled) {
			if(eval.contains(per)) {
				continue;
			}
			eval.add(per);
			List<BaseRecord> parts1 = per.get("partners");
			if(parts1.size() == 0) {
				logger.error(per.get(FieldNames.FIELD_ID) + " " + per.get(FieldNames.FIELD_NAME) + " misplaced in couples list");
				continue;
			}

			
			if(rand.nextDouble() <= Rules.INITIAL_DIVORCE_RATE) {
				/// Use the population copy of the partner since the partners list may be consulted later in the same evolution cycle
				///
				BaseRecord partner = parts1.get(0);
				long pid = partner.get(FieldNames.FIELD_ID);
				Optional<BaseRecord> popt = coupled.stream().filter(f -> ((long)f.get(FieldNames.FIELD_ID)) == pid).findFirst();
				boolean cleanup = false;
				if(!popt.isPresent()) {
					logger.warn(per.get(FieldNames.FIELD_ID) + " " + per.get(FieldNames.FIELD_NAME) + " refers to " + partner.get(FieldNames.FIELD_ID) + " " + partner.get(FieldNames.FIELD_NAME) + " who was not found in the coupled demographic");
				}
				else {
					partner = popt.get();
				}

				long time = ((Date)parentEvent.get("eventStart")).getTime() + (rand.nextInt(364) * OlioUtil.DAY);
				EventUtil.addEvent(user, world, parentEvent, EventEnumType.DIVORCE, "Divorce of " + per.get(FieldNames.FIELD_NAME) + " from " + partner.get(FieldNames.FIELD_NAME), time, new BaseRecord[] {per, partner}, null, null, queue);
				CharacterUtil.couple(user, per, partner, false);
				remap.add(per);
				remap.add(partner);
				eval.add(partner);
			}
			
		}
		if(pots.get("male").size() > 0 && pots.get("female").size() > 0) {
			for(BaseRecord man : pots.get("male")) {
				BaseRecord woman = pots.get("female").get(rand.nextInt(pots.get("female").size()));
				List<BaseRecord> parts1 = man.get("partners");
				List<BaseRecord> parts2 = woman.get("partners");
				if(!parts1.isEmpty() || !parts2.isEmpty() || eval.contains(man) || eval.contains(woman)) {
					continue;
				}

				eval.add(man);
				eval.add(woman);
				
				if(rand.nextDouble() <= Rules.INITIAL_MARRIAGE_RATE) {
					long time = ((Date)parentEvent.get("eventStart")).getTime() + (rand.nextInt(364) * OlioUtil.DAY);
					
					CharacterUtil.couple(user, man, woman);
					
					EventUtil.addEvent(user, world, parentEvent, EventEnumType.MARRIAGE, "Marriage of " + man.get(FieldNames.FIELD_NAME) + " to " + woman.get(FieldNames.FIELD_NAME), time, new BaseRecord[] {man, woman}, null, null, queue);
					
					remap.add(man);
					remap.add(woman);

				}
			}
		}
		
		for(BaseRecord per : remap) {
			setDemographicMap(user, map, parentEvent, per);
		}
	}

}
