package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Status of one olio.pb.run.
///
/// A run that finished with failures is COMPLETED with a non-zero failedNodeCount -- FAILED means
/// the run itself could not proceed, not that some nodes failed.
///
/// CANCELLED is RESERVED AND CURRENTLY UNWRITTEN.  Nothing in the codebase sets it yet -- grep
/// confirms zero assignments -- so do not read its presence as evidence that a cancelled run is
/// recorded anywhere.
///
/// It was originally omitted on the grounds that "runs are synchronous and there is no cancel
/// endpoint, so a value nothing can set would be a false affordance".  Half of that premise is now
/// gone: the PictureBook and ChapBook bulk operations run on the async job layer and POST
/// /rest/job/{jobId}/cancel genuinely reaches their loops' cooperative stop checks.  The other
/// half still holds for the RUN GRAPH specifically -- the only closeRun call sites are in
/// PictureBookUtil's single-scene image generation, and the ChapBook bulk paths never create an
/// olio.pb.run row at all, so no cancellable loop currently owns a run to stamp.
///
/// The value exists so that when a cancellable path does own a run, it has a truthful terminal
/// status to write instead of mislabelling a user's own cancel as COMPLETED or FAILED.  Wiring it
/// means calling closeRun(graph, PbRunStatusEnumType.CANCELLED, ...) from that path.
///
/// The value is validated against THIS class, not against a list in runModel.json: the field
/// declares baseClass=org.cote.accountmanager.schema.type.PbRunStatusEnumType, so adding it here
/// is sufficient and needs no model-schema change (which on a provisioned deployment would have
/// had no runtime effect anyway -- see .claude/rules/objects7-reference.md on the persisted
/// ModelSchema winning over the resource).
///
@XmlType(name = "PbRunStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbRunStatusEnumType {

    UNKNOWN,
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
    ;

    public String value() {
        return name();
    }

    public static PbRunStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
