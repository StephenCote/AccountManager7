package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.DocumentUtil;

/**
 * Migrates flat promptConfig records into structured promptTemplate records.
 *
 * Each non-empty list&lt;string&gt; field in promptConfig becomes a promptSection
 * with role and condition derived from the field name.
 */
public class PromptConfigMigrator {
	private static final Logger logger = LogManager.getLogger(PromptConfigMigrator.class);

	private static final String MIGRATED_PREFIX = "Migrated - ";
	private static final String TEMPLATE_PATH = "~/Chat";

	/** Field name prefix to role mapping */
	private static final Map<String, String> ROLE_MAP = new HashMap<>();
	/** Field name to condition mapping */
	private static final Map<String, String> CONDITION_MAP = new HashMap<>();

	static {
		ROLE_MAP.put("system", "system");
		ROLE_MAP.put("assistant", "assistant");
		ROLE_MAP.put("user", "user");

		CONDITION_MAP.put("systemNlp", "useNLP");
		CONDITION_MAP.put("assistantNlp", "useNLP");
		CONDITION_MAP.put("userConsentNlp", "useNLP");
		CONDITION_MAP.put("systemCensorWarning", "rating!=E");
		CONDITION_MAP.put("assistantCensorWarning", "rating!=E");
		CONDITION_MAP.put("episodeRule", "episodes.size>0");
		CONDITION_MAP.put("jailBreak", "useJailBreak");
	}

	private PromptConfigMigrator() {}

	/**
	 * Analyze a promptConfig for migration without making changes.
	 *
	 * @param promptConfig The promptConfig record to analyze
	 * @return MigrationReport with counts and section names
	 */
	public static MigrationReport analyze(BaseRecord promptConfig) {
		MigrationReport report = new MigrationReport();
		if (promptConfig == null) {
			return report;
		}

		ModelSchema schema = RecordFactory.getSchema(promptConfig.getSchema());
		if (schema == null) {
			return report;
		}

		int scanned = 0;
		int withContent = 0;
		List<String> sectionNames = new ArrayList<>();

		for (FieldSchema fs : schema.getFields()) {
			if (fs.getFieldType() != FieldEnumType.LIST) {
				continue;
			}
			if (!"string".equals(fs.getBaseType())) {
				continue;
			}
			scanned++;

			List<String> lines = promptConfig.get(fs.getName());
			if (lines != null && !lines.isEmpty()) {
				withContent++;
				sectionNames.add(fs.getName());
			}
		}

		report.setFieldsScanned(scanned);
		report.setFieldsWithContent(withContent);
		report.setSectionsToCreate(withContent);
		report.getSectionNames().addAll(sectionNames);
		return report;
	}

	/**
	 * Migrate a flat promptConfig to a structured promptTemplate.
	 *
	 * @param user         The user context for persistence
	 * @param promptConfig The promptConfig record to migrate
	 * @param apply        If true, persist the template to the database. If false, dry-run only.
	 * @return MigrationResult with migration details
	 */
	public static MigrationResult migrate(BaseRecord user, BaseRecord promptConfig, boolean apply) {
		MigrationResult result = new MigrationResult();
		result.setDryRun(!apply);

		if (promptConfig == null || user == null) {
			return result;
		}

		String configName = promptConfig.get(FieldNames.FIELD_NAME);
		String templateName = MIGRATED_PREFIX + (configName != null ? configName : "unnamed");
		result.setTemplateName(templateName);

		// Idempotency check
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_PROMPT_TEMPLATE, templateName, TEMPLATE_PATH);
		if (existing != null) {
			result.setAlreadyExists(true);
			return result;
		}

		MigrationReport report = analyze(promptConfig);
		result.setSectionsCreated(report.getSectionsToCreate());

		if (!apply) {
			return result;
		}

		// Build sections
		List<BaseRecord> sections = new ArrayList<>();
		int priority = 10;

		ModelSchema schema = RecordFactory.getSchema(promptConfig.getSchema());
		if (schema == null) {
			logger.error("Could not resolve schema for: " + promptConfig.getSchema());
			return result;
		}

		for (FieldSchema fs : schema.getFields()) {
			if (fs.getFieldType() != FieldEnumType.LIST) {
				continue;
			}
			if (!"string".equals(fs.getBaseType())) {
				continue;
			}

			List<String> lines = promptConfig.get(fs.getName());
			if (lines == null || lines.isEmpty()) {
				continue;
			}

			try {
				BaseRecord section = RecordFactory.newInstance(OlioModelNames.MODEL_PROMPT_SECTION);
				section.set("sectionName", fs.getName());
				section.set("role", deriveRole(fs.getName()));
				section.set("lines", new ArrayList<>(lines));
				section.set("priority", priority);

				String condition = CONDITION_MAP.get(fs.getName());
				if (condition != null) {
					section.set("condition", condition);
				}

				sections.add(section);
				priority += 10;
			} catch (FieldException | ModelNotFoundException | ValueException e) {
				logger.error("Error creating section for field '" + fs.getName() + "': " + e.getMessage());
			}
		}

		// Create the template record
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, TEMPLATE_PATH);
			plist.parameter(FieldNames.FIELD_NAME, templateName);

			BaseRecord template = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_PROMPT_TEMPLATE, user, null, plist
			);
			template.set("templateVersion", 1);
			template.set("role", "system");
			template.set("sections", sections);

			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, template);
			if (created == null) {
				logger.error("Failed to persist template '" + templateName + "'");
				result.setSectionsCreated(0);
			} else {
				result.setFieldsUpdated(sections.size());
			}
		} catch (FactoryException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error("Error creating template: " + e.getMessage());
			result.setSectionsCreated(0);
		}

		return result;
	}

	/**
	 * Derive the role from a field name prefix.
	 * system* -> system, assistant* -> assistant, user* -> user, others -> system
	 */
	static String deriveRole(String fieldName) {
		if (fieldName == null) {
			return "system";
		}
		for (Map.Entry<String, String> entry : ROLE_MAP.entrySet()) {
			if (fieldName.startsWith(entry.getKey())) {
				return entry.getValue();
			}
		}
		return "system";
	}
}
