package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Camera framing of an olio.pb.composition (PictureBook V3 design §8): how much of the focal actor the
/// frame holds, from a close-up to an establishing shot. The column carries no DDL default, so a row
/// written without one reads NULL -> UNKNOWN.
///
@XmlType(name = "PbShotTypeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbShotTypeEnumType {

    UNKNOWN,
    CLOSE_UP,
    MEDIUM,
    FULL_BODY,
    WIDE,
    ESTABLISHING
    ;

    public String value() {
        return name();
    }

    public static PbShotTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
