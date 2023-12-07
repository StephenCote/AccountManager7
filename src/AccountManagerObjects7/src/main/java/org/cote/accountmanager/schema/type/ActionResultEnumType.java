package org.cote.accountmanager.schema.type;

public enum ActionResultEnumType {
	UNKNOWN,
	SUCCEEDED,
	FAILED,
	INCOMPLETE,
	PENDING
	;
	
	
    public String value() {
        return name();
    }
	
    public static ActionResultEnumType fromValue(String v) {
        return valueOf(v);
    }
}
