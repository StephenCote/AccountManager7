package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.AssessedEnumType;
import org.cote.accountmanager.olio.AssessmentEnumType;
import org.cote.accountmanager.olio.CharacterRoleEnumType;
import org.cote.accountmanager.olio.DensityEnumType;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.EsteemNeedsEnumType;
import org.cote.accountmanager.olio.EthnicityEnumType;
import org.cote.accountmanager.olio.HighEnumType;
import org.cote.accountmanager.olio.InstinctEnumType;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.LoveNeedsEnumType;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.PointOfInterestEnumType;
import org.cote.accountmanager.olio.RaceEnumType;
import org.cote.accountmanager.olio.ReasonEnumType;
import org.cote.accountmanager.olio.RollEnumType;
import org.cote.accountmanager.olio.SafetyNeedsEnumType;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.VeryEnumType;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.type.AccountEnumType;
import org.cote.accountmanager.schema.type.AccountStatusEnumType;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ApprovalEnumType;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.cote.accountmanager.schema.type.ApproverEnumType;
import org.cote.accountmanager.schema.type.AuthenticationResponseEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.CompressionEnumType;
import org.cote.accountmanager.schema.type.ConditionEnumType;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.schema.type.ContactEnumType;
import org.cote.accountmanager.schema.type.ContactInformationEnumType;
import org.cote.accountmanager.schema.type.ControlActionEnumType;
import org.cote.accountmanager.schema.type.ControlEnumType;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.EffectEnumType;
import org.cote.accountmanager.schema.type.FactEnumType;
import org.cote.accountmanager.schema.type.FunctionEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.LevelEnumType;
import org.cote.accountmanager.schema.type.LocationEnumType;
import org.cote.accountmanager.schema.type.OperationEnumType;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.PatternEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyRequestEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.QueryEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.schema.type.RuleEnumType;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.SpoolStatusEnumType;
import org.cote.accountmanager.schema.type.SqlDataEnumType;
import org.cote.accountmanager.schema.type.StatisticsEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.schema.type.SystemPermissionEnumType;
import org.cote.accountmanager.schema.type.TagEnumType;
import org.cote.accountmanager.schema.type.UserEnumType;
import org.cote.accountmanager.schema.type.UserStatusEnumType;
import org.cote.accountmanager.schema.type.ValidationEnumType;
import org.cote.accountmanager.schema.type.ValueEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.util.CategoryUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.ValidationUtil;

public class SchemaUtil {
	public static final Logger logger = LogManager.getLogger(SchemaUtil.class);
	
	private static String categoryBuffer = null;
	private static String enumBuffer = null;
	private static String modelBuffer = null;
	private static String validationRuleBuffer = null;
	//private static Map<String, String> modelMap = new HashMap<>();
	private static Set<String> modelSet = ConcurrentHashMap.newKeySet();
	private static List<String> models = new ArrayList<>();
	
	private static List<Class<? extends Enum<?>>> enumList = Arrays.asList(
		FieldEnumType.class, SystemPermissionEnumType.class, AccountEnumType.class, AccountStatusEnumType.class, ActionEnumType.class, ApprovalEnumType.class, ApprovalResponseEnumType.class, ApproverEnumType.class, AuthenticationResponseEnumType.class, ComparatorEnumType.class, CompressionEnumType.class, ConditionEnumType.class, ConnectionEnumType.class, ContactEnumType.class, ContactInformationEnumType.class, ControlActionEnumType.class, ControlEnumType.class, CredentialEnumType.class, EffectEnumType.class, FactEnumType.class, FunctionEnumType.class, GroupEnumType.class, LevelEnumType.class, LocationEnumType.class, OperationEnumType.class, OperationResponseEnumType.class, OrderEnumType.class, OrganizationEnumType.class, PatternEnumType.class, PermissionEnumType.class, PolicyRequestEnumType.class, PolicyResponseEnumType.class, QueryEnumType.class, ResponseEnumType.class, RoleEnumType.class, RuleEnumType.class, SpoolBucketEnumType.class, SpoolNameEnumType.class, SpoolStatusEnumType.class, SqlDataEnumType.class, StatisticsEnumType.class, StreamEnumType.class, TagEnumType.class, UserEnumType.class, UserStatusEnumType.class, ValidationEnumType.class, ValueEnumType.class, VerificationEnumType.class,
		AlignmentEnumType.class, AssessedEnumType.class, AssessmentEnumType.class, CharacterRoleEnumType.class, DensityEnumType.class, DirectionEnumType.class, EsteemNeedsEnumType.class, EthnicityEnumType.class, HighEnumType.class, InstinctEnumType.class, InteractionEnumType.class, LoveNeedsEnumType.class, OutcomeEnumType.class, PointOfInterestEnumType.class, RaceEnumType.class, ReasonEnumType.class, RollEnumType.class, SafetyNeedsEnumType.class, ThreatEnumType.class, VeryEnumType.class, WearLevelEnumType.class,
		ESRBEnumType.class, LLMServiceEnumType.class
		);
	
