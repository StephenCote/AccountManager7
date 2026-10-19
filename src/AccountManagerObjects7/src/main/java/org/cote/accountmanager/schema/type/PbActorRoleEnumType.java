package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Narrative weight of an olio.pb.sceneActor inside its composition (PictureBook V3 design §8): the hero
/// of the frame, a supporting figure, a named figure in the background, an unnamed extra, or a crowd
/// (count > 1, no character FK). The default priority of an actor is derived from this role by the
/// composer; the role column itself carries no DDL default, so a row written without one reads
/// NULL -> UNKNOWN.
///
@XmlType(name = "PbActorRoleEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbActorRoleEnumType {

    UNKNOWN,
    HERO,
    SUPPORTING,
    NAMED_BACKGROUND,
    EXTRA,
    CROWD
    ;

    public String value() {
        return name();
    }

    public static PbActorRoleEnumType fromValue(String v) {
        return valueOf(v);
    }

}
