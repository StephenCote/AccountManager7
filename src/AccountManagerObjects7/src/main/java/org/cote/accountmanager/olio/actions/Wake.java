package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class Wake implements IAction {
	
	public static final Logger logger = LogManager.getLogger(Wake.class);
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

	}
	
	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		if((boolean)actor.get("state.awake") == true) {
			logger.warn("Actor " + FieldNames.FIELD_NAME + " is already awake");
		}
		else {
			int minTime = actionResult.get("action.minimumTime");
			ActionUtil.edgeSecondsUntilEnd(actionResult, minTime);
			context.queueUpdate(actionResult, new String[]{"actionEnd"});
		}
		return actionResult;
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.MOVE;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		boolean awake = actor.get("state.awake");
		if(!awake) {
			int minTime = actionResult.get("action.minimumTime");
			ActionUtil.addProgressSeconds(actionResult, minTime);
		}
		
		return true;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		int timeSecs = actionResult.get("action.minimumTime");
		return (long)(timeSecs * 1000);
	}

	@Override
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		boolean awake = actor.get("state.awake");
		if(!awake) {
			BaseRecord state = actor.get("state");
			state.setValue("awake", true);
			context.queueUpdate(state, new String[] {"awake"});
		}

		actionResult.setValue(FieldNames.FIELD_TYPE, (awake ? ActionResultEnumType.COMPLETE : ActionResultEnumType.FAILED));

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
