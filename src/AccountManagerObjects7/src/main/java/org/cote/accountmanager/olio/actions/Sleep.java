package org.cote.accountmanager.olio.actions;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class Sleep implements IAction {
	
	public static final Logger logger = LogManager.getLogger(Sleep.class);
	/// Track sleep in 1 min increments
	///
	private int timeIncrement = 60;
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

	}
	
	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		BaseRecord params = actionResult.get("parameters");
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		boolean awake = actor.get("state.awake");
		if(!awake) {
			throw new OlioException("Actor " + actor.get(FieldNames.FIELD_NAME) + " is already asleep");
		}
		
		int dur = params.get("duration");
		if(dur <= 0) {
			dur = 360;
			params.setValue("duration", dur);
		}
		
		ActionUtil.edgeSecondsUntilEnd(actionResult, dur);
		Queue.queueUpdate(actionResult, new String[]{"actionEnd"});
		return actionResult;
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.MOVE;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		
		BaseRecord params = actionResult.get("parameters");
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}

		ActionUtil.addProgressSeconds(actionResult, timeIncrement);
		BaseRecord state = actor.get("state");
		state.setValue("awake", false);
		Queue.queueUpdate(state, new String[] {"awake"});
		
		return true;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		int timeSecs = actionResult.get("action.minimumTime");
		return (long)(timeSecs * 1000);
	}

	@Override
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		ZonedDateTime prog = actionResult.get("actionProgress");
		ZonedDateTime end = actionResult.get("actionEnd");
		if(prog.until(end, ChronoUnit.MILLIS) <= 0L) {
			BaseRecord state = actor.get("state");
			state.setValue("awake", true);
			Queue.queueUpdate(state, new String[] {"awake"});
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.COMPLETE);
		}

		return actionResult.getEnum(FieldNames.FIELD_TYPE);
	}

	@Override
	public List<BaseRecord> definePolicyFactParameters(OlioContext context, BaseRecord actionResult, BaseRecord actor,
			BaseRecord interactor) throws OlioException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean counterAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor)
			throws OlioException {
		// TODO Auto-generated method stub
		return false;
	}




}
