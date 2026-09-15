package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Upstream MODEL-SERVER FAMILY behind a system.connection endpoint - i.e. what actually runs the
/// model, as distinct from the wire dialect AM7 speaks to reach it. Serialized lowercase on the
/// wire, read back via getEnum() in Java (UPPERCASE).
///
/// WHY THIS EXISTS (KI-72): AM7 previously decided whether to emit Ollama's extension parameters
/// (num_ctx, top_k, repeat_penalty, typical_p, min_p, repeat_last_n, num_gpu, think) from the wire
/// dialect. Put a LiteLLM proxy in front of the LAN Ollama and the connection's dialect becomes
/// OPENAI_COMPAT, so every one of those parameters was silently dropped and the memory-extraction
/// token floor halved - the same physical model behaving differently depending on which door AM7
/// knocked on. Widening the dialect test to include OPENAI_COMPAT is NOT the fix and is expressly
/// forbidden by KI-72: that dialect also fronts Azure OpenAI and any other OpenAI-compatible
/// endpoint, which would then be sent Ollama-only parameters. The upstream family is a separate,
/// operator-asserted axis.
///
/// *** OPENAI HERE DOES NOT MEAN THE SAME THING AS ConnectionDialectEnumType.OPENAI. ***
/// Do not map the two enums 1:1 - a reader who does will be wrong:
///   ConnectionDialectEnumType.OPENAI  = a WIRE SCHEME: Azure's /openai/deployments/... URL layout.
///   ConnectionUpstreamEnumType.OPENAI = a MODEL-SERVER FAMILY: the upstream is OpenAI-family
///                                       (Azure OpenAI, OpenAI proper, or an OpenAI-native endpoint
///                                       sitting behind a proxy).
///
/// Values:
///   UNKNOWN - not asserted. Ollama extensions are suppressed. This is the value every pre-existing
///             row reads as, because the added column is nullable with NO DDL default (see
///             DBUtil.generateSchemaLine: enum/string fields get no `default` clause), and
///             ChatUtil.resolveUpstream maps SQL NULL onto the dialect-derived inference.
///   OLLAMA  - the upstream is Ollama, whether reached natively (/api/chat) or through an
///             OpenAI-compatible proxy such as LiteLLM. This is the value that restores the Ollama
///             extension parameters and `think` on a proxied connection.
///   OPENAI  - the upstream is OpenAI-family (see the warning above).
///
/// BEHAVIOURALLY UNKNOWN AND OPENAI ARE IDENTICAL TODAY - both suppress the Ollama extensions.
/// OPENAI exists so an operator can assert the upstream positively rather than leaving the field
/// unset, and so a future OpenAI-family-only parameter has somewhere to hang.
///
/// NOTHING MAY CALL fromValue(): it is a raw valueOf and throws on an unrecognized string. Read the
/// value with BaseRecord.getEnum("upstream"), which resolves through the schema's baseClass.
/// fromValue is retained only for shape-parity with ConnectionDialectEnumType / JAXB.
///
@XmlType(name = "ConnectionUpstreamEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum ConnectionUpstreamEnumType {

    UNKNOWN,
    OLLAMA,
    OPENAI
    ;

    public String value() {
        return name();
    }

    public static ConnectionUpstreamEnumType fromValue(String v) {
        return valueOf(v);
    }

}
