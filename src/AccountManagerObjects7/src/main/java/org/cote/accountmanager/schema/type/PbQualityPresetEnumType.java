package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Quality preset of an olio.pb.style (PictureBook V3 design §18). The preset VALUES (resolution
/// scale, steps mode, variants, upscale, QA) live in olio/illustration/qualityPresets.json and are
/// read through QualityPresets; this enum is only the key. The column carries no DDL default, so a
/// row written without one reads NULL -> UNKNOWN, which QualityPresets.resolve maps to the
/// resource's defaultPreset.
///
@XmlType(name = "PbQualityPresetEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbQualityPresetEnumType {

    UNKNOWN,
    DRAFT,
    STANDARD,
    FINAL
    ;

    public String value() {
        return name();
    }

    public static PbQualityPresetEnumType fromValue(String v) {
        return valueOf(v);
    }

}
