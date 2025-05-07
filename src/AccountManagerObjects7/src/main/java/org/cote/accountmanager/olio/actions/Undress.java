package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.RollEnumType;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class Undress extends CommonAction implements IAction {
	
	public static final Logger logger = LogManager.getLogger(Undress.class);
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

	}
	
	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		BaseRecord params = actionResult.get(FieldNames.FIELD_PARAMETERS);
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		WearLevelEnumType level = params.getEnum(OlioFieldNames.FIELD_WEAR_LEVEL);
		if(level == WearLevelEnumType.UNKNOWN) {
			throw new OlioException("Unexpected target level");
		}

		int minSeconds = actionResult.get(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME);
		ActionUtil.edgeSecondsUntilEnd(actionResult, minSeconds);
		Queue.queueUpdate(actionResult, new String[]{OlioFieldNames.FIELD_ACTION_END});

		return actionResult;
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.DISCONTINUE;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		
		BaseRecord params = actionResult.get(FieldNames.FIELD_PARAMETERS);
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		WearLevelEnumType level = params.getEnum(OlioFieldNames.FIELD_WEAR_LEVEL);
		if(level == WearLevelEnumType.UNKNOWN) {
			throw new OlioException("Unexpected target level");
		}
		
		BaseRecord targ = (interactor !=  null ? interactor : actor);
		boolean canUndress = true;
		if(interactor == null || interactor.get(FieldNames.FIELD_ID) == actor.get(FieldNames.FIELD_ID)) {
			canUndress = true;
		}
		else {
			double dist = GeoLocationUtil.getDistanceToState(actor.get(FieldNames.FIELD_STATE), interactor.get(FieldNames.FIELD_STATE));
			RollEnumType ret = RollUtil.rollContact(actor, interactor); 
			canUndress = (ret == RollEnumType.SUCCESS || ret == RollEnumType.NATURAL_SUCCESS);
			List<String> res = actionResult.get(FieldNames.FIELD_RESULTS);
			if(ret == RollEnumType.CATASTROPHIC_FAILURE){
				res.add(actor.get(FieldNames.FIELD_NAME) + " is groping at " + interactor.get(FieldNames.FIELD_NAME));
			}
			else {
				res.add(actor.get(FieldNames.FIELD_NAME) + " reach " + interactor.get(FieldNames.FIELD_NAME) + " from " + dist + " meters");
			}
		}

		if(canUndress) {
			int cwl = WearLevelEnumType.valueOf(level);
			List<String> desc = actionResult.get(FieldNames.FIELD_RESULTS);
			ApparelUtil.getWearing(targ).forEach(w -> {
				WearLevelEnumType wlvl = w.getEnum(OlioFieldNames.FIELD_LEVEL);
				int wl = WearLevelEnumType.valueOf(wlvl);
				if(wl > cwl) {
					desc.add("Strip: " + NarrativeUtil.describeWearable(w));
					w.setValue(OlioFieldNames.FIELD_IN_USE, false);
					Queue.queueUpdate(w, new String[] {OlioFieldNames.FIELD_IN_USE});
				}
			});
			
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);
		}
		else {
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
		}
		
		return canUndress;
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

		return null;
	}

	@Override
	public boolean counterAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor)
			throws OlioException {
		// TODO Auto-generated method stub
		return false;
	}




}
