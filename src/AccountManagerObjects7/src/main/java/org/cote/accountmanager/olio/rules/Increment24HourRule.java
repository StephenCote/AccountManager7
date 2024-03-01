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

	@Override
	public void startEpoch(OlioContext context, BaseRecord epoch) {
		EventUtil.edgeTimes(epoch);
		EventUtil.edgeEndOfYear(epoch);
	}
	
	@Override
	public void continueEpoch(OlioContext context, BaseRecord epoch) {
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
		EventUtil.edgeTimes(epoch);
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
		BaseRecord rec = continueIncrement(context, locationEpoch);
		if(rec != null) {
			logger.warn("Returning current pending increment.");
			return rec;
		}
		return nextIncrement(context, locationEpoch);
	}

	@Override
	public BaseRecord continueIncrement(OlioContext context, BaseRecord locationEpoch) {
		BaseRecord rec = EventUtil.getLastEvent(context.getUser(), context.getWorld(), locationEpoch.get("location"), EventEnumType.PERIOD, incrementType, ActionResultEnumType.PENDING, false); 
		if(rec != null) {
			return rec;
		}
		return null;
	}

	@Override
	public void endIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord currentIncrement) {
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

	@Override
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent) {
		return EventUtil.findNextIncrement(context, parentEvent, incrementType);
	}

	

	@Override
	public void evaluateIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment) {
		// TODO Auto-generated method stub
		
	}
	
}