	private static String getModel(String name) {
		return ResourceUtil.getInstance().getModelResource(name);
	}
	
	private static void loadModel(String name) {
		if(modelSet.contains(name)) {
			return;
		}
		String modelStr = getModel(name);
		if(modelStr != null) {
			ModelSchema ms = RecordFactory.getSchema(name);
			for(String s: ms.getInherits()) {
				loadModel(s);
			}
			modelSet.add(name);
			models.add(modelStr);
		}
	}

	public static String getModelSchemaJSON() {
		if(modelBuffer == null) {
			for(String s: ModelNames.MODELS) {
				loadModel(s);
			}
			StringBuilder buff = new StringBuilder();
			buff.append("[");
			for(String s: models) {
				if(buff.length() > 1) {
					buff.append(",\n");
				}
				buff.append(s);
			}
			buff.append("]\n");
			modelBuffer = "\"models\": " + buff.toString();
		}
		return modelBuffer;
	}
	
	public static String getEnumSchemaJSON() {
		if(enumBuffer == null) {
			StringBuilder buff = new StringBuilder();
			for(Class<? extends Enum<?>> c : enumList) {
				if(buff.length() > 1) {
					buff.append(",\n");
					
				}
				int lidx = c.getName().lastIndexOf(".") + 1;
				String name = c.getName().substring(lidx, lidx + 1).toLowerCase() + c.getName().substring(lidx + 1);
				String enumList = Stream.of(c.getEnumConstants()).map(Enum::name).collect(Collectors.joining("\", \""));
				buff.append("\"" + name + "\": [\"" + enumList + "\"]");
			}
			enumBuffer = "\"enums\":{" + buff.toString() + "}";
		}
		return enumBuffer;

	}
	
	public static String getCategoriesJSON() {
		if(categoryBuffer == null) {
			StringBuilder buff = new StringBuilder();
			buff.append("[");
			for(String s: CategoryUtil.CATEGORY_NAMES) {
				if(buff.length() > 1) {
					buff.append(",\n");
				}
				buff.append(CategoryUtil.getResourceCategory(s));
			}
			buff.append("]\n");
			categoryBuffer = "\"categories\": " + buff.toString();
		}
		return categoryBuffer;
	}
	
	public static String getValidationRulesJSON() {
		if(validationRuleBuffer == null) {
			StringBuilder buff = new StringBuilder();
			buff.append("[");
			for(String s: ValidationUtil.SYSTEM_RULES) {
				if(buff.length() > 1) {
					buff.append(",\n");
				}
				buff.append(ResourceUtil.getInstance().getValidationRuleResource(s));
			}
			buff.append("]\n");
			validationRuleBuffer = "\"validationRules\": " + buff.toString();
		}
		return validationRuleBuffer;
	}
	
	public static String getSchemaJSON() {
		return "{\"jsonModelKey\" : \"" + RecordFactory.JSON_MODEL_KEY + "\", " + getCategoriesJSON() + ",\n" + getEnumSchemaJSON() + ",\n" + getModelSchemaJSON() + ",\n" + getValidationRulesJSON() + "\n}";
	}
	
