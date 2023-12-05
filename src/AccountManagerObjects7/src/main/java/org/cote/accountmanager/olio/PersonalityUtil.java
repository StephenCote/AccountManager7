package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.List;

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
import org.cote.accountmanager.schema.type.EventEnumType;

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
	
	public static PersonalityProfile analyzePersonality(OlioContext octx, BaseRecord person) {
		PersonalityProfile prof = createProfile(octx.getWorld(), person);
		IOSystem.getActiveContext().getReader().populate(person, new String[] {"personality", "statistics"});
		BaseRecord per = person.get("personality");
		BaseRecord stats = person.get("statistics");
		IOSystem.getActiveContext().getReader().populate(per);
		IOSystem.getActiveContext().getReader().populate(stats);
		logger.info("Analyzing " + person.get(FieldNames.FIELD_NAME));
		return prof;
	}
	
	public static PersonalityProfile createProfile(BaseRecord world, BaseRecord person) {
		PersonalityProfile prof = new PersonalityProfile();
		prof.setId(person.get(FieldNames.FIELD_ID));
		List<BaseRecord> parts = person.get("partners");
		List<BaseRecord> deps = person.get("dependents");
		prof.setMarried(parts.size() > 0);
		prof.setChildren(deps.size() > 0);
		try {
			prof.setAlive(!CharacterUtil.isDeceased(person));
		} catch (ModelException e) {
			logger.error(e);
		}
		/*
		long eventId = world.get("events.id");
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, eventId);
		q.field(FieldNames.FIELD_TYPE, EventEnumType.DIVORCE);
		q.filterParticipation(person, "actors", ModelNames.MODEL_CHAR_PERSON, null);
		q.setRequestRange(0L, 1);
		if(IOSystem.getActiveContext().getSearch().findRecord(q) != null) {
			prof.setDivorced(true);
		}
		*/
		BaseRecord per = person.get("personality");
		prof.setOpen(VeryEnumType.valueOf((double)per.get("openness")));
		prof.setConscientious(VeryEnumType.valueOf((double)per.get("conscientiousness")));
		prof.setExtraverted(VeryEnumType.valueOf((double)per.get("extraversion")));
		prof.setAgreeable(VeryEnumType.valueOf((double)per.get("agreeableness")));
		prof.setNeurotic(VeryEnumType.valueOf((double)per.get("neuroticism")));

		return prof;
	}
	
}

