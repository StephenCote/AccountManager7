package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.Arrays;
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
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.CalendarUtil;

public class EpochUtil {
	public static final Logger logger = LogManager.getLogger(EpochUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	public static String generateEpochTitle(BaseRecord user, BaseRecord world, AlignmentEnumType alignment) {
		BaseRecord dir = world.get("dictionary");
		return generateEpochTitle(user, (long)dir.get(FieldNames.FIELD_ID), alignment);
	}
	
	public static String generateEpochTitle(BaseRecord user, long groupId, AlignmentEnumType alignment){
	
		Query bq = QueryUtil.createQuery(ModelNames.MODEL_WORD_NET, FieldNames.FIELD_GROUP_ID, groupId);
		Query advQ = new Query(bq.copyRecord());
		advQ.field("type", WordNetParser.adverbWordType);
		String advWord = OlioUtil.randomSelectionName(user, advQ);

		Query verQ = new Query(bq.copyRecord());
		verQ.field("type", WordNetParser.verbWordType);
		String verWord = OlioUtil.randomSelectionName(user, verQ);

		Query adjQ = new Query(bq.copyRecord());
		adjQ.field("type", WordNetParser.adjectiveWordType);
		String adjWord = OlioUtil.randomSelectionName(user, adjQ);

		Query nounQ = new Query(bq.copyRecord());
		nounQ.field("type", WordNetParser.nounWordType);
		String nouWord = OlioUtil.randomSelectionName(user, nounQ);
		
		// logger.info(nounQ.toFullString());
		
		String title = null;
		switch(alignment){
			case CHAOTICEVIL:
				title = "Vile period of " + adjWord + " " + nouWord;
				break;
			case CHAOTICGOOD:
				title = "The " + advWord + " " + verWord + " upheavel";
				break;
			case CHAOTICNEUTRAL:
				title = "All quiet on the " + adjWord + " " + nouWord;
				break;
			case LAWFULEVIL:
				title = "The " + verWord + " " + nouWord + " circumstance";
				break;
			case LAWFULGOOD:
				title = "Triumph of " + adjWord + " " + nouWord;
				break;
			case LAWFULNEUTRAL:
				title = "Quiet of " + adjWord + " " + nouWord;
				break;
			case UNKNOWN:
				title = "Stillness of " + nouWord;
				break;
			case NEUTRAL:
				title = "A " + adjWord + " " + nouWord + " mystery"; 
				break;
			case NEUTRALEVIL:
				title = "The " + adjWord + " " + nouWord + " confusion";
				break;
			case NEUTRALGOOD:
				title = "The " + verWord + " of the " + nouWord;
				break;
			default:
				break;
		}


		return title;
	}

	public static BaseRecord generateEpoch(BaseRecord user, BaseRecord world, int evolutions){
		return generateEpoch(user, world, evolutions, 1);
	}
	private static BaseRecord generateEpoch(BaseRecord user, BaseRecord world, int evolutions, int increment){

		BaseRecord epoch = null;
		IOSystem.getActiveContext().getReader().populate(world, 2);
		BaseRecord parWorld = world.get("basis");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		BaseRecord rootEvt = EventUtil.getRootEvent(user, world);
		BaseRecord lastEpoch = EventUtil.getLastEpochEvent(user, world);
		if(lastEpoch == null) {
			lastEpoch = rootEvt;
		}
		BaseRecord rootLoc = GeoLocationUtil.getRootLocation(user, world);
		if(rootLoc == null){
			logger.error("Failed to find root location");
			return null;
		}
		BaseRecord[] locs = GeoLocationUtil.getRegionLocations(user, world);
		if(locs.length == 0) {
			logger.error("Failed to find child locations");
		}
		
		for(int i = 0; i < evolutions; i++) {
		
			AlignmentEnumType alignment = OlioUtil.getRandomAlignment();
			int alignmentScore = AlignmentEnumType.getValue(alignment);
			AlignmentEnumType invertedAlignment = AlignmentEnumType.valueOf(-1 * alignmentScore);
			
			String title = EpochUtil.generateEpochTitle(user, parWorld, alignment);
			
			ParameterList plist = ParameterList.newParameterList("path", world.get("events.path"));
			try {
				epoch = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, plist);
				/// TODO: Need a way to bulk-add hierarchies
				/// The previous version used a complex method of identifier assignment and rewrite with negative values
				epoch.set(FieldNames.FIELD_NAME, "Epoch: " + title);
				epoch.set(FieldNames.FIELD_LOCATION, rootLoc);
				epoch.set(FieldNames.FIELD_TYPE, (alignmentScore < 0 ? EventEnumType.DESTABILIZE : EventEnumType.STABLIZE));
				epoch.set(FieldNames.FIELD_ALIGNMENT, alignment);
				epoch.set(FieldNames.FIELD_PARENT_ID, rootEvt.get(FieldNames.FIELD_ID));
				epoch.set("epoch", true);
				long startTimeMS = ((Date)lastEpoch.get("eventEnd")).getTime();
				epoch.set("eventStart", new Date(startTimeMS));
				epoch.set("eventEnd", new Date(startTimeMS + (OlioUtil.YEAR * increment)));
				
				logger.info("Epoch: " + alignment.toString() + " " + title + " takes place between " + CalendarUtil.exportDateAsString(epoch.get("eventStart"), "yyyy/MM/dd") + " and " + CalendarUtil.exportDateAsString(epoch.get("eventEnd"), "yyyy/MM/dd"));
				
				IOSystem.getActiveContext().getRecordUtil().updateRecord(epoch);
				List<BaseRecord> grps = Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get("population.id"))));
				for(BaseRecord loc : locs) {
					IOSystem.getActiveContext().getReader().populate(loc, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID});
					String locName = loc.get(FieldNames.FIELD_NAME) + " Population";
					BaseRecord popGrp = grps.stream().filter(f -> locName.equals((String)f.get(FieldNames.FIELD_NAME))).findFirst().get();
					
