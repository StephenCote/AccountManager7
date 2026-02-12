package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * Processes structured prompt templates (olio.llm.promptTemplate) into composed text.
 *
 * Pipeline:
 * 1. Resolve inheritance chain (extends field)
 * 2. Merge parent sections with child overrides
 * 3. Evaluate conditions to include/exclude sections
 * 4. Order sections by sectionOrder or priority
 * 5. Join section lines into composed template text
 * 6. Delegate token replacement to PromptUtil.getChatPromptTemplate()
 */
public class PromptTemplateComposer {
	private static final Logger logger = LogManager.getLogger(PromptTemplateComposer.class);

	private static final Pattern UNREPLACED_TOKEN = Pattern.compile("\\$\\{[^}]+\\}");
	private static final int MAX_INHERITANCE_DEPTH = 10;

	private PromptTemplateComposer() {}

	/**
	 * Compose a structured template into final prompt text for a specific role.
	 *
	 * @param template    The olio.llm.promptTemplate record
	 * @param promptConfig The olio.llm.promptConfig record (for token replacement)
	 * @param chatConfig   The olio.llm.chatConfig record (for conditions + token replacement)
	 * @param role         Role to filter sections by (system, user, assistant). Null = use template default.
	 * @return Composed prompt text with tokens replaced, or null on error
	 */
	public static String compose(BaseRecord template, BaseRecord promptConfig, BaseRecord chatConfig, String role) {
		if (template == null) {
			logger.error("Template is null");
			return null;
		}

		String targetRole = role;
		if (targetRole == null || targetRole.isEmpty()) {
			targetRole = template.get("role");
			if (targetRole == null || targetRole.isEmpty()) {
				targetRole = "system";
			}
		}

		// Step 1-2: Resolve inheritance and merge sections
		Map<String, BaseRecord> mergedSections = resolveInheritance(template, 0);

		// Step 3: Evaluate conditions and filter
		List<BaseRecord> activeSections = new ArrayList<>();
		for (BaseRecord section : mergedSections.values()) {
			String sectionRole = section.get("role");
			// Include section if its role matches target or section has no role override
			if (sectionRole != null && !sectionRole.isEmpty() && !sectionRole.equals(targetRole)) {
				continue;
			}
			String condition = section.get("condition");
			if (PromptConditionEvaluator.evaluate(condition, chatConfig)) {
				activeSections.add(section);
			}
		}

		// Step 4: Order by sectionOrder or priority
		List<String> sectionOrder = template.get("sectionOrder");
		if (sectionOrder != null && !sectionOrder.isEmpty()) {
			activeSections = orderBySectionOrder(activeSections, sectionOrder);
		} else {
			activeSections.sort(Comparator.comparingInt(s -> (int) s.get("priority")));
		}

		// Step 5: Join section lines
		StringBuilder composed = new StringBuilder();
		for (BaseRecord section : activeSections) {
			List<String> lines = section.get("lines");
			if (lines != null && !lines.isEmpty()) {
				if (composed.length() > 0) {
					composed.append(System.lineSeparator());
				}
				composed.append(lines.stream().collect(Collectors.joining(System.lineSeparator())));
			}
		}

		// Step 6: Run through PromptUtil token replacement pipeline
		String composedText = composed.toString();
		if (promptConfig != null) {
			composedText = PromptUtil.getChatPromptTemplate(promptConfig, chatConfig, composedText);
		}

		return composedText.trim();
	}

	/**
	 * Compose a template for the system role (convenience method).
	 */
	public static String composeSystem(BaseRecord template, BaseRecord promptConfig, BaseRecord chatConfig) {
		return compose(template, promptConfig, chatConfig, "system");
	}

	/**
	 * Compose a template for the user role (convenience method).
	 */
	public static String composeUser(BaseRecord template, BaseRecord promptConfig, BaseRecord chatConfig) {
		return compose(template, promptConfig, chatConfig, "user");
	}

	/**
	 * Compose a template for the assistant role (convenience method).
	 */
	public static String composeAssistant(BaseRecord template, BaseRecord promptConfig, BaseRecord chatConfig) {
		return compose(template, promptConfig, chatConfig, "assistant");
	}

