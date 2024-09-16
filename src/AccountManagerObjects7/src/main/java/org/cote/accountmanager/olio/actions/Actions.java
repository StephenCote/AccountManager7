package org.cote.accountmanager.olio.actions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AssessmentEnumType;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.Overwatch.OverwatchEnumType;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;

public class Actions {
	public static final Logger logger = LogManager.getLogger(Actions.class);
	
	private static Map<String, IAction> actionMap = new HashMap<>();
	
	protected static String getActionEventName(String actionName, BaseRecord actor, BaseRecord interactor) {
		StringBuilder nameBuff = new StringBuilder();
		nameBuff.append(actor.get(FieldNames.FIELD_NAME) + " (" + actor.get(FieldNames.FIELD_OBJECT_ID));
		nameBuff.append(" " + actionName);
		if(interactor != null) {
			nameBuff.append(" - " + interactor.get(FieldNames.FIELD_NAME) + " (" + interactor.get(FieldNames.FIELD_OBJECT_ID));
		}
		/*
		for(BaseRecord p : participants) {
			nameBuff.append(" - " + p.get(FieldNames.FIELD_NAME) + " (" + p.get(FieldNames.FIELD_OBJECT_ID));
		}
		for(BaseRecord i : influencers) {
			nameBuff.append(" - " + i.get(FieldNames.FIELD_NAME) + " (" + i.get(FieldNames.FIELD_OBJECT_ID));
		}
		*/

		return nameBuff.toString();
	}
	public static void pruneActionState(OlioContext context, BaseRecord actor) {
		BaseRecord state = actor.get(FieldNames.FIELD_STATE);
		List<BaseRecord> actions = state.get(OlioFieldNames.FIELD_ACTIONS);
		Set<Long> aset = new HashSet<>();
		for(BaseRecord a: actions) {
			ActionResultEnumType aet = a.getEnum(FieldNames.FIELD_TYPE);
			if(aet != ActionResultEnumType.IN_PROGRESS && aet != ActionResultEnumType.PENDING) {
				aset.add(a.get(FieldNames.FIELD_ID));
				IOSystem.getActiveContext().getMemberUtil().member(context.getOlioUser(), state, OlioFieldNames.FIELD_ACTIONS, a, null, false);
			}
		}
	}
	public static void updateState(OlioContext context, BaseRecord actionResult, BaseRecord actor) {
		
		pruneActionState(context, actor);
		
		BaseRecord state = actor.get(FieldNames.FIELD_STATE);
		List<BaseRecord> actions = state.get(OlioFieldNames.FIELD_ACTIONS);
		IOSystem.getActiveContext().getMemberUtil().member(context.getOlioUser(), state, OlioFieldNames.FIELD_ACTIONS, actionResult, null, true);
		actions.add(actionResult);

	}
	public static IAction getActionProvider(OlioContext ctx, String actionName) throws OlioException {

		BaseRecord act = ActionUtil.getAction(ctx, actionName);
		if(act == null) {
			throw new OlioException("Failed to find action " + actionName);
		}
		return getActionProvider(ctx, act);
	}
	
	public static IAction getActionProvider(OlioContext ctx, BaseRecord act) throws OlioException {
		if(act == null) {
			throw new OlioException("Action is null");
		}

		IAction actProv = null;
		String actionName = act.get(FieldNames.FIELD_NAME);

		if(actionMap.containsKey(actionName)) {
			actProv = actionMap.get(actionName);
		}
		else if(act.hasField("provider")) {
			actProv = RecordFactory.getClassInstance((String)act.get("provider"));
		}
		if(actProv == null) {
			throw new OlioException("Failed to find provider for " + actionName);
		}
		return actProv;
	}
	
	public static void dependAction(OlioContext ctx, BaseRecord actionResult1, BaseRecord actionResult2) {
		List<BaseRecord> dacts = actionResult1.get("dependentActions");
		dacts.add(actionResult2);
		Queue.queue(ParticipationFactory.newParticipation(ctx.getOlioUser(), actionResult1, "dependentActions", actionResult2));
	}
	
