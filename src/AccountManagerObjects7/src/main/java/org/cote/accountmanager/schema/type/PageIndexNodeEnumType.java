package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;

/**
 * <p>Java class for PageIndexNodeEnumType.
 *
 * <p>Node role within a PageIndex tree: ROOT (document handle), SECTION (interior summary node),
 * or CHUNK (leaf content node).
 */
@XmlType(name = "PageIndexNodeEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum PageIndexNodeEnumType {

    UNKNOWN,
    ROOT,
    SECTION,
    CHUNK;

    public String value() {
        return name();
    }

    public static PageIndexNodeEnumType fromValue(String v) {
        return valueOf(v);
    }

}
