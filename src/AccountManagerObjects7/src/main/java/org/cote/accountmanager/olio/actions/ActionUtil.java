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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.AssessmentEnumType;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
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
			String name = a.get(OlioFieldNames.FIELD_ACTION_NAME2);
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
			actEvt = EventUtil.newEvent(context, parentEvent, type, eventName, parentEvent.get(OlioFieldNames.FIELD_EVENT_PROGRESS), actors, participants, influencers, false);
			IOSystem.getActiveContext().getRecordUtil().createRecord(actEvt);
		}
		return actEvt;
	}
	
	public static void updateTimeToDistance(BaseRecord actionResult, BaseRecord per1, BaseRecord per2) {
		double dist = GeoLocationUtil.getDistanceToState(per1.get(FieldNames.FIELD_STATE), per2.get(FieldNames.FIELD_STATE));
		long timeSeconds = (long)(dist / AnimalUtil.walkMetersPerSecond(per1));
		edgeSecondsUntilEnd(actionResult, timeSeconds);
	}
	
	public static void addProgressMS(BaseRecord actionResult, long ms) {
		ZonedDateTime prog = actionResult.get(OlioFieldNames.FIELD_ACTION_PROGRESS);
		actionResult.setValue(OlioFieldNames.FIELD_ACTION_PROGRESS, prog.plus(ms, ChronoUnit.MILLIS));
	}
	public static void addProgressSeconds(BaseRecord actionResult, int seconds) {
		ZonedDateTime prog = actionResult.get(OlioFieldNames.FIELD_ACTION_PROGRESS);
		actionResult.setValue(OlioFieldNames.FIELD_ACTION_PROGRESS, prog.plus(seconds, ChronoUnit.SECONDS));
	}
	
	public static void edgeSecondsUntilEnd(BaseRecord actionResult, long seconds) {
		ZonedDateTime end = actionResult.get(OlioFieldNames.FIELD_ACTION_END);
		actionResult.setValue(OlioFieldNames.FIELD_ACTION_END, end.plusSeconds(seconds));
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
		actions = OlioUtil.list(ctx, OlioModelNames.MODEL_ACTION, OlioFieldNames.FIELD_ACTIONS);
		return actions;
	}
	
	public static void setDestination(OlioContext ctx, BaseRecord actionResult, BaseRecord targState) {
		BaseRecord state = actionResult.get(FieldNames.FIELD_STATE);
		if(state == null) {
			try {
				state = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATE, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("states.path")));
				actionResult.set(FieldNames.FIELD_STATE, state);
			} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
				logger.error(e);
			}
		}
		state.setValue(OlioFieldNames.FIELD_CURRENT_LOCATION, targState.get(OlioFieldNames.FIELD_CURRENT_LOCATION));
		state.setValue(FieldNames.FIELD_CURRENT_NORTH, targState.get(FieldNames.FIELD_CURRENT_NORTH));
		state.setValue(FieldNames.FIELD_CURRENT_EAST, targState.get(FieldNames.FIELD_CURRENT_EAST));
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

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get(OlioFieldNames.FIELD_ACTION_RESULTS_PATH));
		BaseRecord actionResult = null;
		try {
			actionResult = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ACTION_RESULT, ctx.getOlioUser(), null, plist);
			actionResult.set(FieldNames.FIELD_ACTION, action);
			actionResult.set(OlioFieldNames.FIELD_BUILDER, null);
			actionResult.set(OlioFieldNames.FIELD_NEED_TYPE, params.get(OlioFieldNames.FIELD_NEED_TYPE));
			actionResult.set(OlioFieldNames.FIELD_NEED, params.get(OlioFieldNames.FIELD_NEED_NAME));
			actionResult.set(FieldNames.FIELD_PARAMETERS, params);
			actionResult.set(FieldNames.FIELD_TYPE, ActionResultEnumType.PENDING);
			if(interaction != null) {
				List<BaseRecord> iacts = actionResult.get(OlioFieldNames.FIELD_INTERACTIONS);
				iacts.add(interaction);
			}
		} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		return actionResult;
	}
	
	public static BaseRecord newActionParameters(AssessmentEnumType needType, String needName, String actionName) {
		return newActionParameters(needType, needName, actionName, false);
	}
	public static BaseRecord newActionParameters(AssessmentEnumType needType, String needName, String actionName, boolean autoComplete) {

		BaseRecord actionParams = null;
		try {
			actionParams = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ACTION_PARAMETERS);
			actionParams.set(OlioFieldNames.FIELD_ACTION_NAME, actionName);
			actionParams.set(OlioFieldNames.FIELD_NEED_TYPE, needType);
			actionParams.set(OlioFieldNames.FIELD_NEED_NAME, needName);
			actionParams.set(OlioFieldNames.FIELD_AUTOCOMPLETE, autoComplete);

		} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		return actionParams;
	}
	
	public static void loadActions(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(ctx.getOlioUser(), OlioModelNames.MODEL_ACTION, ctx.getWorld().get("actions.path")));
		// int count = IOSystem.getActiveContext().getAccessPoint().count(ctx.getOlioUser(), OlioUtil.getQuery(ctx.getOlioUser(), OlioModelNames.MODEL_ACTION, ctx.getWorld().get("actions.path")));
		if(count == 0) {
			importActions(ctx);
			Queue.processQueue();
		}
	}
	protected static BaseRecord[] importActions(OlioContext ctx) {
		// logger.info("Import default action configuration");
		List<BaseRecord> acts = JSONUtil.getList(ResourceUtil.getInstance().getResource("olio/actions.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		List<BaseRecord> oacts = new ArrayList<>();
		try {
			
			for(BaseRecord act : acts) {
				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("actions.path"));
				plist.parameter(FieldNames.FIELD_NAME, act.get(FieldNames.FIELD_NAME));

				BaseRecord actr = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ACTION, ctx.getOlioUser(), act, plist);
				List<BaseRecord> tags = actr.get(FieldNames.FIELD_TAGS);
				List<BaseRecord> itags = new ArrayList<>();
				for(BaseRecord t: tags) {
					itags.add(OlioUtil.getCreateTag(ctx, t.get(FieldNames.FIELD_NAME), act.getSchema()));
				}
				actr.set(FieldNames.FIELD_TAGS, itags);
				Queue.queue(actr);
				oacts.add(actr);
			}
			
		}
		catch(ModelNotFoundException | FieldException | ValueException | FactoryException e) {
			logger.error(e);
		}

		return oacts.toArray(new BaseRecord[0]);
	}
	
}
