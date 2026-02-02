package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

@XmlType(name = "StepStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum StepStatusEnumType {

    UNKNOWN,
    PENDING,
    EXECUTING,
    COMPLETED,
    FAILED,
    GATED,
    SKIPPED
    ;

    public String value() {
        return name();
    }

    public static StepStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
