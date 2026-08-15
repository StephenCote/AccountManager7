package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Aggregate state of an olio.pb.workflow's node graph.
///
@XmlType(name = "PbGraphStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbGraphStatusEnumType {

    UNKNOWN,
    CLEAN,
    DIRTY,
    RUNNING,
    FAILED
    ;

    public String value() {
        return name();
    }

    public static PbGraphStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
