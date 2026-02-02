package org.cote.accountmanager.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.mcp.Am7Uri;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.mcp.McpContextParser;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.IChainEventListener;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.StepStatusEnumType;
import org.cote.accountmanager.schema.type.StepTypeEnumType;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.VectorUtil;

import io.jsonwebtoken.lang.Arrays;

public class ChainExecutor {
	public static final Logger logger = LogManager.getLogger(ChainExecutor.class);

	private static final int ABSOLUTE_MAX_STEPS = 50;
	private static final int MAX_DYNAMIC_PER_ROUTING = 3;
	private static final Pattern CONTEXT_VARIABLE_PATTERN = Pattern.compile("\\{\\{(.+?)\\}\\}");

	private AgentToolManager toolManager;
	private BaseRecord toolUser;
	private Map<String, Object> chainContext = new HashMap<>();
	private IChainEventListener listener;

	private String lastLLMOutput = null;

	public ChainExecutor(AgentToolManager toolManager, BaseRecord user) {
		this.toolManager = toolManager;
		this.toolUser = user;
	}

	public void setListener(IChainEventListener listener) {
		this.listener = listener;
	}

	public IChainEventListener getListener() {
		return listener;
	}

	public Map<String, Object> getChainContext() {
		return chainContext;
	}

	public void executeChain(BaseRecord plan) throws PlanExecutionError {
		if (plan == null) {
			throw new PlanExecutionError("Plan is null");
		}
		if ((boolean) plan.get("executed")) {
			logger.warn("Plan " + plan.get(FieldNames.FIELD_NAME) + " has already been executed, skipping");
			return;
		}

		List<BaseRecord> steps = plan.get("steps");
		if (steps == null || steps.isEmpty()) {
			throw new PlanExecutionError("No steps in plan");
		}

		int maxSteps = (int) plan.get("maxSteps");
		if (maxSteps > ABSOLUTE_MAX_STEPS) {
			maxSteps = ABSOLUTE_MAX_STEPS;
		}

		steps.sort(Comparator.comparingInt(s -> (int) s.get("step")));

		String planQuery = plan.get("planQuery");
		logger.info("Executing chain: " + planQuery + " with " + steps.size() + " steps (max=" + maxSteps + ")");

		int totalExecuted = 0;

		for (int i = 0; i < steps.size(); i++) {
			if (totalExecuted >= maxSteps) {
				logger.warn("Chain reached maxSteps limit: " + maxSteps);
				emitEvent(plan, steps, i, "chainMaxSteps", "Chain reached maximum step limit: " + maxSteps, null);
				break;
			}

			BaseRecord step = steps.get(i);
			StepTypeEnumType stepType = step.getEnum("stepType");
			if (stepType == null) {
				stepType = StepTypeEnumType.TOOL;
			}

			step.setValue("stepStatus", StepStatusEnumType.EXECUTING);
			emitEvent(plan, steps, i, "stepStart", "Starting step " + step.get("step") + " (" + stepType + ")", null);

			try {
				switch (stepType) {
					case TOOL:
						executeToolStep(step);
						break;
					case LLM:
						executeLLMStep(plan, step);
						break;
					case RAG_QUERY:
						executeRAGStep(step);
						break;
					case POLICY_GATE:
						executePolicyGateStep(plan, step, steps, i);
						break;
					default:
						throw new PlanExecutionError("Unknown step type: " + stepType + " at step " + step.get("step"));
				}

				if (step.getEnum("stepStatus") != StepStatusEnumType.GATED) {
					step.setValue("stepStatus", StepStatusEnumType.COMPLETED);
				}
				totalExecuted++;
				plan.setValue("totalExecutedSteps", totalExecuted);

				emitEvent(plan, steps, i, "stepComplete",
					step.get("summaryText") != null ? (String) step.get("summaryText") : "Step " + step.get("step") + " completed",
					null);

				// Handle dynamic step insertion for LLM steps
				if (stepType == StepTypeEnumType.LLM) {
					handleDynamicStepInsertion(plan, step, steps, i, maxSteps);
				}

			} catch (PlanExecutionError e) {
				step.setValue("stepStatus", StepStatusEnumType.FAILED);
				step.setValue("summaryText", e.getMessage());
				emitEvent(plan, steps, i, "stepError", e.getMessage(), e.getMessage());
				throw e;
			} catch (Exception e) {
				step.setValue("stepStatus", StepStatusEnumType.FAILED);
				step.setValue("summaryText", e.getMessage());
				emitEvent(plan, steps, i, "stepError", e.getMessage(), e.getMessage());
				throw new PlanExecutionError("Error executing step #" + step.get("step") + ": " + e.getMessage());
			}
		}

		plan.setValue("executed", true);
		saveContext(plan);
		emitEvent(plan, steps, steps.size() - 1, "chainComplete", "Chain execution completed", null);
	}

