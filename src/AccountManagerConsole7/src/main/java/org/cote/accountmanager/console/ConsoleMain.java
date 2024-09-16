package org.cote.accountmanager.console;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.console.actions.ActionUtil;
import org.cote.accountmanager.console.actions.AdminAction;
import org.cote.accountmanager.console.actions.ChatAction;
import org.cote.accountmanager.console.actions.ExportAction;
import org.cote.accountmanager.console.actions.IAction;
import org.cote.accountmanager.console.actions.PatchAction;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;

public class ConsoleMain {
	public static final Logger logger = LogManager.getLogger(ConsoleMain.class);
	private static IOContext ioContext = null;
	private static OrganizationContext orgContext = null;

	// private static BaseRecord user = null;
	
	private static IAction adminAction = new AdminAction();
	private static IAction[] actions = new IAction[] {
		new ChatAction(),
		new PatchAction(),
		new ExportAction()
	};
	
	public static void main(String[] args){
		logger.info("AM7 Console");		
		Properties properties = loadProperties();
		Options options = new Options();
		options.addOption("organization",true,"AccountManager Organization Path");
		options.addOption("username", true, "AccountManager user name");
		options.addOption("password",true,"AccountManager password");
		options.addOption("reset", false, "Generic bit to indicate a value reset");
		options.addOption("resetPassword", false, "Reset user password");
		options.addOption("inspect", false, "Generic bit");
		options.addOption(OlioFieldNames.FIELD_COLOR, false, "Generic bit");
		options.addOption("pattern", false, "Generic bit");
		options.addOption(FieldNames.FIELD_NAME, true, "Generic placeholder");
		options.addOption(FieldNames.FIELD_PATH, true, "Generic placeholder");
		options.addOption("delete", false, "Generic placeholder");
		options.addOption("update", false, "Generic bit to indicate a value update");
		options.addOption("list", false, "Generic bit to list values");
		options.addOption("filter", true, "Generic placeholder to filter lists");
		options.addOption("import", false, "Generic bit");
		options.addOption("export", false, "Generic bit");
		options.addOption("debug", false, "Generic bit");
		

		
		adminAction.addOptions(options);
		adminAction.setProperties(properties);
		for(IAction act : actions) {
			act.addOptions(options);
			act.setProperties(properties);
		}
		
		CommandLineParser parser = new DefaultParser();
		try {
			CommandLine cmd = parser.parse( options, args);
			logger.info("Initialize Context ...");
			startContext(cmd.hasOption("setup"));
			if(ioContext == null) {
				logger.error("Unable to initiate IOContext - proceed to setup");
			}

			if(ioContext != null) {
				adminAction.handleCommand(cmd);

				if(cmd.hasOption("organization") && cmd.hasOption("username") && cmd.hasOption("password")) {
					BaseRecord user = ActionUtil.login(cmd.getOptionValue("organization"), cmd.getOptionValue("username"), cmd.getOptionValue("password"));
					if(user != null) {
						for(IAction act: actions) {
							act.handleCommand(cmd, user);
						}
					}
					else {
						logger.warn("Failed to authenticate as " + cmd.getOptionValue("username"));
					}
				}
			}
		} catch (ParseException e) {
			logger.error(e);
			e.printStackTrace();
		}
		logger.info("... Closing Context");
		if(ioContext != null) {
			clearIO();
		}
		/*
		ChatMain chat = new ChatMain();
		chat.startContext();
		(new ChatMain()).chatConsole();
		chat.clearIO();
		*/
	}
	
	private static void startContext(boolean setup) {
		OlioModelNames.use();
		Properties properties = loadProperties();
		IOFactory.DEFAULT_FILE_BASE = properties.getProperty("app.basePath");
		resetContext(properties.getProperty("test.db.url"), properties.getProperty("test.db.user"), properties.getProperty("test.db.password"), setup && Boolean.parseBoolean(properties.getProperty("test.db.reset")));
	}

	private static void resetContext(String dataUrl, String dataUser, String dataPassword, boolean reset) {
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(dataUrl);
		props.setDataSourceUserName(dataUser);
		props.setDataSourcePassword(dataPassword);
		props.setSchemaCheck(false);
		props.setReset(reset);
		resetIO(RecordIO.DATABASE, props);
	}
	private static void resetIO(RecordIO ioType, IOProperties properties) {
		clearIO();
		IOContext octx = null;
		try {
			octx = IOSystem.open(ioType, properties);
			if(!octx.isInitialized()) {
				logger.error("Context cannot be initialized");
				octx = null;
			}
		} catch (StackOverflowError | Exception e) {
			octx = null;
			logger.error(e);
			e.printStackTrace();
		}
		ioContext = octx;
	}
	
	
	
	protected static void clearIO() {
		IOSystem.close();
		ioContext = null;
		orgContext = null;
	}
	private static Properties loadProperties() {
		Properties properties = new Properties();
		try {
			InputStream fis = ClassLoader.getSystemResourceAsStream("resource.properties"); 
			properties.load(fis);
			fis.close();
		} catch (IOException e) {
			logger.error(e);
			return null;
		}
		return properties;
	}
}
