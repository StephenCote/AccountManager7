package org.cote.accountmanager.console.actions;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;

public class PatchAction extends CommonAction implements IAction {

	@Override
	public void handleCommand(CommandLine cmd) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		if(cmd.hasOption("patch") && cmd.hasOption("model") && cmd.hasOption("objectId")) {
			BaseRecord targ = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, cmd.getOptionValue("model"), cmd.getOptionValue("objectId"));
			if(targ != null) {
				BaseRecord src = RecordFactory.importRecord(cmd.getOptionValue("model"), cmd.getOptionValue("patch"));
				if(src != null) {
					ActionUtil.patch(src, targ);
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
