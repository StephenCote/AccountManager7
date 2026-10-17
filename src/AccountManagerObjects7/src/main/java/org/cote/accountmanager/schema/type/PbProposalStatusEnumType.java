package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Review state of an olio.pb.locationProposal (PictureBook V3 design §7.1 / §28 step 3): the whole-book
/// location pass writes PROPOSED rows; a reviewer accepts (a location is resolved and scenes re-pointed)
/// or rejects each one. The column carries no DDL default, so a row written without one reads
/// NULL -> UNKNOWN.
///
@XmlType(name = "PbProposalStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbProposalStatusEnumType {

    UNKNOWN,
    PROPOSED,
    ACCEPTED,
    REJECTED
    ;

    public String value() {
        return name();
    }

    public static PbProposalStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
