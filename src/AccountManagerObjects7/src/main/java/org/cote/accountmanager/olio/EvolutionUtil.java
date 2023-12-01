package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;


public class EvolutionUtil {
	public static final Logger logger = LogManager.getLogger(EvolutionUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	private static String[] demographicLabels = new String[]{"Alive","Child","Young Adult","Adult","Available","Senior","Mother","Coupled","Deceased"};
	private static Map<String,List<BaseRecord>> newDemographicMap(){
		Map<String,List<BaseRecord>> map = new HashMap<>();
		for(String label : demographicLabels){
			map.put(label, new ArrayList<>());
		}
		return map;
	}
	
	/// The default config - 1 epoch/increment = 1 year.  Evolve will run through 12 months, and within each month branch out into smaller clusters of events
	protected static void evolvePopulation(BaseRecord user, BaseRecord world, BaseRecord parentEvent, AlignmentEnumType eventAlignment, BaseRecord population, int increment){
		try {

			Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON);
			q.filterParticipation(population, null, ModelNames.MODEL_CHAR_PERSON, null);
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);

			List<BaseRecord> pop = Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q));
			if(pop.isEmpty()){
				logger.warn("Population is decimated");
				return;
			}
			
			Map<String,List<BaseRecord>> demographicMap = newDemographicMap();
			Map<String, List<BaseRecord>> queue = new HashMap<>();
			logger.info("Mapping ...");
			for(BaseRecord p : pop) {
				setDemographicMap(demographicMap, parentEvent, p);
			}

			logger.info("Evolving ...");
			for(int i = 0; i < (increment * 12); i++){
				evolvePopulation(user, world, parentEvent, eventAlignment, population, pop, demographicMap, queue, i);
			}
			
			for(BaseRecord p: pop) {
				int age =  CharacterUtil.getCurrentAge(user, world, p);
				p.set("age",age);
				queueUpdate(queue, p, new String[] {"age"});
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
	
	private static void setDemographicMap(Map<String,List<BaseRecord>> map, BaseRecord parentEvent, BaseRecord person) throws ModelException {
		Date birthDate = person.get("birthDate");
		Date endDate = parentEvent.get("eventEnd");
		int age = (int)((endDate.getTime() - birthDate.getTime()) / OlioUtil.YEAR);
		if(CharacterUtil.isDeceased(person)){
			map.get("Deceased").add(person);
		}
		else{
			map.get("Alive").add(person);

			if(age <= Rules.MAXIMUM_CHILD_AGE){
				map.get("Child").add(person);
			}
			else if(age >= Rules.SENIOR_AGE){
				map.get("Senior").add(person);
			}
			else{
				if(age < Rules.MINIMUM_ADULT_AGE) map.get("Young Adult").add(person);
				else map.get("Adult").add(person);
				List<BaseRecord> partners = person.get("partners");
				if(age >= Rules.MINIMUM_MARRY_AGE && age <= Rules.MAXIMUM_MARRY_AGE && partners.isEmpty()) map.get("Available").add(person);
				else if(!partners.isEmpty()) map.get("Coupled").add(person);
				if("female".equals(person.get("gender")) && age >= Rules.MINIMUM_ADULT_AGE && age <= Rules.MAXIMUM_FERTILITY_AGE_FEMALE) map.get("Mother").add(person);
			}
		}
	}
	
	private static void queueAttribute(Map<String, List<BaseRecord>> queue, BaseRecord record) {
		String key = record.getModel();
		if(!queue.containsKey(key)) {
			queue.put(key, new ArrayList<>());
		}
		queue.get(key).add(record);
	}
	private static void queueUpdate(Map<String, List<BaseRecord>> queue, BaseRecord record, String[] fields) {
		List<String> fnlist =new ArrayList<>(Arrays.asList(fields));
		if(fnlist.size() == 0) {
			return;
		}
		fnlist.add(FieldNames.FIELD_ID);
		fnlist.add(FieldNames.FIELD_OWNER_ID);
		fnlist.sort((f1, f2) -> f1.compareTo(f2));
		Set<String> fieldSet = fnlist.stream().collect(Collectors.toSet());
		String key = record.getModel() + "-" + fieldSet.stream().collect(Collectors.joining("-"));
		if(!queue.containsKey(key)) {
			queue.put(key, new ArrayList<>());
		}
		queue.get(key).add(record.copyRecord(fieldSet.toArray(new String[0])));
	}
	
	protected static void couple(BaseRecord user, BaseRecord person1, BaseRecord person2, boolean enabled) {

		/// The properties are being manually updated here so as not to reread from the database
		List<BaseRecord> partners1 = person1.get("partners");
		List<BaseRecord> partners2 = person2.get("partners");
		if(!enabled) {
			partners1.clear();
			partners2.clear();
		}
		else {
			partners1.add(person2);
			partners2.add(person1);
		}
		IOSystem.getActiveContext().getMemberUtil().member(user, person1, person2, null, enabled);
		IOSystem.getActiveContext().getMemberUtil().member(user, person2, person1, null, enabled);

	}
	private static <T> void addAttribute(Map<String, List<BaseRecord>> queue, BaseRecord obj, String attrName, T val) throws ModelException, FieldException, ModelNotFoundException, ValueException {
		BaseRecord attr = AttributeUtil.addAttribute(obj, attrName, val);
		queueAttribute(queue, attr);
	}
	protected static void evolvePopulation(BaseRecord user, BaseRecord world, BaseRecord parentEvent, AlignmentEnumType eventAlignment, BaseRecord population, List<BaseRecord> pop, Map<String,List<BaseRecord>> map, Map<String, List<BaseRecord>> queue, int iteration){
		try {
			
			for(BaseRecord per : pop) {
				if(CharacterUtil.isDeceased(per)) {
					continue;
				}

				int currentAge = (int)((((Date)parentEvent.get("eventStart")).getTime() - ((Date)per.get("birthDate")).getTime() + (OlioUtil.DAY * iteration))/OlioUtil.YEAR);

				if(rulePersonDeath(eventAlignment, population, per, currentAge)){
					//BaseRecord attr = AttributeUtil.
					addAttribute(queue, per, "deceased", true);
					
					/*
					if(person.getPartners().isEmpty() == false){
						person.getPartners().get(0).getPartners().clear();
						person.getPartners().clear();
					}
					
					EventType death = ((EventFactory)Factories.getFactory(FactoryEnumType.EVENT)).newEvent(user, parentEvent);
					death.setName("Death of " + person.getName() + " at the age of " + age);
					death.setEventType(EventEnumType.EGRESS);
					death.getActors().add(person);
					death.setLocation(parentEvent.getLocation());
					BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.EVENT, death);
					*/
				}
			
			}

		}
		catch(ModelException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	/*
	 	private Map<String,List<PersonType>> getPotentialPartnerMap(Map<String,List<PersonType>>map){
		Map<String,List<PersonType>> potentials = new HashMap<>();
		potentials.put("male", new ArrayList<>());
		potentials.put("female", new ArrayList<>());
		for(PersonType person : map.get("Available")){
			if(person.getPartners().isEmpty() == false){
				logger.warn(person.getName() + " is not a viable partner");
				continue;
			}
			if("male".equalsIgnoreCase(person.getGender())){
				potentials.get("male").add(person);
			}
			else{
				potentials.get("female").add(person);
			}
		}
		return potentials;
	}

	private boolean rulePersonBirth(AlignmentEnumType eventAlignmentType, PersonGroupType populationGroup, PersonType mother, int age) throws FactoryException, ArgumentException{
		boolean outBool = false;
		if("female".equalsIgnoreCase(mother.getGender()) == false || age < minMarryAge || age > maxFertilityAge) return outBool;
		double odds = 0.001 + (mother.getPartners().isEmpty() ? 0.001 : 0.025 - (mother.getDependents().size() * 0.001));
		double rand = Math.random();
		if(rand < odds){
			outBool = true;
		}
		
		return outBool;
	}
	
	

	 */

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
	private void evolvePopulation(String sessionId, EventType parentEvent, AlignmentEnumType eventAlignment, PersonGroupType population, List<PersonType> personPopulation){
		try {
			Map<String,List<PersonType>> demographicMap = newDemographicMap();
			ruleImmigration(sessionId, parentEvent, population);

			List<PersonType> newAdditions = new ArrayList<>();
			for(PersonType person : personPopulation){
				if(isDeceased(person)){
					continue;
				}
				int age = (int)(CalendarUtil.getTimeSpan(person.getBirthDate(),parentEvent.getStartDate()) / YEAR);
				
				/// If a female is ruled to be a mother, generate the baby
				///
				if("female".equalsIgnoreCase(person.getGender()) && rulePersonBirth(eventAlignment, population, person,age)){
					PersonType partner = (person.getPartners().isEmpty() ? null : person.getPartners().get(0));
					PersonType baby = randomPerson(user, personsDir, (partner != null && isPatriarchal ? partner : person).getLastName());
					baby.setBirthDate(parentEvent.getStartDate());
					BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.PERSON, baby);
					person.getDependents().add(baby);

					if(partner != null){
						partner.getDependents().add(baby);
					}
					BaseParticipantType bpt = ((GroupParticipationFactory)Factories.getBulkFactory(FactoryEnumType.GROUPPARTICIPATION)).newPersonGroupParticipation(population, baby);
					BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.GROUPPARTICIPATION, bpt);
					parentEvent.getActors().add(baby);
					newAdditions.add(baby);
					
					/// Note: When dealing with bulk sessions that involve complex object types, consistency within the session is necessary because the objects may result in dirty write operations, where a transitive dependency (aka the dirty entry) is introduced within the bulk session.
					/// So if, for example, a person is added with contact information here, but without it later, such as in immigration, and the person factory adds it by default, that default entry becomes dirty.  The dirty entry can cause a bulk schema difference on the operation, which results in a null identifier.
					///
					addressPerson(baby,parentEvent.getLocation(), sessionId);
					
					EventType birth = ((EventFactory)Factories.getFactory(FactoryEnumType.EVENT)).newEvent(user, parentEvent);
					birth.setName("Birth of " + baby.getName());
					birth.setEventType(EventEnumType.INGRESS);
					birth.getOrchestrators().add(person);
					if(partner != null) birth.getInfluencers().add(partner);
					birth.getActors().add(baby);
					birth.setLocation(parentEvent.getLocation());

					BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.EVENT, birth);
				}
				if(rulePersonDeath(eventAlignment, population, person, age)){
					AttributeType attr4 = new AttributeType();
					attr4.setDataType(SqlDataEnumType.VARCHAR);
					attr4.setName("deceased");
					attr4.getValues().add("true");
					person.getAttributes().add(attr4);
					if(person.getPartners().isEmpty() == false){
						person.getPartners().get(0).getPartners().clear();
						person.getPartners().clear();
					}
					
					EventType death = ((EventFactory)Factories.getFactory(FactoryEnumType.EVENT)).newEvent(user, parentEvent);
					death.setName("Death of " + person.getName() + " at the age of " + age);
					death.setEventType(EventEnumType.EGRESS);
					death.getActors().add(person);
					death.setLocation(parentEvent.getLocation());
					BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.EVENT, death);
				}
				setDemographicMap(demographicMap, parentEvent, person);

			}
			populationCache.get(population.getId()).addAll(newAdditions);
			for(PersonType person : newAdditions){
				setDemographicMap(demographicMap, parentEvent, person);
			}
			
			ruleCouples(sessionId,parentEvent,demographicMap);
			
		} catch (FactoryException | ArgumentException e) {
			
			logger.error(e.getMessage());
		}

	}
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
	private void ruleCouples(String sessionId, EventType parentEvent, Map<String,List<PersonType>> demographicMap) throws ArgumentException, FactoryException{
		Map<String,List<PersonType>> potentials = getPotentialPartnerMap(demographicMap);
		if(potentials.get("male").size() > 0 && potentials.get("female").size() > 0){
			for(int i = 0; i < potentials.get("female").size(); i++){
				PersonType fem = potentials.get("female").get(i);
				if(fem.getPartners().isEmpty() == false) continue;
				//boolean partnered = false;
				for(int m = 0; m < potentials.get("male").size(); m++){
					PersonType mal = potentials.get("male").get(m);
					if(mal.getPartners().isEmpty() == false) continue;
					double rand = Math.random();
					if(rand < marriageRate){
						fem.getPartners().add(mal);
						mal.getPartners().add(fem);
						EventType marriage = ((EventFactory)Factories.getFactory(FactoryEnumType.EVENT)).newEvent(user, parentEvent);
						marriage.setName("Marriage of " + fem.getName() + " to " + mal.getName() + " (" + UUID.randomUUID().toString() + ")");
						marriage.setEventType(EventEnumType.STABLIZE);
						marriage.getActors().add(mal);
						marriage.getActors().add(fem);
						marriage.setLocation(parentEvent.getLocation());
						BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.EVENT, marriage);
						break;
					}
				}
			}
		}

		Set<PersonType> evaluated = new HashSet<PersonType>();
		for(PersonType person : demographicMap.get("Coupled")){
			if(person.getPartners().isEmpty()){
				continue;
			}
			PersonType partner = person.getPartners().get(0);
			if(evaluated.contains(partner) || evaluated.contains(person)) continue; 
			evaluated.add(person);
			evaluated.add(partner);
			double rand = Math.random();
			if(rand < divorceRate){
				EventType divorce = ((EventFactory)Factories.getFactory(FactoryEnumType.EVENT)).newEvent(user, parentEvent);
				divorce.setName("Divorce of " + person.getName() + " from " + partner.getName() + " (" + UUID.randomUUID().toString() + ")");
				divorce.setEventType(EventEnumType.DESTABILIZE);
				divorce.getActors().add(person);
				divorce.getActors().add(partner);
				divorce.setLocation(divorce.getLocation());
				person.getPartners().clear();
				partner.getPartners().clear();
				BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.EVENT, divorce);
			}
		}
	
	}
	*/
}
