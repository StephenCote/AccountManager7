package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;
import org.cote.accountmanager.util.AttributeUtil;

public class EpochUtil {
	public static final Logger logger = LogManager.getLogger(EpochUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	public static String generateEpochTitle(BaseRecord user, BaseRecord world, AlignmentEnumType alignment) {
		BaseRecord dir = world.get(OlioFieldNames.FIELD_DICTIONARY);
		return generateEpochTitle(user, (long)dir.get(FieldNames.FIELD_ID), alignment);
	}
	
	public static String generateEpochTitle(BaseRecord user, long groupId, AlignmentEnumType alignment){
	
		Query bq = QueryUtil.createQuery(ModelNames.MODEL_WORD_NET, FieldNames.FIELD_GROUP_ID, groupId);
		Query advQ = new Query(bq.copyRecord());
		advQ.field(FieldNames.FIELD_TYPE, WordNetParser.adverbWordType);
		String advWord = OlioUtil.randomSelectionName(user, advQ);

		Query verQ = new Query(bq.copyRecord());
		verQ.field(FieldNames.FIELD_TYPE, WordNetParser.verbWordType);
		String verWord = OlioUtil.randomSelectionName(user, verQ);

		Query adjQ = new Query(bq.copyRecord());
		adjQ.field(FieldNames.FIELD_TYPE, WordNetParser.adjectiveWordType);
		String adjWord = OlioUtil.randomSelectionName(user, adjQ);

		Query nounQ = new Query(bq.copyRecord());
		nounQ.field(FieldNames.FIELD_TYPE, WordNetParser.nounWordType);
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
		
		BaseRecord cepoch = ctx.clock().getEpoch();
		if(cepoch != null) {
			ActionResultEnumType aet = cepoch.getEnum(FieldNames.FIELD_STATE);
			if(aet != ActionResultEnumType.COMPLETE) {
				logger.error("The current epoch " + cepoch.get(FieldNames.FIELD_NAME) + " is not marked as being complete.  This will result in inconsistent and skewed time and metric evaluations");
				return null;
			}
		}

		int alignmentScore = AlignmentEnumType.getValue(alignment);
		//AlignmentEnumType invertedAlignment = AlignmentEnumType.valueOf(-1 * alignmentScore);
		if(title == null) {
			title = EpochUtil.generateEpochTitle(ctx.getOlioUser(), ctx.getUniverse(), alignment);
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("events.path"));
		ZonedDateTime startTime = ((ZonedDateTime)lastEpoch.get(OlioFieldNames.FIELD_EVENT_END)).plusDays(1).with(LocalTime.of(0,0,0));
		logger.info("Start time: " + startTime);
		epoch = EventUtil.newEvent(ctx, rootEvt, (alignmentScore < 0 ? EventEnumType.DESTABILIZE : EventEnumType.STABLIZE), title, startTime);
		try {
			epoch.set(OlioFieldNames.FIELD_EVENT_PROGRESS, startTime);
			epoch.set(OlioFieldNames.FIELD_IN_PROGRESS, true);
			epoch.set(OlioFieldNames.FIELD_EVENT_END, startTime.plusYears(1));
			epoch.set(OlioFieldNames.FIELD_EPOCH, true);
			epoch.set(OlioFieldNames.FIELD_TIME_TYPE, TimeEnumType.YEAR);
			epoch.set(FieldNames.FIELD_ALIGNMENT, alignment);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		ctx.clock().setEpoch(epoch);
		
		for(IOlioEvolveRule r : ctx.getConfig().getEvolutionRules()) {
			r.startEpoch(ctx, epoch);
		}
		IOSystem.getActiveContext().getRecordUtil().createRecord(epoch);
		
		/// TODO: Each locations initial reactions (alignment) to the random epoch alignment
		/// should be based on a weighted average of prior aligments
		/// Differentiating entry and exit alignments should be handled through the entry/exit trait alignments in the event there are more than one of each
		///
		return epoch;
		
	}

	public static void endEpoch(OlioContext ctx) {

		if(!ctx.validateContext()) {
			logger.error("Context is not valid");
			return;
		}
		BaseRecord cepoch = ctx.clock().getEpoch();
		if(cepoch == null) {
			logger.error("Current epoch is null");
			return;
		}
		ActionResultEnumType aet = cepoch.getEnum(FieldNames.FIELD_STATE);
		if(aet != ActionResultEnumType.PENDING) {
			logger.error("The current location epoch is not in a pending state.  Therefore, no activities will take place");
			return;
		}
		try {
			cepoch.set(FieldNames.FIELD_STATE, ActionResultEnumType.COMPLETE);
			Queue.queue(cepoch.copyRecord(new String[]{FieldNames.FIELD_ID, FieldNames.FIELD_STATE}));
			Queue.processQueue();
			ctx.clock().setEvent(null);
		} catch (FieldException | ValueException | ModelNotFoundException | ClockException e) {
			logger.error(e);
		}
	}
	
	
	public static void endRealmEvent(OlioContext ctx, BaseRecord realm) {
		endEpoch(ctx, realm.get(OlioFieldNames.FIELD_CURRENT_EVENT));
		realm.setValue(OlioFieldNames.FIELD_CURRENT_EVENT, null);
		Queue.queueUpdate(realm, new String[]{OlioFieldNames.FIELD_CURRENT_EVENT});
		Queue.processQueue();
	}
	
	public static void endLocationEpoch(OlioContext ctx, BaseRecord location) {
		endEpoch(ctx, ctx.clock().getEvent());
	}
	
	protected static void endEpoch(OlioContext ctx, BaseRecord evt) {

		if(!ctx.validateContext()) {
			logger.error("Context is not valid");
			return;
		}
		
		if(evt == null) {
			logger.error("Current epoch is null");
			return;
		}

		
		ActionResultEnumType aet = ActionResultEnumType.valueOf(evt.get(FieldNames.FIELD_STATE));
		if(aet != ActionResultEnumType.PENDING) {
			logger.error("The current location epoch is not in a pending state.  Therefore, no activities will take place");
			return;
		}
		try {
			evt.set(FieldNames.FIELD_STATE, ActionResultEnumType.COMPLETE);
			Queue.queueUpdate(evt, new String[]{FieldNames.FIELD_STATE});
			Queue.processQueue();
			// ctx.setCurrentEvent(null);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public static BaseRecord startRealmEvent(OlioContext ctx, BaseRecord realm) {
		BaseRecord evt = null;
		if(!ctx.validateContext()) {
			logger.error("Context is not valid");
			return evt;
		}
		Clock clock = ctx.clock();
		BaseRecord cepoch = clock.getEpoch();
		if(cepoch == null) {
			logger.error("Current epoch is null");
			return evt;
		}
		ActionResultEnumType aet = cepoch.getEnum(FieldNames.FIELD_STATE);
		if(aet != ActionResultEnumType.PENDING) {
			logger.error("The current epoch is not in a pending state.  Therefore, no activities will take place");
			return null;
		}
		
		Clock rclock = null;
		try {
			rclock = ctx.clock().realmClock(realm);
		} catch (ClockException e) {
			logger.error(e);
		}
		if (rclock == null) {
			logger.error("Failed to get realm clock");
			return evt;
		}
		if(rclock.getEvent() != null) {
			logger.error("The current realm already has an ongoing event");
			return evt;
		}
		
		AlignmentEnumType alignment = cepoch.getEnum(FieldNames.FIELD_ALIGNMENT);
		int alignmentScore = AlignmentEnumType.getValue(alignment);
		AlignmentEnumType invertedAlignment = AlignmentEnumType.valueOf(-1 * alignmentScore);

		BaseRecord popGrp = realm.get(OlioFieldNames.FIELD_POPULATION);
		int count = OlioUtil.countPeople(popGrp);
		try {
			if(count == 0){
				logger.warn("Realm " + realm.get(FieldNames.FIELD_NAME) + " is decimated");
				if(!AttributeUtil.getAttributeValue(realm, "decimated", false)) {
					AttributeUtil.addAttribute(realm, "decimated", true);
					Queue.queue(realm.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ATTRIBUTES, FieldNames.FIELD_ORGANIZATION_ID}));
				}
			}
			else {
				AlignmentEnumType useAlignment = (rand.nextDouble() < Rules.ODDS_INVERT_ALIGNMENT ? invertedAlignment : alignment);
				String childTitle = EpochUtil.generateEpochTitle(ctx.getOlioUser(), ctx.getUniverse(), useAlignment);
				
				BaseRecord childEpoch = EventUtil.newEvent(ctx, cepoch, (alignmentScore < 0 ? EventEnumType.DESTABILIZE : EventEnumType.STABLIZE), childTitle, clock.getStart());
				childEpoch.set(OlioFieldNames.FIELD_REALM, realm);
				childEpoch.set(FieldNames.FIELD_LOCATION, realm.get(OlioFieldNames.FIELD_ORIGIN));
				childEpoch.set(OlioFieldNames.FIELD_EVENT_PROGRESS, clock.getCurrent());
				childEpoch.set(OlioFieldNames.FIELD_IN_PROGRESS, true);
				childEpoch.set(OlioFieldNames.FIELD_EVENT_END, clock.getEnd());
				childEpoch.set(FieldNames.FIELD_ALIGNMENT, alignment);
				childEpoch.set(OlioFieldNames.FIELD_TIME_TYPE, TimeEnumType.YEAR);

				List<BaseRecord> lgrps = childEpoch.get(FieldNames.FIELD_GROUPS);
				lgrps.add(popGrp);
				
				IOSystem.getActiveContext().getRecordUtil().updateRecord(childEpoch);
				rclock.setEvent(childEpoch);
				
				realm.setValue(OlioFieldNames.FIELD_CURRENT_EVENT, childEpoch);
				Queue.queueUpdate(realm, new String[] {OlioFieldNames.FIELD_CURRENT_EVENT});
				
				evt = childEpoch;
				
				for(IOlioEvolveRule r : ctx.getConfig().getEvolutionRules()) {
					r.startRealmEvent(ctx, realm);
				}
			}
		}
		catch (FieldException | ValueException | ModelNotFoundException | ModelException | ClockException e) {
			logger.error(e);
		}
		
		Queue.processQueue();
		
		return evt;
	}
	
	/// The current setup is to break up events by global epoch, realm events, and realm increments
	/// OLD
	/// The original implementation didn't discretely break up events into time-based increments
	/// The intent is to let the rules determine the increment, or leave it at the locationEpoch level
	
	public static BaseRecord startRealmIncrement(OlioContext ctx, BaseRecord realm) {
		BaseRecord inc = null;
		
		for(IOlioEvolveRule rule : ctx.getConfig().getEvolutionRules()) {
			inc = rule.startRealmIncrement(ctx, realm);
			if(inc != null) {
				realm.setValue(OlioFieldNames.FIELD_CURRENT_INCREMENT, inc);
				Queue.queueUpdate(realm, new String[] {OlioFieldNames.FIELD_CURRENT_INCREMENT});
				break;
			}
		}
		Queue.processQueue();
		return inc;
	}
	
	public static BaseRecord continueRealmIncrement(OlioContext ctx, BaseRecord realm) {
		BaseRecord inc = null;
		
		for(IOlioEvolveRule rule : ctx.getConfig().getEvolutionRules()) {
			inc = rule.continueRealmIncrement(ctx, realm);
			if(inc != null) {
				break;
			}
		}
		Queue.processQueue();
		return inc;
	}

	
	public static BaseRecord endRealmIncrement(OlioContext ctx, BaseRecord realm) {
		BaseRecord inc = null;
		for(IOlioEvolveRule rule : ctx.getConfig().getEvolutionRules()) {
			rule.endRealmIncrement(ctx, realm);
		}
		Queue.processQueue();
		return inc;
	}

	
	/// TODO: Split all of this up to better allow for intra cycle and asynchronous rule evaluation
	/// GenerateEpoch = startEpoch, continueEpoch, evolutions (years), 12 month rule evaluation, daily evaluation, <...updateQueue>, stopEpoch  
	/// The process steps can run as deep or as long as desired, and the queue will be updated automatically at the end of each month, and the epoch
	/// Or, when running asynchronously, updated at the end of the next step.
	/*
	public static BaseRecord generateEpoch(OlioContext ctx, int evolutions) {
		BaseRecord epoch = null;
		int increment = 1;
		BaseRecord world = ctx.getWorld();
		IOSystem.getActiveContext().getReader().populate(world, 2);
		BaseRecord parWorld = world.get(OlioFieldNames.FIELD_BASIS);
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
		logger.info("Root location: " + rootLoc.get(FieldNames.FIELD_NAME));
		// BaseRecord[] locs = GeoLocationUtil.getRegionLocations(ctx);
		if(ctx.getLocations().length == 0) {
			logger.error("Failed to find child locations");
		}
		
		for(int i = 0; i < evolutions; i++) {
		
			AlignmentEnumType alignment = OlioUtil.getRandomAlignment();
			int alignmentScore = AlignmentEnumType.getValue(alignment);
			AlignmentEnumType invertedAlignment = AlignmentEnumType.valueOf(-1 * alignmentScore);
			
			String title = EpochUtil.generateEpochTitle(ctx.getOlioUser(), parWorld, alignment);
			
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, world.get("events.path"));
			try {
				epoch = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_EVENT, ctx.getOlioUser(), null, plist);
				/// TODO: Need a way to bulk-add hierarchies
				/// The previous version used a complex method of identifier assignment and rewrite with negative values
				epoch.set(FieldNames.FIELD_NAME, "Epoch: " + title);
				epoch.set(FieldNames.FIELD_LOCATION, rootLoc);
				epoch.set(FieldNames.FIELD_TYPE, (alignmentScore < 0 ? EventEnumType.DESTABILIZE : EventEnumType.STABLIZE));
				epoch.set(FieldNames.FIELD_ALIGNMENT, alignment);
				epoch.set(FieldNames.FIELD_PARENT_ID, rootEvt.get(FieldNames.FIELD_ID));
				epoch.set(OlioFieldNames.FIELD_EPOCH, true);
				long startTimeMS = ((Date)lastEpoch.get(OlioFieldNames.FIELD_EVENT_END)).getTime();
				epoch.set(OlioFieldNames.FIELD_EVENT_START, new Date(startTimeMS));
				epoch.set(OlioFieldNames.FIELD_EVENT_END, new Date(startTimeMS + (OlioUtil.YEAR * increment)));
				
				logger.info("Epoch: " + alignment.toString() + " " + title + " takes place between " + CalendarUtil.exportDateAsString(epoch.get(OlioFieldNames.FIELD_EVENT_START), "yyyy/MM/dd") + " and " + CalendarUtil.exportDateAsString(epoch.get(OlioFieldNames.FIELD_EVENT_END), "yyyy/MM/dd"));
				
				IOSystem.getActiveContext().getRecordUtil().updateRecord(epoch);
				
				ctx.setCurrentEpoch(epoch);
				
				// List<BaseRecord> grps = Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get(OlioFieldNames.FIELD_POPULATION_ID))));
				for(BaseRecord loc : ctx.getLocations()) {
					IOSystem.getActiveContext().getReader().populate(loc, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID});
					
					String locName = loc.get(FieldNames.FIELD_NAME) + " Population";
					//BaseRecord popGrp = grps.stream().filter(f -> locName.equals((String)f.get(FieldNames.FIELD_NAME))).findFirst().get();
					BaseRecord popGrp = ctx.getPopulationGroup(loc, "Population");

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
						String childTitle = EpochUtil.generateEpochTitle(ctx.getOlioUser(), parWorld, useAlignment);
						BaseRecord childEpoch = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_EVENT, ctx.getOlioUser(), null, plist);
						childEpoch.set(FieldNames.FIELD_LOCATION, loc);
						childEpoch.set(FieldNames.FIELD_NAME, locName + " experienced a " + useAlignment.toString() + " event: " + childTitle);
						childEpoch.set(FieldNames.FIELD_ALIGNMENT, useAlignment);
						childEpoch.set(FieldNames.FIELD_PARENT_ID, epoch.get(FieldNames.FIELD_ID));
						childEpoch.set(OlioFieldNames.FIELD_EVENT_START, epoch.get(OlioFieldNames.FIELD_EVENT_START));
						childEpoch.set(OlioFieldNames.FIELD_EVENT_END, epoch.get(OlioFieldNames.FIELD_EVENT_END));
						childEpoch.set(FieldNames.FIELD_STATE, ActionResultEnumType.PENDING);
						List<BaseRecord> lgrps = childEpoch.get(FieldNames.FIELD_GROUPS);
						lgrps.add(popGrp);
						logger.info((String)childEpoch.get(FieldNames.FIELD_NAME));
						IOSystem.getActiveContext().getRecordUtil().updateRecord(childEpoch);
						ctx.setCurrentEvent(childEpoch);
						ctx.setCurrentLocation(loc);
						//EvolutionUtil.evolvePopulation(ctx.getOlioUser(), world, childEpoch, useAlignment, popGrp, increment);
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
	*/
}
