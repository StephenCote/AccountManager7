package org.cote.accountmanager.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.util.MemoryUtil;

public class AM7AgentTool {
	public static final Logger logger = LogManager.getLogger(AM7AgentTool.class);
	private BaseRecord toolUser = null;
	
	public AM7AgentTool(BaseRecord user) {
		this.toolUser = user;
	}
	
	
    @AgentTool(
		description = "Create a new query field object.",
		returnType = FieldEnumType.MODEL,
		returnModel = ModelNames.MODEL_QUERY_FIELD,
		returnName = "queryField",
		example = "BaseRecord queryField = newQueryField(\"name\", ComparatorEnumType.LIKE, \"Administrator\");"
	)
    public BaseRecord newQueryField(
    		@AgentToolParameter(name = "fieldName", type = FieldEnumType.STRING)
    		String fieldName,
    		@AgentToolParameter(name = "comparator", type = FieldEnumType.ENUM, enumClass = ComparatorEnumType.class)
    		ComparatorEnumType comparator,
    		@AgentToolParameter(name = "value", type = FieldEnumType.STRING)
    		String value
    ) {
        QueryField field = new QueryField();
		field.setValue(FieldNames.FIELD_NAME, fieldName);
		field.setValue(FieldNames.FIELD_VALUE, value);
        field.setValue(FieldNames.FIELD_COMPARATOR, comparator);

        return field;
    }



    @AgentTool(
		description = "Find auth.role models based on criteria.",
		example = "{name: \"queryFields\", valueType = \"list\", value: [{name: \"name\", comparator: \"LIKE\", value: \"Administrator\"}]}",
		returnType = FieldEnumType.LIST,
		returnModel = ModelNames.MODEL_MODEL,
		returnName = "roles"
	)
    public List<BaseRecord> findRoles(
    		@AgentToolParameter(name = "queryFields", type = FieldEnumType.LIST, model = ModelNames.MODEL_QUERY_FIELD)
    		List<BaseRecord> queryFields
    ) {
    	String[] flds = new String[] {
    		FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_ORGANIZATION_ID
        };
        return AgentUtil.findObjects(toolUser, ModelNames.MODEL_ROLE, queryFields, flds);
    }

