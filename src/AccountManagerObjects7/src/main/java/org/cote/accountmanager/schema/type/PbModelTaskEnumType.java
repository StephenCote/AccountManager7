package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// The task a checkpoint is resolved for (PictureBook V3 design §16). olio.pb.style.taskModels is a
/// JSON object keyed by the lower-camel spelling of these values (portrait, location, sceneDirect,
/// inpaint, harmonize, upscale); ModelResolver (P2-2) maps a key to its value here.
///
@XmlType(name = "PbModelTaskEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbModelTaskEnumType {

    UNKNOWN,
    PORTRAIT,
    LOCATION,
    SCENE_DIRECT,
    INPAINT,
    HARMONIZE,
    UPSCALE
    ;

    public String value() {
        return name();
    }

    public static PbModelTaskEnumType fromValue(String v) {
        return valueOf(v);
    }

}
