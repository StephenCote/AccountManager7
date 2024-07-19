package org.cote.accountmanager.olio.actions;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.AssessmentEnumType;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.LoveNeedsEnumType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;

public class Actions {
	public static final Logger logger = LogManager.getLogger(Actions.class);
	
	private static Map<String, IAction> actionMap = new HashMap<>();
	static {
		// actionMap.put("move", new MoveTo());
	}
	
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
	
	public static void updateState(OlioContext context, BaseRecord actionResult, BaseRecord actor) {
		BaseRecord state = actor.get("state");
		/*
		if(state.get("currentEvent") != null) {
			logger.warn("Overwriting current event");
		}
		state.setValue("currentEvent", event);
		context.queueUpdate(state, new String[] {"currentEvent"});
		*/
		List<BaseRecord> actions = state.get("actions");
		if(actions.size() > 0) {
			logger.warn("Overwriting current actions?");
			for(BaseRecord a: actions) {
				IOSystem.getActiveContext().getMemberUtil().member(context.getOlioUser(), state, "actions", a, null, false);
			}
			actions.clear();
		}
		IOSystem.getActiveContext().getMemberUtil().member(context.getOlioUser(), state, "actions", actionResult, null, true);
		actions.add(actionResult);
		
		/*
		IOSystem.getActiveContext().getMemberUtil().member(context.getOlioUser(), event, "actions", actionResult, null, true);
		actions = event.get("actions");
		actions.add(actionResult);
		*/
	}
	protected static IAction getActionProvider(OlioContext ctx, String actionName) throws OlioException {

		BaseRecord act = ActionUtil.getAction(ctx, actionName);
		if(act == null) {
			throw new OlioException("Failed to find action " + actionName);
		}
		return getActionProvider(ctx, act);
	}
	
	protected static IAction getActionProvider(OlioContext ctx, BaseRecord act) throws OlioException {
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
	
	public static BaseRecord beginAction(OlioContext context, BaseRecord event, String actionName, AssessmentEnumType needType, String needName, BaseRecord actor, BaseRecord interactor) throws OlioException {
		BaseRecord iact = null;
		if(interactor != null) {
			iact = InteractionUtil.newInteraction(context, InteractionEnumType.UNKNOWN, null, actor, ThreatEnumType.NONE, interactor);
		}
		return beginAction(context, event, actionName, needType, needName, actor, interactor, iact);
	}
	public static BaseRecord beginAction(OlioContext context, BaseRecord event, String actionName, AssessmentEnumType needType, String needName, BaseRecord actor, BaseRecord interactor, BaseRecord interaction) throws OlioException {
			
		
		BaseRecord cact = ActionUtil.getInAction(actor, actionName);
		if(cact != null) {
			logger.warn(actor.get(FieldNames.FIELD_NAME) + " is already in the middle of a '" + actionName + "' action.  Current action must be completed or abandoned.");
			return cact;
		}
		
		BaseRecord action = ActionUtil.getAction(context, actionName);
		if(action == null) {
			throw new OlioException("Invalid action: " + actionName);
		}
		IAction act = getActionProvider(context, actionName);

		BaseRecord actr = ActionUtil.newActionResult(context, action, needType, needName, interaction);
		if(actr == null) {
			throw new OlioException("Failed to create action result for " + actionName);
		}
		
		actr.setValue("actor", actor);
		actr.setValue("actorType", actor.getModel());
		
		act.configureAction(context, actr, actor, interactor);
		if(event != null) {
			actr.setValue("actionStart", event.get("eventProgress"));
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

		context.processQueue();
		
		return actr;
	}
	
	public static boolean executeAction(OlioContext context, BaseRecord actionResult) throws OlioException {
		IOSystem.getActiveContext().getReader().populate(actionResult);
		//logger.info(actionResult.toFullString());
		BaseRecord actor = actionResult.get("actor");
		BaseRecord interactor = null;
		List<BaseRecord> inters = actionResult.get("interactions");
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
		List<BaseRecord> inters = actionResult.get("interactions");
		if(inters.size() > 0) {
			logger.info(NarrativeUtil.describeInteraction(inters.get(0)));
		}
		
		ActionUtil.addProgressMS(actionResult, action.calculateCostMS(context, actionResult, actor, interactor));
		context.queueUpdate(actionResult, new String[] {"actionProgress"});
		
		logger.info("Execute action: " + actName);
		boolean outBool = action.executeAction(context, actionResult, actor, interactor);
		
		return outBool;
	}

	public static BaseRecord beginMoveTo(OlioContext ctx, BaseRecord evt, BaseRecord per1, BaseRecord per2) throws OlioException {
		return beginAction(ctx, evt, "walkTo", AssessmentEnumType.LOVE, LoveNeedsEnumType.FRIENDSHIP.toString().toLowerCase(), per1, per2);
	}


}