    @AgentTool(
		description = "Finds all members (user, person, account) for a given list of roles.",
    	example = "inputs: [{name: \"memberModel\", valueType = \"string\", value: \"olio.charPerson\"}, [{name: \"roles\", valueType: \"list\", valueModel: \"auth.role\", value: [{\"Account Administrators\", id: 13}]}]",
		returnType = FieldEnumType.LIST,
		returnModel = ModelNames.MODEL_MODEL,
		returnName = "members"
	)
    public List<BaseRecord> findMembersOfRoles(
    		@AgentToolParameter(name = "memberModel", type = FieldEnumType.STRING)
    		String memberModel,
    		@AgentToolParameter(name = "roles", type = FieldEnumType.LIST, model = ModelNames.MODEL_ROLE)
    		List<BaseRecord> roles
    	) {
        List<BaseRecord> members = new ArrayList<>();
        if(roles.size() == 0) {
            return members;
        }
        
        String roleNames = roles.stream().map(role -> (String)role.get(FieldNames.FIELD_NAME)).collect(Collectors.joining(","));
        long parentId = roles.get(0).get(FieldNames.FIELD_PARENT_ID);

    		Query q = QueryUtil.createQuery(memberModel);
    		q.filterParticipation(roles.get(0), null, memberModel, null);
    		q.setRequestRange(0, 20);
    		q.field(FieldNames.FIELD_NAME,  ComparatorEnumType.IN, roleNames);
    		q.field(FieldNames.FIELD_PARENT_ID, parentId);

        return Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q));

    }

    @AgentTool(
		description = "Find olio.charPerson models using a list of io.queryField objects named 'queryFields'.",
	    example = "inputs:[{name: \"queryFields\", valueModel: \"io.queryField\", valueType: \"list\", value: [{name: \"hairColor\", comparator: \"EQUALS\", value: \"red\"}]}]",
		returnType = FieldEnumType.LIST,
		returnModel = OlioModelNames.MODEL_CHAR_PERSON,
		returnName = "persons"
	)
    public List<BaseRecord> findPersons(
    	@AgentToolParameter(name = "queryFields", type = FieldEnumType.LIST, model = ModelNames.MODEL_QUERY_FIELD)
    	List<BaseRecord> queryFields
    ) {
        // As noted before, assuming 'hairColor' exists on the person model for this example

    	logger.info("Finding persons..");
    	if(queryFields.isEmpty()) {
    		logger.error("No query fields were defined");

    		return new ArrayList<>();
    	}

    	List<BaseRecord> sanQueryFields = AgentUtil.sanitizeQueryFields(toolUser, OlioModelNames.MODEL_CHAR_PERSON, queryFields);
    	if(sanQueryFields.isEmpty()) {
    		logger.error("No query fields were sanitized");
    		return new ArrayList<>();
    	}
    	String[] flds = new String[] {
    		FieldNames.FIELD_ID, FieldNames.FIELD_NAME, "age", "gender", FieldNames.FIELD_ORGANIZATION_ID
    		//,"hairColor", "hairStyle", "eyeColor"
    	};
        List<BaseRecord> res = AgentUtil.findObjects(toolUser, OlioModelNames.MODEL_CHAR_PERSON, sanQueryFields, flds);
        logger.info("Found " + res.size() + " matches");
        return res;
    }
    


    /*
    @AgentTool(
		description = "Refine the upcoming plan step with a response from the current or a previous step",
		returnType = FieldEnumType.MODEL,
		returnModel = ModelNames.MODEL_PLAN_STEP
	)
    public BaseRecord refinePlanStep(
    	@AgentToolParameter(name = "stepIndex", type = FieldEnumType.INT)
    	int stepIndex,
    	@AgentToolParameter(name = "previousStepToolName", type = FieldEnumType.STRING)
    	String previousStepToolName,
    	@AgentToolParameter(name = "reviseStepToolName", type = FieldEnumType.STRING)
    	String reviseStepToolName

    ) {
    	logger.info("Refine plan step " + stepIndex + " with tool " + previousStepToolName + " and revise with " + reviseStepToolName);
    	return null;
    }
	*/
    
    @AgentTool(description = "Returns a list of available models including any description.", returnName = "modelList", returnType = FieldEnumType.STRING)
    public String describeAllModels() {
    	return SchemaUtil.getModelDescriptions(false);
    }

    @AgentTool(description = "Returns a summary of the specified model schema, including inheritence and fields", returnName = "modelDescription", returnType = FieldEnumType.STRING)
    public String describeModel(
    	@AgentToolParameter(name = "modelName", type = FieldEnumType.STRING) String modelName
    ) {
    	return SchemaUtil.getModelDescription(modelName);
    }

    @AgentTool(
		description = "Search memories for relevant context based on a semantic query. Returns formatted memory context.",
		returnName = "memories",
		returnType = FieldEnumType.STRING
	)
    public String searchMemories(
    	@AgentToolParameter(name = "query", type = FieldEnumType.STRING) String query,
    	@AgentToolParameter(name = "limit", type = FieldEnumType.INT) int limit
    ) {
    	if (limit <= 0) limit = 10;
    	List<BaseRecord> results = MemoryUtil.searchMemories(toolUser, query, limit, 0.5);
    	if (results.isEmpty()) {
    		return "No relevant memories found.";
    	}
    	return MemoryUtil.formatMemoriesAsContext(results);
    }

    /// Phase 14e: Search memories by person pair IDs. Returns formatted MCP context
    /// of all memories associated with the specific character pair.
    @AgentTool(
		description = "Search memories by character person pair IDs. Returns all memories for the specific character pair, ordered by importance.",
		returnName = "pairMemories",
		returnType = FieldEnumType.STRING
	)
    public String searchMemoriesByPair(
    	@AgentToolParameter(name = "personId1", type = FieldEnumType.LONG) long personId1,
    	@AgentToolParameter(name = "personId2", type = FieldEnumType.LONG) long personId2,
    	@AgentToolParameter(name = "limit", type = FieldEnumType.INT) int limit
    ) {
    	if (limit <= 0) limit = 20;
    	List<BaseRecord> results = MemoryUtil.searchMemoriesByPersonPair(toolUser, personId1, personId2, limit);
    	if (results.isEmpty()) {
    		return "No memories found for person pair " + personId1 + "/" + personId2;
    	}
    	return MemoryUtil.formatMemoriesAsContext(results);
    }

    /// Phase 14e: Extract and store memories from LLM-generated text.
    /// Parses a JSON array of memory objects and persists them with person pair tagging.
    @AgentTool(
		description = "Extract and store memories from a JSON array of memory objects. Each object should have: content, summary, memoryType (FACT/RELATIONSHIP/EMOTION/DECISION/DISCOVERY), importance (1-10).",
		returnName = "extractedCount",
		returnType = FieldEnumType.INT
	)
    public int extractMemories(
    	@AgentToolParameter(name = "text", type = FieldEnumType.STRING) String text,
    	@AgentToolParameter(name = "conversationId", type = FieldEnumType.STRING) String conversationId,
    	@AgentToolParameter(name = "personId1", type = FieldEnumType.LONG) long personId1,
    	@AgentToolParameter(name = "personId2", type = FieldEnumType.LONG) long personId2
    ) {
    	String sourceUri = "am7://agent/memory-extraction/" + (conversationId != null ? conversationId : "unknown");
    	List<BaseRecord> extracted = MemoryUtil.extractMemoriesFromResponse(
    		toolUser, text, sourceUri, conversationId, personId1, personId2
    	);
    	return extracted.size();
    }

    /// Phase 14e: Search memories for a single person across all their conversations.
    @AgentTool(
		description = "Search all memories involving a specific person (as either character in a pair). Returns memories ordered by importance.",
		returnName = "personMemories",
		returnType = FieldEnumType.STRING
	)
    public String searchMemoriesByPerson(
    	@AgentToolParameter(name = "personId", type = FieldEnumType.LONG) long personId,
    	@AgentToolParameter(name = "limit", type = FieldEnumType.INT) int limit
    ) {
    	if (limit <= 0) limit = 20;
    	List<BaseRecord> results = MemoryUtil.searchMemoriesByPerson(toolUser, personId, limit);
    	if (results.isEmpty()) {
    		return "No memories found for person " + personId;
    	}
    	return MemoryUtil.formatMemoriesAsContext(results);
    }

}