	public static String getModelDescriptions(boolean incInherits) {
		StringBuilder buff = new StringBuilder();
		ModelNames.MODELS.sort((f1, f2) -> f1.compareTo(f2));
		for (String model : ModelNames.MODELS) {
			ModelSchema ms = RecordFactory.getSchema(model);
			if(ms.isAbs() || ms.isEphemeral()) continue;
			if(ms.getIoConstraints().size() > 0 && (ms.getIoConstraints().get(0).equals("file") || ms.getIoConstraints().get(0).equals("unknown"))) {
				continue;
			}
			buff.append(ms.getName());
			if(incInherits) {
				String inh = ms.getInherits().stream().collect(Collectors.joining(", "));
				buff.append((inh.length() > 0 ? " [inherits: " + inh + "]" : ""));
			}
			if(ms.getDescription() != null) {
				buff.append(" - " + ms.getDescription());
			}
			buff.append(System.lineSeparator());
		}

		return buff.toString();
	}
	
	public static String getModelDescription(String schemaName) {
		return getModelDescription(RecordFactory.getSchema(schemaName));
	}
	public static String getModelDescription(ModelSchema schema) {
		StringBuilder buff = new StringBuilder();
		String sep =  System.lineSeparator();
		buff.append((schema.isEphemeral() ? "Ephemeral " : "") + (schema.isAbs() ? "Abstract " : "") + "Model: " + schema.getName());
		if (schema.getDescription() != null && schema.getDescription().length() > 0) {
			buff.append(sep + "Description: " + schema.getDescription());
		}
		String inh = schema.getInherits().stream().collect(Collectors.joining(", "));
		// String inh = RecordUtil.inheritence(schema).stream().collect(Collectors.joining(", "));
		if (inh != null && inh.length() > 0) {
			buff.append(System.lineSeparator() + "Inherits: " + inh);
		}
		RecordUtil.sortFields(schema);
		if (schema.getFields() != null && schema.getFields().size() > 0) {
			buff.append(sep + "Fields:" + sep);
			for (FieldSchema fld : schema.getFields()) {
				buff.append("  " + getNameTypeStatement(fld) + sep);

			}
		}
		return buff.toString();
	}
	
	public static String getNameTypeStatement(FieldSchema fs) {

		String name = fs.getName();
		FieldEnumType ftype = fs.getFieldType();
		String type = ftype.toString().toLowerCase();
		String arr = "";
		String suppl = "";
		switch (ftype) {
			case BOOLEAN:
				type = "bool";
				break;

			case BLOB:
				type = "byte";
				arr = "[]";
				break;
			case LIST:
				arr = "[]";
				type = fs.getBaseType();
				if(type != null && type.equals(FieldEnumType.MODEL.toString().toLowerCase())) {
					type = fs.getBaseModel();
				}
				break;
			case BYTE:
				logger.warn("Unused");
				break;
			case TIMESTAMP:
			case ZONETIME:
			case CALENDAR:
				type = "datetime";
				break;
				
			case FLEX:
				type = "var";

				if(fs.getValueType() != null) {
					Class<? extends Enum<?>> fcls = FieldEnumType.class;
					String ftypes = Stream.of(fcls.getEnumConstants()).map(Enum::name).collect(Collectors.joining(","));
					suppl = System.lineSeparator() + "enum(" + ftypes.toLowerCase() + ") " + fs.getValueType();
				}
				break;
			case MODEL:
				type = fs.getBaseModel();
				break;
			case STRING:
				type = "str";
				break;
			case ENUM:
				Class<? extends Enum<?>> ecls = (Class<? extends Enum<?>>)RecordFactory.getClass(fs.getBaseClass());
				
				type = "enum(" + Stream.of(ecls.getEnumConstants()).map(Enum::name).collect(Collectors.joining(",")).toLowerCase() + ")";
				break;
			case INT:
			case LONG:
			case DOUBLE:
			
			case VECTOR:
				break;
			default:
				logger.error("Unhandled type: " + type.toString());
				break;
		}
		String prim = "";
		if (fs.isPrimaryKey()) {
			prim = "(primary key) ";
		}
		else if(fs.isIdentity()) {
			prim = "(identity) ";
		}
		return prim + type + " " + name + arr + suppl;


	}
	
}
