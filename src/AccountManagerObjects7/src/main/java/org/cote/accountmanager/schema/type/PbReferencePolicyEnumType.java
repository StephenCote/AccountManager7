package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Whether an olio.pb.sceneActor's character must render from its approved reference image (PictureBook
/// V3 design §8): AUTO lets the composer decide from role and priority, REQUIRE_REFERENCE always binds
/// the reference, TEXT_ONLY never does. The column carries no DDL default, so a row written without one
/// reads NULL -> UNKNOWN.
///
@XmlType(name = "PbReferencePolicyEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbReferencePolicyEnumType {

    UNKNOWN,
    AUTO,
    REQUIRE_REFERENCE,
    TEXT_ONLY
    ;

    public String value() {
        return name();
    }

    public static PbReferencePolicyEnumType fromValue(String v) {
        return valueOf(v);
    }

}