	public static BaseRecord beginAction(OlioContext ctx, BaseRecord evt, BaseRecord per1, BaseRecord per2, AssessmentEnumType assessType, String actionName) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(assessType, null, actionName);
		return beginAction(ctx, evt, params, per1, per2);
	}
	
	public static BaseRecord beginAction(OlioContext context, BaseRecord event, String actionName, BaseRecord actor, BaseRecord interactor) throws OlioException {
		return beginAction(context, event, ActionUtil.newActionParameters(AssessmentEnumType.UNKNOWN, null, actionName), actor, interactor);
	}
	public static BaseRecord beginAction(OlioContext context, BaseRecord event, BaseRecord params, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		BaseRecord iact = null;
		if(interactor != null) {
			iact = InteractionUtil.newInteraction(context, InteractionEnumType.UNKNOWN, null, actor, ThreatEnumType.NONE, interactor);
		}
		return beginAction(context, event, params, actor, interactor, iact);
	}
	public static BaseRecord beginAction(OlioContext context, BaseRecord event, BaseRecord params, BaseRecord actor, BaseRecord interactor, BaseRecord interaction) throws OlioException {
			
		String actionName = params.get("actionName");
		BaseRecord cact = ActionUtil.getInAction(actor, actionName);
		if(cact != null) {
			if(params != null && (boolean)params.get("autoComplete") == true) {
				logger.info("Auto-completing " + actor.get(FieldNames.FIELD_NAME) + "'s current '" + actionName + "' action.");
				cact.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.INCOMPLETE);
				Queue.queueUpdate(cact, new String[] {FieldNames.FIELD_TYPE});
			}
			else {
				logger.warn(actor.get(FieldNames.FIELD_NAME) + " is already in the middle of a '" + actionName + "' action.  Current action must be completed or abandoned.");
				return cact;
			}
		}
		
		BaseRecord action = ActionUtil.getAction(context, actionName);
		if(action == null) {
			throw new OlioException("Invalid action: " + actionName);
		}
		IAction act = getActionProvider(context, actionName);

		BaseRecord actr = ActionUtil.newActionResult(context, action, params, interaction);
		if(actr == null) {
			throw new OlioException("Failed to create action result for " + actionName);
		}
		
		actr.setValue("actor", actor);
		actr.setValue("actorType", actor.getModel());
		
		act.configureAction(context, actr, actor, interactor);
		if(event != null) {
			actr.setValue("actionStart", event.get(OlioFieldNames.FIELD_EVENT_PROGRESS));
			actr.setValue("actionProgress", actr.get("actionStart"));
			actr.setValue("actionEnd", actr.get("actionStart"));
		}
		IOSystem.getActiveContext().getRecordUtil().createRecord(actr);
		String ename = getActionEventName(actionName, actor, interactor);
		//BaseRecord actEvt = EventUtil.getEvent(context, event, ename, act.getEventType());
		BaseRecord actEvt = null;
		if(event != null) {
			// actEvt = ActionUtil.getActionEvent(context, event, act.getEventType(), ename, new BaseRecord[] {actor}, new BaseRecord[] {interactor}, new BaseRecord[0]);
		}
		act.beginAction(context, actr, actor, interactor);
		updateState(context, actr, actor);

		Queue.processQueue();
		
		context.getOverwatch().watch(OverwatchEnumType.ACTION, new BaseRecord[] {actr});
		
		return actr;
	}
	
	public static boolean executeAction(OlioContext context, BaseRecord actionResult) throws OlioException {
		IOSystem.getActiveContext().getReader().populate(actionResult);
		//logger.info(actionResult.toFullString());
		BaseRecord actor = actionResult.get("actor");
		BaseRecord interactor = null;
		List<BaseRecord> inters = actionResult.get(OlioFieldNames.FIELD_INTERACTIONS);
		BaseRecord interaction = null;
		if(inters.size() > 0) {
			interaction = inters.get(0);
			IOSystem.getActiveContext().getReader().populate(interaction, new String[] {"actor", "actorType", "interactor", "interactorType"});
			interactor = interaction.get("interactor");
		}

		return executeAction(context, actionResult, actor, interactor);
	}
	
	public static boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		String actName = actionResult.get("action.name");
		if(actName == null) {
			throw new OlioException("Unknown action name");
		}
		IAction action = getActionProvider(context, actName);
		if(action == null) {
			throw new OlioException("Invalid action: " + actName);
		}
		
		boolean narrate = actionResult.get("parameters.narrate");
		
		if(narrate) {
			List<BaseRecord> inters = actionResult.get(OlioFieldNames.FIELD_INTERACTIONS);
			if(inters.size() > 0) {
				logger.info(NarrativeUtil.describeInteraction(inters.get(0)));
			}
		}
		ActionUtil.addProgressMS(actionResult, action.calculateCostMS(context, actionResult, actor, interactor));
		Queue.queueUpdate(actionResult, new String[] {"actionProgress"});
		
		//logger.info("Execute action: " + actName);
		return action.executeAction(context, actionResult, actor, interactor);
		
	}
	
	public static ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		String actName = actionResult.get("action.name");
		if(actName == null) {
			throw new OlioException("Unknown action name");
		}
		IAction action = getActionProvider(context, actName);
		if(action == null) {
			throw new OlioException("Invalid action: " + actName);
		}
		
		ActionResultEnumType aet = action.concludeAction(context, actionResult, actor, interactor);
		
		if(aet != ActionResultEnumType.IN_PROGRESS && aet != ActionResultEnumType.PENDING) {
			actionResult.setValue("actionEnd", actionResult.get("actionProgress"));
		}
		Queue.queueUpdate(actionResult, new String[] {"actionEnd", "actionProgress", FieldNames.FIELD_TYPE});

		return aet;
		
	}
	public static BaseRecord beginGather(OlioContext ctx, BaseRecord evt, BaseRecord per1, String itemCategory, int quantity) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(AssessmentEnumType.PHYSIOLOGICAL, null, "gather", true);
		params.setValue("itemCategory", itemCategory);
		params.setValue("quantity", quantity);
		return beginAction(ctx, evt, params, per1, null);
	}
	public static BaseRecord beginMove(OlioContext ctx, BaseRecord evt, BaseRecord per1, DirectionEnumType dir) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(AssessmentEnumType.CURIOSITY, null, "walk");
		params.setValue("direction", dir);
		return beginAction(ctx, evt, params, per1, null);
	}
	
	public static BaseRecord beginMoveTo(OlioContext ctx, BaseRecord evt, BaseRecord per1, BaseRecord per2) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(AssessmentEnumType.CURIOSITY, null, "walkTo", true);
		return beginAction(ctx, evt, params, per1, per2);
	}
	
	public static BaseRecord beginDress(OlioContext ctx, BaseRecord evt, BaseRecord per1, BaseRecord per2, WearLevelEnumType level) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(AssessmentEnumType.CURIOSITY, null, "dress");
		params.setValue("wearLevel", level);
		return beginAction(ctx, evt, params, per1, per2);
	}

	public static BaseRecord beginUndress(OlioContext ctx, BaseRecord evt, BaseRecord per1, BaseRecord per2, WearLevelEnumType level) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(AssessmentEnumType.CURIOSITY, null, "undress");
		params.setValue("wearLevel", level);
		return beginAction(ctx, evt, params, per1, per2);
	}

	public static BaseRecord beginPeek(OlioContext ctx, BaseRecord evt, BaseRecord per1, BaseRecord per2) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(AssessmentEnumType.CURIOSITY, null, "peek", true);
		return beginAction(ctx, evt, params, per1, per2);
	}

	public static BaseRecord beginLook(OlioContext ctx, BaseRecord evt, BaseRecord per1) throws OlioException {
		BaseRecord params = ActionUtil.newActionParameters(AssessmentEnumType.CURIOSITY, null, "look", true);
		return beginAction(ctx, evt, params, per1, null);
	}


}
