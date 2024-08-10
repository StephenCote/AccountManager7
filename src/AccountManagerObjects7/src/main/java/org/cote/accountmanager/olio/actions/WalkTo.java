package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class WalkTo implements IAction {
	
	public static final Logger logger = LogManager.getLogger(WalkTo.class);
	private static double proximateDistance = 1.5;
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		// TODO Auto-generated method stub
		/*
		BaseRecord targ = getTarget(participants, influencers);

		*/
		if(interactor == null) {
			throw new OlioException("Expected a target");
		}
		ActionUtil.setDestination(context, actionResult, interactor.get("state"));
		List<BaseRecord> inters = actionResult.get("interactions");
		if(inters.size() > 0) {
			BaseRecord inter = inters.get(0);
			inter.setValue("type", InteractionEnumType.INVESTIGATE);
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
		context.queueUpdate(actionResult, new String[]{"actionEnd"});
		return actionResult;
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.MOVE;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		double angle = GeoLocationUtil.getAngleBetweenInDegrees(actor.get("state"), interactor.get("state"));
		DirectionEnumType dir = DirectionEnumType.getDirectionFromDegrees(angle);
		
		boolean moved = StateUtil.moveByOneMeterInCell(context, actor, dir);

		double dist = GeoLocationUtil.getDistance(actor.get("state"), interactor.get("state"));
		long cost = calculateCostMS(context, actionResult, actor, interactor);
		//long rem = (long)(dist * cost);
		//logger.info("Remaining: " + dist + "m / " + rem + "ms");
		
		return moved;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		return (long)((1/AnimalUtil.walkMetersPerSecond(actor)) * 1000);
	}

	@Override
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		// TODO Auto-generated method stub
		ActionResultEnumType aret = actionResult.getEnum(FieldNames.FIELD_TYPE);
		//long cost = calculateCostMS(context, actionResult, actor, interactor);
		double dist = GeoLocationUtil.getDistance(actor.get("state"), interactor.get("state"));
		if(dist <= proximateDistance) {
			aret = ActionResultEnumType.COMPLETE;
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
