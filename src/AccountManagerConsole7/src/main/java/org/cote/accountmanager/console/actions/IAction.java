package org.cote.accountmanager.console.actions;

import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.record.BaseRecord;

public interface IAction {
	public Properties getProperties();
	public void setProperties(Properties properties);
	public void handleCommand(CommandLine cmd);
	public void handleCommand(CommandLine cmd, BaseRecord user);
	public void addOptions(Options options);
}
