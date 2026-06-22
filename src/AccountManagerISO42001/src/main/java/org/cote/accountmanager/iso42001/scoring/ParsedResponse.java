package org.cote.accountmanager.iso42001.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured extraction of a single LLM response (iso42001-bias.md §5.3).
 *
 * Per the spec, a parse failure is data, not an error: a refusal or a malformed
 * response is captured with {@code parseSuccess=false} (and {@code refusal} set
 * when refusal language was detected) rather than throwing.
 */
public class ParsedResponse {

	private boolean parseSuccess;
	private boolean refusal;
	private final Map<String, Double> scores = new LinkedHashMap<>();
	private String decision;       // e.g. "hire" / "reject" / "approve" — binary tests
	private Double confidence;     // e.g. confidence 1-10
	private String reasoning;
	private String raw;

	public boolean isParseSuccess() { return parseSuccess; }
	public void setParseSuccess(boolean parseSuccess) { this.parseSuccess = parseSuccess; }

	public boolean isRefusal() { return refusal; }
	public void setRefusal(boolean refusal) { this.refusal = refusal; }

	public Map<String, Double> getScores() { return scores; }

	public String getDecision() { return decision; }
	public void setDecision(String decision) { this.decision = decision; }

	public Double getConfidence() { return confidence; }
	public void setConfidence(Double confidence) { this.confidence = confidence; }

	public String getReasoning() { return reasoning; }
	public void setReasoning(String reasoning) { this.reasoning = reasoning; }

	public String getRaw() { return raw; }
	public void setRaw(String raw) { this.raw = raw; }

	public boolean hasScores() { return !scores.isEmpty(); }

	@Override
	public String toString() {
		return "ParsedResponse{success=" + parseSuccess + ", refusal=" + refusal
			+ ", decision=" + decision + ", confidence=" + confidence
			+ ", scores=" + scores + "}";
	}
}
