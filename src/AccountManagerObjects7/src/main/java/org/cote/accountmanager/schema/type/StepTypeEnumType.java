package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

@XmlType(name = "StepTypeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum StepTypeEnumType {

    UNKNOWN,
    TOOL,
    LLM,
    RAG_QUERY,
    POLICY_GATE
    ;

    public String value() {
        return name();
    }

    public static StepTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
