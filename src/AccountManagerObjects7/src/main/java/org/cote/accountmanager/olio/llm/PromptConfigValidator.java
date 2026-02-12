package org.cote.accountmanager.olio.llm;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

/**
 * Validates prompt config records for unknown or unreplaced template tokens.
 *
 * Scans all list&lt;string&gt; fields in a promptConfig for ${...} tokens and
 * checks each against the known set from TemplatePatternEnumType.
 * Runtime-only tokens (image.*, audio.*, nlp.*) are always allowed.
 */
public class PromptConfigValidator {
	private static final Logger logger = LogManager.getLogger(PromptConfigValidator.class);

	private static final Pattern TOKEN_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

	private static final Set<String> KNOWN_TOKENS = new HashSet<>();
	private static final String[] RUNTIME_PREFIXES = {"image.", "audio.", "nlp."};

	static {
		for (TemplatePatternEnumType t : TemplatePatternEnumType.values()) {
			KNOWN_TOKENS.add(t.getKey());
		}
	}

	private PromptConfigValidator() {}

	/**
	 * Validate a promptConfig record for unknown tokens.
	 * Scans all list&lt;string&gt; fields for ${...} tokens and flags any
	 * that are not in the known set and not runtime-only.
	 */
	public static ValidationResult validate(BaseRecord promptConfig) {
		ValidationResult result = new ValidationResult();
		if (promptConfig == null) {
			return result;
		}

		ModelSchema schema = RecordFactory.getSchema(promptConfig.getSchema());
		if (schema == null) {
			logger.warn("Could not resolve schema for: " + promptConfig.getSchema());
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

			for (String line : lines) {
				Matcher m = TOKEN_PATTERN.matcher(line);
				while (m.find()) {
					String tokenKey = m.group(1);
					if (!isKnownToken(tokenKey) && !isRuntimeToken(tokenKey)) {
						result.addUnknownToken(tokenKey, fs.getName());
					}
				}
			}
		}

		return result;
	}

	/**
	 * Validate composed template text for unreplaced tokens.
	 * Delegates to PromptTemplateComposer.findUnreplacedTokens() and wraps
	 * the result in a ValidationResult.
	 */
	public static ValidationResult validateComposed(String composedText) {
		ValidationResult result = new ValidationResult();
		if (composedText == null) {
			return result;
		}

		List<String> unreplaced = PromptTemplateComposer.findUnreplacedTokens(composedText);
		for (String token : unreplaced) {
			// Extract the key from "${key}" format
			Matcher m = TOKEN_PATTERN.matcher(token);
			if (m.find()) {
				result.addUnknownToken(m.group(1), "composed");
			} else {
				result.addUnknownToken(token, "composed");
			}
		}

		return result;
	}

	private static boolean isKnownToken(String tokenKey) {
		return KNOWN_TOKENS.contains(tokenKey);
	}

	private static boolean isRuntimeToken(String tokenKey) {
		for (String prefix : RUNTIME_PREFIXES) {
			if (tokenKey.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}
}
