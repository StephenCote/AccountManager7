package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Variant type for an olio.pb.book.  STORY is the standard PictureBook 2 narrative book;
/// CHAPBOOK is a poetry ChapBook variant where each scene page shows a stanza overlaid on a landscape.
///
@XmlType(name = "PbBookTypeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbBookTypeEnumType {

    UNKNOWN,
    STORY,
    CHAPBOOK
    ;

    public String value() {
        return name();
    }

    public static PbBookTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
