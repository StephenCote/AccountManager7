package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Denormalized status of one olio.pb.node.  The node's inputHash is the authority for staleness;
/// this is a repairable cache that exists so 'show me the stale nodes' is one indexed query rather
/// than a graph walk.  DONE_UNVERIFIED is distinct from DONE deliberately: a produced-but-unverified
/// image is not the same claim as a verified one.
///
@XmlType(name = "PbNodeStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbNodeStatusEnumType {

    UNKNOWN,
    PENDING,
    READY,
    RUNNING,
    DONE,
    DONE_UNVERIFIED,
    STALE,
    FAILED,
    SKIPPED
    ;

    public String value() {
        return name();
    }

    public static PbNodeStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
