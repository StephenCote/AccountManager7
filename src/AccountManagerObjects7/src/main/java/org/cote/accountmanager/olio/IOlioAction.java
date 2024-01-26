package org.cote.accountmanager.olio;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.ActionResultEnumType;

public interface IOlioAction {
	public Options getCommandLineOptions();
	public ActionResultEnumType execute(OlioContext ctx, BaseRecord realm, BaseRecord currentEvent);
	public void execute(Console processor, CommandLine command, String[] line);
}
