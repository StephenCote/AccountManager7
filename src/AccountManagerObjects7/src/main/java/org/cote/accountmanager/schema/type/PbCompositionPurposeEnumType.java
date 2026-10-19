package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// What an olio.pb.composition is for (PictureBook V3 design §8): a story scene, the cover, the title page,
/// a chapter opener, series-level art, or a ChapBook page. SCENE and CHAPBOOK_PAGE carry a scene FK; the
/// rest do not. The column carries no DDL default, so a row written without one reads NULL -> UNKNOWN.
///
@XmlType(name = "PbCompositionPurposeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbCompositionPurposeEnumType {

    UNKNOWN,
    SCENE,
    COVER,
    TITLE_PAGE,
    CHAPTER_OPENER,
    SERIES_ART,
    CHAPBOOK_PAGE
    ;

    public String value() {
        return name();
    }

    public static PbCompositionPurposeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