	protected void executeToolStep(BaseRecord step) throws PlanExecutionError {
		String toolName = step.get("toolName");
		Method meth = toolManager.getToolMethod(toolName);
		if (meth == null) {
			throw new PlanExecutionError("Tool " + toolName + " not found for step " + step.get("step"));
		}

		logger.info("Executing TOOL step #" + step.get("step") + " for tool " + toolName);
		AgentTool tool = meth.getAnnotation(AgentTool.class);
		Object[] arguments = prepareArguments(step, meth);

		try {
			Object result = meth.invoke(toolManager.getToolInstance(), arguments);

			BaseRecord output = RecordFactory.newInstance(ModelNames.MODEL_PARAMETER);

			String rname = tool.returnName();
			output.set(FieldNames.FIELD_NAME, rname);
			output.set(FieldNames.FIELD_VALUE_TYPE, tool.returnType());
			if (tool.returnModel() != null && !tool.returnModel().isEmpty()) {
				output.set("valueModel", tool.returnModel());
				output.set(FieldNames.FIELD_VALUE, null);
				if (tool.returnType() == FieldEnumType.MODEL) {
					output.setFlex("value", (BaseRecord) result);
				} else if (tool.returnType() == FieldEnumType.LIST) {
					output.setFlex("value", tool.returnType(), (List<BaseRecord>) result);
				} else {
					logger.warn("Unhandled return model type");
				}
			} else {
				output.setFlex("value", result);
			}

			// Attach output to step after all values are populated
			step.setValue("output", output);
			chainContext.put(rname, result);

			String summary = "Tool " + toolName + " executed";
			if (result != null) {
				String resultStr = result.toString();
				summary = resultStr.length() > 200 ? resultStr.substring(0, 200) + "..." : resultStr;
			}
			step.setValue("summaryText", summary);

		} catch (NullPointerException | IllegalArgumentException | IllegalAccessException
				| InvocationTargetException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			throw new PlanExecutionError("Error executing TOOL step #" + step.get("step") + " for tool " + toolName + ": " + e.getMessage());
		}
	}

