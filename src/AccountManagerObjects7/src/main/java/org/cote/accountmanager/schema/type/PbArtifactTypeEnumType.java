package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// What an olio.pb.artifact wraps.  Together with the artifact's role this becomes the source of
/// truth for image classification, which today is an attribute set on only some image kinds.
///
@XmlType(name = "PbArtifactTypeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbArtifactTypeEnumType {

    UNKNOWN,
    TEXT,
    PROMPT,
    IMAGE,
    IMAGE_STRIP,
    COMPOSITE_CANVAS,
    JSON,
    RECORD_REF
    ;

    public String value() {
        return name();
    }

    public static PbArtifactTypeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
