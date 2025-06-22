package org.cote.accountmanager.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class AM7AgentTool {
	private BaseRecord toolUser = null;
	public AM7AgentTool(BaseRecord user) {
		this.toolUser = user;
	}
	
	
	 private List<BaseRecord> findObjects(String modelName, List<BaseRecord> queryFields){
	      Query query = QueryUtil.createQuery(modelName, FieldNames.FIELD_ORGANIZATION_ID, toolUser.get(FieldNames.FIELD_ORGANIZATION_ID));
	      if(queryFields != null){
	          for(BaseRecord field : queryFields){
                  query.field(
                      field.get("name"),
                      field.getEnum("comparator"),
                      field.get("value")
                  );
	          }
	      }
	      query.planMost(true);
	      return Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(query));
	    }


	    @AgentTool(
	    		description = "Find auth.role models based on criteria.",
   			inputs = "{name: \"queryFields\", valueType = \"list\", value: [{name: \"${name}\", comparator: \"${comparator}\", value: ${value}}]}",
	    		example = "{name: \"queryFields\", valueType = \"list\", value: [{name: \"name\", comparator: \"LIKE\", value: \"Administrator\"}]}",
	    		output = "{name: \"return\", valueType: \"list\", value: [${baseRecord}, ...]}"
	    	)
	    public List<BaseRecord> findRoles(List<BaseRecord> queryFields) {
	        return findObjects(ModelNames.MODEL_ROLE, queryFields);
	    }

	    @AgentTool(
	    		description = "Finds all members (user, person, account) for a given list of roles.",
	    		inputs = "{name: \"memberModel\", valueType: \"string\", value: \"${modelName}\"}, {name: \"roles\", valueType: \"list\", value: [${auth.role}, ...]}",
    	    		example = "[{name: \"memberModel\", valueType = \"string\", value: \"olio.charPerson\"}, [{name: \"Account Administrators\", id: 13]}]",
	    		output = "{name: \"return\", valueType: \"list\", value: [${auth.role}, ...]}"
        	)
	    public List<BaseRecord> findMembersOfRoles(String memberModel, List<BaseRecord> roles) {
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
	    		description = "Find identity.person models based on attributes.",
    	    		inputs = "{name: \"queryFields\", valueType = \"list\", value: [{name: \"${name}\", comparator: \"${comparator}\", value: ${value}}]}",
	    	    	example = "{name: \"queryFields\", valueType: \"list\", value: [{name: \"hairColor\", comparator: \"EQUALS\", value: \"red\"}]",
    	    		output = "{name: \"return\", valueType: \"list\", value: [${baseRecord}, ...]}"
	    	)

	    public List<BaseRecord> findPersons(List<BaseRecord> queryFields) {
	        // As noted before, assuming 'hairColor' exists on the person model for this example
	        return findObjects(ModelNames.MODEL_PERSON, queryFields);
	    }
	    
	    @AgentTool(description = "Finds the intersection of two lists of records.", inputs = "list1: [], list2: []")
	    public List<BaseRecord> intersect(List<BaseRecord> list1, List<BaseRecord> list2) {
	    		return new ArrayList<>();
	    }
	    
	    @AgentTool(description = "Returns a list of available models including any description.", inputs = "")
        public String describeAllModels() {
	    		return SchemaUtil.getModelDescriptions(false);
        }

	    @AgentTool(description = "Returns a summary of the specified model schema, including inheritence and fields", inputs = "[\"input\": [{\"name\": \"modelName\", \"valueType\": \"${type}\", \"value\": \"${value}\"}]]", example = "[{name=\"modelName\", valueType: \"string\", value: \"identity.person\"}]")
        public String describeModel(String modelName) {
	    		return SchemaUtil.getModelDescription(modelName);
        }


}
