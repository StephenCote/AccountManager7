package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;


public class EvolutionUtil {
	public static final Logger logger = LogManager.getLogger(EvolutionUtil.class);
	
	private void evolvePopulation(BaseRecord user, BaseRecord world, BaseRecord parentEvent, AlignmentEnumType eventAlignment, BaseRecord population, int iterations){

		List<BaseRecord> personPopulation = new ArrayList<>();
	}
		/*
		if(populationCache.containsKey(population.getId())) personPopulation = populationCache.get(population.getId());
		else{
			try {
				personPopulation = ((GroupParticipationFactory)Factories.getFactory(FactoryEnumType.GROUPPARTICIPATION)).getPersonsInGroup(population);
				for(PersonType person : personPopulation){
					((PersonFactory)Factories.getFactory(FactoryEnumType.PERSON)).populate(person);
					Factories.getAttributeFactory().populateAttributes(person);
				}
				populationCache.put(population.getId(), personPopulation);
			} catch (FactoryException | ArgumentException e) {
				
				logger.error(FactoryException.LOGICAL_EXCEPTION,e);
			}
		}
		if(personPopulation.isEmpty()){
			logger.warn("Population is decimated");
			return;
		}
		
		for(int i = 0; i < iterations; i++){
			evolvePopulation(sessionId, parentEvent, eventAlignment, population, personPopulation);
		}
		
		try {
			for(PersonType person : personPopulation){
				if(Factories.getAttributeFactory().getAttributeValueByName(person, "alignment") == null){
					logger.error("Null alignment when " + (person.getId() > 0L ? "updating" : "adding") + " " + person.getName() + (person.getId() > 0L ? " " + person.getUrn() : ""));
				}
				if(person.getId() > 0L){

					BulkFactories.getBulkFactory().modifyBulkEntry(sessionId, FactoryEnumType.PERSON, person);
				}
			}

		} catch (ArgumentException | FactoryException e) {
			
			logger.error(FactoryException.LOGICAL_EXCEPTION,e);
		}
	}
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
