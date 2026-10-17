package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Kind of an olio.pb.location (PictureBook V3 design §7.1): whether the place is an INTERIOR, an
/// EXTERIOR, MIXED (a courtyard, a porch, a ruin open to the sky) or ABSTRACT (a dreamscape, a void,
/// a place the text never pins down). The column carries no DDL default, so a row written without
/// one reads NULL -> UNKNOWN; the POI link (§7.2 rule 1) maps the kind onto the POI type.
///
@XmlType(name = "PbLocationKindEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbLocationKindEnumType {

    UNKNOWN,
    INTERIOR,
    EXTERIOR,
    MIXED,
    ABSTRACT
    ;

    public String value() {
        return name();
    }

    public static PbLocationKindEnumType fromValue(String v) {
        return valueOf(v);
    }

}
