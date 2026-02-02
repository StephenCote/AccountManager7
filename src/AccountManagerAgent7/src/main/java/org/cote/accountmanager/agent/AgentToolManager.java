package org.cote.accountmanager.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
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
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
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
	private ChainExecutor chainExec = null;

	private static Pattern toolDescsPat = Pattern.compile("\\$\\{toolDescriptions\\}");
	private static Pattern planStepSchemaPat = Pattern.compile("\\$\\{planStepSchema\\}");
	private static Pattern paramSchemaPat = Pattern.compile("\\$\\{parameterSchema\\}");
	private static Pattern toolDescPat = Pattern.compile("\\$\\{toolDescription\\}");
	private static Pattern toolNamePat = Pattern.compile("\\$\\{toolName\\}");
	private static Pattern currentStepPat = Pattern.compile("\\$\\{currentStep\\}");
	private static Pattern totalStepsPat = Pattern.compile("\\$\\{totalSteps\\}");
	private static Pattern previousOutputPat = Pattern.compile("\\$\\{previousOutput\\}");
	
	public AgentToolManager(BaseRecord user, BaseRecord chatConfig, Object toolInstance) {
		this.toolInstance = toolInstance;
		this.toolUser = user;
		this.chatConfig = chatConfig;
		planExec = new PlanExecutor(this);
		chainExec = new ChainExecutor(this, user);
		discoverTools();
	}

	protected Object getToolInstance() {
		return toolInstance;
	}

	public PlanExecutor getPlanExecutor() {
		return planExec;
	}

	public ChainExecutor getChainExecutor() {
		return chainExec;
	}

	public BaseRecord getChatConfig() {
		return chatConfig;
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
			if(!preparePlanStep(step)) {
				err++;
			}
		}
		return (err == 0);
	}
	
	public boolean preparePlanStep(BaseRecord step) {
		String toolName = step.get("toolName");
		if(toolName == null || !toolMap.containsKey(toolName)) {
			logger.error("Tool " + toolName + " not found for step " + step.get("step"));
			return false;
		}
		preparePlanStep(step, toolMap.get(toolName));
		return true;
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
		return createPlan("Plan - " + UUID.randomUUID().toString(), defaultPlanPromptName, defaultPlanChatName, query);
	}

	public BaseRecord createChainPlan(String query) {
		BaseRecord plan = createPlan(query);
		if (plan != null) {
			plan.setValue("chainMode", true);
		}
		return plan;
	}
	
	public BaseRecord createPlan(String planName, String planPromptConfigName, String planChatName, String query) {
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
		List<BaseRecord> steps = extractJSON("tool.planStep", cnt);
		if (steps == null || steps.size() == 0) {
			logger.error("No steps in chat response: " + cnt);
			return null;
		}
		BaseRecord plan = null;
		try {
			plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			plan.set(FieldNames.FIELD_NAME, planName);
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


	public BaseRecord refineStep(BaseRecord plan, BaseRecord step, String planChatName) {
		List<BaseRecord> steps = plan.get("steps");
		BaseRecord stepPrompt = getPlanStepPromptConfig(plan, step.get("step"));

		if (stepPrompt == null) {
			logger.error("Prompt configuration was null");
			return null;
		}
		
		logger.info("Refining " + step.get("step") + " " + step.get("toolName"));
		
		String query = "Create plan step #" + step.get("step") + " for tool " + step.get("toolName")
				+ " which will be used in part or whole to answer this question: \"" + plan.get("planQuery") + "\"";

		Chat chat = new Chat(toolUser, chatConfig, stepPrompt);
		chat.setEnableKeyFrame(false);

		BaseRecord creq = ChatUtil.getCreateChatRequest(toolUser, planChatName, chatConfig, stepPrompt);
		if (creq != null) {
			/// Fetch again since the create will only return the identifiers. 
			creq = ChatUtil.getChatRequest(toolUser, planChatName, chatConfig, stepPrompt);
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
		// logger.info("Response: " + cnt);
		List<BaseRecord> steps2 = extractJSON("tool.planStep", cnt);
		if (steps2 == null || steps2.size() != 1) {
			logger.error("Expected a single step in response: " + System.lineSeparator() + cnt);
			return null;
		}

		BaseRecord cstep = steps2.get(0);
		step.setValue("inputs", cstep.get("inputs"));
		step.setValue("output", cstep.get("output"));
		step.setValue("step", cstep.get("step"));
		return step;
	}
	
	public BaseRecord evaluateResult(BaseRecord plan) {
		List<BaseRecord> steps = plan.get("steps");
		BaseRecord resultPrompt = getPlanResultConfig(plan);
		if(plan.get("output") != null) {
			logger.error("Plan already has an execution output");
			return null;
		}
		if (resultPrompt == null) {
			logger.error("Prompt configuration was null");
			return null;
		}
		if(steps.size() == 0) {
			logger.error("Plan does not define any steps");
			return null;
		}
		
		BaseRecord lastStep = steps.get(steps.size() - 1);
		BaseRecord lastResult = lastStep.get("output");
		if(lastResult == null) {
			logger.error("Last plan step does not contain a result");
			return null;
		}

		String resultStr = null;
		FieldEnumType valueType = lastResult.getEnum(FieldNames.FIELD_VALUE_TYPE);
		if(lastResult.get(FieldNames.FIELD_VALUE) != null) {
			switch(valueType) {
				case LIST:
					resultStr = JSONUtil.exportObject((List<BaseRecord>)lastResult.get(FieldNames.FIELD_VALUE), RecordSerializerConfig.getForeignUnfilteredModule());
					break;
				case MODEL:
					resultStr = JSONUtil.exportObject((BaseRecord)lastResult.get(FieldNames.FIELD_VALUE), RecordSerializerConfig.getForeignUnfilteredModule());
					break;
				case STRING:
				case INT:
				case LONG:
				case BOOLEAN:
				case ENUM:
					resultStr = lastResult.get(FieldNames.FIELD_VALUE);
					break;
				default:
					logger.error("Unsupported value type for result evaluation: " + valueType);
					break;
			}
		}
		if(resultStr == null || resultStr.length() == 0) {
			logger.error("Result string is null or empty");
			return null;
		}
		logger.info("Evaluating result for plan: " + plan.get(FieldNames.FIELD_NAME));
		
		
		String query = "The following information is the answer to a question: " + System.lineSeparator() + resultStr + System.lineSeparator() + "Use this information as the answer to the question: \"" + plan.get("planQuery") + "\"";
		String planChatName = plan.get("planChatName") + " Result - " + UUID.randomUUID().toString();
		Chat chat = new Chat(toolUser, chatConfig, resultPrompt);
		chat.setEnableKeyFrame(false);

		BaseRecord creq = ChatUtil.getCreateChatRequest(toolUser, planChatName, chatConfig, resultPrompt);
		if (creq != null) {
			/// Fetch again since the create will only return the identifiers. 
			creq = ChatUtil.getChatRequest(toolUser, planChatName, chatConfig, resultPrompt);
		}
		OpenAIRequest req = ChatUtil.getOpenAIRequest(toolUser, new ChatRequest(creq));
		logger.info(req.toFullString());
		logger.info(query);
		chat.continueChat(req, query);
		List<OpenAIMessage> msgs = req.getMessages();
		/// Account for system prompt and question
		///
		if (msgs.size() <= 2) {
			logger.error("No messages in chat response");
			return null;
		}
		String cnt = msgs.get(msgs.size() - 1).getContent();
		logger.info("Result: " + cnt);
		List<BaseRecord> outs = extractJSON("dev.parameter", cnt);
		if (outs == null || outs.size() != 1) {
			logger.error("Expected a single output in response: " + System.lineSeparator() + cnt);
			return null;
		}
		BaseRecord out = outs.get(0);
		plan.setValue("output", out);
		return out;
	}

	public static List<BaseRecord> extractJSON(final String model, final String contents) {
		logger.info("Extracting JSON from contents: " + contents);
		int fbai = contents.indexOf("{");
		String uconts = contents.substring(0, fbai + 1) + "\"schema\":\"" + model + "\","
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

		buff.append(sep + getParameterSchema() + sep);

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

		return buff.toString();

	}
	
	public String getParameterSchema() {
		StringBuilder buff = new StringBuilder();
		String sep = System.lineSeparator();

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
		return buff.toString();
	}
	
	public String getToolParameterDescription(BaseRecord put, String pref) {
		StringBuilder buff = new StringBuilder();
		String sep = System.lineSeparator();
		buff.append(pref + "{" + sep);
		buff.append(pref + "toolName: \"" + put.get("name") + "\"," + sep);
		buff.append(pref + "valueType: \"" + put.get("valueType") + "\"");
		String model = put.get("valueModel");
		if(model != null) {
			buff.append("," + sep + pref + "valueModel: \"" + model + "\","+ sep);
			buff.append(pref + "Model Schema: " + put.get("valueSchema") + sep);
		}
		else {
			buff.append(sep);
		}
		buff.append(pref + "}" + sep);
		return buff.toString();
	}

	public String getToolForPlanStep(BaseRecord step) {

		StringBuilder buff = new StringBuilder();
		String sep = System.lineSeparator();
		if(!preparePlanStep(step)) {
			logger.error("Failed to prepare plan step");
			return null;
		}
		String toolName = step.get("toolName");
		
		Optional<Method> ometh = discoveredTools.stream().filter(m -> toolName.equals(m.getName())).findFirst();
		if (!ometh.isPresent()) {
			logger.error("Tool " + toolName + " not found");
			return null;
		}
		Method meth = ometh.get();
		AgentTool annotation = meth.getAnnotation(AgentTool.class);
		buff.append("\tstep: " + step.get("step") + sep);
		buff.append("\ttoolName: " + meth.getName() + sep);
		buff.append("\tdescription: " + annotation.description() + sep);
		List<BaseRecord> inputs = step.get("inputs");
		BaseRecord output = step.get("output");
		if(inputs.size() > 0) {
			buff.append("\tTool Parameters:" + sep);
			for(BaseRecord in : inputs) {
				buff.append(getToolParameterDescription(in, "\t\t"));
			}
		}
		if(output != null) {
			buff.append("\tTool Output:" + sep);
			buff.append(getToolParameterDescription(output, "\t\t"));
		}
		//buff.append("\tinputs: " + annotation.inputs() + sep);
		//buff.append("\toutput: " + annotation.output() + sep);
		buff.append("\texample: " + annotation.example() + sep);

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
			//preparePlanSteps(plan);
		}
		catch (ModelNotFoundException | FieldException e) {
			logger.error(e);
		}
		return steps.stream().map(s -> s.toFullString()).collect(Collectors.joining(System.lineSeparator()));
	}
	
	public String getToolsForPlan() {
		JSONArray toolsArray = new JSONArray();
		for (Method method : this.discoveredTools) {
			AgentTool annotation = method.getAnnotation(AgentTool.class);
			JSONObject toolJson = new JSONObject();
			toolJson.put("toolName", method.getName());
			toolJson.put("description", annotation.description());
			//toolJson.put("parameters", annotation.parameters());
			// toolJson.put("example", annotation.example());
			toolsArray.put(toolJson);
		}
		return toolsArray.toString(2);
	}

	protected BaseRecord getCreatePromptConfig(String name) {
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
			String promptStr = ResourceUtil.getInstance().getResource("planPrompt.txt");
			if(promptStr == null || promptStr.isEmpty()) {
				logger.error("Plan prompt is null");
				return null;
			}
			promptStr = toolDescsPat.matcher(promptStr).replaceAll(Matcher.quoteReplacement(toolDescriptions));
			promptStr = planStepSchemaPat.matcher(promptStr).replaceAll(Matcher.quoteReplacement(getPlanStepSchema()));
			
			sysPrompt.clear();
			sysPrompt.addAll(promptStr.lines().collect(Collectors.toList()));
			IOSystem.getActiveContext().getAccessPoint().update(toolUser, prompt);
		}

		return prompt;
	}

	public BaseRecord getPlanResultConfig(BaseRecord plan) {
		String planName = plan.get(FieldNames.FIELD_NAME);
		BaseRecord prompt = getCreatePromptConfig(toolInstance.getClass().getName() + " " + planName + " Result");
		List<String> sysPrompt = prompt.get("system");
		
		// if(sysPrompt.size() == 0) {

			String promptStr = ResourceUtil.getInstance().getResource("planResultPrompt.txt");
			if(promptStr == null || promptStr.isEmpty()) {
				logger.error("Plan prompt is null");
				return null;
			}
			promptStr = paramSchemaPat.matcher(promptStr).replaceAll(Matcher.quoteReplacement(getParameterSchema()));
			
			sysPrompt.clear();
			sysPrompt.addAll(promptStr.lines().collect(Collectors.toList()));

			IOSystem.getActiveContext().getAccessPoint().update(toolUser, prompt);
		// }

		return prompt;
	}
	
	public BaseRecord getPlanStepPromptConfig(BaseRecord plan, int currentStep) {
		String planName = plan.get(FieldNames.FIELD_NAME);
		List<BaseRecord> steps = ((List<BaseRecord>)plan.get("steps")).stream().filter(s -> currentStep == (int)s.get("step")).collect(Collectors.toList());
		BaseRecord step = null;
		if(steps.size() != 1) {
			logger.error("No steps in plan or current step is out of bounds: " + currentStep);
			return null;
		}
		step = steps.get(0);
		String toolName = step.get("toolName");
		
		String promptName = toolInstance.getClass().getName() + " " + planName + " Step " + currentStep + " " + toolName;
		logger.info("Constructing prompt " + promptName);
		BaseRecord prompt = getCreatePromptConfig(promptName);
		List<String> sysPrompt = prompt.get("system");
		if(sysPrompt.size() == 0) {
			String toolDescription = getToolForPlanStep(step);
	
			String promptStr = ResourceUtil.getInstance().getResource("planStepPrompt.txt");
			if(promptStr == null || promptStr.isEmpty()) {
				logger.error("Plan step prompt is null");
				return null;
			}
			promptStr = toolNamePat.matcher(promptStr).replaceAll(Matcher.quoteReplacement(toolName));
			promptStr = toolDescPat.matcher(promptStr).replaceAll(Matcher.quoteReplacement(toolDescription));
			promptStr = planStepSchemaPat.matcher(promptStr).replaceAll(Matcher.quoteReplacement(getPlanStepSchema()));
			promptStr = previousOutputPat.matcher(promptStr).replaceAll(Matcher.quoteReplacement(""));
			promptStr = currentStepPat.matcher(promptStr).replaceAll(Integer.toString(currentStep));
			promptStr = totalStepsPat.matcher(promptStr).replaceAll(Integer.toString(steps.size()));
			
			sysPrompt.clear();
			sysPrompt.add(promptStr);
			IOSystem.getActiveContext().getAccessPoint().update(toolUser, prompt);
		}



		return prompt;
	}

	
	
}