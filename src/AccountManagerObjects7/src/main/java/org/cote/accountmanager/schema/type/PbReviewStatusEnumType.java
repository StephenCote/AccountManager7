package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Review status of one olio.pb.artifact (PictureBook V3 design §10).
///
/// GENERATED is the default: a freshly produced variant nobody has looked at. The column carries no
/// DDL default (enum columns never do), so rows written before the field existed read SQL NULL.
/// EnumValueType.setValue maps a null to the literal "UNKNOWN" before validating it against this
/// class, so UNKNOWN must exist here or every pre-Phase-1 artifact row throws ValueException inside
/// StatementUtil.populateRecord and becomes unreadable. UNKNOWN is never written on purpose; every
/// reader folds it into GENERATED via PbArtifactUtil.reviewStatusOf.
///
/// APPROVED is the only value that means "approved". olio.pb.artifact.approvedAt is a zonetime column
/// and zonetime columns get `default now()` on the boot patch, so approvedAt is NEVER null and must not
/// be used as the approval test; approvedBy is "AUTO" when the book's autoApprove switch did it.
///
/// Validated against THIS class: artifactModel.json declares baseClass on this type, so a new value
/// needs no model-schema change.
///
@XmlType(name = "PbReviewStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbReviewStatusEnumType {

    UNKNOWN,
    GENERATED,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    ARCHIVED
    ;

    public String value() {
        return name();
    }

    public static PbReviewStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
