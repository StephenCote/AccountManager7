package org.cote.accountmanager.console.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;

public class TestAction extends CommonAction {

	@Override
	public void handleCommand(CommandLine cmd) {
		
	}

	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		if(cmd.hasOption("test") && cmd.hasOption("vector")) {
			logger.info("Testing vector store");
			List<BaseRecord> store = new ArrayList<>();
			try {
				store = IOSystem.getActiveContext().getVectorUtil().createVectorStore(user, "Random content - " + UUID.randomUUID(), ChunkEnumType.UNKNOWN, 0);
				if(store != null) {
					logger.info("Created: " + store.size());
				}
			} catch (FieldException e) {
				logger.error(e);
			}
			if(store == null || store.size() == 0) {
				logger.error("Expected a vector store.");
			}
		}
	}

	@Override
	public void addOptions(Options options) {
		options.addOption("vector", false, "Generic bit for testing vector setup");
		options.addOption("test", false, "Generic bit for testing");
		
	}

}