	protected void executeLLMStep(BaseRecord plan, BaseRecord step) throws PlanExecutionError {
		String promptConfigName = step.get("promptConfigName");
		String chatConfigName = step.get("chatConfigName");

		if (promptConfigName == null || promptConfigName.isEmpty()) {
			promptConfigName = plan.get("planPromptConfigName");
		}
		if (chatConfigName == null || chatConfigName.isEmpty()) {
			chatConfigName = plan.get("planChatName");
		}

		logger.info("Executing LLM step #" + step.get("step") + " with prompt=" + promptConfigName + " chat=" + chatConfigName);

		BaseRecord prompt = toolManager.getCreatePromptConfig(promptConfigName);
		if (prompt == null) {
			throw new PlanExecutionError("Prompt config '" + promptConfigName + "' not found for LLM step " + step.get("step"));
		}

		// Compose the message from step inputs and accumulated context
		String message = composeLLMMessage(step);

		Chat chat = new Chat(toolUser, toolManager.getChatConfig(), prompt);
		chat.setEnableKeyFrame(false);

		String chatName = chatConfigName + " Chain LLM " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(toolUser, chatName, toolManager.getChatConfig(), prompt);
		if (creq != null) {
			creq = ChatUtil.getChatRequest(toolUser, chatName, toolManager.getChatConfig(), prompt);
		}
		OpenAIRequest req = ChatUtil.getOpenAIRequest(toolUser, new ChatRequest(creq));

		chat.continueChat(req, message);
		List<OpenAIMessage> msgs = req.getMessages();

		if (msgs.size() <= 2) {
			step.setValue("stepStatus", StepStatusEnumType.FAILED);
			step.setValue("summaryText", "LLM returned no response");
			throw new PlanExecutionError("LLM returned no response for step " + step.get("step"));
		}

		String assistantResponse = msgs.get(msgs.size() - 1).getContent();
		if (assistantResponse == null || assistantResponse.trim().isEmpty()) {
			step.setValue("stepStatus", StepStatusEnumType.FAILED);
			step.setValue("summaryText", "LLM returned empty response");
			throw new PlanExecutionError("LLM returned empty response for step " + step.get("step"));
		}

		// Filter MCP context blocks from response
		String cleanResponse = McpContextParser.stripEphemeral(assistantResponse);

		// Store output
		try {
			BaseRecord output = RecordFactory.newInstance(ModelNames.MODEL_PARAMETER);

			List<BaseRecord> inputs = step.get("inputs");
			String outputName = "llmOutput_" + step.get("step");
			if (inputs != null && !inputs.isEmpty()) {
				BaseRecord firstInput = inputs.get(0);
				String iname = firstInput.get(FieldNames.FIELD_NAME);
				if (iname != null && !iname.isEmpty()) {
					outputName = iname + "_result";
				}
			}

			output.set(FieldNames.FIELD_NAME, outputName);
			output.set(FieldNames.FIELD_VALUE_TYPE, FieldEnumType.STRING);
			output.set(FieldNames.FIELD_VALUE, cleanResponse);

			// Attach output to step after all values are populated
			step.setValue("output", output);
			chainContext.put(outputName, cleanResponse);

			String summary = cleanResponse.length() > 200 ? cleanResponse.substring(0, 200) + "..." : cleanResponse;
			step.setValue("summaryText", summary);

			// Stall detection
			if (lastLLMOutput != null && lastLLMOutput.equals(cleanResponse)) {
				logger.warn("Stall detected: identical LLM output in consecutive steps");
				throw new PlanExecutionError("Chain stalled: identical LLM output detected at step " + step.get("step"));
			}
			lastLLMOutput = cleanResponse;

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			throw new PlanExecutionError("Error storing LLM output for step " + step.get("step") + ": " + e.getMessage());
		}
	}

	protected void executeRAGStep(BaseRecord step) throws PlanExecutionError {
		String ragQuery = step.get("ragQuery");
		if (ragQuery == null || ragQuery.isEmpty()) {
			throw new PlanExecutionError("RAG query is null for step " + step.get("step"));
		}

		// Resolve context variables in the query
		ragQuery = resolveContextVariables(ragQuery);

		int ragLimit = (int) step.get("ragLimit");
		if (ragLimit <= 0) ragLimit = 10;

		logger.info("Executing RAG step #" + step.get("step") + " query='" + ragQuery + "' limit=" + ragLimit);

		VectorUtil vectorUtil = IOSystem.getActiveContext().getVectorUtil();
		if (vectorUtil == null) {
			throw new PlanExecutionError("VectorUtil is not available for RAG step " + step.get("step"));
		}

		try {
			// If a vectorReferenceUri is set, scope the search accordingly
			String vectorReferenceUri = step.get("vectorReferenceUri");
			List<BaseRecord> results;
			if (vectorReferenceUri != null && !vectorReferenceUri.isEmpty()) {
				// Validate the URI format; scoping will be refined when MCP RAG pipeline lands
				Am7Uri.parse(vectorReferenceUri);
				logger.info("RAG scoped to URI: " + vectorReferenceUri);
				results = vectorUtil.find(toolUser, ragQuery, ragLimit, 60.0);
			} else {
				results = vectorUtil.find(toolUser, ragQuery, ragLimit, 60.0);
			}

			// Format results as MCP context blocks
			McpContextBuilder ctxBuilder = new McpContextBuilder();
			List<Map<String, Object>> resultList = new ArrayList<>();

			for (BaseRecord result : results) {
				Map<String, Object> entry = new HashMap<>();
				String content = result.get("content");
				if (content != null) {
					entry.put("content", content);
				}
				if (result.hasField("score")) {
					entry.put("score", result.get("score"));
				}
				if (result.hasField("chunk")) {
					entry.put("chunk", result.get("chunk"));
				}
				resultList.add(entry);
			}

			String stepId = UUID.randomUUID().toString();
			ctxBuilder.addResource(
				"am7://vector/citations/" + stepId,
				"urn:am7:vector:search-result",
				Map.of("query", ragQuery, "results", resultList),
				true
			);

			String formattedResults = ctxBuilder.build();

			BaseRecord output = RecordFactory.newInstance(ModelNames.MODEL_PARAMETER);

			String outputName = "ragResults_" + step.get("step");
			output.set(FieldNames.FIELD_NAME, outputName);
			output.set(FieldNames.FIELD_VALUE_TYPE, FieldEnumType.STRING);
			output.set(FieldNames.FIELD_VALUE, formattedResults);

			// Attach output to step after all values are populated
			step.setValue("output", output);
			chainContext.put(outputName, formattedResults);

			step.setValue("summaryText", "RAG search returned " + results.size() + " results for: " + ragQuery);

		} catch (Exception e) {
			throw new PlanExecutionError("Error executing RAG step #" + step.get("step") + ": " + e.getMessage());
		}
	}

