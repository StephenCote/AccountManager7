package org.cote.accountmanager.olio.llm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.LibraryUtil;

public class ChatLibraryUtil {

	private static final Logger logger = LogManager.getLogger(ChatLibraryUtil.class);

	public static final String LIBRARY_CHAT_CONFIGS = "ChatConfigs";
	public static final String LIBRARY_PROMPT_CONFIGS = "PromptConfigs";
	public static final String LIBRARY_PATH_CHAT = LibraryUtil.basePath + "/" + LIBRARY_CHAT_CONFIGS;
	public static final String LIBRARY_PATH_PROMPT = LibraryUtil.basePath + "/" + LIBRARY_PROMPT_CONFIGS;

	public static BaseRecord getCreateChatConfigLibrary(BaseRecord user) {
		LibraryUtil.configureLibraryRootReader(user);
		return LibraryUtil.getCreateSharedLibrary(user, LIBRARY_CHAT_CONFIGS, true);
	}

	public static BaseRecord getCreatePromptConfigLibrary(BaseRecord user) {
		LibraryUtil.configureLibraryRootReader(user);
		return LibraryUtil.getCreateSharedLibrary(user, LIBRARY_PROMPT_CONFIGS, true);
	}

	public static BaseRecord findLibraryDir(BaseRecord user, String name) {
		IOContext ctx = IOSystem.getActiveContext();
		OrganizationContext octx = ctx.getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if (octx == null) {
			return null;
		}
		return ctx.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_GROUP, LibraryUtil.basePath + "/" + name, GroupEnumType.DATA.toString(), octx.getOrganizationId());
	}

	public static boolean isLibraryPopulated(BaseRecord user) {
		BaseRecord dir = findLibraryDir(user, LIBRARY_CHAT_CONFIGS);
		if (dir == null) {
			return false;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, "generalChat");
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().findRecord(q) != null;
	}

	public static boolean isPromptLibraryPopulated(BaseRecord user) {
		BaseRecord dir = findLibraryDir(user, LIBRARY_PROMPT_CONFIGS);
		if (dir == null) {
			return false;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_NAME, "default");
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().findRecord(q) != null;
	}

	public static void populatePromptDefaults(BaseRecord user) {
		IOContext ctx = IOSystem.getActiveContext();
		OrganizationContext octx = ctx.getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if (octx == null) {
			logger.error("Failed to find organization context");
			return;
		}
		BaseRecord adminUser = octx.getAdminUser();
		BaseRecord promptLibDir = getCreatePromptConfigLibrary(user);
		if (promptLibDir == null) {
			logger.error("Failed to create prompt library directory");
			return;
		}
		BaseRecord defaultPrompt = ChatUtil.getDefaultPrompt();
		if (defaultPrompt != null) {
			createLibraryPromptConfig(adminUser, promptLibDir, "default", defaultPrompt);
		}
	}

	public static void populateDefaults(BaseRecord user, String serverUrl, String model, String serviceType) {
		IOContext ctx = IOSystem.getActiveContext();
		OrganizationContext octx = ctx.getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if (octx == null) {
			logger.error("Failed to find organization context");
			return;
		}
		BaseRecord adminUser = octx.getAdminUser();

		BaseRecord chatLibDir = getCreateChatConfigLibrary(user);
		BaseRecord promptLibDir = getCreatePromptConfigLibrary(user);

		if (chatLibDir == null || promptLibDir == null) {
			logger.error("Failed to create library directories");
			return;
		}

		String[] templateNames = ChatUtil.getChatConfigTemplateNames();
		for (String templateName : templateNames) {
			createLibraryChatConfig(adminUser, chatLibDir, templateName, templateName, serverUrl, model, serviceType);
		}

		/// Create "Open Chat" as an alias for the generalChat template
		createLibraryChatConfig(adminUser, chatLibDir, "Open Chat", "generalChat", serverUrl, model, serviceType);

		/// Create default prompt config
		BaseRecord defaultPrompt = ChatUtil.getDefaultPrompt();
		if (defaultPrompt != null) {
			createLibraryPromptConfig(adminUser, promptLibDir, "default", defaultPrompt);
		}
	}

	private static void createLibraryChatConfig(BaseRecord adminUser, BaseRecord libDir, String name, String templateName, String serverUrl, String model, String serviceType) {
		/// Check if already exists (idempotent)
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, libDir.get(FieldNames.FIELD_ID));
		if (IOSystem.getActiveContext().getSearch().findRecord(q) != null) {
			return;
		}

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, libDir.get("path"));
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, adminUser, null, plist);

			ChatUtil.applyChatConfigTemplate(cfg, templateName);

			if (serverUrl != null) {
				cfg.set("serverUrl", serverUrl);
			}
			if (model != null) {
				cfg.set("model", model);
			}
			if (serviceType != null) {
				cfg.set("serviceType", serviceType);
			}

			IOSystem.getActiveContext().getAccessPoint().create(adminUser, cfg);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to create library chat config '" + name + "': " + e.getMessage());
		}
	}

	private static void createLibraryPromptConfig(BaseRecord adminUser, BaseRecord libDir, String name, BaseRecord template) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, libDir.get(FieldNames.FIELD_ID));
		if (IOSystem.getActiveContext().getSearch().findRecord(q) != null) {
			return;
		}

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, libDir.get("path"));
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, adminUser, template, plist);

			IOSystem.getActiveContext().getAccessPoint().create(adminUser, cfg);
		} catch (FactoryException e) {
			logger.error("Failed to create library prompt config '" + name + "': " + e.getMessage());
		}
	}
}
