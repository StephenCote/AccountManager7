package org.cote.accountmanager.schema.type;

public enum CompressionEnumType {

    UNKNOWN,
    NONE,
    GZIP;

    public String value() {
        return name();
    }

    public static CompressionEnumType fromValue(String v) {
        return valueOf(v);
    }

}
