package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class WalkTo implements IAction {
	
	public static final Logger logger = LogManager.getLogger(WalkTo.class);
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		// TODO Auto-generated method stub
		/*
		BaseRecord targ = getTarget(participants, influencers);

		*/
		if(interactor == null) {
			throw new OlioException("Expected a target");
		}
		ActionUtil.setDestination(context, actionResult, interactor.get(FieldNames.FIELD_STATE));
		List<BaseRecord> inters = actionResult.get(OlioFieldNames.FIELD_INTERACTIONS);
		if(inters.size() > 0) {
			BaseRecord inter = inters.get(0);
			inter.setValue(FieldNames.FIELD_TYPE, InteractionEnumType.INVESTIGATE);
		}
	}
	
	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		/*
		BaseRecord targ = getTarget(participants, influencers);

		*/
		if(interactor == null) {
			throw new OlioException("Expected a target");
		}
		ActionUtil.updateTimeToDistance(actionResult, actor, interactor);
		Queue.queueUpdate(actionResult, new String[]{OlioFieldNames.FIELD_ACTION_END});
		return actionResult;
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.MOVE;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		double angle = GeoLocationUtil.getAngleBetweenInDegrees(actor.get(FieldNames.FIELD_STATE), interactor.get(FieldNames.FIELD_STATE));
		DirectionEnumType dir = DirectionEnumType.getDirectionFromDegrees(angle);
		
		boolean moved = StateUtil.moveByOneMeterInCell(context, actor, dir);

		return moved;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		return (long)((1/AnimalUtil.walkMetersPerSecond(actor)) * 1000);
	}

	@Override
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		ActionResultEnumType aret = actionResult.getEnum(FieldNames.FIELD_TYPE);
		double dist = GeoLocationUtil.getDistanceToState(actor.get(FieldNames.FIELD_STATE), interactor.get(FieldNames.FIELD_STATE));
		if(dist <= Rules.PROXIMATE_CONTACT_DISTANCE) {
			aret = ActionResultEnumType.COMPLETE;
			actionResult.setValue(FieldNames.FIELD_TYPE, aret);
		}
		logger.info("Remaining: " + dist + "m");
		return aret;
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
