package org.cote.accountmanager.olio.actions;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.AssessmentEnumType;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ActionUtil {
	public static final Logger logger = LogManager.getLogger(ActionUtil.class);
	private static BaseRecord[] actions = new BaseRecord[0];
	public static List<BaseRecord> getCurrentActions(BaseRecord animal, String actionName, List<ActionResultEnumType> filter) {
		List<BaseRecord> actions = animal.get("state.actions");
		if(actions == null) {
			logger.error("Unpopulated state");
			return new ArrayList<>();
		}
		return actions.stream().filter(a -> {
			String name = a.get("action.name");
			ActionResultEnumType type = a.getEnum(FieldNames.FIELD_TYPE);
			
			if(name == null) {
				logger.error("Unpopulated action name");
			}
			return (actionName.equals(name) && (filter.size() == 0 || filter.contains(type)));
		}).collect(Collectors.toList());

	}
	public static BaseRecord getInAction(BaseRecord animal, String actionName) {
		List<BaseRecord> iacts = getCurrentActions(animal, actionName, Arrays.asList(new ActionResultEnumType[] {ActionResultEnumType.PENDING, ActionResultEnumType.IN_PROGRESS}));
		return (iacts.size() > 0 ? iacts.get(0) : null);
	}
	public static boolean isInAction(BaseRecord animal, String actionName) {
		return (getInAction(animal, actionName) != null);
	}
	
	public static BaseRecord getActionEvent(OlioContext context, BaseRecord parentEvent, EventEnumType type, String eventName, BaseRecord[] actors, BaseRecord[] participants, BaseRecord[] influencers) {
		BaseRecord actEvt = EventUtil.getEvent(context, parentEvent, eventName, type);
		if(actEvt == null) {
			actEvt = EventUtil.newEvent(context, parentEvent, type, eventName, parentEvent.get("eventProgress"), actors, participants, influencers, false);
			IOSystem.getActiveContext().getRecordUtil().createRecord(actEvt);
		}
		return actEvt;
	}
	
	public static void updateTimeToDistance(BaseRecord actionResult, BaseRecord per1, BaseRecord per2) {
		double dist = GeoLocationUtil.getDistance(per1.get("state"), per2.get("state"));
		long timeSeconds = (long)(dist / AnimalUtil.walkMetersPerSecond(per1));
		edgeSecondsUntilEnd(actionResult, timeSeconds);
	}
	
	public static void addProgressMS(BaseRecord actionResult, long ms) {
		ZonedDateTime prog = actionResult.get("actionProgress");
		actionResult.setValue("actionProgress", prog.plus(ms, ChronoUnit.MILLIS));
	}
	
	public static void edgeSecondsUntilEnd(BaseRecord actionResult, long seconds) {
		ZonedDateTime prog = actionResult.get("actionProgress");
		actionResult.setValue("actionEnd", prog.plusSeconds(seconds));
	}
	
	public static BaseRecord getAction(OlioContext ctx, String name) {
		Optional<BaseRecord> opt = Arrays.asList(getActions(ctx)).stream().filter(b -> ((String)b.get(FieldNames.FIELD_NAME)).equals(name)).findFirst();
		if(opt.isPresent()) {
			return opt.get();
		}
		return null;
	}
	public static BaseRecord[] getActions(OlioContext ctx) {
		if(actions.length > 0) {
			return actions;
		}
		actions = OlioUtil.list(ctx, ModelNames.MODEL_ACTION, "actions");
		return actions;
	}
	
	public static void setDestination(OlioContext ctx, BaseRecord actionResult, BaseRecord targState) {
		BaseRecord state = actionResult.get("state");
		if(state == null) {
			try {
				state = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CHAR_STATE, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("states.path")));
				actionResult.set("state", state);
			} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
				logger.error(e);
			}
		}
		state.setValue("currentLocation", targState.get("currentLocation"));
		state.setValue("currentNorth", targState.get("currentNorth"));
		state.setValue("currentEast", targState.get("currentEast"));
	}
	
	public static BaseRecord newActionResult(OlioContext ctx, String actionName) {
		BaseRecord act = ActionUtil.getAction(ctx, actionName);
		if(act == null) {
			logger.error("Null action");
			return null;
		}
		return newActionResult(ctx, act, newActionParameters(AssessmentEnumType.UNKNOWN, null, actionName), null);
	}
	public static BaseRecord newActionResult(OlioContext ctx, BaseRecord action, BaseRecord params, BaseRecord interaction) {

		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("actionResults.path"));
		BaseRecord actionResult = null;
		try {
			actionResult = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACTION_RESULT, ctx.getOlioUser(), null, plist);
			actionResult.set("action", action);
			actionResult.set("builder", null);
			actionResult.set("needType", params.get("needType"));
			actionResult.set("need", params.get("needName"));
			actionResult.set("parameters", params);
			actionResult.set(FieldNames.FIELD_TYPE, ActionResultEnumType.PENDING);
			if(interaction != null) {
				List<BaseRecord> iacts = actionResult.get("interactions");
				iacts.add(interaction);
			}
		} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		return actionResult;
	}
	
	public static BaseRecord newActionParameters(AssessmentEnumType needType, String needName, String actionName) {

		BaseRecord actionParams = null;
		try {
			actionParams = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACTION_PARAMETERS);
			actionParams.set("actionName", actionName);
			actionParams.set("needType", needType);
			actionParams.set("needName", needName);

		} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		return actionParams;
	}
	
	public static void loadActions(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(ctx.getOlioUser(), ModelNames.MODEL_ACTION, ctx.getWorld().get("actions.path")));
		// int count = IOSystem.getActiveContext().getAccessPoint().count(ctx.getOlioUser(), OlioUtil.getQuery(ctx.getOlioUser(), ModelNames.MODEL_ACTION, ctx.getWorld().get("actions.path")));
		if(count == 0) {
			importActions(ctx);
			ctx.processQueue();
		}
	}
	protected static BaseRecord[] importActions(OlioContext ctx) {
		// logger.info("Import default action configuration");
		List<BaseRecord> acts = JSONUtil.getList(ResourceUtil.getResource("olio/actions.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		List<BaseRecord> oacts = new ArrayList<>();
		try {
			
			for(BaseRecord act : acts) {
				ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("actions.path"));
				plist.parameter(FieldNames.FIELD_NAME, act.get(FieldNames.FIELD_NAME));

				BaseRecord actr = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACTION, ctx.getOlioUser(), act, plist);
				List<BaseRecord> tags = actr.get("tags");
				List<BaseRecord> itags = new ArrayList<>();
				for(BaseRecord t: tags) {
					itags.add(OlioUtil.getCreateTag(ctx, t.get(FieldNames.FIELD_NAME), act.getModel()));
				}
				actr.set("tags", itags);
				ctx.queue(actr);
				oacts.add(actr);
			}
			
		}
		catch(ModelNotFoundException | FieldException | ValueException | FactoryException e) {
			logger.error(e);
		}

		return oacts.toArray(new BaseRecord[0]);
	}
	
}
