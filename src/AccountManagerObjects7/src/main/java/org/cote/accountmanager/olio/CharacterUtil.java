package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.util.AttributeUtil;


public class CharacterUtil {
	public static final Logger logger = LogManager.getLogger(CharacterUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	//// TODO: Deprecate in favor of state.alive
	public static boolean isDeceased(BaseRecord person) throws ModelException{
		
		return AttributeUtil.getAttributeValue(person, "deceased", false);
	}
	public static int getCurrentAge(OlioContext ctx, BaseRecord person) {
		IOSystem.getActiveContext().getReader().populate(person, new String[] {FieldNames.FIELD_BIRTH_DATE});
		return getAgeAtEpoch(ctx.getOlioUser(), ctx.clock().getEpoch(), person);
	}
	public static int getCurrentAge(BaseRecord user, BaseRecord world, BaseRecord person) {
		IOSystem.getActiveContext().getReader().populate(person, new String[] {FieldNames.FIELD_BIRTH_DATE});
		BaseRecord evt = EventUtil.getLastEpochEvent(user, world);
		return getAgeAtEpoch(user, evt, person);
	}
	public static int getAgeAtEpoch(BaseRecord user, BaseRecord epoch, BaseRecord person) {
		Date bday = person.get(FieldNames.FIELD_BIRTH_DATE);
		Date cday = epoch.get(OlioFieldNames.FIELD_EVENT_END);
		return (int)(Math.abs(cday.getTime() - bday.getTime()) / OlioUtil.YEAR);
	}


	
	public static BaseRecord randomPerson(OlioContext ctx, String preferredLastName) {
		return randomPerson(ctx, preferredLastName, ZonedDateTime.now(), null, null, null, null);
	}
	public static BaseRecord randomPerson(OlioContext ctx, String preferredLastName, ZonedDateTime inceptionDate, String[] mnames, String[] fnames, String[] snames, String[] tnames) {
		BaseRecord user = ctx.getOlioUser();
		BaseRecord world = ctx.getWorld();
		BaseRecord parWorld = world.get(OlioFieldNames.FIELD_BASIS);
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}

		BaseRecord namesDir = parWorld.get(OlioFieldNames.FIELD_NAMES);
		BaseRecord surDir = parWorld.get(OlioFieldNames.FIELD_SURNAMES);
		BaseRecord popDir = world.get(OlioFieldNames.FIELD_POPULATION);
		// IOSystem.getActiveContext().getReader().populate(popDir);
		

		BaseRecord person = null;
		
		try {
			BaseRecord stats = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATISTICS, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("statistics.path")));
			BaseRecord inst = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_INSTINCT, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("instincts.path")));
			BaseRecord beh = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_BEHAVIOR, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("behaviors.path")));
			BaseRecord pper = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_PERSONALITY, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("personalities.path")));
			BaseRecord st = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATE, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("states.path")));
			BaseRecord sto = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get(OlioFieldNames.FIELD_STORES_PATH)));
			BaseRecord pro = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_PROFILE, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("profiles.path")));
			
			person = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("population.path")));
			person.set(OlioFieldNames.FIELD_STATISTICS, stats);
			person.set(OlioFieldNames.FIELD_INSTINCT, inst);
			person.set(FieldNames.FIELD_BEHAVIOR, beh);
			person.set(FieldNames.FIELD_PERSONALITY, pper);
			person.set(FieldNames.FIELD_STATE, st);
			person.set(FieldNames.FIELD_STORE, sto);
			person.set(FieldNames.FIELD_PROFILE, pro);
			boolean isMale = (Math.random() < 0.5);
			
			if(inceptionDate != null) {
				ZonedDateTime birthDate = inceptionDate.minus(rand.nextInt(75), ChronoUnit.YEARS);
				person.set(FieldNames.FIELD_BIRTH_DATE, birthDate);
			}
			String gen = isMale ? "male":"female";
			person.set(FieldNames.FIELD_GENDER, gen);
			person.set(OlioFieldNames.FIELD_RACE, randomRaceType().stream().map(k -> k.toString()).collect(Collectors.toList()));
			setStyleByRace(ctx, person);
			
			Query fnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesDir.get(FieldNames.FIELD_ID));
			fnq.field(FieldNames.FIELD_GENDER, gen.substring(0, 1).toUpperCase());
			String[] names = mnames;
			if(gen.equals("female")) {
				names = fnames;
			}
			String firstName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
			String middleName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
			String lastName = (preferredLastName != null ? preferredLastName : (snames != null ? snames[rand.nextInt(snames.length)] : OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID)))));			
			String name = firstName + " " + middleName + " " + lastName;
	
			while(OlioUtil.nameInDirExists(user, OlioModelNames.MODEL_CHAR_PERSON, (long)popDir.get(FieldNames.FIELD_ID), name)) {
				logger.info("Name " + name + " exists .... trying again");
				firstName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
				middleName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
				lastName = (preferredLastName != null ? preferredLastName : (snames != null ? snames[rand.nextInt(snames.length)] : OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID)))));

				name = firstName + " " + middleName + " " + lastName;
			}
	
			person.set(FieldNames.FIELD_FIRST_NAME, firstName);
			person.set(FieldNames.FIELD_MIDDLE_NAME, middleName);
			person.set(FieldNames.FIELD_LAST_NAME, lastName);
			person.set(FieldNames.FIELD_NAME, name);
	
			/*
			List<String> trades = person.get(OlioFieldNames.FIELD_TRADES);
			trades.add((
				tnames != null ? tnames[rand.nextInt(tnames.length)]
				:
					OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, occDir.get(FieldNames.FIELD_ID)))
			));
			if(Math.random() < .15) {
				trades.add((
					tnames != null ? tnames[rand.nextInt(tnames.length)]
					:
						OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, occDir.get(FieldNames.FIELD_ID)))
				));			
			}
			*/
			
			AlignmentEnumType alignment = OlioUtil.getRandomAlignment();
			person.set(FieldNames.FIELD_ALIGNMENT, alignment);
		}
		catch(FieldException | ValueException | ModelNotFoundException | FactoryException | IndexException | ReaderException e) {
			logger.error(e);
		}
		return person;
	}
	
	public static void generateOrganizationHierarchy(BaseRecord[] persons){

		/// limits the top n depth to these positional values
		///
		int[] iDepthLimits = new int[]{3, 5, 10, 20};
		int iDefaultLimit = 7;
		int iDepth = 0;
		int iRepC = 1;
		int iNewDepth = 0;
		int r = 0;
		try {
			for(int i = 0; i < persons.length && iRepC < persons.length; i++){
				int iWidth = (iDepth < iDepthLimits.length ? iDepthLimits[iDepth] : iDefaultLimit);
	
				int iRep = rand.nextInt(iWidth);
				/// take the next 'iRep' number people offset by previously reported people and make them report to person 'i'
				///
				for(r = 0; r < iRep; r++){
					if((iRepC + r) >= persons.length) break;
					AttributeUtil.addAttribute(persons[iRepC + r], "manager", (String)persons[i].get(FieldNames.FIELD_OBJECT_ID));
				}
				iRepC += r;
				if(i >= iNewDepth){
					iDepth++;
					iNewDepth += iWidth;
				}
			}
		}
		catch(ValueException | ModelException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public static void couple(BaseRecord user, BaseRecord person1, BaseRecord person2) {
		couple(user, person1, person2, true);
	}
	
	public static void couple(BaseRecord user, BaseRecord person1, BaseRecord person2, boolean enabled) {

		/// The properties are being manually updated here so as not to reread from the database
		List<BaseRecord> partners1 = person1.get(FieldNames.FIELD_PARTNERS);
		List<BaseRecord> partners2 = person2.get(FieldNames.FIELD_PARTNERS);
		if(!enabled) {
			partners1.clear();
			partners2.clear();
		}
		else {
			partners1.add(person2);
			partners2.add(person1);
		}
		String action = (enabled ? "" : "un") + "couple";
		String dir = (enabled ? "to" : "from");
		// logger.info(action + " " + person1.get(FieldNames.FIELD_ID) + " " + person1.get(FieldNames.FIELD_NAME) + " " + dir + " " + person2.get(FieldNames.FIELD_ID) + " " + person2.get(FieldNames.FIELD_NAME));
		boolean mem1 = IOSystem.getActiveContext().getMemberUtil().member(user, person1, FieldNames.FIELD_PARTNERS, person2, null, enabled);
		boolean mem2 = IOSystem.getActiveContext().getMemberUtil().member(user, person2, FieldNames.FIELD_PARTNERS, person1, null, enabled);
		if(!mem1) {
			logger.warn("Failed to " + action + " " + person1.get(FieldNames.FIELD_ID) + " " + person1.get(FieldNames.FIELD_NAME) + " " + dir + " " + person2.get(FieldNames.FIELD_ID) + " " + person2.get(FieldNames.FIELD_NAME));
		}
		if(!mem2) {
			logger.warn("Failed to " + action + " " + person2.get(FieldNames.FIELD_ID) + " " + person2.get(FieldNames.FIELD_NAME) + " " + dir + " " + person1.get(FieldNames.FIELD_ID) + " " + person1.get(FieldNames.FIELD_NAME));
		}
	}
	
	/// white, black, gray not included
	///
	private static final String[] natHairColors = new String[] {
		"#faf0be",
		"#f4a460",
		"#996515",
		"#654321",
		"#a52a2a",
		"#ff0000"
	};
	private static final String[] natEyeColors = new String[] {
		"#a52a2a",
		"#bc8f8f",
		"#8e7618",
		"#008000",
		"#0000ff",
		"#808080"
	};
	private static final String[] femaleHairStyles = new String[] {
		"pixie cut", "bob", "medium length", "shoulder length", "long", "short", "bun", "ponytail", "pigtails", "curly", "dreadlocks", "mohawk", "long wavy", "plaits", "ringlets", "shaved", "bald", "tangled"	
	};
	private static final String[] maleHairStyles = new String[] {
		"pixie cut", "shoulder length", "short", "dreadlocks", "mohawk", "shaved", "bald", "ponytail", "messy"
	};
	
	public static void setStyleByRace(OlioContext ctx, BaseRecord person) throws FieldException, ValueException, ModelNotFoundException {
		String gender = person.get(FieldNames.FIELD_GENDER);
		List<String> rets = person.get(OlioFieldNames.FIELD_RACE);
		if(rets.size() == 0) {
			return;
		}
		RaceEnumType pret = RaceEnumType.valueOf(rets.get(0));
		String hairColor = null;
		String eyeColor = null;
		String hairStyle = "messy";
		if(gender != null && gender.equals("male")) {
			hairStyle = maleHairStyles[rand.nextInt(maleHairStyles.length)];
		}
		else {
			hairStyle = femaleHairStyles[rand.nextInt(femaleHairStyles.length)];
		}
		switch(pret) {
			
			case A:
			case B:
			case C:
			case D:
			case M:
				eyeColor = "#a52a2a";
				hairColor = "#000000";
				break;
			case L:
			case S:
			case E:
				eyeColor = natEyeColors[rand.nextInt(natEyeColors.length)];
				hairColor = natHairColors[rand.nextInt(natHairColors.length)];
				break;
			case R:
				eyeColor = "#808080";
				hairColor = "#c0c0c0";
				break;
			case V:
				eyeColor = "#ff0000";
				hairColor = "#ffffff";
				break;
			case W:
				eyeColor = "#00ff00";
				hairColor = "#00ff00";
				break;
			case X:
			case Y:
				eyeColor = natEyeColors[rand.nextInt(natEyeColors.length)];
				hairColor = natHairColors[rand.nextInt(natHairColors.length)];
			case Z:
				eyeColor = "#0000ff";
				hairColor = "#c0c0c0";
				hairStyle = "pixie cut";
				break;
		}
		if((long)person.get(FieldNames.FIELD_OWNER_ID) == 0L) {
			logger.warn("Invalid owner");
			logger.warn(person.toFullString());
		}
		person.set(OlioFieldNames.FIELD_EYE_COLOR, ColorUtil.getDefaultColor(ctx, person.get(FieldNames.FIELD_OWNER_ID), eyeColor));
		person.set(OlioFieldNames.FIELD_HAIR_STYLE, hairStyle);
		person.set(OlioFieldNames.FIELD_HAIR_COLOR, ColorUtil.getDefaultColor(ctx, person.get(FieldNames.FIELD_OWNER_ID), hairColor));

	}
	public static List<RaceEnumType> randomRaceType(){
		return randomRaceType(Rules.DEFAULT_RACE_PERCENTAGE);
	}
	public static List<RaceEnumType> randomRaceType(Map<RaceEnumType, Double> odds){
		List<RaceEnumType> races = new ArrayList<>();
		boolean multi = (rand.nextDouble() <= Rules.DEFAULT_TWO_OR_MORE_RACE_PERCENTAGE);
		
		//.mapToInt(Integer::intValue)
		double total = odds.entrySet().stream().map(l -> l.getValue()).mapToDouble(Double::doubleValue).sum();
		double perc = rand.nextDouble(total);
		List<RaceEnumType> sorted = odds.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getValue)).map(Map.Entry::getKey).collect(Collectors.toList());
		double tot = 0.0;
		RaceEnumType ret = RaceEnumType.U;
		for(RaceEnumType rtet : sorted) {
			tot += odds.get(rtet);
			if(perc <= tot) {
				ret = rtet;
				break;
			}
		}
		races.add(ret);
		if(multi) {
			ret = RaceEnumType.U;
			perc = rand.nextDouble(total);
			for(RaceEnumType rtet : sorted) {
				tot += odds.get(rtet);
				if(perc <= tot) {
					ret = rtet;
					break;
				}
			}
			races.add(ret);
		}

		return races;
	}
	
	public static BaseRecord populateRegion(OlioContext ctx, BaseRecord realm, BaseRecord location, BaseRecord rootEvent, int popCount){

		String locName = location.get(FieldNames.FIELD_NAME);
		long start = System.currentTimeMillis();
		BaseRecord event = null;
		BaseRecord parWorld = ctx.getWorld().get(OlioFieldNames.FIELD_BASIS);
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		try {
			BaseRecord popDir = ctx.getWorld().get(OlioFieldNames.FIELD_POPULATION);
			BaseRecord evtDir = ctx.getWorld().get(OlioFieldNames.FIELD_EVENTS);
			
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, evtDir.get(FieldNames.FIELD_PATH));
			event = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_EVENT, ctx.getOlioUser(), null, plist);
			event.set(FieldNames.FIELD_LOCATION, location);
			event.set(FieldNames.FIELD_TYPE, EventEnumType.INCEPT);
			event.set(FieldNames.FIELD_NAME, "Populate " + locName);
			event.set(OlioFieldNames.FIELD_REALM, realm);
			event.set(FieldNames.FIELD_PARENT_ID, rootEvent.get(FieldNames.FIELD_ID));
			ZonedDateTime inceptionDate = rootEvent.get(OlioFieldNames.FIELD_EVENT_START);
			event.set(OlioFieldNames.FIELD_EVENT_START, inceptionDate);
			event.set(OlioFieldNames.FIELD_EVENT_PROGRESS, inceptionDate);
			event.set(OlioFieldNames.FIELD_EVENT_END, rootEvent.get(OlioFieldNames.FIELD_EVENT_END));

			List<BaseRecord> grps = event.get(FieldNames.FIELD_GROUPS);
			BaseRecord popGrp = OlioUtil.newRegionGroup(ctx.getOlioUser(), popDir, locName + " Population");
			BaseRecord cemGrp = OlioUtil.newRegionGroup(ctx.getOlioUser(), popDir, locName + " Cemetary");
			grps.add(popGrp);
			grps.add(cemGrp);

			/*
			for(String name : leaderPopulation){
				grps.add(newRegionGroup(user, popDir, locName + " " + name + " Leaders"));				
			}
			*/
			
			IOSystem.getActiveContext().getRecordUtil().updateRecords(grps.toArray(new BaseRecord[0]));
			
			realm.set(OlioFieldNames.FIELD_POPULATION, popGrp);
			Queue.queueUpdate(realm, new String[] {OlioFieldNames.FIELD_POPULATION});
			realm.set(OlioFieldNames.FIELD_POPULATION_GROUPS, grps);
			Queue.queue(ParticipationFactory.newParticipation(ctx.getOlioUser(), realm, OlioFieldNames.FIELD_POPULATION_GROUPS, popGrp));
			Queue.queue(ParticipationFactory.newParticipation(ctx.getOlioUser(), realm, OlioFieldNames.FIELD_POPULATION_GROUPS, cemGrp));
			//Queue.processQueue();
			
			event.set(FieldNames.FIELD_GROUPS, grps);
			List<BaseRecord> actors = event.get(OlioFieldNames.FIELD_ACTORS);
			if(popCount == 0){
				logger.error("Empty population");
				event.set(FieldNames.FIELD_DESCRIPTION, "Decimated");
			}
			else {
				int totalAbsoluteAlignment = 0;
				ZonedDateTime now = ZonedDateTime.now();

				Decks.shuffleDecks(ctx.getOlioUser(), parWorld);
				if(Decks.maleNamesDeck.length == 0 || Decks.femaleNamesDeck.length == 0 || Decks.surnameNamesDeck.length == 0 || Decks.occupationsDeck.length == 0) {
					logger.error("Empty names");
				}

				// logger.info("Creating population of " + popCount);

				for(int i = 0; i < popCount; i++){
					BaseRecord person = CharacterUtil.randomPerson(ctx, null, inceptionDate, Decks.maleNamesDeck, Decks.femaleNamesDeck, Decks.surnameNamesDeck, Decks.occupationsDeck);
					AddressUtil.simpleAddressPerson(ctx, location, person);
					int alignment = AlignmentEnumType.getAlignmentScore(person);
					long years = Math.abs(now.toInstant().toEpochMilli() - ((ZonedDateTime)person.get(FieldNames.FIELD_BIRTH_DATE)).toInstant().toEpochMilli()) / OlioUtil.YEAR;
					person.set(FieldNames.FIELD_AGE, (int)years);
					
					StatisticsUtil.rollStatistics(person.get(OlioFieldNames.FIELD_STATISTICS), (int)years);
					ProfileUtil.rollPersonality(person.get(FieldNames.FIELD_PERSONALITY));

					totalAbsoluteAlignment += (alignment + 4);
					
					/*
					List<BaseRecord> appl = person.get(OlioFieldNames.FIELD_APPAREL);
					appl.add(ApparelUtil.randomApparel(user, world, person));
					*/
					actors.add(person);
				}
				
				int created = IOSystem.getActiveContext().getRecordUtil().updateRecords(actors.toArray(new BaseRecord[0]));
				if(created != actors.size()) {
					logger.error("Created " + created + " but expected " + actors.size() + " records");
				}
				
				/// Add event membership
				List<BaseRecord> parts = new ArrayList<>();
				// logger.info("**** Add " + actors.size() + " to " + popGrp.get(FieldNames.FIELD_ID));
				for(BaseRecord rec : actors) {
					parts.add(ParticipationFactory.newParticipation(ctx.getOlioUser(), popGrp, null, rec));
				}
				IOSystem.getActiveContext().getRecordUtil().updateRecords(parts.toArray(new BaseRecord[0]));
				int eventAlignment = (totalAbsoluteAlignment / popCount) - 4;
				if(eventAlignment == 0) {
					eventAlignment++;
				}
				event.set(FieldNames.FIELD_ALIGNMENT, AlignmentEnumType.valueOf(eventAlignment));				
				/*
				if(organizePersonManagement){
					generatePersonOrganization(event.getActors().toArray(new PersonType[0]));
				}
				*/
			}
			// logger.info("Update event");
			IOSystem.getActiveContext().getRecordUtil().updateRecord(event);

		} catch (ValueException | FieldException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		Queue.processQueue();
		logger.info("Finished populating " + locName + " in " + (System.currentTimeMillis() - start) + "ms");
		return event;
	}
	
}
