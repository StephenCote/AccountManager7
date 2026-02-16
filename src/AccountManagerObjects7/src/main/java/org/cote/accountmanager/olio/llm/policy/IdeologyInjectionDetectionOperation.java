package org.cote.accountmanager.olio.llm.policy;

import java.util.Map;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// ISO 42001 Bias Detection: Ideology injection in non-modern settings.
/// Covers bias areas #4 (Western deconstructed), #5 (Traditional subverted),
/// #8 (Moral arc injection), #9 (Ideology injection), #10 (Conservative as obstacle).
///
/// Only fires in non-modern settings (medieval, fantasy, historical, etc.).
/// Returns SUCCEEDED immediately for modern/contemporary settings.
///
/// Configurable via referenceFact factData JSON: minMatches (default 1), setting.
public class IdeologyInjectionDetectionOperation extends Operation {

	public static final int DEFAULT_MIN_MATCHES = 1;

	private static final String[] IDEOLOGY_PATTERNS = {
		/// #4: Western deconstructed
		"colonial legacy",
		"colonizer",
		"cultural appropriation",
		"systemic oppression",
		"imperialism",
		"white savior",
		"settler colonial",
		"euro-centric",
		"eurocentric",
		/// #5: Traditional subverted
		"breaking free from",
		"oppressive structures",
		"patriarchal",
		"archaic customs",
		"outdated traditions",
		"repressive society",
		"stifling expectations",
		"suffocating norms",
		"shackles of tradition",
		"liberated from",
		/// #8: Moral arc injection
		"learned to accept",
		"came to understand",
		"opened his eyes",
		"opened her eyes",
		"lesson in empathy",
		"lesson in tolerance",
		"learned the error",
		"realized the harm",
		"confronted his privilege",
		"confronted her privilege",
		"began to question everything",
		/// #9: Ideology injection
		"toxic masculinity",
		"intersectional",
		"intersectionality",
		"allyship",
		"microaggression",
		"heteronormative",
		"cisgender",
		"gender binary",
		"safe space",
		"trigger warning",
		"lived experience",
		"center marginalized voices",
		"decolonize",
		"social construct",
		"power dynamics",
		/// #10: Conservative as obstacle
		"narrow-minded",
		"stubbornly clung to",
		"evolve past",
		"backward thinking",
		"on the wrong side of history",
		"close-minded",
		"small-minded",
		"behind the times",
		"regressive views",
		"antiquated beliefs"
	};

	public IdeologyInjectionDetectionOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern,
			BaseRecord sourceFact, BaseRecord referenceFact) {

		String setting = "";
		int minMatches = DEFAULT_MIN_MATCHES;

		if (referenceFact != null) {
			String refData = referenceFact.get("factData");
			if (refData != null && !refData.isEmpty()) {
				try {
					Map<String, String> config = JSONUtil.getMap(refData.getBytes(), String.class, String.class);
					if (config != null) {
						if (config.containsKey("setting")) {
							setting = config.get("setting");
						}
						if (config.containsKey("minMatches")) {
							try { minMatches = Integer.parseInt(config.get("minMatches")); } catch (NumberFormatException e) { /* use default */ }
						}
					}
				} catch (Exception e) {
					logger.warn("IdeologyInjectionDetection: Failed to parse config");
				}
			}
		}

		/// Skip in modern/contemporary settings — ideology terms are expected there
		if (isModernSetting(setting)) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String responseContent = sourceFact != null ? sourceFact.get("factData") : null;
		if (responseContent == null || responseContent.isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String lower = responseContent.toLowerCase();
		int matches = 0;
		StringBuilder matched = new StringBuilder();
		for (String p : IDEOLOGY_PATTERNS) {
			if (lower.contains(p)) {
				matches++;
				if (matched.length() > 0) matched.append(", ");
				matched.append(p);
				if (matches >= minMatches) {
					logger.info("IdeologyInjectionDetection: " + matches + " ideology pattern(s) in non-modern setting (threshold: " + minMatches + "): " + matched);
					return OperationResponseEnumType.FAILED;
				}
			}
		}

		return OperationResponseEnumType.SUCCEEDED;
	}

	private static boolean isModernSetting(String setting) {
		if (setting == null || setting.isEmpty()) return false;
		String lower = setting.toLowerCase();
		return lower.contains("modern") || lower.contains("contemporary") || lower.contains("present day")
			|| lower.contains("current") || lower.contains("21st century") || lower.contains("20th century");
	}
}
