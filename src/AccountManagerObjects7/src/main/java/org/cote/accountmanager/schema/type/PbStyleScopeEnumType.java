package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Scope of an olio.pb.style (PictureBook V3 design §4): a BOOK style belongs to one book, a SERIES
/// style is inherited by the chapter books of its series (§4.1), a LIBRARY style is shared. The
/// column carries no DDL default, so a row written without one reads NULL -> UNKNOWN, which the
/// resolver (P2-3) treats as BOOK.
///
@XmlType(name = "PbStyleScopeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbStyleScopeEnumType {

    UNKNOWN,
    BOOK,
    SERIES,
    LIBRARY
    ;

    public String value() {
        return name();
    }

    public static PbStyleScopeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