	protected void executePolicyGateStep(BaseRecord plan, BaseRecord step, List<BaseRecord> steps, int index) throws PlanExecutionError {
		String policyName = step.get("policyName");
		if (policyName == null || policyName.isEmpty()) {
			throw new PlanExecutionError("Policy name is null for POLICY_GATE step " + step.get("step"));
		}

		logger.info("Executing POLICY_GATE step #" + step.get("step") + " with policy=" + policyName);

		// Get previous step output as fact data
		String factData = "";
		if (index > 0) {
			BaseRecord prevStep = steps.get(index - 1);
			BaseRecord prevOutput = prevStep.get("output");
			if (prevOutput != null) {
				Object val = prevOutput.get(FieldNames.FIELD_VALUE);
				if (val != null) {
					factData = val.toString();
				}
			}
		}

		try {
			// Build fact
			BaseRecord fact = RecordFactory.newInstance(ModelNames.MODEL_FACT);
			fact.set(FieldNames.FIELD_NAME, "chainStepOutput");
			fact.set("factData", factData);
			fact.set("factType", "PARAMETER");

			// Build policy request with the policy URN and fact
			BaseRecord prt = RecordFactory.newInstance(ModelNames.MODEL_POLICY_REQUEST);
			prt.set("urn", "urn:am7:chain:policy-gate:" + policyName);
			prt.set("organizationPath", (String) toolUser.get(FieldNames.FIELD_ORGANIZATION_PATH));
			prt.set("contextUser", toolUser);
			List<BaseRecord> facts = prt.get(FieldNames.FIELD_FACTS);
			facts.add(fact);

			// Find the policy to pass directly
			BaseRecord policy = IOSystem.getActiveContext().getPathUtil().findPath(toolUser, ModelNames.MODEL_POLICY, "~/Policies/" + policyName, "DATA", toolUser.get(FieldNames.FIELD_ORGANIZATION_ID));

			if (policy == null) {
				throw new PlanExecutionError("Policy '" + policyName + "' not found for step " + step.get("step"));
			}

			// Evaluate via instance-based PolicyEvaluator
			PolicyEvaluator evaluator = new PolicyEvaluator(IOSystem.getActiveContext());
			BaseRecord prr = evaluator.evaluatePolicyRequest(prt, policy);

			String responseStr = prr != null ? (String) prr.get(FieldNames.FIELD_TYPE) : null;

			if (prr != null && "PERMIT".equals(responseStr)) {
				step.setValue("stepStatus", StepStatusEnumType.COMPLETED);
				step.setValue("summaryText", "Policy gate PERMITTED: " + policyName);
				logger.info("Policy gate PERMIT for step " + step.get("step"));
			} else {
				step.setValue("stepStatus", StepStatusEnumType.GATED);
				step.setValue("summaryText", "Policy gate DENIED: " + policyName);
				logger.warn("Policy gate DENY for step " + step.get("step"));
				throw new PlanExecutionError("Policy gate denied at step " + step.get("step") + ": " + policyName);
			}

		} catch (PlanExecutionError e) {
			throw e;
		} catch (Exception e) {
			throw new PlanExecutionError("Error evaluating policy gate at step " + step.get("step") + ": " + e.getMessage());
		}
	}

