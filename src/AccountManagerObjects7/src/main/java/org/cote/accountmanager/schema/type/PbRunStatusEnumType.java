package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/// Status of one olio.pb.run.  There is no CANCELLED value: runs are synchronous and there is no
/// cancel endpoint for them, so a value nothing can set would be a false affordance.  A run that
/// finished with failures is COMPLETED with a non-zero failedNodeCount.
///
@XmlType(name = "PbRunStatusEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PbRunStatusEnumType {

    UNKNOWN,
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
    ;

    public String value() {
        return name();
    }

    public static PbRunStatusEnumType fromValue(String v) {
        return valueOf(v);
    }

}
