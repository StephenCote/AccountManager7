package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Kind of non-character thing an olio.pb.sceneElement places in a composition (PictureBook V3 design §8):
/// a prop, furniture, an animal, a vehicle, a structure, a natural feature, or something else. The column
/// carries no DDL default, so a row written without one reads NULL -> UNKNOWN.
///
@XmlType(name = "PbElementTypeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbElementTypeEnumType {

    UNKNOWN,
    PROP,
    FURNITURE,
    ANIMAL,
    VEHICLE,
    STRUCTURE,
    NATURE,
    OTHER
    ;

    public String value() {
        return name();
    }

    public static PbElementTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