	protected void handleDynamicStepInsertion(BaseRecord plan, BaseRecord step, List<BaseRecord> steps, int index, int maxSteps) {
		int totalExecuted = (int) plan.get("totalExecutedSteps");
		if (totalExecuted >= maxSteps) {
			logger.info("Already at maxSteps, skipping dynamic insertion");
			return;
		}

		try {
			String routePromptStr = ResourceUtil.getInstance().getResource("chainRoutePrompt.txt");
			if (routePromptStr == null || routePromptStr.isEmpty()) {
				logger.warn("Chain route prompt template not found, skipping dynamic insertion");
				return;
			}

			// Build routing context
			String planQuery = plan.get("planQuery");
			String stepOutput = "";
			BaseRecord output = step.get("output");
			if (output != null && output.get(FieldNames.FIELD_VALUE) != null) {
				stepOutput = output.get(FieldNames.FIELD_VALUE).toString();
			}

			String accumulatedContext = buildAccumulatedContextSummary();

			routePromptStr = routePromptStr
				.replace("${planQuery}", planQuery != null ? planQuery : "")
				.replace("${stepOutput}", stepOutput.length() > 2000 ? stepOutput.substring(0, 2000) : stepOutput)
				.replace("${accumulatedContext}", accumulatedContext.length() > 4000 ? accumulatedContext.substring(0, 4000) : accumulatedContext)
				.replace("${remainingBudget}", String.valueOf(maxSteps - totalExecuted));

			// Call routing LLM
			BaseRecord routePrompt = toolManager.getCreatePromptConfig("Chain Route Prompt");
			List<String> sysPrompt = routePrompt.get("system");
			sysPrompt.clear();
			sysPrompt.add(routePromptStr);
			IOSystem.getActiveContext().getAccessPoint().update(toolUser, routePrompt);

			Chat chat = new Chat(toolUser, toolManager.getChatConfig(), routePrompt);
			chat.setEnableKeyFrame(false);

			String chatName = "Chain Route " + UUID.randomUUID().toString();
			BaseRecord creq = ChatUtil.getCreateChatRequest(toolUser, chatName, toolManager.getChatConfig(), routePrompt);
			if (creq != null) {
				creq = ChatUtil.getChatRequest(toolUser, chatName, toolManager.getChatConfig(), routePrompt);
			}
			OpenAIRequest req = ChatUtil.getOpenAIRequest(toolUser, new ChatRequest(creq));

			chat.continueChat(req, "What should the next steps be?");
			List<OpenAIMessage> msgs = req.getMessages();

			if (msgs.size() <= 2) {
				logger.warn("No routing response, skipping dynamic insertion");
				return;
			}

			String routeResponse = msgs.get(msgs.size() - 1).getContent();
			parseAndInsertDynamicSteps(plan, step, steps, index, maxSteps, routeResponse);

		} catch (Exception e) {
			logger.warn("Dynamic step insertion failed (non-fatal): " + e.getMessage());
		}
	}

