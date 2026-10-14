package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Page format of an olio.pb.style (PictureBook V3 design §4). CUSTOM reads customWidth/customHeight.
/// Validated against THIS class: styleModel.json declares baseClass on this type. The column carries
/// no DDL default (enum columns never do), so a row written without one reads NULL, which
/// EnumValueType.setValue surfaces as UNKNOWN - the resolver (P2-3) folds that into §29's SQUARE.
///
@XmlType(name = "PbPageFormatEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbPageFormatEnumType {

    UNKNOWN,
    PORTRAIT_PAGE,
    LANDSCAPE_PAGE,
    SQUARE,
    CUSTOM
    ;

    public String value() {
        return name();
    }

    public static PbPageFormatEnumType fromValue(String v) {
        return valueOf(v);
    }

}
