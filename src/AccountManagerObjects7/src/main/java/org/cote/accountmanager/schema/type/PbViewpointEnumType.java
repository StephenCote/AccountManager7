package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Camera height / angle of an olio.pb.composition (PictureBook V3 design §8). Distinct from
/// olio.pb.locationVariant.viewpoint, which is free text ("from the doorway"); this is the enumerated
/// camera angle the prompt composer renders. The column carries no DDL default, so a row written
/// without one reads NULL -> UNKNOWN.
///
@XmlType(name = "PbViewpointEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbViewpointEnumType {

    UNKNOWN,
    EYE_LEVEL,
    HIGH,
    LOW,
    OVER_SHOULDER,
    BIRDS_EYE
    ;

    public String value() {
        return name();
    }

    public static PbViewpointEnumType fromValue(String v) {
        return valueOf(v);
    }

}
