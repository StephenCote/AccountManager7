package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Lifecycle of an olio.pb.book.  Sits beside StepTypeEnumType / StepStatusEnumType because the
/// PictureBook graph is the persisted form of a plan/step graph.
///
@XmlType(name = "PbBookStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbBookStatusEnumType {

    UNKNOWN,
    DRAFT,
    EXTRACTING,
    EXTRACTED,
    GENERATING,
    COMPLETE,
    FAILED
    ;

    public String value() {
        return name();
    }

    public static PbBookStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
