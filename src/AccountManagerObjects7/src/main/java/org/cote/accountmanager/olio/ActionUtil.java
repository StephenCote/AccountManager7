package org.cote.accountmanager.olio;

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

public class ActionUtil {
	public static final Logger logger = LogManager.getLogger(ActionUtil.class);
	
	protected static BaseRecord newAction(OlioContext ctx, String name) {
		BaseRecord rec = null;
		ParameterList plist = ParameterList.newParameterList("path", ctx.getUniverse().get("actions.path"));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ITEM, ctx.getUser(), null, plist);
		}
		catch(FactoryException e) {
			logger.error(e);
		}
		return rec;
	}
	
	public static BaseRecord[] getActions(OlioContext ctx) {
		return OlioUtil.list(ctx, ModelNames.MODEL_ACTION, "actions");
	}
}
