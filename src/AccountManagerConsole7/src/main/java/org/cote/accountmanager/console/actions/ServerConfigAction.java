package org.cote.accountmanager.console.actions;

import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.ServerConfigUtil;

/// List / set the six deployment media-AI server URLs (sd, face, tag, voice.tts, voice.stt,
/// embedding).
///
/// SCOPE, deliberately narrow (Stephen's decision): this action reads and writes the DB records
/// ONLY. It does NOT bind the live utility singletons — no syncBoundUtils, no setVoiceUtil. That is
/// not an oversight:
///   - IOContext.getVoiceUtil() is NULL in Console7 (ConsoleMain.java:127 only calls setVectorUtil),
///     so there is nothing here to bind.
///   - The CLI runs in a SEPARATE JVM from the Tomcat WAR, so binding anything in this process
///     could never affect the running server anyway.
/// Propagation to a running server happens on the server side, when ServerConfigUtil's 30s TTL
/// entry expires and is re-resolved (which re-applies voice/embedding onto the live singletons
/// there). Worst case is one TTL, which this action prints.
///
/// Options:
///   -serverConfig -list
///   -serverConfig -serverConfigName <name> -serverConfigUrl <url>
///   -serverConfig -serverConfigName <name> -apiKey <key>
///
/// Writes require the /System administrator, so -organization /System -username admin -password ...
/// (the authenticated form, handled by handleCommand(cmd, user)).
public class ServerConfigAction extends CommonAction implements IAction {

	public static final String OPT_ACTION = "serverConfig";
	public static final String OPT_NAME = "serverConfigName";
	public static final String OPT_URL = "serverConfigUrl";

	@Override
	public void addOptions(Options options) {
		options.addOption(OPT_ACTION, false, "List or set the deployment media/AI server configuration");
		options.addOption(OPT_NAME, true, "Deployment server config name: " + String.join(", ", ServerConfigUtil.SERVER_NAMES));
		options.addOption(OPT_URL, true, "Deployment server URL to set for -" + OPT_NAME);
		/// NOTE: -serverUrl and -apiKey are already registered by OlioAction; -apiKey is reused here
		/// on purpose, and -serverConfigUrl exists precisely to avoid colliding with -serverUrl.
	}

	/// Unauthenticated pass: listing only. Never writes.
	@Override
	public void handleCommand(CommandLine cmd) {
		if(!cmd.hasOption(OPT_ACTION)) {
			return;
		}
		if(cmd.hasOption(OPT_NAME) && (cmd.hasOption(OPT_URL) || cmd.hasOption("apiKey"))) {
			/// A write was requested: it needs an authenticated /System admin, which arrives in the
			/// handleCommand(cmd, user) pass.
			return;
		}
		list();
	}

	/// Authenticated pass: writes.
	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		if(!cmd.hasOption(OPT_ACTION)) {
			return;
		}
		String name = cmd.getOptionValue(OPT_NAME);
		String url = cmd.getOptionValue(OPT_URL);
		String apiKey = cmd.getOptionValue("apiKey");
		if(name == null || (url == null && apiKey == null)) {
			return;
		}
		if(!ServerConfigUtil.isServerName(name)) {
			logger.error("Unknown deployment server config name '" + name + "'. Expected one of: "
				+ String.join(", ", ServerConfigUtil.SERVER_NAMES));
			return;
		}
		/// The six URLs are /System-global deployment configuration, so the write must be performed
		/// by the /System administrator regardless of which organization the operator logged into.
		OrganizationContext sysOctx = IOSystem.getActiveContext().getOrganizationContext(
			OrganizationContext.SYSTEM_ORGANIZATION,
			org.cote.accountmanager.schema.type.OrganizationEnumType.SYSTEM);
		BaseRecord sysAdmin = (sysOctx != null ? sysOctx.getAdminUser() : null);
		if(sysAdmin == null) {
			logger.error("Cannot write server configuration: the " + OrganizationContext.SYSTEM_ORGANIZATION
				+ " administration user could not be resolved");
			return;
		}
		if(!ServerConfigUtil.putConnection(sysAdmin, name, url, apiKey)) {
			logger.error("Failed to write the server configuration for '" + name + "'");
			return;
		}
		logger.info("Set " + name + (url != null ? " = " + url : "") + (apiKey != null ? " (apiKey updated)" : ""));
		/// State the REAL propagation bound, per name class. A running AccountManagerService7 is a
		/// separate JVM, so the in-process invalidate() putConnection just performed cannot reach it.
		if(java.util.Arrays.asList(ServerConfigUtil.BOUND_SERVER_NAMES).contains(name)) {
			logger.warn("Propagation: '" + name + "' is bound into a long-lived server singleton"
				+ " (EmbeddingUtil/VoiceUtil), so it is refreshed by AccountManagerService7's maintenance"
				+ " thread rather than per request. Expect up to the cache TTL ("
				+ (ServerConfigUtil.CACHE_TTL_MS / 1000) + "s) plus one maintenance interval"
				+ " (maintenance.interval, default 10s) — typically under a minute. NOTE: this requires a"
				+ " Service7 build that includes ServerConfigRefreshThread; against an older build this"
				+ " name does NOT take effect until Tomcat is restarted.");
		}
		else {
			logger.warn("Propagation: '" + name + "' is resolved per request by AccountManagerService7,"
				+ " so the change takes effect within the cache TTL ("
				+ (ServerConfigUtil.CACHE_TTL_MS / 1000) + "s). No restart is required.");
		}
		list();
	}

	/// Print the six configured URLs. Never prints apiKeys.
	private void list() {
		Map<String, String> urls = ServerConfigUtil.listServerUrls();
		logger.info("Deployment media/AI server configuration (" + OrganizationContext.SYSTEM_ORGANIZATION + "-global):");
		for(String name : ServerConfigUtil.SERVER_NAMES) {
			String url = urls.get(name);
			logger.info(String.format("  %-12s %s%s", name,
				(url != null && url.length() > 0 ? url : "(not configured — the "
					+ ServerConfigUtil.getInitParameterName(name) + " web.xml value is used)"),
				(ServerConfigUtil.hasApiKey(name) ? "  [apiKey set]" : "")));
		}
	}
}
