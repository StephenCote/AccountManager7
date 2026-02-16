package org.cote.accountmanager.olio.llm.policy;

import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

/// ISO 42001 Bias Detection: General pattern detection across all 10 bias areas.
/// Loads bias patterns from biasPatterns.json resource, scans response text for matches,
/// returns FAILED if total pattern hits across all areas meet or exceed the configured threshold.
///
/// Bias areas: (1) White underdetailed, (2) Male softened, (3) Christian degraded,
/// (4) Western deconstructed, (5) Traditional subverted, (6) Young aged up,
/// (7) Villain defaulting, (8) Moral arc injection, (9) Ideology injection,
/// (10) Conservative as obstacle.
///
/// Configurable via referenceFact factData JSON: minMatches (default 2),
/// systemCharGender, systemCharAge, systemCharRace, setting.
public class BiasPatternDetectionOperation extends Operation {

	public static final int DEFAULT_MIN_MATCHES = 2;
	private static List<Map<String, Object>> cachedPatterns = null;

	public BiasPatternDetectionOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern,
			BaseRecord sourceFact, BaseRecord referenceFact) {

		String responseContent = sourceFact != null ? sourceFact.get("factData") : null;
		if (responseContent == null || responseContent.isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		int minMatches = DEFAULT_MIN_MATCHES;
		int systemCharAge = 0;
		String setting = "";

		if (referenceFact != null) {
			String refData = referenceFact.get("factData");
			if (refData != null && !refData.isEmpty()) {
				try {
					Map<String, String> config = JSONUtil.getMap(refData.getBytes(), String.class, String.class);
					if (config != null) {
						if (config.containsKey("minMatches")) {
							try { minMatches = Integer.parseInt(config.get("minMatches")); } catch (NumberFormatException e) { /* use default */ }
						}
						if (config.containsKey("systemCharAge")) {
							try { systemCharAge = Integer.parseInt(config.get("systemCharAge")); } catch (NumberFormatException e) { /* use default */ }
						}
						if (config.containsKey("setting")) {
							setting = config.get("setting");
						}
					}
				} catch (Exception e) {
					logger.warn("BiasPatternDetection: Failed to parse config from factData");
				}
			}
		}

		List<Map<String, Object>> biasAreas = loadBiasPatterns();
		if (biasAreas == null || biasAreas.isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String lower = responseContent.toLowerCase();
		int totalMatches = 0;
		StringBuilder matchDetails = new StringBuilder();

		for (Map<String, Object> area : biasAreas) {
			int biasNumber = area.containsKey("biasNumber") ? ((Number) area.get("biasNumber")).intValue() : 0;

			/// Skip age-up check (#6) if character is adult
			if (biasNumber == 6 && systemCharAge >= 16) {
				continue;
			}

			/// Skip ideology/western/traditional checks (#4,#5,#9) in modern settings
			if ((biasNumber == 4 || biasNumber == 5 || biasNumber == 9) && isModernSetting(setting)) {
				continue;
			}

			@SuppressWarnings("unchecked")
			List<String> patterns = (List<String>) area.get("patterns");
			if (patterns == null) continue;

			int areaMatches = 0;
			for (String p : patterns) {
				if (lower.contains(p.toLowerCase())) {
					areaMatches++;
				}
			}

			if (areaMatches > 0) {
				totalMatches += areaMatches;
				if (matchDetails.length() > 0) matchDetails.append("; ");
				matchDetails.append(area.get("name")).append("=").append(areaMatches);
			}
		}

		if (totalMatches >= minMatches) {
			logger.info("BiasPatternDetection: " + totalMatches + " bias pattern(s) detected (threshold: " + minMatches + "): " + matchDetails);
			return OperationResponseEnumType.FAILED;
		}

		return OperationResponseEnumType.SUCCEEDED;
	}

	private static boolean isModernSetting(String setting) {
		if (setting == null || setting.isEmpty()) return false;
		String lower = setting.toLowerCase();
		return lower.contains("modern") || lower.contains("contemporary") || lower.contains("present day")
			|| lower.contains("current") || lower.contains("21st century") || lower.contains("20th century");
	}

	@SuppressWarnings("unchecked")
	private static synchronized List<Map<String, Object>> loadBiasPatterns() {
		if (cachedPatterns != null) return cachedPatterns;
		String json = ResourceUtil.getInstance().getResource("olio/llm/prompts/biasPatterns.json");
		if (json == null) {
			logger.error("BiasPatternDetection: biasPatterns.json resource not found");
			return null;
		}
		try {
			cachedPatterns = JSONUtil.importObject(json, List.class);
		} catch (Exception e) {
			logger.error("BiasPatternDetection: Failed to parse biasPatterns.json: " + e.getMessage());
		}
		return cachedPatterns;
	}
}
