package org.cote.accountmanager.schema.type;

public enum LevelEnumType {

    UNKNOWN,
	LOW,
	MODERATE,
	CONSIDERABLE,
	HIGH,
	SEVERE,
	EXTREME,
	GRAVE
	;
    public String value() {
        return name();
    }

    public static LevelEnumType fromValue(String v) {
        return valueOf(v);
    }

}
