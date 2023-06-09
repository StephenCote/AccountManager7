//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.3.0 
// See <a href="https://javaee.github.io/jaxb-v2/">https://javaee.github.io/jaxb-v2/</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2022.10.27 at 10:38:29 AM CDT 
//


package org.cote.accountmanager.schema.type;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for SpoolBucketEnumType.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p>
 * <pre>
 * &lt;simpleType name="SpoolBucketEnumType"&gt;
 *   &lt;restriction base="{http://www.w3.org/2001/XMLSchema}string"&gt;
 *     &lt;enumeration value="UNKNOWN"/&gt;
 *     &lt;enumeration value="RAW"/&gt;
 *     &lt;enumeration value="MESSAGE_QUEUE"/&gt;
 *     &lt;enumeration value="SECURITY_TOKEN"/&gt;
 *     &lt;enumeration value="REQUEST"/&gt;
 *     &lt;enumeration value="APPROVAL"/&gt;
 *   &lt;/restriction&gt;
 * &lt;/simpleType&gt;
 * </pre>
 * 
 */
@XmlType(name = "SpoolBucketEnumType", namespace = "http://www.cote.org/accountmanager/objects/types")
@XmlEnum
public enum SpoolBucketEnumType {

    UNKNOWN,
    RAW,
    MESSAGE_QUEUE,
    SECURITY_TOKEN,
    REQUEST,
    APPROVAL;

    public String value() {
        return name();
    }

    public static SpoolBucketEnumType fromValue(String v) {
        return valueOf(v);
    }

}
