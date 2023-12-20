package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
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
	
	public static BaseRecord startEpoch(OlioContext ctx) {
		return startEpoch(ctx, OlioUtil.getRandomAlignment(), null);
	}
	public static BaseRecord startEpoch(OlioContext ctx, AlignmentEnumType alignment, String title) {
		
		BaseRecord epoch = null;
		if(!ctx.validateContext()) {
			return epoch;
		}
		
		BaseRecord rootEvt = EventUtil.getRootEvent(ctx);
		BaseRecord lastEpoch = EventUtil.getLastEpochEvent(ctx);
		if(lastEpoch == null) {
			lastEpoch = rootEvt;
		}
		BaseRecord rootLoc = GeoLocationUtil.getRootLocation(ctx);
		if(rootLoc == null){
			logger.error("Failed to find root location");
			return null;
		}
		
		if(ctx.getCurrentEpoch() != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(ctx.getCurrentEpoch().get(FieldNames.FIELD_STATE));
			if(aet != ActionResultEnumType.COMPLETE) {
				logger.error("The current epoch " + ctx.getCurrentEpoch().get(FieldNames.FIELD_NAME) + " is not marked as being complete.  This will result in inconsistent and skewed time and metric evaluations");
				return null;
			}
		}
		

		int alignmentScore = AlignmentEnumType.getValue(alignment);
		//AlignmentEnumType invertedAlignment = AlignmentEnumType.valueOf(-1 * alignmentScore);
		if(title == null) {
			title = EpochUtil.generateEpochTitle(ctx.getUser(), ctx.getUniverse(), alignment);
		}
		
		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("events.path"));
		long startTimeMS = ((Date)lastEpoch.get("eventEnd")).getTime();

		epoch = EventUtil.newEvent(ctx.getUser(), ctx.getWorld(), rootEvt, (alignmentScore < 0 ? EventEnumType.DESTABILIZE : EventEnumType.STABLIZE), title, startTimeMS);
		try {
			epoch.set("eventEnd", new Date(startTimeMS + (OlioUtil.YEAR)));
			epoch.set("epoch", true);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		logger.info("Epoch " + title + " begins");
		IOSystem.getActiveContext().getRecordUtil().createRecord(epoch);
		ctx.setCurrentEpoch(epoch);
		/// TODO: Each locations initial reactions (alignment) to the random epoch alignment
		/// should be based on a weighted average of prior aligments
		/// Differentiating entry and exit alignments should be handled through the entry/exit trait alignments in the event there are more than one of each
		///
		return epoch;
		
	}
	
	public static BaseRecord startLocationEvent(OlioContext ctx, BaseRecord location) {
		BaseRecord evt = null;
		if(!ctx.validateContext()) {
			logger.error("Context is not valid");
			return evt;
		}
		if(ctx.getCurrentEpoch() == null) {
			logger.error("Current epoch is null");
			return evt;
		}
		ActionResultEnumType aet = ActionResultEnumType.valueOf(ctx.getCurrentEpoch().get(FieldNames.FIELD_STATE));
		if(aet != ActionResultEnumType.PENDING) {
			logger.error("The current epoch is not in a pending state.  Therefore, no activities will take place");
			return null;
		}
		
		AlignmentEnumType alignment = AlignmentEnumType.valueOf(ctx.getCurrentEpoch().get(FieldNames.FIELD_ALIGNMENT));
		int alignmentScore = AlignmentEnumType.getValue(alignment);
		AlignmentEnumType invertedAlignment = AlignmentEnumType.valueOf(-1 * alignmentScore);

		BaseRecord popGrp = ctx.getPopulationGroup(location, "Population");
		int count = OlioUtil.countPeople(popGrp);
		try {
			if(count == 0){
				logger.warn("Location " + location.get(FieldNames.FIELD_NAME) + " is decimated");
				if(!AttributeUtil.getAttributeValue(location, "decimated", false)) {
					AttributeUtil.addAttribute(location, "decimated", true);
					ctx.queue(location.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ATTRIBUTES, FieldNames.FIELD_ORGANIZATION_ID}));
				}
			}
			else {
				AlignmentEnumType useAlignment = (rand.nextDouble() < Rules.ODDS_INVERT_ALIGNMENT ? invertedAlignment : alignment);
				String childTitle = EpochUtil.generateEpochTitle(ctx.getUser(), ctx.getUniverse(), useAlignment);
				
				BaseRecord childEpoch = EventUtil.newEvent(ctx.getUser(), ctx.getWorld(), ctx.getCurrentEpoch(), (alignmentScore < 0 ? EventEnumType.DESTABILIZE : EventEnumType.STABLIZE), childTitle, ((Date)ctx.getCurrentEpoch().get("eventStart")).getTime());
	
					childEpoch.set("eventEnd", ctx.getCurrentEpoch().get("eventEnd"));
				
				
				List<BaseRecord> lgrps = childEpoch.get("groups");
				lgrps.add(popGrp);
				logger.info("Location " + location.get(FieldNames.FIELD_NAME) + " begins " + (String)childEpoch.get(FieldNames.FIELD_NAME));
				IOSystem.getActiveContext().getRecordUtil().updateRecord(childEpoch);
				ctx.setCurrentEvent(childEpoch);
				ctx.setCurrentLocation(location);
				evt = childEpoch;
			}
		}
		catch (FieldException | ValueException | ModelNotFoundException | ModelException e) {
			logger.error(e);
		}
		
		return evt;
	}
	
	/// TODO: Split all of this up to better allow for intra cycle and asynchronous rule evaluation
	/// GenerateEpoch = startEpoch, continueEpoch, evolutions (years), 12 month rule evaluation, daily evaluation, <...updateQueue>, stopEpoch  
	/// The process steps can run as deep or as long as desired, and the queue will be updated automatically at the end of each month, and the epoch
	/// Or, when running asynchronously, updated at the end of the next step.
	public static BaseRecord generateEpoch(OlioContext ctx, int evolutions) {
		BaseRecord epoch = null;
		int increment = 1;
		BaseRecord world = ctx.getWorld();
		IOSystem.getActiveContext().getReader().populate(world, 2);
		BaseRecord parWorld = world.get("basis");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		BaseRecord rootEvt = EventUtil.getRootEvent(ctx);
		BaseRecord lastEpoch = EventUtil.getLastEpochEvent(ctx);
		if(lastEpoch == null) {
			lastEpoch = rootEvt;
		}
		BaseRecord rootLoc = GeoLocationUtil.getRootLocation(ctx);
		if(rootLoc == null){
			logger.error("Failed to find root location");
			return null;
		}
		logger.info("Root location: " + rootLoc.get("name"));
		// BaseRecord[] locs = GeoLocationUtil.getRegionLocations(ctx);
		if(ctx.getLocations().length == 0) {
			logger.error("Failed to find child locations");
		}
		
		for(int i = 0; i < evolutions; i++) {
		
			AlignmentEnumType alignment = OlioUtil.getRandomAlignment();
			int alignmentScore = AlignmentEnumType.getValue(alignment);
			AlignmentEnumType invertedAlignment = AlignmentEnumType.valueOf(-1 * alignmentScore);
			
			String title = EpochUtil.generateEpochTitle(ctx.getUser(), parWorld, alignment);
			
			ParameterList plist = ParameterList.newParameterList("path", world.get("events.path"));
			try {
				epoch = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, ctx.getUser(), null, plist);
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
				
				ctx.setCurrentEpoch(epoch);
				
				// List<BaseRecord> grps = Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get("population.id"))));
				for(BaseRecord loc : ctx.getLocations()) {
					IOSystem.getActiveContext().getReader().populate(loc, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID});
					
					String locName = loc.get(FieldNames.FIELD_NAME) + " Population";
					//BaseRecord popGrp = grps.stream().filter(f -> locName.equals((String)f.get(FieldNames.FIELD_NAME))).findFirst().get();
					BaseRecord popGrp = ctx.getPopulationGroup(loc, "Population");
					/*
					Query pq = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON);
					pq.filterParticipation(popGrp, null, ModelNames.MODEL_CHAR_PERSON, null);
					int count = IOSystem.getActiveContext().getSearch().count(pq);
					*/
					int count = OlioUtil.countPeople(popGrp);
					if(count == 0){
						logger.warn("Location " + locName + " is decimated");
						if(!AttributeUtil.getAttributeValue(loc, "decimated", false)) {
							AttributeUtil.addAttribute(loc, "decimated", true);
							IOSystem.getActiveContext().getRecordUtil().updateRecord(loc.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ATTRIBUTES, FieldNames.FIELD_ORGANIZATION_ID}));
						}
					}
					else {
						AlignmentEnumType useAlignment = (rand.nextDouble() < .35 ? invertedAlignment : alignment);
						String childTitle = EpochUtil.generateEpochTitle(ctx.getUser(), parWorld, useAlignment);
						BaseRecord childEpoch = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, ctx.getUser(), null, plist);
						childEpoch.set(FieldNames.FIELD_LOCATION, loc);
						childEpoch.set(FieldNames.FIELD_NAME, locName + " experienced a " + useAlignment.toString() + " event: " + childTitle);
						childEpoch.set(FieldNames.FIELD_ALIGNMENT, useAlignment);
						childEpoch.set(FieldNames.FIELD_PARENT_ID, epoch.get(FieldNames.FIELD_ID));
						childEpoch.set("eventStart", epoch.get("eventStart"));
						childEpoch.set("eventEnd", epoch.get("eventEnd"));
						childEpoch.set(FieldNames.FIELD_STATE, ActionResultEnumType.PENDING);
						List<BaseRecord> lgrps = childEpoch.get("groups");
						lgrps.add(popGrp);
						logger.info((String)childEpoch.get(FieldNames.FIELD_NAME));
						IOSystem.getActiveContext().getRecordUtil().updateRecord(childEpoch);
						ctx.setCurrentEvent(childEpoch);
						ctx.setCurrentLocation(loc);
						//EvolutionUtil.evolvePopulation(ctx.getUser(), world, childEpoch, useAlignment, popGrp, increment);
						EvolutionUtil.evolvePopulation(ctx);
						childEpoch.set(FieldNames.FIELD_STATE, ActionResultEnumType.COMPLETE);
						IOSystem.getActiveContext().getRecordUtil().updateRecord(childEpoch.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_STATE}));
					}
	
				}
				lastEpoch = epoch;
				epoch.set(FieldNames.FIELD_STATE, ActionResultEnumType.COMPLETE);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(epoch.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_STATE}));
			}
			catch(ModelNotFoundException | FactoryException | FieldException | ValueException | ModelException e) {
				logger.error(e);
			}
		}
		return epoch;
	}
}
