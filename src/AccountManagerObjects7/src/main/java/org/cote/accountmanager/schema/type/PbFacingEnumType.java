package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Which way an olio.pb.sceneActor faces relative to the camera (PictureBook V3 design §8). The column
/// carries no DDL default, so a row written without one reads NULL -> UNKNOWN.
///
@XmlType(name = "PbFacingEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbFacingEnumType {

    UNKNOWN,
    LEFT,
    RIGHT,
    TOWARD,
    AWAY,
    PROFILE
    ;

    public String value() {
        return name();
    }

    public static PbFacingEnumType fromValue(String v) {
        return valueOf(v);
    }

}