	private void parseAndInsertDynamicSteps(BaseRecord plan, BaseRecord step, List<BaseRecord> steps, int index, int maxSteps, String routeResponse) {
		try {
			// Extract JSON from response
			int jsonStart = routeResponse.indexOf("{");
			int jsonEnd = routeResponse.lastIndexOf("}");
			if (jsonStart < 0 || jsonEnd < 0 || jsonEnd <= jsonStart) {
				logger.warn("No valid JSON in routing response");
				return;
			}

			String jsonStr = routeResponse.substring(jsonStart, jsonEnd + 1);
			org.json.JSONObject routeJson = new org.json.JSONObject(jsonStr);

			boolean complete = routeJson.optBoolean("complete", true);
			if (complete) {
				logger.info("Routing indicates chain is complete");
				return;
			}

			org.json.JSONArray nextSteps = routeJson.optJSONArray("nextSteps");
			if (nextSteps == null || nextSteps.length() == 0) {
				return;
			}

			int totalExecuted = (int) plan.get("totalExecutedSteps");
			int budget = Math.min(maxSteps - totalExecuted, MAX_DYNAMIC_PER_ROUTING);
			int toInsert = Math.min(nextSteps.length(), budget);

			if (toInsert <= 0) {
				return;
			}

			int baseStep = (int) step.get("step");
			List<BaseRecord> newSteps = new ArrayList<>();

			for (int j = 0; j < toInsert; j++) {
				org.json.JSONObject ns = nextSteps.getJSONObject(j);

				BaseRecord newStep = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
				newStep.setValue("step", baseStep + j + 1);
				newStep.setValue("dynamic", true);
				newStep.setValue("parentStep", baseStep);

				String nsType = ns.optString("stepType", "TOOL");
				newStep.setValue("stepType", StepTypeEnumType.valueOf(nsType));
				newStep.setValue("stepStatus", StepStatusEnumType.PENDING);

				if (ns.has("toolName")) {
					newStep.setValue("toolName", ns.getString("toolName"));
				}
				if (ns.has("promptConfigName")) {
					newStep.setValue("promptConfigName", ns.getString("promptConfigName"));
				}
				if (ns.has("chatConfigName")) {
					newStep.setValue("chatConfigName", ns.getString("chatConfigName"));
				}
				if (ns.has("ragQuery")) {
					newStep.setValue("ragQuery", ns.getString("ragQuery"));
				}
				if (ns.has("policyName")) {
					newStep.setValue("policyName", ns.getString("policyName"));
				}
				if (ns.has("description")) {
					newStep.setValue("description", ns.getString("description"));
				}

				newSteps.add(newStep);
			}

			// Insert after current index
			int insertPos = index + 1;
			steps.addAll(insertPos, newSteps);

			// Renumber steps after insertion
			for (int k = insertPos + newSteps.size(); k < steps.size(); k++) {
				int currentStepNum = (int) steps.get(k).get("step");
				steps.get(k).setValue("step", currentStepNum + newSteps.size());
			}

			logger.info("Inserted " + newSteps.size() + " dynamic steps after step " + baseStep);
			emitEvent(plan, steps, index, "stepsInserted",
				"Inserted " + newSteps.size() + " dynamic steps", null);

		} catch (Exception e) {
			logger.warn("Failed to parse routing response for dynamic insertion: " + e.getMessage());
		}
	}

	private String composeLLMMessage(BaseRecord step) {
		StringBuilder sb = new StringBuilder();

		// Add step inputs
		List<BaseRecord> inputs = step.get("inputs");
		if (inputs != null) {
			for (BaseRecord input : inputs) {
				Object val = input.get(FieldNames.FIELD_VALUE);
				if (val != null) {
					String resolved = resolveContextVariables(val.toString());
					sb.append(resolved);
					sb.append(System.lineSeparator());
				}
			}
		}

		// If no explicit inputs, use accumulated context
		if (sb.length() == 0 && !chainContext.isEmpty()) {
			McpContextBuilder ctxBuilder = new McpContextBuilder();
			for (Map.Entry<String, Object> entry : chainContext.entrySet()) {
				ctxBuilder.addResource(
					"am7://chain/context/" + entry.getKey(),
					"urn:am7:chain:step-output",
					Map.of("key", entry.getKey(), "value", entry.getValue() != null ? entry.getValue().toString() : ""),
					true
				);
			}
			sb.append(ctxBuilder.build());
		}

		return sb.toString();
	}

