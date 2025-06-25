package org.cote.accountmanager.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.json.JSONArray;
import org.json.JSONObject;

public class AgentToolManager {

	public static final Logger logger = LogManager.getLogger(AgentToolManager.class);
	private Object toolInstance = null;
	private List<Method> discoveredTools = new ArrayList<>();
	private Map<String, Method> toolMap = new HashMap<>();
	private BaseRecord toolUser = null;
	private BaseRecord chatConfig = null;
	private String defaultPlanPromptName = "Plan Prompt";
	private String defaultPlanChatName = "Plan Chat";
	private String defaultStepPromptName = "Tool Prompt";
	private String defaultStepChatName = "Tool Chat";
	private PlanExecutor planExec = null;
	public AgentToolManager(BaseRecord user, BaseRecord chatConfig, Object toolInstance) {
		this.toolInstance = toolInstance;
		this.toolUser = user;
		this.chatConfig = chatConfig;
		planExec = new PlanExecutor(this);
		discoverTools();
	}

	protected Object getToolInstance() {
		return toolInstance;
	}
	
	public PlanExecutor getPlanExecutor() {
		return planExec;
	}
	
	protected Method getToolMethod(String toolName) {
		Optional<Method> ometh = discoveredTools.stream().filter(m -> toolName.equals(m.getName())).findFirst();
		if (!ometh.isPresent()) {
			logger.error("Tool " + toolName + " not found");
			return null;
		}
		return ometh.get();
	}
	
	public boolean preparePlanSteps(BaseRecord plan) {
		List<BaseRecord> steps = plan.get("steps");
		int err = 0;
		for(BaseRecord step : steps) {
			String toolName = step.get("toolName");
			if(toolName == null || !toolMap.containsKey(toolName)) {
				logger.error("Tool " + toolName + " not found for step " + step.get("step"));
				err++;
				continue;
			}
			preparePlanStep(step, toolMap.get(toolName));
		}
		return (err == 0);
	}
	
	public void preparePlanStep(BaseRecord step, Method method) {
		List<BaseRecord> inputs = step.get("inputs");
		inputs.clear();
		step.setValue("output", prepareOutput(method));
		try {
			for(Parameter param : method.getParameters()) {
	            inputs.add(prepareInput(param));
			}
		}
		catch(ModelNotFoundException | FieldException e) {
			logger.error(e);
		}
    }
	
	private BaseRecord prepareOutput(Method method) {
		BaseRecord output = null;
		try {
			output = RecordFactory.newInstance("dev.parameter");
			AgentTool annotation = method.getAnnotation(AgentTool.class);
			if(annotation.returnType() == null || annotation.returnType() == FieldEnumType.UNKNOWN) {
				return null;
			}
			output.setValue("valueType", annotation.returnType());
			if(annotation.returnModel() != null && !annotation.returnModel().isEmpty()) {
				output.setValue("valueModel", annotation.returnModel());
			}
		}
		catch(ModelNotFoundException | FieldException e) {
			logger.error(e);
		}
		return output;
	}
	
	private BaseRecord prepareInput(Parameter param) throws FieldException, ModelNotFoundException {
		BaseRecord input = RecordFactory.newInstance("dev.parameter");
        if (!param.isAnnotationPresent(AgentToolParameter.class)) {
            throw new IllegalArgumentException("Parameter " + param.getName() + " is not annotated with @AgentToolParameter");	
        }
        AgentToolParameter atp = param.getAnnotation(AgentToolParameter.class);
		
        input.setValue("name", atp.name());
        input.setValue("valueType", atp.type());
        if(atp.model() != null && !atp.model().isEmpty()) {
        	input.setValue("valueModel", atp.model());
        	if(!atp.model().equals(ModelNames.MODEL_MODEL)) {
        		logger.info("Model: " + atp.model());
        		input.setValue("valueSchema", SchemaUtil.getModelDescription(atp.model()));
        	}
        }
        
        return input;
	}

	public BaseRecord createPlan(String query) {
		return createPlan(defaultPlanPromptName, defaultPlanChatName, query);
	}
	
