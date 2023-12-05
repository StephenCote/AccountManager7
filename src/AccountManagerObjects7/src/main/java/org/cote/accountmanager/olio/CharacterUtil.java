package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;


public class CharacterUtil {
	public static final Logger logger = LogManager.getLogger(CharacterUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	public static boolean isDeceased(BaseRecord person) throws ModelException{
		return AttributeUtil.getAttributeValue(person, "deceased", false);
	}

	public static int getCurrentAge(BaseRecord user, BaseRecord world, BaseRecord person) {
		IOSystem.getActiveContext().getReader().populate(person, new String[] {"birthDate"});
		BaseRecord evt = EventUtil.getLastEpochEvent(user, world);
		return getAgeAtEpoch(user, evt, person);
	}
	public static int getAgeAtEpoch(BaseRecord user, BaseRecord epoch, BaseRecord person) {
		Date bday = person.get("birthDate");
		Date cday = epoch.get("eventEnd");
		return (int)(Math.abs(cday.getTime() - bday.getTime()) / OlioUtil.YEAR);
	}
	
	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world) {
		return randomPerson(user, world, null, null, null, null, null, null);
	}
	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world, String preferredLastName) {
		return randomPerson(user, world, preferredLastName, new Date(), null, null, null, null);
	}
	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world, String preferredLastName, Date inceptionDate, String[] mnames, String[] fnames, String[] snames, String[] tnames) {

		BaseRecord parWorld = world.get("basis");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}

		BaseRecord namesDir = parWorld.get("names");
		BaseRecord surDir = parWorld.get("surnames");
		BaseRecord occDir = parWorld.get("occupations");
		BaseRecord popDir = world.get("population");
		BaseRecord statDir = world.get("statistics");
		BaseRecord instDir = world.get("instincts");
		BaseRecord behDir = world.get("behaviors");
		BaseRecord pperDir = world.get("personalities");
		IOSystem.getActiveContext().getReader().populate(popDir);
		
		ParameterList plist = ParameterList.newParameterList("path", popDir.get(FieldNames.FIELD_PATH));
		ParameterList plist2 = ParameterList.newParameterList("path", statDir.get(FieldNames.FIELD_PATH));
		ParameterList plist3 = ParameterList.newParameterList("path", instDir.get(FieldNames.FIELD_PATH));
		ParameterList plist4 = ParameterList.newParameterList("path", behDir.get(FieldNames.FIELD_PATH));
		ParameterList plist5 = ParameterList.newParameterList("path", pperDir.get(FieldNames.FIELD_PATH));
		BaseRecord person = null;
		
		try {
			BaseRecord stats = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CHAR_STATISTICS, user, null, plist2);
			BaseRecord inst = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_INSTINCT, user, null, plist3);
			BaseRecord beh = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_BEHAVIOR, user, null, plist4);
			BaseRecord pper = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_PERSONALITY, user, null, plist5);
			person = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, user, null, plist);
			person.set("statistics", stats);
			person.set("instinct", inst);
			person.set("behavior", beh);
			person.set("personality", pper);
			boolean isMale = (Math.random() < 0.5);
			
			if(inceptionDate != null) {
				long birthEpoch = inceptionDate.getTime() - (OlioUtil.YEAR * rand.nextInt(75));
				Date birthDate = new Date(birthEpoch);
				person.set("birthDate", birthDate);
			}
			String gen = isMale ? "male":"female";
			person.set("gender", gen);
			
			Query fnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesDir.get(FieldNames.FIELD_ID));
			fnq.field("gender", gen.substring(0, 1).toUpperCase());
			String[] names = mnames;
			if(gen.equals("female")) {
				names = fnames;
			}
			String firstName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
			String middleName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
			String lastName = (preferredLastName != null ? preferredLastName : (snames != null ? snames[rand.nextInt(snames.length)] : OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID)))));			
			String name = firstName + " " + middleName + " " + lastName;
	
			while(OlioUtil.nameInDirExists(user, ModelNames.MODEL_CHAR_PERSON, (long)popDir.get(FieldNames.FIELD_ID), name)) {
				logger.info("Name " + name + " exists .... trying again");
				firstName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
				middleName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
				lastName = (preferredLastName != null ? preferredLastName : (snames != null ? snames[rand.nextInt(snames.length)] : OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID)))));

				name = firstName + " " + middleName + " " + lastName;
			}
	
			person.set("firstName", firstName);
			person.set("middleName", middleName);
			person.set("lastName", lastName);
			person.set(FieldNames.FIELD_NAME, name);
	
			List<String> trades = person.get("trades");
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
		String action = (enabled ? "" : "un") + "couple";
		String dir = (enabled ? "to" : "from");
		// logger.info(action + " " + person1.get(FieldNames.FIELD_ID) + " " + person1.get(FieldNames.FIELD_NAME) + " " + dir + " " + person2.get(FieldNames.FIELD_ID) + " " + person2.get(FieldNames.FIELD_NAME));
		boolean mem1 = IOSystem.getActiveContext().getMemberUtil().member(user, person1, "partners", person2, null, enabled);
		boolean mem2 = IOSystem.getActiveContext().getMemberUtil().member(user, person2, "partners", person1, null, enabled);
		if(!mem1) {
			logger.warn("Failed to " + action + " " + person1.get(FieldNames.FIELD_ID) + " " + person1.get(FieldNames.FIELD_NAME) + " " + dir + " " + person2.get(FieldNames.FIELD_ID) + " " + person2.get(FieldNames.FIELD_NAME));
		}
		if(!mem2) {
			logger.warn("Failed to " + action + " " + person2.get(FieldNames.FIELD_ID) + " " + person2.get(FieldNames.FIELD_NAME) + " " + dir + " " + person1.get(FieldNames.FIELD_ID) + " " + person1.get(FieldNames.FIELD_NAME));
		}
	}
	
}
