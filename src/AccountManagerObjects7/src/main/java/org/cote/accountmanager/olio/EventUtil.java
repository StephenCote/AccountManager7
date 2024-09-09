package org.cote.accountmanager.olio;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class EventUtil {
	public static final Logger logger = LogManager.getLogger(EventUtil.class);
	
	public static BaseRecord getEvent(OlioContext ctx, BaseRecord parent, String name, EventEnumType type) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_GROUP_ID, parent.get(FieldNames.FIELD_GROUP_ID));
		if(type != EventEnumType.UNKNOWN) {
			q.field(FieldNames.FIELD_TYPE, type);
		}
		q.getRequest().addAll(Arrays.asList(new String[] {"location", "eventStart", "eventProgress", "eventEnd"}));
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	public static BaseRecord[] getChildEvents(BaseRecord world, BaseRecord parentEvent, EventEnumType eventType) {
		return getChildEvents(world, parentEvent, null, null, null, TimeEnumType.UNKNOWN, eventType);
	}
	
	public static BaseRecord[] getChildEvents(BaseRecord world, BaseRecord parentEvent, BaseRecord realm, BaseRecord location, String name, TimeEnumType timeType, EventEnumType eventType) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, world.get("events.id"));
		if(eventType != EventEnumType.UNKNOWN) {
			q.field(FieldNames.FIELD_TYPE, eventType);
		}
		if(timeType != TimeEnumType.UNKNOWN) {
			q.field("timeType", timeType);
		}
		if(name != null) {
			q.field(FieldNames.FIELD_NAME, name);
		}
		if(location != null) {
			q.field(FieldNames.FIELD_LOCATION, location.copyRecord(new String[] {FieldNames.FIELD_ID}));
		}
		if(realm != null) {
			q.field(FieldNames.FIELD_REALM, realm.copyRecord(new String[] {FieldNames.FIELD_ID}));
		}
		q.field(FieldNames.FIELD_PARENT_ID, parentEvent.get(FieldNames.FIELD_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TYPE, FieldNames.FIELD_LOCATION, FieldNames.FIELD_STATE, "eventStart", "eventProgress", "eventEnd"});
		q.setRequestRange(0L, 100);
		// q.setCache(false);

		try {
			q.set(FieldNames.FIELD_SORT_FIELD, "eventStart");
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return IOSystem.getActiveContext().getSearch().findRecords(q);
	}

	public static BaseRecord[] getEvents(BaseRecord world, BaseRecord person, String[] fieldNames, EventEnumType eventType) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, world.get("events.id"));
		if(eventType != EventEnumType.UNKNOWN) {
			q.field(FieldNames.FIELD_TYPE, eventType);
		}
		//q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TYPE});
		q.getRequest().addAll(Arrays.asList(new String[] {"location", "eventStart", "eventProgress", "eventEnd"}));
		QueryUtil.filterParticipant(q, ModelNames.MODEL_EVENT, fieldNames, person, null);
		q.setRequestRange(0L, 100);
		// q.setCache(false);

		try {
			q.set(FieldNames.FIELD_SORT_FIELD, "eventStart");
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return IOSystem.getActiveContext().getSearch().findRecords(q);
	}
	
	public static BaseRecord newEvent(
		OlioContext ctx, BaseRecord parentEvent, EventEnumType type, String name, ZonedDateTime startTime
	) {
		return newEvent(ctx, parentEvent, type, name, startTime, null, null, null, false);
	}
	public static BaseRecord newEvent(
			OlioContext ctx, BaseRecord parentEvent, EventEnumType type, String name, ZonedDateTime startTime,
			BaseRecord[] actors, BaseRecord[] participants, BaseRecord[] influencers,
			boolean enqueue
		) {
		BaseRecord evt = null;
		ParameterList elist = ParameterList.newParameterList("path", ctx.getWorld().get("events.path"));
		try {
			evt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, ctx.getOlioUser(), null, elist);
			/// TODO: Need a way to bulk-add hierarchies
			/// The previous version used a complex method of identifier assignment and rewrite with negative values
			evt.set(FieldNames.FIELD_NAME, name);
			evt.set(FieldNames.FIELD_LOCATION, parentEvent.get(FieldNames.FIELD_LOCATION));
			evt.set(FieldNames.FIELD_STATE, ActionResultEnumType.PENDING);
			if(actors != null && actors.length > 0) {
				List<BaseRecord> acts = evt.get("actors");
				acts.addAll(Arrays.asList(actors));
			}
			if(participants != null && participants.length > 0) {
				List<BaseRecord> parts = evt.get("participants");
				parts.addAll(Arrays.asList(participants));
			}
			if(influencers != null && influencers.length > 0) {
				List<BaseRecord> inf = evt.get("influencers");
				inf.addAll(Arrays.asList(influencers));
			}
			evt.set(FieldNames.FIELD_TYPE, type);
			evt.set(FieldNames.FIELD_PARENT_ID, parentEvent.get(FieldNames.FIELD_ID));
			evt.set("eventStart", startTime);
			evt.set("eventProgress", startTime);
			evt.set("eventEnd", startTime);
			if(enqueue) {
				ctx.queue(evt);
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return evt;
	}

	public static BaseRecord getRootEvent(OlioContext ctx) {
		return getRootEvent(ctx.getOlioUser(), ctx.getWorld());
	}

	public static BaseRecord getRootEvent(BaseRecord user, BaseRecord world) {
		IOSystem.getActiveContext().getReader().populate(world, 2);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, (long)world.get("events.id"));
		q.field(FieldNames.FIELD_PARENT_ID, 0L);
		q.getRequest().addAll(Arrays.asList(new String[] {"location", "eventStart", "eventProgress", "eventEnd"}));
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	public static BaseRecord[] getBaseRegionEvents(BaseRecord user, BaseRecord world) {
		BaseRecord root = getRootEvent(user, world);
		BaseRecord[] evts = new BaseRecord[0];
		if(root != null) {
			IOSystem.getActiveContext().getReader().populate(world, 2);
			Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, (long)world.get("events.id"));
			q.field(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_TYPE, EventEnumType.INCEPT);
			q.getRequest().addAll(Arrays.asList(new String[] {"location", "eventStart", "eventProgress", "eventEnd"}));
			evts = IOSystem.getActiveContext().getSearch().findRecords(q);
		}
		return evts;
	}
	public static BaseRecord getLastEvent(BaseRecord user, BaseRecord world, BaseRecord location) {
		return getLastEvent(user, world, location, EventEnumType.UNKNOWN, TimeEnumType.UNKNOWN, ActionResultEnumType.UNKNOWN, true);
	}
	public static BaseRecord getLastEvent(BaseRecord user, BaseRecord world, BaseRecord location, EventEnumType eventType, TimeEnumType timeType, ActionResultEnumType state, boolean epochChild) {
		BaseRecord lastEpoch = getLastEpochEvent(user, world);
		if(lastEpoch == null && epochChild) {
			return null;
		}
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, world.get("events.id"));
		if(epochChild && lastEpoch != null) {
			q.field(FieldNames.FIELD_PARENT_ID, lastEpoch.get(FieldNames.FIELD_ID));
		}
		if(location != null) {
			q.field(FieldNames.FIELD_LOCATION, location.copyRecord(new String[] {FieldNames.FIELD_ID}));
		}
		if(timeType != TimeEnumType.UNKNOWN) {
			q.field("timeType", timeType);
		}
		if(state != ActionResultEnumType.UNKNOWN) {
			q.field(FieldNames.FIELD_STATE, state);
		}
		if(eventType != EventEnumType.UNKNOWN) {
			q.field(FieldNames.FIELD_TYPE, eventType);
		}
		BaseRecord lastEvt = null;
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, "eventStart");
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			q.getRequest().addAll(Arrays.asList(new String[] {"location", "eventStart", "eventProgress", "eventEnd"}));
			q.setRequestRange(0L, 1);
			lastEvt = IOSystem.getActiveContext().getSearch().findRecord(q);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return lastEvt;
	}
	public static BaseRecord getLastEpochEvent(OlioContext ctx) {
		return getLastEpochEvent(ctx.getOlioUser(), ctx.getWorld());
	}
	public static BaseRecord getLastEpochEvent(BaseRecord user, BaseRecord world) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, world.get("events.id"));
		q.field("epoch", true);
		BaseRecord epoch = null;
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, "eventStart");
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			q.requestCommonFields();
			q.getRequest().addAll(Arrays.asList(new String[] {"state", "location", "eventStart", "eventProgress", "eventEnd"}));
			q.setRequestRange(0L, 1);
			epoch = IOSystem.getActiveContext().getSearch().findRecord(q);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return epoch;
	}
	public static void addProgressMS(BaseRecord event, long ms) {
		ZonedDateTime prog = event.get("eventProgress");
		event.setValue("eventProgress", prog.plus(ms, ChronoUnit.MILLIS));
	}
	public static void edgeSecondsUntilEnd(BaseRecord event, long seconds) {
		ZonedDateTime prog = event.get("eventProgress");
		try {
			event.set("eventEnd", prog.plusSeconds(seconds));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	
	public static void edgeHour(BaseRecord event) {
		ZonedDateTime start = event.get("eventStart");
		ZonedDateTime end = event.get("eventEnd");
		ZonedDateTime prog = event.get("eventProgress");

		try {
			event.set("eventStart", prog.withMinute(0).withSecond(0));
			event.set("eventProgress", event.get("eventStart"));
			event.set("eventEnd", prog.withMinute(59).withSecond(59));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	public static void edgeDay(BaseRecord event) {
		ZonedDateTime start = event.get("eventStart");
		ZonedDateTime end = event.get("eventEnd");
		ZonedDateTime prog = event.get("eventProgress");

		try {
			event.set("eventStart", prog.with(LocalTime.of(0, 0, 0)));
			event.set("eventProgress", event.get("eventStart"));
			event.set("eventEnd", prog.with(LocalTime.of(23, 59, 59)));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	public static void edgeMonth(BaseRecord event) {
		ZonedDateTime start = event.get("eventStart");
		ZonedDateTime end = event.get("eventEnd");

		try {
			event.set("eventStart", start.withDayOfMonth(1).with(LocalTime.of(0, 0, 0)));
			event.set("eventProgress", event.get("eventStart"));
			event.set("eventEnd", end.withDayOfMonth(end.getMonth().length(end.toLocalDate().isLeapYear())).with(LocalTime.of(23, 59, 59)));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	public static void edgeTimes(BaseRecord event) {
		ZonedDateTime start = event.get("eventStart");
		ZonedDateTime end = event.get("eventEnd");
		try {
			event.set("eventStart", start.with(LocalTime.of(0, 0, 0)));
			event.set("eventProgress", event.get("eventStart"));
			event.set("eventEnd", end.with(LocalTime.of(23, 59, 59)));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	
	public static void edgeEndOfYear(BaseRecord event) {
		ZonedDateTime start = event.get("eventStart");
		try {
			event.set("eventEnd", start.withYear(start.getYear()).withMonth(12).withDayOfMonth(31).with(LocalTime.of(23, 59, 59)));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	/// Given the current progress from a parent event, return an event representing the next time increment
	/// The 24HourRule is to move into the next TimeEnumType.HOUR, advancing as needed the DAY and MONTH
	/// 
	public static BaseRecord findNextIncrement(OlioContext context, BaseRecord parentEvent, TimeEnumType incrementType)  {
		BaseRecord outRec = null;
		try {
			ZonedDateTime prog = parentEvent.get("eventProgress");
			ZonedDateTime end = parentEvent.get("eventEnd");
			int imonth = prog.getMonthValue();
			if(incrementType == TimeEnumType.HOUR) {
				prog = prog.plusHours(1);
			}
			else if(incrementType == TimeEnumType.DAY) {
				prog = prog.plusDays(1);
			}
			else if(incrementType == TimeEnumType.MONTH) {
				prog = prog.plusMonths(1);
			}
			
			if(prog.toEpochSecond() >= end.toEpochSecond()) {
				logger.warn("Reached the end of the epoch");
				return null;
			}
			if(prog.getMonthValue() < imonth) {
				logger.warn("Reached the end of the epoch (#2)");
				return null;
			}
			
			BaseRecord year = getCreateIncrement(context, parentEvent, prog, TimeEnumType.YEAR);
			if(year == null) {
				logger.error("Failed to find or create yearly increment");
				return null;
			}
			BaseRecord month = getCreateIncrement(context, year, prog, TimeEnumType.MONTH);
			if(month == null) {
				logger.error("Failed to find or create monthly increment");
				return null;
			}
			if(incrementType == TimeEnumType.MONTH) {
				outRec = month;
			}
			else if(incrementType == TimeEnumType.DAY || incrementType == TimeEnumType.HOUR) {
			
				BaseRecord day = getCreateIncrement(context, month, prog, TimeEnumType.DAY);
				if(day == null) {
					logger.error("Failed to find or create daily increment");
					return null;
				}
				if(incrementType == TimeEnumType.DAY) {
					outRec = day;
				}
				else {
					BaseRecord hour = getCreateIncrement(context, day, prog, TimeEnumType.HOUR);
					if(hour == null) {
						logger.error("Failed to find or create hourly increment");
						return null;
					}
					outRec = hour;
				}
			}
			else {
				logger.error("Unhandled increment type: " + incrementType);
				return null;
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return outRec;
	}
	
	public static BaseRecord getCreateIncrement(OlioContext context, BaseRecord parentEvent, ZonedDateTime time, TimeEnumType tet) throws FieldException, ValueException, ModelNotFoundException {
		String name = getTimeName(time, tet);
		BaseRecord[] cevts = EventUtil.getChildEvents(context.getWorld(), parentEvent, null, null, name, tet, EventEnumType.UNKNOWN);
		if(cevts.length > 0) {
			parentEvent.set("eventProgress", time);
			context.queue(parentEvent.copyRecord(new String[] {FieldNames.FIELD_ID, "eventProgress"}));
			return cevts[0];
		}
		BaseRecord evt = EventUtil.newEvent(context, parentEvent, EventEnumType.PERIOD, name, time);
		evt.set("timeType", tet);
		parentEvent.set("eventProgress", time);

		if(tet == TimeEnumType.MONTH) {
			EventUtil.edgeMonth(evt);
		}
		else if(tet == TimeEnumType.DAY) {
			EventUtil.edgeDay(evt);
		}
		else if(tet == TimeEnumType.HOUR) {
			EventUtil.edgeHour(evt);
		}
		IOSystem.getActiveContext().getRecordUtil().createRecord(evt);
		context.queue(parentEvent.copyRecord(new String[] {FieldNames.FIELD_ID, "eventProgress"}));
		return evt;
	}
	public static String getTimeName(ZonedDateTime prog, TimeEnumType tet) {
		StringBuilder buff = new StringBuilder();
		buff.append(prog.getYear() + "/" + String.format("%02d", prog.getMonthValue()));
		if(tet == TimeEnumType.DAY || tet == TimeEnumType.HOUR) {
			 buff.append("/" + String.format("%02d", prog.getDayOfMonth()));
		}
		if(tet == TimeEnumType.HOUR) {
			 buff.append(" " + String.format("%02d", prog.getHour()) + ":00");
		}

		return buff.toString();
	}
	public static String getChildTimeName(BaseRecord parentEvent) {
		TimeEnumType ptet = TimeEnumType.valueOf(parentEvent.get("timeType"));
		TimeEnumType tet = getChildTime(ptet);
		ZonedDateTime prog = parentEvent.get("eventProgress");
		return EventUtil.getTimeName(prog, tet);
	}
	
	public static TimeEnumType getChildTime(TimeEnumType time) {
		TimeEnumType otime = TimeEnumType.UNKNOWN;
		switch(time) {
			case YEAR:
				otime = TimeEnumType.MONTH;
				break;
			case MONTH:
				otime = TimeEnumType.DAY;
				break;
			case DAY:
				otime = TimeEnumType.HOUR;
				break;
			default:
				logger.error("Unhandled time type: " + time.toString());
				return otime;
		}
		return otime;
	}
	

}
