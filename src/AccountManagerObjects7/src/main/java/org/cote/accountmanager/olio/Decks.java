package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class Decks {
	public static final Logger logger = LogManager.getLogger(Decks.class);
	private static SecureRandom rand = new SecureRandom();
	private static int traitDeckSize = 100;
	protected static BaseRecord[] traitDeck = new BaseRecord[0];
	protected static String[] maleNamesDeck = new String[0];
	protected static String[] femaleNamesDeck = new String[0];
	protected static String[] surnameNamesDeck = new String[0];
	protected static String[] occupationsDeck = new String[0];
	private static int namesDeckCount = 500;
	
	

	private static void shuffleOccupationsDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query tnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, world.get("occupations.id"));
		tnq.set("cache", false);
		occupationsDeck = OlioUtil.randomSelectionNames(user, tnq, count);
	}
	
	private static void shuffleMaleNamesDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query mnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, world.get("names.id"));
		mnq.field("gender", "M");
		mnq.set("cache", false);
		maleNamesDeck = OlioUtil.randomSelectionNames(user, mnq, count);
	}
	
	private static void shuffleFemaleNamesDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query mnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, world.get("names.id"));
		mnq.field("gender", "F");
		mnq.set("cache", false);
		femaleNamesDeck = OlioUtil.randomSelectionNames(user, mnq, count);
	}
	
	private static void shuffleSurnameNamesDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query snq = QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, world.get("surnames.id"));
		snq.set("cache", false);
		surnameNamesDeck = OlioUtil.randomSelectionNames(user, snq, count);
	}
	
	protected static void shuffleDecks(BaseRecord user, BaseRecord world) {
		try {
			// logger.info("Shuffling decks");
			shuffleMaleNamesDeck(user, world, namesDeckCount);
			shuffleFemaleNamesDeck(user, world, namesDeckCount);
			shuffleSurnameNamesDeck(user, world, namesDeckCount * 2);
			shuffleOccupationsDeck(user, world, namesDeckCount * 2);
			ApparelUtil.shuffleDecks(user, world);
			OlioUtil.dirNameCache.clear();
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	protected static void shuffleTraitDeck(BaseRecord user, BaseRecord world) {
		long traitDir = world.get("traits.id");
		Query q = QueryUtil.createQuery(ModelNames.MODEL_TRAIT, FieldNames.FIELD_GROUP_ID, traitDir);
		q.setRequest(new String[]{FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID});
		traitDeck = OlioUtil.randomSelections(user, q, traitDeckSize);
	}
	
	public static BaseRecord[] getRandomTraits(BaseRecord user, BaseRecord world, int count) {
		List<BaseRecord> traits = new ArrayList<>();
		if(traitDeck.length == 0) {
			shuffleTraitDeck(user, world);
		}
		
		if(traitDeck.length > 0) {
			for(int i = 0; i < count; i++) {
				BaseRecord trait = traitDeck[rand.nextInt(traitDeck.length)];
				if(!traits.contains(trait)) {
					traits.add(trait);
				}
			}
		}
		return traits.toArray(new BaseRecord[0]);
	}
}
