package org.cote.accountmanager.olio.llm.policy;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;

/// Phase 9: Detects null/empty LLM responses indicating timeout or connection drop.
/// sourceFact.factData = response content (or null if timed out)
/// Returns FAILED if response is null/empty (timeout detected), SUCCEEDED otherwise.
public class TimeoutDetectionOperation extends Operation {

	public TimeoutDetectionOperation(IReader reader, ISearch search) {
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
		if (responseContent == null || responseContent.trim().isEmpty()) {
			logger.info("TimeoutDetection: No response content - timeout or empty response detected");
			return OperationResponseEnumType.FAILED;
		}
		return OperationResponseEnumType.SUCCEEDED;
	}
}
