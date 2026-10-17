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
    STYLE_BIBLE,
    /// V3 §12: the node whose JSON artifact is the compiled render plan for a scene. Added in Phase 2
    /// P2-1 as a vocabulary value only; it becomes executable in P2-5 (PbNodeExecutor.EXECUTABLE_TYPES).
    RENDER_PLAN,
    /// V3 §7: renders one olio.pb.location (or one of its variants) as a reusable, approvable world
    /// asset; scope "location" (scopeRef = location objectId) or "locationVariant" (scopeRef = variant
    /// objectId), executable since P3-4. LANDSCAPE stays: it is the per-scene background of the PB1/v1 path.
    LOCATION
    ;

    public String value() {
        return name();
    }

    public static PbNodeTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
