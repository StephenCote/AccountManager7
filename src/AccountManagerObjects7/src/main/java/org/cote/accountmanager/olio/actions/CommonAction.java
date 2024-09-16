package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public abstract class CommonAction implements IAction {

	protected void edgeEnd(OlioContext ctx, BaseRecord actionResult, int iter) {
		int minSeconds = actionResult.get(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME);
		ActionUtil.edgeSecondsUntilEnd(actionResult, minSeconds * iter);
		Queue.queueUpdate(actionResult, new String[]{OlioFieldNames.FIELD_ACTION_END});
	}
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.OPERATE;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		int timeSecs = actionResult.get(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME);
		return (long)(timeSecs * 1000);
	}

	@Override
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
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
