package org.cote.accountmanager.agent;

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
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.util.VectorUtil;

public class Assistant {
	public static final Logger logger = LogManager.getLogger(Assistant.class);
	private static IOContext ioContext = null;
	private static OrganizationContext orgContext = null;

	public static void main(String[] args){

		logger.info("AM7 Console");		
		Properties properties = loadProperties();
		Options options = new Options();
		options.addOption("organization",true,"AccountManager Organization Path");
		options.addOption("username", true, "AccountManager user name");
		options.addOption("password",true,"AccountManager password");

		CommandLineParser parser = new DefaultParser();
		try {
			CommandLine cmd = parser.parse( options, args);
			logger.info("Initialize Context ...");
			startContext(cmd.hasOption("setup"));
			if(ioContext == null) {
				logger.error("Unable to initiate IOContext - proceed to setup");
			}

			if(ioContext != null) {
				
				if(cmd.hasOption("organization") && cmd.hasOption("username") && cmd.hasOption("password")) {
					BaseRecord user = AgentUtil.login(cmd.getOptionValue("organization"), cmd.getOptionValue("username"), cmd.getOptionValue("password"));
					if(user != null) {
						/// Handle command
						logger.info("Authenticated");
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
	}
	
	private static void startContext(boolean setup) {
		OlioModelNames.use();
		Properties properties = loadProperties();
		IOFactory.DEFAULT_FILE_BASE = properties.getProperty("app.basePath");

		resetContext(properties.getProperty("test.db.url"), properties.getProperty("test.db.user"), properties.getProperty("test.db.password"), setup && Boolean.parseBoolean(properties.getProperty("test.db.reset")));
		if(ioContext != null) {
			ioContext.setVectorUtil(new VectorUtil(LLMServiceEnumType.valueOf(properties.getProperty("test.embedding.type").toUpperCase()), properties.getProperty("test.embedding.server"), properties.getProperty("test.embedding.authorizationToken")));
		}

		
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