	private String resolveContextVariables(String text) {
		if (text == null) return "";
		Matcher matcher = CONTEXT_VARIABLE_PATTERN.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			String key = matcher.group(1);
			Object val = chainContext.get(key);
			if (val != null) {
				matcher.appendReplacement(sb, Matcher.quoteReplacement(val.toString()));
			} else {
				logger.warn("Context variable not found: " + key);
				matcher.appendReplacement(sb, Matcher.quoteReplacement("{{" + key + "}}"));
			}
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	private Object[] prepareArguments(BaseRecord step, Method method) {
		List<Object> preparedArgs = new ArrayList<>();
		List<BaseRecord> inputs = step.get("inputs");
		List<Parameter> params = Arrays.asList(method.getParameters());

		for (BaseRecord param : inputs) {
			Object value = null;
			if (param.hasField("value")) {
				value = param.get("value");
			} else {
				logger.warn("Parameter " + param.get("name") + " does not have a value field");
			}

			Parameter methodParam = params.stream()
				.filter(p -> p.getName().equals(param.get("name")))
				.findFirst()
				.orElse(null);

			if (methodParam == null) {
				if (method.getParameterCount() == inputs.size()) {
					methodParam = params.get(0);
				}
				if (methodParam == null) {
					throw new IllegalArgumentException("Parameter " + param.get("name") + " not found in method " + method.getName());
				}
			}

			if (value instanceof String) {
				Matcher matcher = CONTEXT_VARIABLE_PATTERN.matcher((String) value);
				if (matcher.matches()) {
					String contextKey = matcher.group(1);
					preparedArgs.add(chainContext.get(contextKey));
				} else {
					preparedArgs.add(value);
				}
			} else {
				preparedArgs.add(value);
			}
		}

		return preparedArgs.toArray(new Object[0]);
	}

	private String buildAccumulatedContextSummary() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> entry : chainContext.entrySet()) {
			sb.append(entry.getKey()).append(": ");
			String val = entry.getValue() != null ? entry.getValue().toString() : "null";
			sb.append(val.length() > 500 ? val.substring(0, 500) + "..." : val);
			sb.append(System.lineSeparator());
		}
		return sb.toString();
	}

	public void saveContext(BaseRecord plan) {
		if (chainContext.isEmpty()) {
			return;
		}
		McpContextBuilder ctxBuilder = new McpContextBuilder();
		String planId = plan.get(FieldNames.FIELD_NAME);
		if (planId == null) planId = "unknown";

		for (Map.Entry<String, Object> entry : chainContext.entrySet()) {
			ctxBuilder.addResource(
				"am7://chain/" + planId + "/context/" + entry.getKey(),
				"urn:am7:chain:step-output",
				Map.of("value", entry.getValue() != null ? entry.getValue().toString() : ""),
				true
			);
		}
		plan.setValue("chainContextJson", ctxBuilder.build());
	}

	public void restoreContext(BaseRecord plan) {
		String ctxJson = plan.get("chainContextJson");
		if (ctxJson == null || ctxJson.isEmpty()) {
			return;
		}
		// Parse MCP context blocks and restore to chainContext map
		List<org.cote.accountmanager.mcp.McpContext> contexts = McpContextParser.parse(ctxJson);
		for (org.cote.accountmanager.mcp.McpContext ctx : contexts) {
			String uri = ctx.getUri();
			if (uri != null && uri.contains("/context/")) {
				String key = uri.substring(uri.lastIndexOf("/context/") + "/context/".length());
				String body = ctx.getBody();
				if (body != null) {
					try {
						org.json.JSONObject json = new org.json.JSONObject(body);
						if (json.has("data") && json.getJSONObject("data").has("value")) {
							chainContext.put(key, json.getJSONObject("data").get("value"));
						}
					} catch (Exception e) {
						chainContext.put(key, body);
					}
				}
			}
		}
	}

	private void emitEvent(BaseRecord plan, List<BaseRecord> steps, int index, String eventType, String summary, String errorMessage) {
		if (listener == null) {
			return;
		}
		try {
			BaseRecord event = RecordFactory.newInstance(ModelNames.MODEL_CHAIN_EVENT);
			event.set("eventType", eventType);
			event.set("planName", plan.get(FieldNames.FIELD_NAME));
			event.set("stepNumber", index < steps.size() ? (int) steps.get(index).get("step") : 0);
			event.set("totalSteps", steps.size());
			if (index < steps.size()) {
				BaseRecord s = steps.get(index);
				StepTypeEnumType st = s.getEnum("stepType");
				event.set("stepType", st != null ? st.name() : "TOOL");
				StepStatusEnumType ss = s.getEnum("stepStatus");
				event.set("stepStatus", ss != null ? ss.name() : "UNKNOWN");
				event.set("toolName", s.get("toolName"));
			}
			event.set("stepSummary", summary);
			if (errorMessage != null) {
				event.set("errorMessage", errorMessage);
			}
			event.set("timestamp", System.currentTimeMillis());

			String planName = plan.get(FieldNames.FIELD_NAME);
			if (planName != null) {
				event.set("mcpContextUri", "am7://default/tool.plan/" + planName);
			}

			listener.onChainEvent(toolUser, event);
		} catch (Exception e) {
			logger.warn("Failed to emit chain event: " + e.getMessage());
		}
	}
}
