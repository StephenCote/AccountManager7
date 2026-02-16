package org.cote.accountmanager.olio.llm.policy;

import java.util.Map;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// ISO 42001 Bias Detection #2: Dedicated male character softening detection.
/// Detects apologetic language, emotional vulnerability without personality basis,
/// deferential speech, tears/crying, seeking validation — when system character is male.
/// Returns SUCCEEDED immediately if system character gender is not male.
///
/// Configurable via referenceFact factData JSON: minMatches (default 2), systemCharGender.
public class MasculineSofteningDetectionOperation extends Operation {

	public static final int DEFAULT_MIN_MATCHES = 2;

	private static final String[] SOFTENING_PATTERNS = {
		"he apologized",
		"he said softly",
		"he said gently",
		"he whispered",
		"he murmured apologetically",
		"tears welled in his eyes",
		"tears streamed down his",
		"tears pricked his eyes",
		"fought back tears",
		"his voice breaking",
		"his voice cracking",
		"his voice wavered",
		"he stammered",
		"he stuttered nervously",
		"he shuffled his feet",
		"he looked away guiltily",
		"he wrung his hands",
		"he bit his lip",
		"he blushed",
		"he flinched at her words",
		"vulnerable moment",
		"allowed himself to feel",
		"let down his guard",
		"opened up about his feelings",
		"needed to process his emotions",
		"i'm sorry, i didn't mean",
		"maybe you're right",
		"i shouldn't have",
		"seeking her approval",
		"seeking validation",
		"looked to her for guidance",
		"deferred to her judgment",
		"stepped aside to let her lead",
		"knew she was the stronger one",
		"admired her strength",
		"emotionally unavailable",
		"afraid of intimacy",
		"toxic masculinity",
		"needed to be more open",
		"learned to express his feelings"
	};

	public MasculineSofteningDetectionOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern,
			BaseRecord sourceFact, BaseRecord referenceFact) {

		/// Check if system character is male — skip if not
		String systemCharGender = null;
		int minMatches = DEFAULT_MIN_MATCHES;

		if (referenceFact != null) {
			String refData = referenceFact.get("factData");
			if (refData != null && !refData.isEmpty()) {
				try {
					Map<String, String> config = JSONUtil.getMap(refData.getBytes(), String.class, String.class);
					if (config != null) {
						systemCharGender = config.get("systemCharGender");
						if (config.containsKey("minMatches")) {
							try { minMatches = Integer.parseInt(config.get("minMatches")); } catch (NumberFormatException e) { /* use default */ }
						}
					}
				} catch (Exception e) {
					logger.warn("MasculineSofteningDetection: Failed to parse config");
				}
			}
		}

		/// Only evaluate for male characters
		if (systemCharGender == null || !systemCharGender.equalsIgnoreCase("male")) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String responseContent = sourceFact != null ? sourceFact.get("factData") : null;
		if (responseContent == null || responseContent.isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String lower = responseContent.toLowerCase();
		int matches = 0;
		for (String p : SOFTENING_PATTERNS) {
			if (lower.contains(p)) {
				matches++;
				if (matches >= minMatches) {
					logger.info("MasculineSofteningDetection: " + matches + " softening pattern(s) detected (threshold: " + minMatches + ")");
					return OperationResponseEnumType.FAILED;
				}
			}
		}

		return OperationResponseEnumType.SUCCEEDED;
	}
}
