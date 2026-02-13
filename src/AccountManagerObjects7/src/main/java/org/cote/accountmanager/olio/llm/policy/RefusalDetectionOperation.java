package org.cote.accountmanager.olio.llm.policy;

import java.util.Map;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// Phase 9: Detects LLM safety refusals and hedging within the configured content rating.
/// Single match could be in-character dialogue; 2+ matches indicates actual refusal (configurable).
/// Configurable via referenceFact factData JSON with key "minMatches".
/// Enforces Section 0 design directive at the response level.
public class RefusalDetectionOperation extends Operation {

	public static final int DEFAULT_MIN_MATCHES = 2;

	private static final String[] REFUSAL_PATTERNS = {
		"i can't help with that",
		"i'm not able to",
		"as an ai language model",
		"i must respectfully decline",
		"i cannot generate content that",
		"this goes against my guidelines",
		"i'd prefer not to",
		"let's keep things appropriate",
		"i'm unable to assist",
		"i cannot provide",
		"as a responsible ai",
		"i'm designed to be helpful",
		"i cannot comply with",
		"i'm not comfortable"
	};

	public RefusalDetectionOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern,
			BaseRecord sourceFact, BaseRecord referenceFact) {

		String responseContent = sourceFact.get("factData");
		if (responseContent == null || responseContent.isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		int minMatches = DEFAULT_MIN_MATCHES;
		if (referenceFact != null) {
			String refData = referenceFact.get("factData");
			if (refData != null && !refData.isEmpty()) {
				try {
					Map<String, String> config = JSONUtil.getMap(refData.getBytes(), String.class, String.class);
					if (config != null && config.containsKey("minMatches")) {
						try { minMatches = Integer.parseInt(config.get("minMatches")); } catch (NumberFormatException e) { /* use default */ }
					}
				} catch (Exception e) {
					logger.warn("RefusalDetection: Failed to parse config from factData");
				}
			}
		}

		String lower = responseContent.toLowerCase();
		int matches = 0;
		for (String refusalPhrase : REFUSAL_PATTERNS) {
			if (lower.contains(refusalPhrase)) {
				matches++;
				if (matches >= minMatches) {
					logger.info("RefusalDetection: " + matches + " refusal phrase(s) detected (threshold: " + minMatches + ")");
					return OperationResponseEnumType.FAILED;
				}
			}
		}
		return OperationResponseEnumType.SUCCEEDED;
	}
}
