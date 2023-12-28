package org.cote.accountmanager.olio.rules;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class GenericEvolveRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(GenericEvolveRule.class);
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
		return nextIncrement(context, locationEpoch, locationEpoch);
	}

	@Override
	public BaseRecord continueIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		// logger.info(locationEpoch.toFullString());
		BaseRecord rec = EventUtil.getLastEvent(context.getUser(), context.getWorld(), locationEpoch.get("location"), incrementType, ActionResultEnumType.PENDING, false); 
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
	public void endIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		BaseRecord rec = continueIncrement(context, locationEpoch);
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

	private TimeEnumType nextTime(TimeEnumType time) {
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
	
	private String getTimeName(BaseRecord parentEvent) {
		TimeEnumType ptet = TimeEnumType.valueOf(parentEvent.get("timeType"));
		TimeEnumType tet = nextTime(ptet);
		ZonedDateTime prog = parentEvent.get("eventProgress");
		int val = 0;
		StringBuilder buff = new StringBuilder();
		// buff.append(prog.getYear() + "/" + String.format("%02d", prog.getMonthValue()) + "/" + String.format("%02d", prog.getDayOfMonth()) + " " + String.format("%02d", prog.getHour()) + ":00");
		buff.append(prog.getYear() + "/" + String.format("%02d", prog.getMonthValue()));
		if(tet == TimeEnumType.DAY || tet == TimeEnumType.HOUR) {
			 buff.append("/" + String.format("%02d", prog.getDayOfMonth()));
		}
		if(tet == TimeEnumType.HOUR) {
			 buff.append(" " + String.format("%02d", prog.getHour()) + ":00");
		}
		/*
		if(tet == TimeEnumType.MONTH) {
			val = prog.getMonthValue();
		}
		else if(tet == TimeEnumType.DAY) {
			val = prog.getDayOfMonth();
		}
		else if(tet == TimeEnumType.HOUR) {
			val = prog.getHour();
		}
		return tet.toString() + " " + val;
		*/
		return buff.toString();
	}
	
	@Override
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent) {
		return nextIncrement(context, parentEvent, parentEvent);
	}
	
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent, BaseRecord baseEvent) {

		IOSystem.getActiveContext().getReader().populate(parentEvent);
		
		TimeEnumType ptet = TimeEnumType.valueOf(parentEvent.get("timeType")); 

		/// Read progress from the base because the start, end, and prog times will be edged
		///
		ZonedDateTime prog = baseEvent.get("eventProgress");
		
		int month = prog.getMonthValue();
		/// Don't queue
		BaseRecord inc = null;

		/// Determine the period type
		TimeEnumType tet = nextTime(ptet);
		
		String ename = getTimeName(parentEvent);
		try {
			/// If it's the end of the year or the end of the month
			/// Then request the nextIncrement of the parentEvent parent
			///
			BaseRecord[] cevts = EventUtil.getChildEvents(context.getWorld(), parentEvent, null, ename, tet, EventEnumType.UNKNOWN);

			boolean create = false;
			if(cevts.length == 0) {
				create = true;
			}
			else {
				// logger.info("Advance to next " + ptet.toString() + " by incrementing " + incrementType.toString());
				if(incrementType == TimeEnumType.HOUR) {
					prog = prog.plusHours(1);
				}
				else if(incrementType == TimeEnumType.DAY) {
					prog = prog.plusDays(1);
					
				}
				else if(incrementType == TimeEnumType.MONTH) {
					prog = prog.plusMonths(1); 
				}
				/// if rolled over the end of the year, bail out
				if(prog.getMonthValue() < month) {
					logger.warn("End of epoch.  No further increments");
					return null;
				}
				
				baseEvent.set("eventProgress", prog);
				logger.info("Sniff: " + tet + " " + prog + " Name:'" + ename + "'");
				if(tet != TimeEnumType.UNKNOWN) {
					if(tet != incrementType) {
						/// move down to a lower time increment
						inc = nextIncrement(context, cevts[0], baseEvent);
					}
					else {
						/// move to the next adjacent time increment
						inc = nextIncrement(context, parentEvent, baseEvent);
					}
				}
				else {
					logger.warn("Outside time scope: " + tet + " -> " + incrementType);
					return null;
				}

			}
			/// times should already be adjusted to the next increment
			/// for month, day, and year progress will be set to the top of the day/hour
			/// for hour, start and end will follow the progress
			if(create) {
				inc = EventUtil.newEvent(context.getUser(), context.getWorld(), parentEvent, EventEnumType.PERIOD, ename, prog);
				inc.set("timeType", tet);
				if(tet == TimeEnumType.MONTH) {
					edgeMonth(inc);
				}
				else if(tet == TimeEnumType.DAY) {
					edgeDay(inc);
				}
				else if(tet == TimeEnumType.HOUR) {
					edgeHour(inc);
				}
				IOSystem.getActiveContext().getRecordUtil().createRecord(inc);
				
				if(tet != TimeEnumType.UNKNOWN && tet != incrementType) {
					inc = nextIncrement(context, inc, baseEvent);
				}
			}
			else {
				logger.warn("Inside existing time scope - " + tet.toString());
			}

			if(inc != null) {
				parentEvent.set("eventProgress", prog);
				context.queue(parentEvent.copyRecord(new String[] {FieldNames.FIELD_ID, "eventProgress"}));
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}

		return inc;
	}
	
	

}
