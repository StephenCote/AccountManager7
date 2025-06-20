package org.cote.accountmanager.agent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
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

    public AgentToolManager(BaseRecord user, BaseRecord chatConfig, Object toolInstance) {
        this.toolInstance = toolInstance;
        this.toolUser = user;
        this.chatConfig = chatConfig;
        discoverTools();
    }

    private void discoverTools() {
        Set<Method> methodSet = new HashSet<>();
        
        // Start the search from the runtime class of the toolInstance, as you correctly pointed out.
        Class<?> currentClass = this.toolInstance.getClass();

        // Walk up the inheritance tree
        while (currentClass != null && !currentClass.equals(Object.class)) {
            findAnnotatedMethods(currentClass, methodSet);
            currentClass = currentClass.getSuperclass();
        }
        
        this.discoveredTools.addAll(methodSet);
        for(Method m : discoveredTools){
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



    public String getToolsForPrompt() {
        JSONArray toolsArray = new JSONArray();
        for (Method method : this.discoveredTools) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            JSONObject toolJson = new JSONObject();
            toolJson.put("toolName", method.getName());
            toolJson.put("description", annotation.description());
            toolJson.put("parameters_example", annotation.parameters());
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
			pcfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, toolUser, null, plist);
			opcfg = IOSystem.getActiveContext().getAccessPoint().create(toolUser, pcfg);
		}
		catch(NullPointerException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return opcfg;
	}
    
    public BaseRecord getPromptConfig() {
        BaseRecord prompt = getCreatePromptConfig(toolInstance.getClass().getName());
        List<String> sysPrompt = prompt.get("system");
        // if(sysPrompt.size() == 0) {
            String toolDescriptions = getToolsForPrompt();
            String promptStr = "You are a helpful assistant that creates an execution plan to answer a user's question.\n"
                                + "Respond with a JSON plan that uses the following tools:\n"
                                + toolDescriptions;
            sysPrompt.add(promptStr);
            IOSystem.getActiveContext().getAccessPoint().update(toolUser, prompt);
        //}

        return prompt;
    }

}