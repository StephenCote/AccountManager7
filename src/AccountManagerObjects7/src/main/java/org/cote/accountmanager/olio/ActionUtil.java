package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.parsers.data.WordParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ActionUtil {
	public static final Logger logger = LogManager.getLogger(ActionUtil.class);
	
	protected static BaseRecord newAction(OlioContext ctx, String name) {
		BaseRecord rec = null;
		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("actions.path"));
		if(name != null) {
			plist.parameter(FieldNames.FIELD_NAME, name);
		}
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACTION, ctx.getUser(), null, plist);
		}
		catch(FactoryException e) {
			logger.error(e);
		}
		return rec;
	}
	
	public static BaseRecord[] getActions(OlioContext ctx) {
		return OlioUtil.list(ctx, ModelNames.MODEL_ACTION, "actions");
	}
	
	public static void loadActions(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getAccessPoint().count(ctx.getUser(), OlioUtil.getQuery(ctx.getUser(), ModelNames.MODEL_ACTION, ctx.getWorld().get("actions.path")));
		if(count == 0) {
			BaseRecord[] actions = importActions(ctx);
			IOSystem.getActiveContext().getRecordUtil().createRecords(actions);
		}
	}
	protected static BaseRecord[] importActions(OlioContext ctx) {
		logger.info("Import default action configuration");
		String[] builders = JSONUtil.importObject(ResourceUtil.getResource("./olio/actions.json"), String[].class);
		List<BaseRecord> acts = new ArrayList<>();
		try {
			for(String build : builders) {
				BaseRecord act = newAction(ctx, null);
				String[] pairs = build.split(":");
				if(pairs.length < 12) {
					logger.error("Unexpected format - expected at least 12 pairs, found " + pairs.length);
					logger.error(build);
					continue;
				}
				act.set(FieldNames.FIELD_NAME, pairs[0]);
				
				if(pairs[1].length() > 0) {
					List<String> ca = act.get("counterActions");
					ca.addAll(Arrays.asList(pairs[1].split(",")));
				}
				if(pairs[2].length() > 0) {
					act.set("minimumTime", Integer.parseInt(pairs[2]));
				}
				if(pairs[3].length() > 0) {
					act.set("maximumTime", Integer.parseInt(pairs[3]));
				}
				if(pairs[4].length() > 0) {
					act.set("minimumEnergyCost", Double.parseDouble(pairs[4]));
				}
				/// 5 - positive per
				if(pairs[5].length() > 0) {
					List<String> ca = act.get("positivePersonality");
					ca.addAll(Arrays.asList(pairs[5].split(",")));
				}
				/// 6 - negative per
				if(pairs[6].length() > 0) {
					List<String> ca = act.get("negativePersonality");
					ca.addAll(Arrays.asList(pairs[6].split(",")));
				}
				
				/// 7 - positive instinct
				if(pairs[7].length() > 0) {
					List<String> ca = act.get("positiveInstincts");
					ca.addAll(Arrays.asList(pairs[7].split(",")));
				}

				/// 8 - negative instinct
				if(pairs[8].length() > 0) {
					List<String> ca = act.get("negativeInstincts");
					ca.addAll(Arrays.asList(pairs[8].split(",")));
				}

				/// 9 - positive stat
				if(pairs[9].length() > 0) {
					List<String> ca = act.get("positiveStatistics");
					ca.addAll(Arrays.asList(pairs[9].split(",")));
				}
				
				/// 10 - negative stat
				if(pairs[10].length() > 0) {
					List<String> ca = act.get("negativeStatistics");
					ca.addAll(Arrays.asList(pairs[10].split(",")));
				}
				
				/// 11 - positive state
				if(pairs[11].length() > 0) {
					List<String> ca = act.get("positiveStates");
					ca.addAll(Arrays.asList(pairs[11].split(",")));
				}

				/// 12 - negative state
				if(pairs.length > 12 && pairs[12].length() > 0) {
					List<String> ca = act.get("negativeStates");
					ca.addAll(Arrays.asList(pairs[12].split(",")));
				}

				acts.add(act);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return acts.toArray(new BaseRecord[0]);
	}
	
}
