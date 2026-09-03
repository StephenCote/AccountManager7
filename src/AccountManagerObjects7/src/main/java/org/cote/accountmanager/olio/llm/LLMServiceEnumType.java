package org.cote.accountmanager.olio.llm;

public enum LLMServiceEnumType {
	UNKNOWN,
	LOCAL,
	OLLAMA,
	OPENAI,
	/// OpenAI-compatible endpoint (e.g. a LiteLLM proxy) that serves the standard
	/// /v1/chat/completions API. Distinct from OPENAI, which is wired to Azure's
	/// /openai/deployments/... deployment scheme. Reuses the OpenAI request body,
	/// choices/delta SSE parser, and Bearer auth.
	OPENAI_COMPAT
}
