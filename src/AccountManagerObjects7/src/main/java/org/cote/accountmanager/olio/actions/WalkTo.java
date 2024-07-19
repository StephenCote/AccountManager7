package org.cote.accountmanager.olio.actions;

import java.time.ZonedDateTime;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.AssessmentEnumType;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.LoveNeedsEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.EventEnumType;

public class WalkTo implements IAction {
	
	public static final Logger logger = LogManager.getLogger(WalkTo.class);
	
	private BaseRecord getTarget(BaseRecord event) {
		List<BaseRecord> participants = event.get("participants");
		List<BaseRecord> influencers = event.get("influencers");
		return getTarget(participants.toArray(new BaseRecord[0]), influencers.toArray(new BaseRecord[0]));
	}
	private BaseRecord getTarget(BaseRecord[] participants, BaseRecord[] influencers) {
		BaseRecord targ = null;
		if(participants.length > 0) {
			targ = participants[0];
		}
		else if(influencers.length > 0) {
			targ = influencers[0];
		}
		return targ;
	}

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
		DirectionEnumType dir = DirectionEnumType.getDirectionFromDegrees(GeoLocationUtil.getAngleBetweenInDegrees(actor.get("state"), interactor.get("state")));
		long cost = calculateCostMS(context, actionResult, actor, interactor);
		
		return false;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		return (long)((1/AnimalUtil.walkMetersPerSecond(actor)) * 1000);
	}




}
