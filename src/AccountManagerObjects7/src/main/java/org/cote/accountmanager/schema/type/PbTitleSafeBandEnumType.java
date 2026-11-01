package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Where a cover-type olio.pb.composition keeps its title band (PictureBook V3 Phase 9 P9-1): the render
/// leaves the TOP or BOTTOM band clear for title text, NONE means art with no title. The column carries no
/// DDL default, so a row written before the field existed reads NULL -> UNKNOWN.
///
@XmlType(name = "PbTitleSafeBandEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbTitleSafeBandEnumType {

    UNKNOWN,
    NONE,
    TOP,
    BOTTOM
    ;

    public String value() {
        return name();
    }

    public static PbTitleSafeBandEnumType fromValue(String v) {
        return valueOf(v);
    }

}
