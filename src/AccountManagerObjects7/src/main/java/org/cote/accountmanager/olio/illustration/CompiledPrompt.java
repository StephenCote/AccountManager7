package org.cote.accountmanager.olio.illustration;

import java.util.LinkedHashMap;
import java.util.Map;

/// One stage's compiled prompt pair (PictureBook V3 design §12 "compiled"). The prompt shown is the
/// prompt sent: an adapter must send exactly these strings.
public final class CompiledPrompt {

	private final String prompt;
	private final String negative;
	private final int estimatedTokens;

	public CompiledPrompt(String prompt, String negative, int estimatedTokens) {
		this.prompt = (prompt == null ? "" : prompt);
		this.negative = (negative == null ? "" : negative);
		this.estimatedTokens = estimatedTokens;
	}

	public String getPrompt() {
		return prompt;
	}

	public String getNegative() {
		return negative;
	}

	public int getEstimatedTokens() {
		return estimatedTokens;
	}

	Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("prompt", prompt);
		m.put("negative", negative);
		m.put("estimatedTokens", estimatedTokens);
		return m;
	}

	static CompiledPrompt fromMap(Map<?, ?> m) {
		if(m == null) {
			return null;
		}
		return new CompiledPrompt(RenderPlan.str(m, "prompt"), RenderPlan.str(m, "negative"), RenderPlan.intOf(m, "estimatedTokens", 0));
	}

	@Override
	public String toString() {
		return "CompiledPrompt[tokens=" + estimatedTokens + ", prompt=" + prompt + ", negative=" + negative + "]";
	}
}
