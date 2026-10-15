package org.cote.accountmanager.olio.illustration;

/// Prompt token budget and estimator for the shared compiler rules (PictureBook V3 design §14).
/// The estimator is an approximation: whitespace words, +1 per ',.:;()' character, +1 per word
/// longer than eight characters. The repo has no CLIP BPE tokenizer, so the budget is a soft bound
/// that errs long on short words and short on long ones; the family budgets in capabilities.json
/// are sized with that in mind.
public final class PromptBudget {

	private PromptBudget() { }

	public static int estimateTokens(String s) {
		if(s == null || s.isBlank()) {
			return 0;
		}
		int tokens = 0;
		for(String w : s.trim().split("\\s+")) {
			if(w.isEmpty()) {
				continue;
			}
			tokens++;
			int punct = 0;
			for(int i = 0; i < w.length(); i++) {
				char c = w.charAt(i);
				if(c == ',' || c == '.' || c == ':' || c == ';' || c == '(' || c == ')') {
					punct++;
				}
			}
			tokens += punct;
			if(w.length() - punct > 8) {
				tokens++;
			}
		}
		return tokens;
	}

	/// The family's budget, or the SDXL default when the family is unknown (null).
	public static int budgetFor(RendererCapabilities caps) {
		if(caps == null) {
			return RendererCapabilities.DEFAULT_PROMPT_TOKEN_BUDGET;
		}
		return caps.getPromptTokenBudget();
	}
}