	public BaseRecord createPlan(String planPromptConfigName, String planChatName, String query) {
		BaseRecord prompt = getPlanPromptConfig(planPromptConfigName);
		if (prompt == null) {
			logger.error("Prompt configuration was null");
			return null;
		}

		Chat chat = new Chat(toolUser, chatConfig, prompt);
		chat.setEnableKeyFrame(false);

		BaseRecord creq = ChatUtil.getCreateChatRequest(toolUser, planChatName, chatConfig, prompt);
		if (creq != null) {

			creq = ChatUtil.getChatRequest(toolUser, planChatName, chatConfig, prompt);
		}
		OpenAIRequest req = ChatUtil.getOpenAIRequest(toolUser, new ChatRequest(creq));

		chat.continueChat(req, query);
		List<OpenAIMessage> msgs = req.getMessages();

		/// Account for system prompt and question
		///
		if (msgs.size() <= 2) {
			logger.error("No messages in chat response");
			return null;
		}
		String cnt = msgs.get(msgs.size() - 1).getContent();
		List<BaseRecord> steps = extractJSON(cnt);
		if (steps == null || steps.size() == 0) {
			logger.error("No steps in chat response: " + cnt);
			return null;
		}
		BaseRecord plan = null;
		try {
			plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			((List<BaseRecord>) plan.get("steps")).addAll(steps);
			plan.set("planPromptConfigName", planPromptConfigName);
			plan.set("planChatName", planChatName);
			plan.set("planQuery", query);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		if (plan == null || ((List<BaseRecord>) plan.get("steps")).size() == 0) {
			logger.error("Plan is null or no steps in plan");
			return null;
		}
		return plan;
	}

	public BaseRecord createPlanXXX(String planPromptConfigName, String planChatName, String query) {
		BaseRecord prompt = getPlanPromptConfigXXX(planPromptConfigName);
		if (prompt == null) {
			logger.error("Prompt configuration was null");
			return null;
		}
		Chat chat = new Chat(toolUser, chatConfig, prompt);
		chat.setEnableKeyFrame(false);

		BaseRecord creq = ChatUtil.getCreateChatRequest(toolUser, planChatName, chatConfig, prompt);
		if (creq != null) {
			/// Fetch again since the create will only return the identifiers. 
			creq = ChatUtil.getChatRequest(toolUser, planChatName, chatConfig, prompt);
		}
		OpenAIRequest req = ChatUtil.getOpenAIRequest(toolUser, new ChatRequest(creq));

		chat.continueChat(req, query);
		List<OpenAIMessage> msgs = req.getMessages();

		/// Account for system prompt and question
		///
		if (msgs.size() <= 2) {
			logger.error("No messages in chat response");
			return null;
		}
		String cnt = msgs.get(msgs.size() - 1).getContent();
		List<BaseRecord> steps = extractJSON(cnt);
		if (steps == null || steps.size() == 0) {
			logger.error("No steps in chat response: " + cnt);
			return null;
		}
		BaseRecord plan = null;
		try {
			plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			((List<BaseRecord>) plan.get("steps")).addAll(steps);
			plan.set("planPromptConfigName", planPromptConfigName);
			plan.set("planChatName", planChatName);
			plan.set("planQuery", query);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		if (plan == null || ((List<BaseRecord>) plan.get("steps")).size() == 0) {
			logger.error("Plan is null or no steps in plan");
			return null;
		}
		return plan;
	}

	public BaseRecord createStepPlanXXX(BaseRecord plan, BaseRecord step) {
		return createStepPlanXXX(defaultStepPromptName, defaultStepChatName, plan, step);
	}

	public BaseRecord createStepPlanXXX(String stepPromptConfigName, String stepChatName, BaseRecord plan,
			BaseRecord step) {
		BaseRecord stepPrompt = getPlanStepPromptConfigXXX(stepPromptConfigName, step.get("step"), step.get("toolName"));

		if (stepPrompt == null) {
			logger.error("Prompt configuration was null");
			return null;
		}

		String query = "Create plan step #" + step.get("step") + " for tool " + step.get("toolName")
				+ " which will be used in part or whole to answer this question: \"" + plan.get("planQuery") + "\"";

		Chat chat = new Chat(toolUser, chatConfig, stepPrompt);
		chat.setEnableKeyFrame(false);

		BaseRecord creq = ChatUtil.getCreateChatRequest(toolUser, stepChatName, chatConfig, stepPrompt);
		if (creq != null) {
			/// Fetch again since the create will only return the identifiers. 
			creq = ChatUtil.getChatRequest(toolUser, stepChatName, chatConfig, stepPrompt);
		}
		OpenAIRequest req = ChatUtil.getOpenAIRequest(toolUser, new ChatRequest(creq));

		chat.continueChat(req, query);
		List<OpenAIMessage> msgs = req.getMessages();

		/// Account for system prompt and question
		///
		if (msgs.size() <= 2) {
			logger.error("No messages in chat response");
			return null;
		}
		String cnt = msgs.get(msgs.size() - 1).getContent();

		List<BaseRecord> steps = extractJSON(cnt);
		if (steps == null || steps.size() == 0) {
			logger.error("No steps in chat response: " + System.lineSeparator() + cnt);
			logger.error("Attempting to identify problem:");
			chat.continueChat(req,
					"Your response was not valid JSON.  Please analyze why you are making formatting mistakes such as double-quoting and provide a recommendation to the system prompt to ensure the JSON is valid.");
			msgs = req.getMessages();
			logger.error(msgs.get(msgs.size() - 1).getContent());
			return null;
		}
		if (steps.size() != 1) {
			logger.error("Unexpected number of steps in response");
			return null;
		}

		BaseRecord cstep = steps.get(0);
		step.setValue("inputs", cstep.get("inputs"));
		step.setValue("output", cstep.get("output"));
		step.setValue("step", cstep.get("step"));
		return step;
	}

	public static List<BaseRecord> extractJSON(final String contents) {
		logger.info("Extracting JSON from contents: " + contents);
		int fbai = contents.indexOf("{");
		String uconts = contents.substring(0, fbai + 1) + "\"schema\":\"tool.planStep\","
				+ contents.substring(fbai + 1, contents.length());
		int fai = uconts.indexOf("[");
		fbai = uconts.indexOf("{");
		int lai = uconts.lastIndexOf("]");
		int lbai = uconts.lastIndexOf("}");

		if (fai == -1 || fai > fbai) {
			uconts = "[" + uconts.substring(fbai, lbai + 1) + "]";
		} else {
			uconts = uconts.substring(fai, lai + 1);
		}

		return JSONUtil.getList(uconts, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}

	public String getDefaultPlanChatName() {
		return defaultPlanChatName;
	}

	public void setDefaultPlanChatName(String defaultPlanChatName) {
		this.defaultPlanChatName = defaultPlanChatName;
	}

	public String getDefaultStepChatName() {
		return defaultStepChatName;
	}

	public void setDefaultStepChatName(String defaultStepChatName) {
		this.defaultStepChatName = defaultStepChatName;
	}

	private void discoverTools() {
		Set<Method> methodSet = new HashSet<>();

		// Start the search from the runtime class of the toolInstance, as you correctly
		// pointed out.
		Class<?> currentClass = this.toolInstance.getClass();

		// Walk up the inheritance tree
		while (currentClass != null && !currentClass.equals(Object.class)) {
			findAnnotatedMethods(currentClass, methodSet);
			currentClass = currentClass.getSuperclass();
		}

		this.discoveredTools.addAll(methodSet);
		for (Method m : discoveredTools) {
			toolMap.put(m.getName(), m);
		}
	}

	private void findAnnotatedMethods(Class<?> clazz, Set<Method> methodSet) {
		for (Method method : clazz.getDeclaredMethods()) {
			if (method.isAnnotationPresent(AgentTool.class)) {
				methodSet.add(method);
			}
		}
		for (Class<?> iface : clazz.getInterfaces()) {
			findAnnotatedMethods(iface, methodSet);
		}
	}
	
	public String getPlanStepSchema() {

		StringBuilder buff = new StringBuilder();
		String sep = System.lineSeparator();
		buff.append("tool.planStep Schema:" + sep);
		buff.append("""
				{    
					"name": "toolName",
					"type": "string"
				},
				{
					"name": "step",
					"type": "int",
					"default": 0
				},
				{
					"name": "inputs",
					"type": "list",
					"baseType": "model",
					"baseModel": "dev.parameter"
				},
				{
					"name": "output",
					"type": "model",
					"baseModel": "dev.parameter"
				}
		""");

		buff.append("dev.parameter Schema:" + sep);
		buff.append("""
		{
			"name": "name",
			"type": "string"
		},
		{
			"name": "value",
			"type": "flex",
			"valueType": "valueType",
			"description": "If the valueType is 'model' then use the provided information or request the model schema before using it."
		},
		{
			"name": "valueType",
			"type": "enum"
		},
		{
			"name": "valueModel",
			"type": "string"
		}

		""");

		buff.append(sep + "tool.planStep Example:" + sep);
		buff.append("""
				{
		    		"toolName": "findMembersOfRoles",
		    		"step": 1,
		    		"inputs": [
		    			{
		    				"name": "modelName",
		    				"valueType": "string",
		    				"value": "olio.charPerson"
		    			},
		    			{
				  			"name": "roles",
				  			"valueType": "list",
				  			"valueModel": "olio.role",
				  			"value": [
				  				{
				  					"name": "Account Administrators",
				  					"id": 13
				  				}
				  			]
		    			}
		    		]
			}
		""");
/*
 ,
		    		"output": {
				    	"name": "return",
						"valueType": "list",
						"valueModel": "olio.charPerson",
						"value": [
							{
								"name": "John Doe",
								"id": 12345
							}
						]
					}
 */
		return buff.toString();

	}

	public String getToolForPlanStepXXX(String toolName) {

		StringBuilder buff = new StringBuilder();
		String sep = System.lineSeparator();
		Optional<Method> ometh = discoveredTools.stream().filter(m -> toolName.equals(m.getName())).findFirst();
		if (!ometh.isPresent()) {
			logger.error("Tool " + toolName + " not found");
			return null;
		}
		Method meth = ometh.get();
		AgentTool annotation = meth.getAnnotation(AgentTool.class);
		buff.append("\ttoolName: " + meth.getName() + sep);
		buff.append("\tdescription: " + annotation.description() + sep);
		//buff.append("\tinputs: " + annotation.inputs() + sep);
		//buff.append("\toutput: " + annotation.output() + sep);
		buff.append("\texample: " + annotation.example() + sep);

		buff.append("Plan Step Schema:" + sep);
		buff.append("""
				      	{
					"name": "toolName",
					"type": "string"
				},
				{
					"name": "step",
					"type": "int",
					"default": 0
				},
				{
					"name": "inputs",
					"type": "list",
					"baseType": "model",
					"baseModel": "dev.parameter"
				},
				{
					"name": "output",
					"type": "model",
					"baseModel": "dev.parameter"
				}
				  """);
		buff.append(sep + "Plan Step Example:" + sep);
		buff.append("""
				    		{
				    		"toolName": "${toolName}",
				    		"step": 1,
				    		"inputs": [
				    			{
				    				"name": "modelName",
				    				"valueType": "string",
				    				"value": "olio.charPerson"
				    			}
				    		],
				    		"output": {
				    "name": "return",
					"valueType": "list",
					"value": [
						{
							"name": "John Doe",
							"id": 12345
						}
					]
				}
				    		}

				    	""");

		return buff.toString();

	}
	public String getMasterPlan() {
		BaseRecord plan = null;
		List<BaseRecord> steps = new ArrayList<>();
		try {
			plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			steps = plan.get("steps");
			for (Method method : this.discoveredTools) {
				BaseRecord step = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
				step.setValue("toolName", method.getName());
				AgentTool annotation = method.getAnnotation(AgentTool.class);
				step.setValue("description", annotation.description());
				steps.add(step);
			}
			preparePlanSteps(plan);
		}
		catch (ModelNotFoundException | FieldException e) {
			logger.error(e);
		}
		return steps.stream().map(s -> s.toFullString()).collect(Collectors.joining(System.lineSeparator()));
	}
	public String getToolsForPlanXXX() {
		JSONArray toolsArray = new JSONArray();
		for (Method method : this.discoveredTools) {
			AgentTool annotation = method.getAnnotation(AgentTool.class);
			JSONObject toolJson = new JSONObject();
			toolJson.put("toolName", method.getName());
			toolJson.put("description", annotation.description());

			// toolJson.put("parameters", annotation.parameters());
			// toolJson.put("example", annotation.example());
			toolsArray.put(toolJson);
		}
		return toolsArray.toString(2);
	}

	private BaseRecord getCreatePromptConfig(String name) {
		BaseRecord opcfg = DocumentUtil.getRecord(toolUser, OlioModelNames.MODEL_PROMPT_CONFIG, name, "~/Chat");
		if (opcfg != null) {
			return opcfg;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);

		BaseRecord pcfg = null;

		try {
			pcfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, toolUser,
					null, plist);
			opcfg = IOSystem.getActiveContext().getAccessPoint().create(toolUser, pcfg);
		} catch (NullPointerException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return opcfg;
	}
	
	public BaseRecord getPlanPromptConfig(String planName) {
		BaseRecord prompt = getCreatePromptConfig(toolInstance.getClass().getName() + " " + planName);
		List<String> sysPrompt = prompt.get("system");
		
		if(sysPrompt.size() == 0) {
			String toolDescriptions = getMasterPlan();
			String promptStr = "You are a helpful assistant that creates an execution plan to answer a user's question."
					+ System.lineSeparator()
					+ "Respond with a JSON array containing tool.planStep models of the tools to call in the relevant order." + System.lineSeparator()
					+ "The following JSON schema describes tool.planStep tools available to you, including field names and types, return value, and descriptions." + System.lineSeparator()
					+ toolDescriptions + System.lineSeparator()
					+ "The following describes the tool.planStep schema you must use to create the plan steps:" + System.lineSeparator()
					+ getPlanStepSchema() + System.lineSeparator()
					+ "It is important to use the correct model schemas - ALWAYS lookup a desired schema definition before using it."
					+ System.lineSeparator() + "Your response MUST BE VALID JSON!"
					+ " Ensure that all string values are enclosed in double quotes without additional escape characters or mismatched quotation types.";
			sysPrompt.addAll(promptStr.lines().collect(Collectors.toList()));
			IOSystem.getActiveContext().getAccessPoint().update(toolUser, prompt);
		}

		return prompt;
	}

	public BaseRecord getPlanPromptConfigXXX(String planName) {
		BaseRecord prompt = getCreatePromptConfig(toolInstance.getClass().getName() + " " + planName);
		List<String> sysPrompt = prompt.get("system");
		// if(sysPrompt.size() == 0) {
		String toolDescriptions = getToolsForPlanXXX();
		String promptStr = "You are a helpful assistant that creates an execution plan to answer a user's question."
				+ System.lineSeparator()
				+ "Respond with a JSON array of tool.planStep objects containing the names of the tools to call in the relevant order.:"
				+ System.lineSeparator() + toolDescriptions + System.lineSeparator()
				+ "It is important to use the correct model schemas - ALWAYS lookup a desired schema definition before using it."
				+ System.lineSeparator() + "Your response MUST BE VALID JSON!"
				+ " Ensure that all string values are enclosed in double quotes without additional escape characters or mismatched quotation types.";
		sysPrompt.add(promptStr);
		IOSystem.getActiveContext().getAccessPoint().update(toolUser, prompt);
		// }

		return prompt;
	}

	public BaseRecord getPlanStepPromptConfigXXX(String planName, int step, String toolName) {
		BaseRecord prompt = getCreatePromptConfig(
				toolInstance.getClass().getName() + " " + planName + " Step " + step + " " + toolName);
		List<String> sysPrompt = prompt.get("system");
		String toolDescriptions = getToolForPlanStepXXX(toolName);
		String promptStr = "You are a helpful assistant that creates an execution step of an overall plan to answer a user's question."
				+ System.lineSeparator() + "You identified the tool \"" + toolName + "\" to use for step #" + step
				+ " in a plan you created." + System.lineSeparator()
				+ "Use the following schema guidance when generating JSON for plan steps:" + System.lineSeparator()
				+ toolDescriptions + System.lineSeparator() + "Your response MUST BE VALID JSON!"
				+ System.lineSeparator()
				+ "1. **Enclose all string values in double quotes**: This includes both keys and their corresponding string values."
				+ System.lineSeparator()
				+ "2. **Ensure correct use of colons and commas**: Each key-value pair should be separated by a colon, and each item in an object or array should be separated by a comma."
				+ System.lineSeparator()
				+ "3. **Verify matching braces**: Ensure that every opening brace `{` has a corresponding closing brace `}`."
				+ System.lineSeparator()
				+ "4. **Avoid trailing commas**: Do not place a comma after the last element in an array or object."
				+ System.lineSeparator()
				+ "5. **Correctly format nested structures**: When using nested objects or arrays, make sure they are properly formatted with appropriate indentation for readability (though indentation does not affect validity)."
				+ System.lineSeparator();
		sysPrompt.clear();
		sysPrompt.add(promptStr);
		IOSystem.getActiveContext().getAccessPoint().update(toolUser, prompt);
		// }

		return prompt;
	}

}