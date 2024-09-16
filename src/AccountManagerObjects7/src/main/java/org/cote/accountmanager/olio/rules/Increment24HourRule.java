package org.cote.accountmanager.olio.rules;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class Increment24HourRule extends CommonEvolveRule implements IOlioEvolveRule {

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
		logger.info("Continuing epoch: " + epoch.get(FieldNames.FIELD_NAME) + " at " + epoch.get(OlioFieldNames.FIELD_EVENT_PROGRESS));
	}


	@Override
	public void startRealmEvent(OlioContext context, BaseRecord realm) {
		EventUtil.edgeTimes(context.clock().realmClock(realm).getEvent());
	}

	
	@Override
	public BaseRecord startRealmIncrement(OlioContext context, BaseRecord realm) {
		BaseRecord rec = continueRealmIncrement(context, realm);
		if(rec != null) {
			logger.warn("Returning current pending increment.");
			return rec;
		}
		return nextRealmIncrement(context, realm);
	}

	@Override
	public BaseRecord continueRealmIncrement(OlioContext context, BaseRecord realm) {
		return context.clock().realmClock(realm).getIncrement();
	}

	@Override
	public void endRealmIncrement(OlioContext context, BaseRecord realm) {
		BaseRecord rec = continueRealmIncrement(context, realm);
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
			Queue.queue(rec.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_STATE}));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

	@Override
	public BaseRecord nextRealmIncrement(OlioContext context, BaseRecord realm) {
		return EventUtil.findNextIncrement(context, context.clock().realmClock(realm).getEvent(), incrementType);
	}
	
}
