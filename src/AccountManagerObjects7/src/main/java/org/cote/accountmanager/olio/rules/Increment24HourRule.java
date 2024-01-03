package org.cote.accountmanager.olio.rules;

import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class Increment24HourRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(Increment24HourRule.class);
	private TimeEnumType incrementType = TimeEnumType.HOUR;
	
	
	
	public TimeEnumType getIncrementType() {
		return incrementType;
	}
	public void setIncrementType(TimeEnumType incrementType) {
		this.incrementType = incrementType;
	}
	private void edgeHour(BaseRecord event) {
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
	private void edgeDay(BaseRecord event) {
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
	private void edgeMonth(BaseRecord event) {
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
	private void edgeTimes(BaseRecord event) {
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
	@Override
	public void startEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		edgeTimes(epoch);

		ZonedDateTime start = epoch.get("eventStart");
		ZonedDateTime end = epoch.get("eventEnd");
		logger.info("Begin epoch: " + epoch.get(FieldNames.FIELD_NAME) + " at " + start);
		/// Move the end of the epoch to the end of the year
		
		try {
			epoch.set("eventEnd", start.withYear(start.getYear()).withMonth(12).withDayOfMonth(31).with(LocalTime.of(23, 59, 59)));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
	}
	
	@Override
	public void continueEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		logger.info("Continuing epoch: " + epoch.get(FieldNames.FIELD_NAME) + " at " + epoch.get("eventProgress"));
	}

	@Override
	public void endEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}



	@Override
	public void beginEvolution(OlioContext context) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void startLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		// TODO Auto-generated method stub
		// logger.info("Begin location epoch: " + epoch.get(FieldNames.FIELD_NAME));
		edgeTimes(epoch);
		logger.info("Location " + location.get(FieldNames.FIELD_NAME) + " begins " + (String)epoch.get(FieldNames.FIELD_NAME));
		
	}

	@Override
	public void continueLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endLocationEpoch(OlioContext context, BaseRecord previousEpoch, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BaseRecord startIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		BaseRecord rec = continueIncrement(context, locationEpoch);
		if(rec != null) {
			logger.warn("Returning current pending increment.");
			return rec;
		}
		return nextIncrement(context, locationEpoch);

	}

	@Override
	public BaseRecord continueIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		// logger.info(locationEpoch.toFullString());
		BaseRecord rec = EventUtil.getLastEvent(context.getUser(), context.getWorld(), locationEpoch.get("location"), EventEnumType.PERIOD, incrementType, ActionResultEnumType.PENDING, false); 
		if(rec != null) {
			return rec;
			/*
			ActionResultEnumType aet = ActionResultEnumType.valueOf(rec.get(FieldNames.FIELD_STATE));
			if(aet == ActionResultEnumType.PENDING) {
				return rec;
			}
			*/
		}
		return null;
	}

	@Override
	public void endIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord currentIncrement) {
		// TODO Auto-generated method stub
		BaseRecord rec = currentIncrement;
		if(currentIncrement == null) {
			rec = continueIncrement(context, locationEpoch);
		}
		if(rec == null) {
			logger.warn("Current increment was not found");
			return;
		}
		ActionResultEnumType aet = ActionResultEnumType.valueOf(rec.get(FieldNames.FIELD_STATE));
		if(aet != ActionResultEnumType.PENDING) {
			logger.error("Increment is not in a pending state");
			return;
		}
		try {
			rec.set(FieldNames.FIELD_STATE, ActionResultEnumType.COMPLETE);
			context.queue(rec.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_STATE}));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		
	}

	/// The generic evolution rule is to evolve down to the hour
	/// Therefore, based on the parent, continue down by month, day, to the next available 'PENDING' hour
	/// This will mean up to 8760 possible hour events for the location epoch
	/// The advancement criteria is as follows:
	///    1) If the event for the current increment doesn't exist, create one and return it

	private TimeEnumType getChildTime(TimeEnumType time) {
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
	
	private String getChildTimeName(BaseRecord parentEvent) {
		TimeEnumType ptet = TimeEnumType.valueOf(parentEvent.get("timeType"));
		TimeEnumType tet = getChildTime(ptet);
		ZonedDateTime prog = parentEvent.get("eventProgress");
		return getTimeName(prog, tet);
	}
	
	private String getTimeName(ZonedDateTime prog, TimeEnumType tet) {
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
	
	@Override
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent) {
		BaseRecord inc = null;
		try {
			inc = findNextIncrement(context, parentEvent);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return inc;
	}
	
	private BaseRecord getCreateIncrement(OlioContext context, BaseRecord parentEvent, ZonedDateTime time, TimeEnumType tet) throws FieldException, ValueException, ModelNotFoundException {
		String name = getTimeName(time, tet);
		BaseRecord[] cevts = EventUtil.getChildEvents(context.getWorld(), parentEvent, null, name, tet, EventEnumType.UNKNOWN);
		if(cevts.length > 0) {
			parentEvent.set("eventProgress", time);
			context.queue(parentEvent.copyRecord(new String[] {FieldNames.FIELD_ID, "eventProgress"}));
			return cevts[0];
		}
		BaseRecord evt = EventUtil.newEvent(context.getUser(), context.getWorld(), parentEvent, EventEnumType.PERIOD, name, time);
		evt.set("timeType", tet);
		parentEvent.set("eventProgress", time);

		if(tet == TimeEnumType.MONTH) {
			edgeMonth(evt);
		}
		else if(tet == TimeEnumType.DAY) {
			edgeDay(evt);
		}
		else if(tet == TimeEnumType.HOUR) {
			edgeHour(evt);
		}
		IOSystem.getActiveContext().getRecordUtil().createRecord(evt);
		context.queue(parentEvent.copyRecord(new String[] {FieldNames.FIELD_ID, "eventProgress"}));
		return evt;
	}
	
	/// Given the current progress from the parent event, return the next time increment
	/// The 24HourRule is to move into the next TimeEnumType.HOUR, advancing as needed the DAY and MONTH
	/// 
	private BaseRecord findNextIncrement(OlioContext context, BaseRecord parentEvent) throws FieldException, ValueException, ModelNotFoundException {
		BaseRecord inc = null;
		
		ZonedDateTime prog = parentEvent.get("eventProgress");
		ZonedDateTime end = parentEvent.get("eventEnd");
		// logger.info("Find next " + incrementType + " increment for " + parentEvent.get(FieldNames.FIELD_NAME));
		TimeEnumType ptet = TimeEnumType.valueOf(parentEvent.get("timeType")); 
		TimeEnumType tet = getChildTime(ptet);
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
		
		BaseRecord outRec = null;
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

		// parentEvent.set("eventProgress", prog);
		return outRec;
		
		//context.processQueue();
	}
	@Override
	public void evaluateIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment) {
		// TODO Auto-generated method stub
		
	}
	
}
