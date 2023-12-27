package org.cote.accountmanager.olio.rules;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class GenericEvolveRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(GenericEvolveRule.class);
	
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
		logger.info("Begin epoch: " + epoch.get(FieldNames.FIELD_NAME) + " at " + epoch.get("eventStart"));
		
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
	public void startIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void continueIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		
	}

	/// The generic evolution rule is to evolve down to the hour
	/// Therefore, based on the parent, continue down by month, day, to the next available 'PENDING' hour
	/// This will mean 8760 hour events for the location epoch

	@Override
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent) {
		TimeEnumType ptet = TimeEnumType.valueOf(parentEvent.get("timeType")); 
		ZonedDateTime start = parentEvent.get("eventStart");
		ZonedDateTime prog = parentEvent.get("eventProgress");
		ZonedDateTime end = parentEvent.get("eventEnd");
		
		/// Don't queue
		BaseRecord inc = EventUtil.newEvent(context.getUser(), context.getWorld(), parentEvent, EventEnumType.PERIOD, null, start, null, null, null, null);
		ZonedDateTime iend = end;
		/// Determine the period type
		TimeEnumType tet = TimeEnumType.UNKNOWN;
		try {
			switch(ptet) {
				case YEAR:
					tet = TimeEnumType.MONTH;
					iend = prog.plusMonths(1).with(LocalTime.of(23, 59, 59));
					break;
				case MONTH:
					tet = TimeEnumType.DAY;
					iend = prog.plusDays(1).with(LocalTime.of(23, 59, 59));
					break;
				case DAY:
					tet = TimeEnumType.HOUR;
					iend = prog.plusHours(1);
					iend = prog.with(LocalTime.of(prog.getHour(), 59, 59));
					break;
				default:
					logger.error("Unhandled time type: " + ptet.toString());
					return null;
			}
			
			inc.set("eventStart", start);
			parentEvent.set("eventProgress", iend);
			inc.set("eventEnd", iend);
			inc.set(FieldNames.FIELD_NAME, tet.toString() + " " + );
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		
		
		long diff = end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli();
		
		long totalDays = TimeUnit.MILLISECONDS.toDays(diff);
		logger.info("Total days: " + totalDays);
		
		return inc;

		
	}
	
	

}
