package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

@XmlType(name = "MemoryTypeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum MemoryTypeEnumType {

    UNKNOWN,
    DISCOVERY,
    BEHAVIOR,
    OUTCOME,
    NOTE,
    INSIGHT,
    DECISION,
    ERROR_LESSON
    ;

    public String value() {
        return name();
    }

    public static MemoryTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
