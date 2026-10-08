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
import org.cote.accountmanager.console.actions.ExportAction;
import org.cote.accountmanager.console.actions.IAction;
import org.cote.accountmanager.console.actions.OlioAction;
import org.cote.accountmanager.console.actions.PatchAction;
import org.cote.accountmanager.console.actions.ServerConfigAction;
import org.cote.accountmanager.console.actions.TestAction;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.VectorUtil;

public class ConsoleMain {
	public static final Logger logger = LogManager.getLogger(ConsoleMain.class);
	private static IOContext ioContext = null;
	private static OrganizationContext orgContext = null;

	// private static BaseRecord user = null;
	
	private static IAction adminAction = new AdminAction();
	private static IAction[] actions = new IAction[] {
		new OlioAction(),
		new PatchAction(),
		new ExportAction(),
		new TestAction(),
		new ServerConfigAction()
	};
	
	public static void main(String[] args){
		logger.info("AM7 Console");		
		Properties properties = loadProperties();
		Options options = new Options();
		options.addOption("organization",true,"AccountManager Organization Path");
		options.addOption("username", true, "AccountManager user name");
		options.addOption("password",true,"AccountManager password");
		options.addOption("reset", false, "Reset the target: with -olio or -list -color, rebuild the grid context; with -chatConfig -episode, clear the episode state");
		options.addOption("resetPassword", false, "Reset user password");
		options.addOption("inspect", false, "With -olio -character1/-character2, print the character's full record");
		options.addOption(OlioFieldNames.FIELD_COLOR, false, "With -list, list the olio color palette (id, name, hex); combine with -filter");
		options.addOption("pattern", false, "Reserved; not currently read by any action");
		options.addOption(FieldNames.FIELD_NAME, true, "Name of the stored item to patch with -olio -update -wearable/-qualities");
		options.addOption(FieldNames.FIELD_PATH, true, "Group path for -export -type, or file path for -import/-export of a -chatConfig, -promptConfig or -session");
		options.addOption("delete", false, "Delete the named -chatConfig, -promptConfig or -session");
		options.addOption("update", false, "Apply the -episode, -wearable, -qualities, -statistics, -personality or -person value to the target");
		options.addOption("list", false, "List values: -episode (with -chatConfig), -color, or the olio population with -olio");
		options.addOption("filter", true, "Substring (SQL LIKE) filter applied to -list -color output");
		options.addOption("import", false, "Import a -chatConfig, -promptConfig or -session from the file at -path");
		options.addOption("export", false, "Export: the -type model under -path (ExportAction), a -chatConfig/-promptConfig/-session to -path, or generated SD images with -reimage/-refigure");
		options.addOption("debug", false, "Print the full request/response exchange for -duel");
		
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
		IOFactory.addPermittedPath(IOFactory.DEFAULT_FILE_BASE + "/.streams");
		boolean enableVector = Boolean.parseBoolean(properties.getProperty("test.vector.enable"));
		/// Opportunistic Ollama unload before GPU-heavy work; OFF unless explicitly enabled (a large
		/// model costs more to reload than the freed VRAM saves). Does not affect unloadAll(true).
		OllamaModelUtil.setUnloadEnabled(Boolean.parseBoolean(properties.getProperty(OllamaModelUtil.CONFIG_KEY)));
		/// Fallback SD checkpoint; names are per-Swarm-install and a wrong one fails silently (empty
		/// image list), so prefer setting this over relying on the olio.sd.config schema default.
		org.cote.accountmanager.olio.sd.SDUtil.setDefaultModel(
			properties.getProperty(org.cote.accountmanager.olio.sd.SDUtil.DEFAULT_MODEL_CONFIG_KEY));
		resetContext(properties.getProperty("test.db.url"), properties.getProperty("test.db.user"), properties.getProperty("test.db.password"), setup && Boolean.parseBoolean(properties.getProperty("test.db.reset")), Boolean.parseBoolean(properties.getProperty("db.schema.dropColumns")));
		if(ioContext != null) {
			VectorUtil vectorUtil = new VectorUtil(LLMServiceEnumType.valueOf(properties.getProperty("test.embedding.type").toUpperCase()), properties.getProperty("test.embedding.server"), properties.getProperty("test.embedding.authorizationToken"));
			/// Model name for the OpenAI-shaped embeddings body (openai / openai_compat); blank -> none sent.
			vectorUtil.getEmbedUtil().setEmbeddingModel(properties.getProperty("test.embedding.model"));
			ioContext.setVectorUtil(vectorUtil);
		}

		
	}

	private static void resetContext(String dataUrl, String dataUser, String dataPassword, boolean reset, boolean dropColumns) {
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(dataUrl);
		props.setDataSourceUserName(dataUser);
		props.setDataSourcePassword(dataPassword);
		props.setSchemaCheck(false);
		props.setReset(reset);
		props.setDropColumns(dropColumns);
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
