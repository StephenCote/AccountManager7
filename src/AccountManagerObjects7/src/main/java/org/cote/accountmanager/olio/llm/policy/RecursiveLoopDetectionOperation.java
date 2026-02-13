package org.cote.accountmanager.olio.llm.policy;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// Phase 9: Detects repeated text blocks in LLM responses indicating a decoding loop.
/// Uses a sliding window approach: if any N-char substring appears M+ times, flags as recursive.
/// Default: windowSize=50, repeatThreshold=3. Configurable via referenceFact factData JSON
/// with keys "windowSize" and "repeatThreshold".
public class RecursiveLoopDetectionOperation extends Operation {

	public static final int DEFAULT_WINDOW_SIZE = 50;
	public static final int DEFAULT_REPEAT_THRESHOLD = 3;

	public RecursiveLoopDetectionOperation(IReader reader, ISearch search) {
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

		int windowSize = DEFAULT_WINDOW_SIZE;
		int repeatThreshold = DEFAULT_REPEAT_THRESHOLD;

		if (referenceFact != null) {
			String refData = referenceFact.get("factData");
			if (refData != null && !refData.isEmpty()) {
				try {
					Map<String, String> config = JSONUtil.getMap(refData.getBytes(), String.class, String.class);
					if (config != null) {
						if (config.containsKey("windowSize")) {
							try { windowSize = Integer.parseInt(config.get("windowSize")); } catch (NumberFormatException e) { /* use default */ }
						}
						if (config.containsKey("repeatThreshold")) {
							try { repeatThreshold = Integer.parseInt(config.get("repeatThreshold")); } catch (NumberFormatException e) { /* use default */ }
						}
					}
				} catch (Exception e) {
					logger.warn("RecursiveLoopDetection: Failed to parse config from factData");
				}
			}
		}

		if (responseContent.length() < windowSize) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		Map<String, Integer> seen = new HashMap<>();
		for (int i = 0; i <= responseContent.length() - windowSize; i++) {
			String window = responseContent.substring(i, i + windowSize).trim().toLowerCase();
			if (window.isEmpty()) continue;
			int count = seen.merge(window, 1, Integer::sum);
			if (count >= repeatThreshold) {
				logger.info("RecursiveLoopDetection: Repeated block detected (" + count + "x): \"" + window.substring(0, Math.min(30, window.length())) + "...\"");
				return OperationResponseEnumType.FAILED;
			}
		}
		return OperationResponseEnumType.SUCCEEDED;
	}
}
