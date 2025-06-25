package org.cote.accountmanager.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.cote.accountmanager.model.field.FieldEnumType;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {
    public String description() default "";
    public String example() default "";
    public FieldEnumType returnType() default FieldEnumType.STRING;
    public String returnModel() default "";
}