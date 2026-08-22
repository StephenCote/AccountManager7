package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Node taxonomy of the PictureBook workflow graph.  The values are lifted verbatim from the step
/// banners the reference pipeline test already prints, so the graph names the stages the pipeline
/// actually has rather than a parallel invented vocabulary.
///
@XmlType(name = "PbNodeTypeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbNodeTypeEnumType {

    UNKNOWN,
    SOURCE_TEXT,
    SCENE_EXTRACT,
    SCENE,
    CHARACTER,
    CHARACTER_DESCRIPTION,
    APPAREL,
    MANNEQUIN,
    PORTRAIT,
    SCENE_PROMPT,
    LANDSCAPE_PROMPT,
    LANDSCAPE,
    REFERENCE_STRIP,
    COMPOSITE,
    PAGE,
    BOOK_ASSEMBLY,
    STYLE_BIBLE
    ;

    public String value() {
        return name();
    }

    public static PbNodeTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
