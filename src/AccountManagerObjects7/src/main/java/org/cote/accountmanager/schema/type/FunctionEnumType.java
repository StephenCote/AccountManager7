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
 * <p>Java class for FunctionEnumType.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p>
 * <pre>
 * &lt;simpleType name="FunctionEnumType"&gt;
 *   &lt;restriction base="{http://www.w3.org/2001/XMLSchema}string"&gt;
 *     &lt;enumeration value="UNKNOWN"/&gt;
 *     &lt;enumeration value="JAVASCRIPT"/&gt;
 *     &lt;enumeration value="JAVA"/&gt;
 *     &lt;enumeration value="REST"/&gt;
 *   &lt;/restriction&gt;
 * &lt;/simpleType&gt;
 * </pre>
 * 
 */
@XmlType(name = "FunctionEnumType")
@XmlEnum
public enum FunctionEnumType {

    UNKNOWN,
    JAVASCRIPT,
    JAVA,
    REST;

    public String value() {
        return name();
    }

    public static FunctionEnumType fromValue(String v) {
        return valueOf(v);
    }

}
