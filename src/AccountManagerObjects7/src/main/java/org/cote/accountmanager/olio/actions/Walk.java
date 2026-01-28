package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class Walk extends CommonAction implements IAction {
	
	public static final Logger logger = LogManager.getLogger(Walk.class);
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

	}
	
	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		BaseRecord params = actionResult.get(FieldNames.FIELD_PARAMETERS);
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		double dist = params.get("distance");
		if(dist == 0.0) {
			dist = 1.0;
		}
		double mps = AnimalUtil.walkMetersPerSecond(actor);
		long timeSeconds = (long)(dist / mps);
		ActionUtil.edgeSecondsUntilEnd(actionResult, timeSeconds);
		Queue.queueUpdate(actionResult, new String[]{OlioFieldNames.FIELD_ACTION_END});
		return actionResult;
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.MOVE;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		BaseRecord params = actionResult.get(FieldNames.FIELD_PARAMETERS);
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		DirectionEnumType dir = params.getEnum("direction");
		if(dir == DirectionEnumType.UNKNOWN) {
			throw new OlioException("Direction is unknown");
		}

		double dist = params.get("distance");
		if(dist == 0.0) {
			dist = 1.0;
		}

		// Log actor state before move
		BaseRecord state = actor.get(FieldNames.FIELD_STATE);
		int beforeEast = state != null ? state.get(FieldNames.FIELD_CURRENT_EAST) : -1;
		int beforeNorth = state != null ? state.get(FieldNames.FIELD_CURRENT_NORTH) : -1;
		logger.info("Walk.executeAction BEFORE: dir={} east={} north={}", dir, beforeEast, beforeNorth);

		boolean moved = false;
		for(double i = 0; i < dist; i++) {
			moved = StateUtil.moveByOneMeterInCell(context, actor, dir);
			if(!moved) {
				logger.warn("Walk.executeAction: moveByOneMeterInCell returned false at iteration {}", i);
				break;
			}
		}

		// Log actor state after move
		int afterEast = state != null ? state.get(FieldNames.FIELD_CURRENT_EAST) : -1;
		int afterNorth = state != null ? state.get(FieldNames.FIELD_CURRENT_NORTH) : -1;
		logger.info("Walk.executeAction AFTER: moved={} east={} north={}", moved, afterEast, afterNorth);

		if(moved) {
			// Always include currentLocation in case a cell boundary was crossed
			StateUtil.queueUpdateLocation(context, actor, true);
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);
		}
		else {
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
		}

		return moved;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		return (long)((1/AnimalUtil.walkMetersPerSecond(actor)) * 1000);
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
