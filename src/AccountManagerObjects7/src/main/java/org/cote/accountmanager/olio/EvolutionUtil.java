package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
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
				setDemographicMap(demographicMap, parentEvent, p);
			}

			logger.info("Evolving " + parentEvent.get("location.name") + " with " + pop.size() + " people ...");
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
	private static void queueAdd(Map<String, List<BaseRecord>> queue, BaseRecord record) {
		record.getFields().sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
		String key = record.getModel() + "-" + record.getFields().stream().map(f -> f.getName()).collect(Collectors.joining("-"));
		if(!queue.containsKey(key)) {
			queue.put(key, new ArrayList<>());
		}
		queue.get(key).add(record);
	}
	
	protected static void decouple(BaseRecord user, BaseRecord person) {
		IOSystem.getActiveContext().getReader().populate(person, new String[] {"partners"});
		List<BaseRecord> partners = person.get("partners");
		if(partners.size() > 0) {
			couple(user, person, partners.get(0), false);
		}
	}
	protected static void couple(BaseRecord user, BaseRecord person1, BaseRecord person2) {
		couple(user, person1, person2, true);
	}
	private static void couple(BaseRecord user, BaseRecord person1, BaseRecord person2, boolean enabled) {

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
			List<BaseRecord> additions = new ArrayList<>();
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
					baby.set("birthDate", new Date(now));
					// queueAdd(queue, baby);
					AddressUtil.addressPerson(user, world, baby, parentEvent.get("location"));
					IOSystem.getActiveContext().getRecordUtil().updateRecord(baby);
					dep1.add(baby);
					queueAdd(queue, ParticipationFactory.newParticipation(user, population, null, baby));
					queueAdd(queue, ParticipationFactory.newParticipation(user, per, "dependents", baby));
					if(partner != null){
						List<BaseRecord> dep2 = partner.get("dependents");
						dep2.add(baby);
						queueAdd(queue, ParticipationFactory.newParticipation(user, partner, "dependents", baby));
					}
					
					additions.add(baby);

					BaseRecord evt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, elist);
					evt.set(FieldNames.FIELD_NAME, "Birth of " + baby.get(FieldNames.FIELD_NAME));
					evt.set(FieldNames.FIELD_LOCATION, parentEvent.get(FieldNames.FIELD_LOCATION));
					List<BaseRecord> acts = evt.get("actors");
					List<BaseRecord> orch = evt.get("participants");
					List<BaseRecord> infl = evt.get("influencers");
					acts.add(baby);
					orch.add(per);
					if(partner  != null) {
						infl.add(partner);
					}
					evt.set(FieldNames.FIELD_TYPE, EventEnumType.INGRESS);
					evt.set(FieldNames.FIELD_PARENT_ID, parentEvent.get(FieldNames.FIELD_ID));
					evt.set("eventStart", new Date(now));
					evt.set("eventEnd", new Date(now));
					queueAdd(queue, evt);
				}
				
				if(rulePersonDeath(eventAlignment, population, per, currentAge)){
					//BaseRecord attr = AttributeUtil.
					addAttribute(queue, per, "deceased", true);
					decouple(user, per);
					IOSystem.getActiveContext().getMemberUtil().member(user, population, per, null, false);
					BaseRecord evt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, elist);
					/// TODO: Need a way to bulk-add hierarchies
					/// The previous version used a complex method of identifier assignment and rewrite with negative values
					evt.set(FieldNames.FIELD_NAME, "Death of " + per.get(FieldNames.FIELD_NAME) + " at the age of " + currentAge);
					evt.set(FieldNames.FIELD_LOCATION, parentEvent.get(FieldNames.FIELD_LOCATION));
					List<BaseRecord> acts = evt.get("actors");
					acts.add(per);
					evt.set(FieldNames.FIELD_TYPE, EventEnumType.EGRESS);
					evt.set(FieldNames.FIELD_PARENT_ID, parentEvent.get(FieldNames.FIELD_ID));
					evt.set("eventStart", new Date(now));
					evt.set("eventEnd", new Date(now));
					queueAdd(queue, evt);
				}
			}
			pop.addAll(additions);
			for(BaseRecord per : additions){
				setDemographicMap(map, parentEvent, per);
			}
			
			ruleCouples(user, world, parentEvent, map, queue);
		}
		catch(ModelException | FieldException | ModelNotFoundException | ValueException | FactoryException e) {
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
		ParameterList elist = ParameterList.newParameterList("path", world.get("events.path"));
		Map<String,List<BaseRecord>> pots = getPotentialPartnerMap(map);
		try {
			Set<BaseRecord> eval = new HashSet<>();
			for(BaseRecord per : map.get("Coupled")) {
				List<BaseRecord> parts1 = per.get("partners");
				if(parts1.size() == 0) {
					continue;
				}
				BaseRecord partner = parts1.get(0);
				long pid = partner.get(FieldNames.FIELD_ID);
				partner = map.get("Coupled").stream().filter(f -> ((long)f.get(FieldNames.FIELD_ID) == pid)).findFirst().get();

				eval.add(per);
				eval.add(partner);
				if(rand.nextDouble() <= Rules.INITIAL_DIVORCE_RATE) {
					long time = ((Date)parentEvent.get("eventStart")).getTime() + (rand.nextInt(364) * OlioUtil.DAY);
					BaseRecord evt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, elist);
					evt.set(FieldNames.FIELD_NAME, "Divorce of " + per.get(FieldNames.FIELD_NAME) + " from " + partner.get(FieldNames.FIELD_NAME));
					evt.set(FieldNames.FIELD_LOCATION, parentEvent.get(FieldNames.FIELD_LOCATION));
					List<BaseRecord> acts = evt.get("actors");
					acts.add(per);
					acts.add(partner);
					evt.set(FieldNames.FIELD_TYPE, EventEnumType.DESTABILIZE);
					evt.set(FieldNames.FIELD_PARENT_ID, parentEvent.get(FieldNames.FIELD_ID));
					evt.set("eventStart", new Date(time));
					evt.set("eventEnd", new Date(time));
					queueAdd(queue, evt);
					decouple(user, per);
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
					if(rand.nextDouble() <= Rules.INITIAL_MARRIAGE_RATE) {
						long time = ((Date)parentEvent.get("eventStart")).getTime() + (rand.nextInt(364) * OlioUtil.DAY);
						
						couple(user, man, woman);
						
						BaseRecord evt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, elist);
						evt.set(FieldNames.FIELD_NAME, "Marriage of " + man.get(FieldNames.FIELD_NAME) + " to " + woman.get(FieldNames.FIELD_NAME));
						evt.set(FieldNames.FIELD_LOCATION, parentEvent.get(FieldNames.FIELD_LOCATION));
						List<BaseRecord> acts = evt.get("actors");
						acts.add(man);
						acts.add(woman);
						evt.set(FieldNames.FIELD_TYPE, EventEnumType.STABLIZE);
						evt.set(FieldNames.FIELD_PARENT_ID, parentEvent.get(FieldNames.FIELD_ID));
						evt.set("eventStart", new Date(time));
						evt.set("eventEnd", new Date(time));
						queueAdd(queue, evt);
						
						eval.add(man);
						eval.add(woman);
					}
				}
			}

		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		/*
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
		*/
	}

}
