package org.cote.accountmanager.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.SchemaUtil;

import io.jsonwebtoken.lang.Arrays;

public class PlanExecutor {
	public static final Logger logger = LogManager.getLogger(PlanExecutor.class);
	private static final Pattern CONTEXT_VARIABLE_PATTERN = Pattern.compile("\\{\\{(.+?)\\}\\}");
	private AgentToolManager toolManager = null;
	public PlanExecutor(AgentToolManager toolManager) {
		this.toolManager = toolManager;
	}
	
	public String executePlan(BaseRecord plan) {
		if (plan == null) {
			logger.error("Plan is null");
			return null;
		}
		List<BaseRecord> steps = (List<BaseRecord>) plan.get("steps");
		if (steps == null || steps.size() == 0) {
			logger.error("No steps in plan");
			return null;
		}
		steps.sort(Comparator.comparingInt(s -> (int) s.get("step")));
		List<String> stepResults = new ArrayList<>();
		Map<String, Object> context = new HashMap<>();
		StringBuilder buff = new StringBuilder();
		logger.info("Executing plan: " + plan.get("planQuery") + " with " + steps.size() + " steps");
		Object lastResult = null;
		for (BaseRecord step : steps) {
			String toolName = step.get("toolName");
			Method meth = toolManager.getToolMethod(toolName);
			if (meth == null) {
				logger.error("Tool " + toolName + " not found for step " + step.get("step"));
				continue;
			}
			Object[] arguments = prepareArguments(step, context, meth);
			try {
	            // Invoke the tool method using reflection
	            lastResult = meth.invoke(toolManager.getToolInstance(), arguments);
	            BaseRecord output = step.get("output");
	            if (output != null) {
	            	String outputVarName = output.get("name");
	            	if(outputVarName != null && !outputVarName.isEmpty()) {
	            		context.put(outputVarName, lastResult);
	            	}
	            }
			} catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
				logger.error("Error executing step #" + step.get("step") + " for tool " + toolName, e);
			}
		}
		return buff.toString();
	}
	
	private Object[] prepareArguments(BaseRecord step, Map<String, Object> context, Method method) {
		List<Object> preparedArgs = new ArrayList<>();
		List<BaseRecord> inputs = step.get("inputs");

		//Class<?>[] parameterTypes = method.getParameterTypes();
		List<Parameter> params = Arrays.asList(method.getParameters());

        for (BaseRecord param : inputs) {
            Object value = param.get("value");
            Parameter methodParam = params.stream()
					.filter(p -> p.getName().equals(param.get("name")))
					.findFirst()
					.orElse(null);
            
            if(methodParam == null) {
            	throw new IllegalArgumentException("Parameter " + param.get("name") + " not found in method " + method.getName());
            }
            logger.info("Parameter " + methodParam.getName() + " " + methodParam.getParameterizedType().getTypeName());
            if (value instanceof String) {
                // Check if the string value is a context reference (e.g., "{{var}}")
                Matcher matcher = CONTEXT_VARIABLE_PATTERN.matcher((String) value);
                if (matcher.matches()) {
                    String contextKey = matcher.group(1);
                    // Replace the reference with the actual value from the context
                    preparedArgs.add(context.get(contextKey));
                } else {
                	
                    preparedArgs.add(value);
                }
            } else {
            	   
                preparedArgs.add(value);
            }
        }
        
        
        // Ensure the arguments match the method signature, basic conversion if needed.
        // A more robust implementation might handle type casting more gracefully.
        return preparedArgs.toArray(new Object[0]);
    }
	
	
}
