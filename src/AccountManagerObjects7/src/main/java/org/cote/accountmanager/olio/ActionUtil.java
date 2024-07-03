package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ActionUtil {
	public static final Logger logger = LogManager.getLogger(ActionUtil.class);
	
	public static BaseRecord[] getActions(OlioContext ctx) {
		return OlioUtil.list(ctx, ModelNames.MODEL_ACTION, "actions");
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
