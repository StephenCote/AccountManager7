package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Render strategy of one scene plan (PictureBook V3 design §13). Chosen by StrategySelector, which
/// is pure and records its reason in the plan; Phase 2 implements TEXT_ONLY only.
///
/// UNKNOWN first: the value is written into the render-plan JSON artifact (§12) rather than an enum
/// column, but EnumValueType.setValue maps a null to "UNKNOWN" wherever an enum is read back, so every
/// enum in this family declares it.
///
@XmlType(name = "PbStrategyEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbStrategyEnumType {

    UNKNOWN,
    /// No referenced actors, or the renderer has no reference support, or forced: BASE only
    TEXT_ONLY,
    /// Referenced actors + location ref + style refs within maxReferences: BASE with references
    DIRECT_REFERENCE,
    /// More referenced actors than maxReferences and the renderer supports masks: BASE -> ACTOR_REGION* -> HARMONIZE
    SEQUENTIAL_INPAINT,
    /// Many low-priority actors or crowds and the renderer supports regional prompts
    REGIONAL_PROMPT,
    /// Legacy only: canvas paste -> img2img, for migrated books that asked for it
    CLASSIC_COMPOSITE
    ;

    public String value() {
        return name();
    }

    public static PbStrategyEnumType fromValue(String v) {
        return valueOf(v);
    }

}
