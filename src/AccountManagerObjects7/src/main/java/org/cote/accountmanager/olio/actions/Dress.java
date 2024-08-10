package org.cote.accountmanager.olio.actions;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.RollEnumType;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class Dress implements IAction {
	
	public static final Logger logger = LogManager.getLogger(Dress.class);
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

	}
	
	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		BaseRecord params = actionResult.get("parameters");
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		WearLevelEnumType level = params.getEnum("wearLevel");
		if(level == WearLevelEnumType.UNKNOWN) {
			throw new OlioException("Unexpected target level");
		}

		int minSeconds = actionResult.get("action.minimumTime");
		ActionUtil.edgeSecondsUntilEnd(actionResult, minSeconds);
		context.queueUpdate(actionResult, new String[]{"actionEnd"});

		return actionResult;
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.UTILIZE;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		
		BaseRecord params = actionResult.get("parameters");
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		WearLevelEnumType level = params.getEnum("wearLevel");
		if(level == WearLevelEnumType.UNKNOWN) {
			throw new OlioException("Unexpected target level");
		}
		
		BaseRecord targ = (interactor !=  null ? interactor : actor);
		boolean canDress = true;
		BaseRecord app = ApparelUtil.getWearingApparel(targ);
		List<String> res = actionResult.get("results");
		if(app == null) {
			res.add(targ.get(FieldNames.FIELD_NAME) + " does not have any active apparel");
		}
		else if(interactor == null || interactor.get(FieldNames.FIELD_ID) == actor.get(FieldNames.FIELD_ID)) {
			canDress = true;
		}
		else {
			double dist = GeoLocationUtil.getDistance(actor.get("state"), interactor.get("state"));
			RollEnumType ret = RollUtil.rollContact(actor, interactor); 
			canDress = (ret == RollEnumType.SUCCESS || ret == RollEnumType.NATURAL_SUCCESS);
			
			if(ret == RollEnumType.CATASTROPHIC_FAILURE){
				res.add(actor.get(FieldNames.FIELD_NAME) + " is groping at " + interactor.get(FieldNames.FIELD_NAME));
			}
			else {
				res.add(actor.get(FieldNames.FIELD_NAME) + " reach " + interactor.get(FieldNames.FIELD_NAME) + " from " + dist + " meters");
			}
		}
		
		if(canDress) {
			int cwl = WearLevelEnumType.valueOf(level);
			List<BaseRecord> wearl = app.get("wearables");
			wearl.forEach(w -> {
				WearLevelEnumType wlvl = w.getEnum("level");
				int wl = WearLevelEnumType.valueOf(wlvl);
				if(wl <= cwl) {
					res.add("Wear: " + NarrativeUtil.describeWearable(w));
					w.setValue("inuse", true);
					context.queueUpdate(w, new String[] {"inuse"});
				}
			});
			if((boolean)app.get("inuse") == false) {
				app.setValue("inuse", true);
				context.queueUpdate(app, new String[] {"inuse"});
			}
			
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);
		}
		else {
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
		}
		
		return canDress;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		int timeSecs = actionResult.get("action.minimumTime");
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
