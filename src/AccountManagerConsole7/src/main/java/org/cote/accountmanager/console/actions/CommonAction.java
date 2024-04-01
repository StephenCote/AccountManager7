package org.cote.accountmanager.console.actions;

import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class CommonAction implements IAction{
	public static final Logger logger = LogManager.getLogger(CommonAction.class);
	private Properties properties = null;
	public void setProperties(Properties properties) {
		this.properties = properties;
	}
	public Properties getProperties() {
		return properties;
	}
}
