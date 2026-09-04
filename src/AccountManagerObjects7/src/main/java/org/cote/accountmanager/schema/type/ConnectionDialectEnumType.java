package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Wire dialect (protocol) of a system.connection endpoint. Migration seam for
/// LiteLLM / OpenAI-compatible endpoints. Serialized lowercase on the wire, read
/// back via getEnum() in Java (UPPERCASE).
///
/// AUTHORITATIVE (Phase 3, P3-1): this field is the single source of truth for LLM
/// transport-protocol selection. Chat.configureChat resolves it once via
/// ChatUtil.resolveServiceType(connection, chatConfig): when dialect is non-UNKNOWN it
/// is mapped by name onto LLMServiceEnumType and wins; the deprecated
/// olio.llm.chatConfig.serviceType is only consulted as the DERIVED FALLBACK when
/// dialect is UNKNOWN (or the connection is absent). Values mirror the relevant
/// LLMServiceEnumType dialects.
///   OLLAMA        - native Ollama /api/chat
///   OPENAI        - Azure OpenAI /openai/deployments/... deployment scheme
///   OPENAI_COMPAT - generic OpenAI-compatible /v1/chat/completions (e.g. LiteLLM)
///
/// NOTE: LLMServiceEnumType additionally carries LOCAL, which has no dialect peer; it is
/// preserved only through the serviceType fallback path (dialect == UNKNOWN).
///
@XmlType(name = "ConnectionDialectEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum ConnectionDialectEnumType {

    UNKNOWN,
    OLLAMA,
    OPENAI,
    OPENAI_COMPAT
    ;

    public String value() {
        return name();
    }

    public static ConnectionDialectEnumType fromValue(String v) {
        return valueOf(v);
    }

}
