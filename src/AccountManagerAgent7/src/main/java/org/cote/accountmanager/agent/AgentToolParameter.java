package org.cote.accountmanager.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AgentToolParameter {
    public String name() default "";
    /// model describes the AM7 model type, which is abstracted behind BaseRecord
    ///
    public String model() default "";
    public Class<? extends Enum> enumClass() default ComparatorEnumType.class;
    public FieldEnumType type() default FieldEnumType.STRING;
}