package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Where an olio.pb.composition came from (PictureBook V3 design §8): written by the LLM scene composer,
/// authored by a user, or migrated from a pre-V3 scene's sdPrompt. The column carries no DDL default,
/// so a row written without one reads NULL -> UNKNOWN.
///
@XmlType(name = "PbCompositionSourceEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbCompositionSourceEnumType {

    UNKNOWN,
    LLM,
    USER,
    MIGRATED
    ;

    public String value() {
        return name();
    }

    public static PbCompositionSourceEnumType fromValue(String v) {
        return valueOf(v);
    }

}