					Query pq = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON);
					pq.filterParticipation(popGrp, null, ModelNames.MODEL_CHAR_PERSON, null);
					int count = IOSystem.getActiveContext().getSearch().count(pq);
					if(count == 0){
						logger.warn("Location " + locName + " is decimated");
						if(!AttributeUtil.getAttributeValue(loc, "decimated", false)) {
							AttributeUtil.addAttribute(loc, "decimated", true);
							IOSystem.getActiveContext().getRecordUtil().updateRecord(loc.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ATTRIBUTES, FieldNames.FIELD_ORGANIZATION_ID}));
						}
					}
					else {
						AlignmentEnumType useAlignment = (rand.nextDouble() < .35 ? invertedAlignment : alignment);
						String childTitle = EpochUtil.generateEpochTitle(user, parWorld, useAlignment);
						BaseRecord childEpoch = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, plist);
						childEpoch.set(FieldNames.FIELD_LOCATION, loc);
						childEpoch.set(FieldNames.FIELD_NAME, locName + " experienced a " + useAlignment.toString() + " event: " + childTitle);
						childEpoch.set(FieldNames.FIELD_ALIGNMENT, useAlignment);
						childEpoch.set(FieldNames.FIELD_PARENT_ID, epoch.get(FieldNames.FIELD_ID));
						childEpoch.set("eventStart", epoch.get("eventStart"));
						childEpoch.set("eventEnd", epoch.get("eventEnd"));
						List<BaseRecord> lgrps = childEpoch.get("groups");
						lgrps.add(popGrp);
						logger.info((String)childEpoch.get(FieldNames.FIELD_NAME));
						IOSystem.getActiveContext().getRecordUtil().updateRecord(childEpoch);
						EvolutionUtil.evolvePopulation(user, world, childEpoch, useAlignment, popGrp, increment);
					}
	
				}
				lastEpoch = epoch;
			}
			catch(ModelNotFoundException | FactoryException | FieldException | ValueException | ModelException e) {
				logger.error(e);
			}
		}
		return epoch;
	}
}
