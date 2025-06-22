package org.cote.accountmanager.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {
    public String description() default "";
    public String inputs() default "";
    public String output() default "";
    public String example() default "";
}