package org.cote.accountmanager.olio;

import java.security.SecureRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class EvolutionUtil {
	public static final Logger logger = LogManager.getLogger(EvolutionUtil.class);
	private static SecureRandom rand = new SecureRandom();
	/*
	protected static void beginEvolution(OlioContext ctx){
		if(ctx.getCurrentEvent() == null || ctx.getCurrentLocation() == null) {
			logger.error("Context is not ready for evolution");
			return;
		}

		logger.info("Begin evolution of " + ctx.getCurrentLocation().get(FieldNames.FIELD_NAME));
		
		ActionResultEnumType art = ctx.getCurrentEvent().get(FieldNames.FIELD_STATE);
		if(art != ActionResultEnumType.PENDING) {
			logger.error("Current event is not in a pending state");
			return;
		}

		List<BaseRecord> pop = OlioUtil.getPopulation(ctx, ctx.getCurrentLocation());
		if(pop.isEmpty()){
			logger.warn("Population is decimated");
			return;
		}
		
		Map<String,List<BaseRecord>> demographicMap = ctx.getDemographicMap(ctx.getCurrentLocation());
		for(BaseRecord p : pop) {
			OlioUtil.setDemographicMap(ctx.getOlioUser(), demographicMap, ctx.getCurrentEvent(), p);
		}

		
		try {
			ctx.getCurrentEvent().set(FieldNames.FIELD_STATE, ActionResultEnumType.IN_PROGRESS);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		Queue.queue(ctx.getCurrentEvent().copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_STATE}));
		

	}
	
	/// The default config - 1 epoch/increment = 1 year.  Evolve will run through 12 months, and within each month branch out into smaller clusters of events
	// protected static void evolvePopulation(BaseRecord user, BaseRecord world, BaseRecord parentEvent, AlignmentEnumType eventAlignment, BaseRecord population, int increment){
	protected static void evolvePopulation(OlioContext ctx){
		try {
			int increment = 1;
			if(ctx.getCurrentEvent() == null || ctx.getCurrentLocation() == null) {
				logger.error("Context is not ready for evolution");
				return;
			}
			List<BaseRecord> pop = OlioUtil.getPopulation(ctx, ctx.getCurrentLocation());
			if(pop.isEmpty()){
				logger.warn("Population is decimated");
				return;
			}
			
			Map<String,List<BaseRecord>> demographicMap = ctx.getDemographicMap(ctx.getCurrentLocation());
			// Map<String, List<BaseRecord>> queue = new HashMap<>();
			
			logger.info("Mapping ...");
			// demographicMap.clear();
			for(BaseRecord p : pop) {
				OlioUtil.setDemographicMap(ctx.getOlioUser(), demographicMap, ctx.getCurrentEvent(), p);
			}
			
			logger.info("Evolving " + ctx.getCurrentEvent().get("location.name") + " with " + pop.size() + " people ...");
			for(int i = 0; i < (increment * 12); i++){
				evolvePopulationByMonth(ctx, i);
				// evolvePopulation(user, world, parentEvent, eventAlignment, population, pop, demographicMap, queue, i);
			}
			
			for(BaseRecord p: pop) {
				int age =  CharacterUtil.getCurrentAge(ctx, p);
				p.set(FieldNames.FIELD_AGE,age);
				Queue.queueUpdate(p, new String[] {FieldNames.FIELD_AGE});
			}
			
			logger.info("Updating population ...");
			Queue.processQueue();
			
		}
		catch(Exception e) {
			logger.error(e);
		}
	}
	
	/// protected static void evolvePopulation(BaseRecord user, BaseRecord world, BaseRecord parentEvent, AlignmentEnumType eventAlignment, BaseRecord population, List<BaseRecord> pop, Map<String,List<BaseRecord>> map, Map<String, List<BaseRecord>> queue, int iteration){
	protected static void evolvePopulationByMonth(OlioContext ctx, int month){
		try {
			List<BaseRecord> additions = new ArrayList<>();
			List<BaseRecord> deaths = new ArrayList<>();
			ParameterList elist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("events.path"));
			List<BaseRecord> pop = ctx.getPopulation(ctx.getCurrentLocation());
			BaseRecord popGrp = ctx.getPopulationGroup(ctx.getCurrentLocation(), "Population");
			BaseRecord cemGrp = ctx.getPopulationGroup(ctx.getCurrentLocation(), "Cemetary");
			AlignmentEnumType eventAlignment = AlignmentEnumType.valueOf(ctx.getCurrentEvent().get("alignment"));
			Map<String,List<BaseRecord>> map = ctx.getDemographicMap(ctx.getCurrentLocation());
			
			for(BaseRecord per : pop) {
				if(CharacterUtil.isDeceased(per)) {
					continue;
				}
				ZonedDateTime estart = ctx.getCurrentEvent().get(OlioFieldNames.FIELD_EVENT_START);
				ZonedDateTime now = estart.plusMonths(month);
				ctx.setCurrentMonth(now);
				long lage = now.toInstant().toEpochMilli() - ((ZonedDateTime)per.get(FieldNames.FIELD_BIRTH_DATE)).toInstant().toEpochMilli();
				//long now = (((Date)parentEvent.get(OlioFieldNames.FIELD_EVENT_START)).getTime() - ((Date)per.get(FieldNames.FIELD_BIRTH_DATE)).getTime() + (OlioUtil.DAY * iteration));
				int currentAge = (int)(lage/OlioUtil.YEAR);
				String gender = per.get(FieldNames.FIELD_GENDER);
				/// If a female is ruled to be a mother, generate the baby
				///
				if("female".equalsIgnoreCase(gender) && rulePersonBirth(eventAlignment, popGrp, per, currentAge)){
					List<BaseRecord> partners = per.get(FieldNames.FIELD_PARTNERS);
					List<BaseRecord> dep1 = per.get("dependents");
					BaseRecord partner = partners.isEmpty() ? null : partners.get(0);
					BaseRecord baby = CharacterUtil.randomPerson(ctx, (Rules.IS_PATRIARCHAL && partner != null ? partner : per).get(FieldNames.FIELD_LAST_NAME));
					StatisticsUtil.rollStatistics(baby.get(OlioFieldNames.FIELD_STATISTICS), 0);
					StatisticsUtil.rollHeight(baby.get(OlioFieldNames.FIELD_STATISTICS), baby.get(OlioFieldNames.FIELD_RACE), baby.get(FieldNames.FIELD_GENDER), 0);
					baby.set(FieldNames.FIELD_BIRTH_DATE, now);
					// queueAdd(queue, baby);
					AddressUtil.randomAddressPerson(ctx.getOlioUser(), ctx.getWorld(), baby, ctx.getCurrentEvent().get(FieldNames.FIELD_LOCATION));
					List<BaseRecord> appl = baby.get(OlioFieldNames.FIELD_STORE_APPAREL);
					appl.add(ApparelUtil.randomApparel(ctx, baby));
					
					IOSystem.getActiveContext().getRecordUtil().updateRecord(baby);
					dep1.add(baby);
					Queue.queue(ParticipationFactory.newParticipation(ctx.getOlioUser(), popGrp, null, baby));
					Queue.queue(ParticipationFactory.newParticipation(ctx.getOlioUser(), per, "dependents", baby));
					if(partner != null){
						BaseRecord partp = pop.stream().filter(p -> ((long)p.get(FieldNames.FIELD_ID)) == ((long)partner.get(FieldNames.FIELD_ID))).findFirst().get();
						List<BaseRecord> dep2 = partp.get("dependents");
						dep2.add(baby);
						Queue.queue(ParticipationFactory.newParticipation(ctx.getOlioUser(), partp, "dependents", baby));
					}
					
					additions.add(baby);

					EventUtil.newEvent(ctx, ctx.getCurrentEvent(), EventEnumType.BIRTH, "Birth of " + baby.get(FieldNames.FIELD_NAME), now, new BaseRecord[] {per}, new BaseRecord[] {baby}, (partner != null ? new BaseRecord[] {partner} : null), true);
				}
				
				if(rulePersonDeath(eventAlignment, popGrp, per, currentAge)){
					OlioUtil.addAttribute(per, "deceased", true);
					List<BaseRecord> partners = per.get(FieldNames.FIELD_PARTNERS);
					BaseRecord partner = partners.isEmpty() ? null : partners.get(0);
					if(partner != null) {
						/// Use the population copy of the partner since the partners list may be consulted later in the same evolution cycle
						///
						BaseRecord partp = pop.stream().filter(p -> ((long)p.get(FieldNames.FIELD_ID)) == ((long)partner.get(FieldNames.FIELD_ID))).findFirst().get();
						CharacterUtil.couple(ctx.getOlioUser(), per, partp, false);
						// decouple(user, per);
					}
					IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), popGrp, per, null, false);
					IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), cemGrp, per, null, true);
					EventUtil.newEvent(ctx, ctx.getCurrentEvent(), EventEnumType.DEATH, "Death of " + per.get(FieldNames.FIELD_NAME) + " at the age of " + currentAge, now, new BaseRecord[] {per}, null, null, true);
					deaths.add(per);
				}
			}
			pop.addAll(additions);
			pop.removeAll(deaths);
			/ *
			for(BaseRecord per : additions){
				OlioUtil.setDemographicMap(ctx.getOlioUser(), map, ctx.getCurrentEvent(), per);
			}
			* /
			for(BaseRecord per : deaths){
				OlioUtil.setDemographicMap(ctx.getOlioUser(), map, ctx.getCurrentEvent(), per);
			}			
			// ruleCouples(user, world, parentEvent, map, queue);
			ruleCouples(ctx);
			
			for(BaseRecord p : pop) {
				OlioUtil.setDemographicMap(ctx.getOlioUser(), map, ctx.getCurrentEvent(), p);
			}
		}
		catch(ModelException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}

	private static boolean rulePersonBirth(AlignmentEnumType eventAlignmentType, BaseRecord populationGroup, BaseRecord mother, int age) {
		boolean outBool = false;
		String gender = mother.get(FieldNames.FIELD_GENDER);
		if(!gender.equals("female") || age < Rules.MINIMUM_MARRY_AGE || age > Rules.MAXIMUM_FERTILITY_AGE_FEMALE) {
			return false;
		}
		
		List<BaseRecord> partners = mother.get(FieldNames.FIELD_PARTNERS);
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
	
	
	/ *

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
	* /
 	private static Map<String,List<BaseRecord>> getPotentialPartnerMap(Map<String, List<BaseRecord>> map){
		Map<String,List<BaseRecord>> potentials = new HashMap<>();
		potentials.put("male", new ArrayList<>());
		potentials.put("female", new ArrayList<>());
		for(BaseRecord person : map.get("Available")){
			List<BaseRecord> partners = person.get(FieldNames.FIELD_PARTNERS);
			String gender = person.get(FieldNames.FIELD_GENDER);
			if(!partners.isEmpty()){
				continue;
			}
			potentials.get(gender).add(person);
		}
		return potentials;
	}
 	private static void ruleCouples(OlioContext ctx) {
	// private static void ruleCouples(BaseRecord user, BaseRecord world, BaseRecord parentEvent, Map<String, List<BaseRecord>> map, Map<String, List<BaseRecord>> queue) {
 		Map<String,List<BaseRecord>> map = ctx.getDemographicMap(ctx.getCurrentLocation());
		Map<String,List<BaseRecord>> pots = getPotentialPartnerMap(map);

		Set<BaseRecord> eval = new HashSet<>();
		List<BaseRecord> remap = new ArrayList<>();
		List<BaseRecord> coupled = map.get("Coupled");
		for(BaseRecord per : coupled) {
			if(eval.contains(per)) {
				continue;
			}
			eval.add(per);
			List<BaseRecord> parts1 = per.get(FieldNames.FIELD_PARTNERS);
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

				EventUtil.newEvent(ctx, ctx.getCurrentEvent(), EventEnumType.DIVORCE, "Divorce of " + per.get(FieldNames.FIELD_NAME) + " from " + partner.get(FieldNames.FIELD_NAME), ctx.getCurrentMonth(), new BaseRecord[] {per, partner}, null, null, true);
				CharacterUtil.couple(ctx.getOlioUser(), per, partner, false);
				remap.add(per);
				remap.add(partner);
				eval.add(partner);
			}
			
		}
		if(pots.get("male").size() > 0 && pots.get("female").size() > 0) {
			for(BaseRecord man : pots.get("male")) {
				BaseRecord woman = pots.get("female").get(rand.nextInt(pots.get("female").size()));
				List<BaseRecord> parts1 = man.get(FieldNames.FIELD_PARTNERS);
				List<BaseRecord> parts2 = woman.get(FieldNames.FIELD_PARTNERS);
				if(!parts1.isEmpty() || !parts2.isEmpty() || eval.contains(man) || eval.contains(woman)) {
					continue;
				}

				eval.add(man);
				eval.add(woman);
				
				if(rand.nextDouble() <= Rules.INITIAL_MARRIAGE_RATE) {
					CharacterUtil.couple(ctx.getOlioUser(), man, woman);
					EventUtil.newEvent(ctx, ctx.getCurrentEvent(), EventEnumType.MARRIAGE, "Marriage of " + man.get(FieldNames.FIELD_NAME) + " to " + woman.get(FieldNames.FIELD_NAME), ctx.getCurrentMonth(), new BaseRecord[] {man, woman}, null, null, true);
					
					remap.add(man);
					remap.add(woman);

				}
			}
		}
		/ *
		for(BaseRecord per : remap) {
			OlioUtil.setDemographicMap(ctx.getOlioUser(), map, ctx.getCurrentEvent(), per);
		}
		* /
	}
 	*/

}
