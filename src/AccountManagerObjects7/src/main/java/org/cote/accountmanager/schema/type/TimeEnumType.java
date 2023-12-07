package org.cote.accountmanager.schema.type;

public enum TimeEnumType {
    UNKNOWN,
    SECOND,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
    DECADE,
    CENTURY,
    MILLENNIUM
    ;

    public String value() {
        return name();
    }

    public static TimeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
