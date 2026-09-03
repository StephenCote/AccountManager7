package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Wire dialect (protocol) of a system.connection endpoint. Additive migration seam
/// for LiteLLM / OpenAI-compatible endpoints. Serialized lowercase on the wire, read
/// back via getEnum() in Java (UPPERCASE).
///
/// NOTE: as of Phase B2 this field is additive and NOT yet authoritative for protocol
/// selection — olio.llm.chatConfig.serviceType still drives resolution. Values mirror
/// the relevant LLMServiceEnumType dialects.
///   OLLAMA        - native Ollama /api/chat
///   OPENAI        - Azure OpenAI /openai/deployments/... deployment scheme
///   OPENAI_COMPAT - generic OpenAI-compatible /v1/chat/completions (e.g. LiteLLM)
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
