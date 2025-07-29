package org.cote.accountmanager.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

import io.jsonwebtoken.lang.Arrays;

public class PlanExecutor {
	public static final Logger logger = LogManager.getLogger(PlanExecutor.class);
	private static final Pattern CONTEXT_VARIABLE_PATTERN = Pattern.compile("\\{\\{(.+?)\\}\\}");
	private AgentToolManager toolManager = null;
	public PlanExecutor(AgentToolManager toolManager) {
		this.toolManager = toolManager;
	}
	
	public BaseRecord evaluateResult(BaseRecord plan) {
		if((boolean)plan.get("executed") == false) {
			logger.warn("Plan " + plan.get(FieldNames.FIELD_NAME) + " has not been executed");
			return null;
		}
		
		return toolManager.evaluateResult(plan);
		
	}
	
	public void executePlan(BaseRecord plan) throws PlanExecutionError {
		if (plan == null) {
			throw new PlanExecutionError("Plan is null");
		}
		if((boolean)plan.get("executed") == true) {
			logger.warn("Plan " + plan.get(FieldNames.FIELD_NAME) + " has already been executed, skipping");
			return;
		}
		List<BaseRecord> steps = (List<BaseRecord>) plan.get("steps");
		if (steps == null || steps.size() == 0) {
			throw new PlanExecutionError("No steps in plan");
		}

		steps.sort(Comparator.comparingInt(s -> (int) s.get("step")));
		List<String> stepResults = new ArrayList<>();
		Map<String, Object> context = new HashMap<>();

		logger.info("Executing plan: " + plan.get("planQuery") + " with " + steps.size() + " steps");
		Object lastResult = null;

		for (BaseRecord step : steps) {
			String toolName = step.get("toolName");
			Method meth = toolManager.getToolMethod(toolName);
			if (meth == null) {
				throw new PlanExecutionError("Tool " + toolName + " not found for step " + step.get("step"));
			}
			logger.info("Executing step #" + step.get("step") + " for tool " + toolName);
			AgentTool tool = meth.getAnnotation(AgentTool.class);
			Object[] arguments = prepareArguments(step, context, meth);
			try {
	            // Invoke the tool method using reflection
	            lastResult = meth.invoke(toolManager.getToolInstance(), arguments);
	           // BaseRecord output = step.get("output");
	            //if(output == null) {
            	BaseRecord output = RecordFactory.newInstance(ModelNames.MODEL_PARAMETER);
            	step.setValue("output", output);
	            //}
	            String rname = tool.returnName();
	            output.set(FieldNames.FIELD_NAME, rname);
	            output.set(FieldNames.FIELD_VALUE_TYPE, tool.returnType());
	            if(tool.returnModel() != null && !tool.returnModel().isEmpty()) {
	            	output.set("valueModel", tool.returnModel());
	            	output.set(FieldNames.FIELD_VALUE, null);
	            	if(tool.returnType() == FieldEnumType.MODEL) {
            			output.setFlex("value", (BaseRecord)lastResult);
	            	}
	            	else if(tool.returnType() == FieldEnumType.LIST) {
	            		output.setFlex("value", tool.returnType(), (List<BaseRecord>)lastResult);
	            	}
	            	else {
	            		logger.warn("Unhandled return model");
	            	}
	            }
	            else {
	            	output.set("value", lastResult);
	            }
           		context.put(rname, lastResult);
	            
			} catch (NullPointerException | IllegalArgumentException | IllegalAccessException | InvocationTargetException | FieldException | ModelNotFoundException | ValueException e) {
				e.printStackTrace();
				throw new PlanExecutionError("Error executing step #" + step.get("step") + " for tool " + toolName);
			}
		}
		plan.setValue("executed", true);

	}
	
	private Object[] prepareArguments(BaseRecord step, Map<String, Object> context, Method method) {
		List<Object> preparedArgs = new ArrayList<>();
		List<BaseRecord> inputs = step.get("inputs");

		//Class<?>[] parameterTypes = method.getParameterTypes();
		List<Parameter> params = Arrays.asList(method.getParameters());

        for (BaseRecord param : inputs) {
            Object value = null;
            if(param.hasField("value")) {
            	value = param.get("value");
            }
            else {
            	logger.warn("Parameter " + param.get("name") + " does not have a value field, skipping");
            	logger.warn(param.toFullString());
            }
            Parameter methodParam = params.stream()
					.filter(p -> p.getName().equals(param.get("name")))
					.findFirst()
					.orElse(null);
            
            if(methodParam == null) {
            	if(method.getParameterCount() == inputs.size()) {
            		
            		methodParam = params.get(0);
            		logger.warn("Parameter name mismatch - " + param.get("name") + " was provided, but " + methodParam.getName() + " was expected");
            	}
            	if(methodParam == null) {
            		throw new IllegalArgumentException("Parameter " + param.get("name") + " not found in method " + method.getName());
            	}
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
