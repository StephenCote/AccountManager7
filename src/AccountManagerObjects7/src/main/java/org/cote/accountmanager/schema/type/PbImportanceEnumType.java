package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// How much an olio.pb.sceneElement matters to the frame (PictureBook V3 design §8): the composer spends
/// prompt budget and reference slots on HIGH elements first. The column carries no DDL default, so a
/// row written without one reads NULL -> UNKNOWN.
///
@XmlType(name = "PbImportanceEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbImportanceEnumType {

    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH
    ;

    public String value() {
        return name();
    }

    public static PbImportanceEnumType fromValue(String v) {
        return valueOf(v);
    }

}
