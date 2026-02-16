package org.cote.accountmanager.olio.llm.policy;

import java.util.Map;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// ISO 42001 Bias Detection #6: Young character aged up detection.
/// Detects adult vocabulary, adult responsibilities, professional composure,
/// and world-weary tone in responses for young characters.
/// Only fires when systemCharAge is below the age threshold (default 16).
/// Returns SUCCEEDED immediately for adult characters.
///
/// Configurable via referenceFact factData JSON:
/// - minMatches (default 2)
/// - systemCharAge (character age)
/// - ageThreshold (default 16)
public class AgeUpDetectionOperation extends Operation {

	public static final int DEFAULT_MIN_MATCHES = 2;
	public static final int DEFAULT_AGE_THRESHOLD = 16;

	private static final String[] AGE_UP_PATTERNS = {
		/// Adult vocabulary beyond age
		"systemic",
		"paradigm",
		"infrastructure",
		"socioeconomic",
		"geopolitical",
		"philosophical implications",
		"existential crisis",
		"epistemological",
		"dialectic",
		"hegemony",
		"ontological",
		"phenomenological",
		"categorical imperative",
		/// Adult composure/authority
		"measured tone",
		"composed herself",
		"composed himself",
		"with quiet authority",
		"with practiced ease",
		"world-weary",
		"seen too much",
		"weary sigh",
		"jaded expression",
		"knowing look",
		"sardonic smile",
		/// Adult responsibilities
		"paid the bills",
		"paid rent",
		"employment",
		"mortgage",
		"investment portfolio",
		"filed taxes",
		"tax return",
		"credit score",
		"salary negotiation",
		"commute to work",
		"board meeting",
		/// Wise-beyond-years tropes
		"wise beyond",
		"mature for her age",
		"mature for his age",
		"old soul",
		"ancient eyes",
		"carried the weight of the world",
		"understood things most adults"
	};

	public AgeUpDetectionOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern,
			BaseRecord sourceFact, BaseRecord referenceFact) {

		int systemCharAge = 0;
		int ageThreshold = DEFAULT_AGE_THRESHOLD;
		int minMatches = DEFAULT_MIN_MATCHES;

		if (referenceFact != null) {
			String refData = referenceFact.get("factData");
			if (refData != null && !refData.isEmpty()) {
				try {
					Map<String, String> config = JSONUtil.getMap(refData.getBytes(), String.class, String.class);
					if (config != null) {
						if (config.containsKey("systemCharAge")) {
							try { systemCharAge = Integer.parseInt(config.get("systemCharAge")); } catch (NumberFormatException e) { /* use default */ }
						}
						if (config.containsKey("ageThreshold")) {
							try { ageThreshold = Integer.parseInt(config.get("ageThreshold")); } catch (NumberFormatException e) { /* use default */ }
						}
						if (config.containsKey("minMatches")) {
							try { minMatches = Integer.parseInt(config.get("minMatches")); } catch (NumberFormatException e) { /* use default */ }
						}
					}
				} catch (Exception e) {
					logger.warn("AgeUpDetection: Failed to parse config");
				}
			}
		}

		/// Only evaluate for young characters below the age threshold
		if (systemCharAge <= 0 || systemCharAge >= ageThreshold) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String responseContent = sourceFact != null ? sourceFact.get("factData") : null;
		if (responseContent == null || responseContent.isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String lower = responseContent.toLowerCase();
		int matches = 0;
		StringBuilder matched = new StringBuilder();
		for (String p : AGE_UP_PATTERNS) {
			if (lower.contains(p)) {
				matches++;
				if (matched.length() > 0) matched.append(", ");
				matched.append(p);
				if (matches >= minMatches) {
					logger.info("AgeUpDetection: " + matches + " age-up pattern(s) for " + systemCharAge + "-year-old (threshold: " + minMatches + "): " + matched);
					return OperationResponseEnumType.FAILED;
				}
			}
		}

		return OperationResponseEnumType.SUCCEEDED;
	}
}
