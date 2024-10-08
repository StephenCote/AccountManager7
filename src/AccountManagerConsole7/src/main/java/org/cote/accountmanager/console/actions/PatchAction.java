package org.cote.accountmanager.console.actions;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class PatchAction extends CommonAction implements IAction {

	@Override
	public void handleCommand(CommandLine cmd) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		if(cmd.hasOption("list")) {
			if(cmd.hasOption(OlioFieldNames.FIELD_COLOR)) {
				OlioContext octx = OlioContextUtil.getGridContext(user, getProperties().getProperty("test.datagen.path"), "My Grid Universe", "My Grid World", cmd.hasOption("reset"));
				BaseRecord dir = octx.getWorld().get(OlioFieldNames.FIELD_COLORS);
				Query q = QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				String filter = cmd.getOptionValue("filter");
				if(filter != null) {
					q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, filter);
				}

				q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, "hex"});
				QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
				for(BaseRecord r : qr.getResults()) {
					String name = r.get(FieldNames.FIELD_NAME);
					System.out.println("{id:" + r.get(FieldNames.FIELD_ID) + ", name: \"" + name + "\", hex:\"" + r.get("hex") + "\"}");
				}
			}
		}
		if(cmd.hasOption("patch") && cmd.hasOption("model") && cmd.hasOption("objectId")) {
			BaseRecord targ = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, cmd.getOptionValue("model"), cmd.getOptionValue("objectId"));
			if(targ != null) {
				BaseRecord src = RecordFactory.importRecord(cmd.getOptionValue("model"), cmd.getOptionValue("patch"));
				if(src != null) {
					IOSystem.getActiveContext().getRecordUtil().patch(src, targ);
				}
			}
		}
		
	}

	@Override
	public void addOptions(Options options) {
		options.addOption("patch", true, "Value to patch");
		options.addOption("model", true, "A model name");
		options.addOption("objectId", true, "An object id");
	}

}
