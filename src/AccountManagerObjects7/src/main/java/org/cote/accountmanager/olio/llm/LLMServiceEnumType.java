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
	OPENAI_COMPAT,
	/// In-process fixture/synthesizer replay via LlmEmulator. No network call. Answers in the
	/// OpenAI-compatible SSE shape so the OPENAI_COMPAT parser path is reused verbatim.
	/// Selected by system.connection.dialect = EMULATOR, or — when the connection carries no dialect — by chatConfig.serviceType = EMULATOR (ChatUtil.resolveServiceType fallback); the unconfigured fail-fast guard in Chat applies either way.
	EMULATOR
}