	/**
	 * Get all active sections for a template, grouped by role.
	 * Returns a map of role -> composed text.
	 */
	public static Map<String, String> composeAllRoles(BaseRecord template, BaseRecord promptConfig, BaseRecord chatConfig) {
		Map<String, String> result = new LinkedHashMap<>();
		result.put("system", compose(template, promptConfig, chatConfig, "system"));
		result.put("user", compose(template, promptConfig, chatConfig, "user"));
		result.put("assistant", compose(template, promptConfig, chatConfig, "assistant"));
		return result;
	}

	/**
	 * Validate a composed template for unreplaced tokens.
	 * Returns list of unreplaced token strings, or empty list if all resolved.
	 * Tokens matching known runtime-only patterns (image.*, audio.*) are excluded.
	 */
	public static List<String> findUnreplacedTokens(String composedText) {
		List<String> unreplaced = new ArrayList<>();
		if (composedText == null) return unreplaced;
		Matcher m = UNREPLACED_TOKEN.matcher(composedText);
		while (m.find()) {
			String token = m.group();
			// Skip runtime-only tokens that are resolved at message time, not composition time
			// Also skip nlp.* tokens which are pipeline ordering artifacts (Stage 6 runs before Stage 7 dynamic rules expansion)
			if (token.startsWith("${image.") || token.startsWith("${audio.") || token.startsWith("${nlp.")) {
				continue;
			}
			unreplaced.add(token);
		}
		return unreplaced;
	}

	/**
	 * Resolve template inheritance chain and merge sections.
	 * Child sections override parent sections with the same sectionName.
	 */
	private static Map<String, BaseRecord> resolveInheritance(BaseRecord template, int depth) {
		if (depth > MAX_INHERITANCE_DEPTH) {
			logger.error("Template inheritance depth exceeds maximum (" + MAX_INHERITANCE_DEPTH + ")");
			return new LinkedHashMap<>();
		}

		Map<String, BaseRecord> sections = new LinkedHashMap<>();

		// Resolve parent first if extends is set
		String parentName = template.get("extends");
		if (parentName != null && !parentName.isEmpty()) {
			BaseRecord parent = findTemplateByName(parentName);
			if (parent != null) {
				sections = resolveInheritance(parent, depth + 1);
			} else {
				logger.warn("Parent template '" + parentName + "' not found");
			}
		}

		// Overlay child sections (override by sectionName)
		List<BaseRecord> childSections = template.get("sections");
		if (childSections != null) {
			for (BaseRecord section : childSections) {
				String name = section.get("sectionName");
				if (name != null && !name.isEmpty()) {
					sections.put(name, section);
				}
			}
		}

		return sections;
	}

	/**
	 * Find a prompt template record by name in the system.
	 */
	private static BaseRecord findTemplateByName(String name) {
		try {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_TEMPLATE, FieldNames.FIELD_NAME, name);
			q.planMost(true);
			BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
			if (recs != null && recs.length > 0) {
				return recs[0];
			}
		} catch (Exception e) {
			logger.warn("Error finding template '" + name + "': " + e.getMessage());
		}
		return null;
	}

	/**
	 * Order sections by explicit sectionOrder list.
	 * Sections not in the order list are appended at the end sorted by priority.
	 */
	private static List<BaseRecord> orderBySectionOrder(List<BaseRecord> sections, List<String> order) {
		Map<String, BaseRecord> byName = new HashMap<>();
		for (BaseRecord s : sections) {
			String name = s.get("sectionName");
			if (name != null) {
				byName.put(name, s);
			}
		}

		List<BaseRecord> ordered = new ArrayList<>();
		for (String name : order) {
			BaseRecord s = byName.remove(name);
			if (s != null) {
				ordered.add(s);
			}
		}

		// Append remaining sections not in the explicit order, sorted by priority
		List<BaseRecord> remaining = new ArrayList<>(byName.values());
		remaining.sort(Comparator.comparingInt(s -> (int) s.get("priority")));
		ordered.addAll(remaining);

		return ordered;
	}
}
