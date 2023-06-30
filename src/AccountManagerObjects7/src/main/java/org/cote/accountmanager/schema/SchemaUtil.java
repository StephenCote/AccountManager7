package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.schema.type.*;
import org.cote.accountmanager.util.CategoryUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class SchemaUtil {
	public static final Logger logger = LogManager.getLogger(SchemaUtil.class);
	
	private static String categoryBuffer = null;
	private static String enumBuffer = null;
	private static String modelBuffer = null;
	//private static Map<String, String> modelMap = new HashMap<>();
	private static Set<String> modelSet = ConcurrentHashMap.newKeySet();
	private static List<String> models = new ArrayList<>();
	
	private static List<Class<? extends Enum<?>>> enumList = Arrays.asList(FieldEnumType.class, SystemPermissionEnumType.class, AccountEnumType.class, AccountStatusEnumType.class, ActionEnumType.class, ApprovalEnumType.class, ApprovalResponseEnumType.class, ApproverEnumType.class, AuthenticationResponseEnumType.class, ComparatorEnumType.class, CompressionEnumType.class, ConditionEnumType.class, ConnectionEnumType.class, ContactEnumType.class, ContactInformationEnumType.class, ControlActionEnumType.class, ControlEnumType.class, CredentialEnumType.class, EffectEnumType.class, FactEnumType.class, FunctionEnumType.class, GroupEnumType.class, LevelEnumType.class, LocationEnumType.class, OperationEnumType.class, OperationResponseEnumType.class, OrderEnumType.class, OrganizationEnumType.class, PatternEnumType.class, PermissionEnumType.class, PolicyRequestEnumType.class, PolicyResponseEnumType.class, QueryEnumType.class, ResponseEnumType.class, RoleEnumType.class, RuleEnumType.class, SpoolBucketEnumType.class, SpoolNameEnumType.class, SpoolStatusEnumType.class, SqlDataEnumType.class, StatisticsEnumType.class, StreamEnumType.class, TagEnumType.class, UserEnumType.class, UserStatusEnumType.class, ValidationEnumType.class, ValueEnumType.class, VerificationEnumType.class);
	
	private static String getModel(String name) {
		return ResourceUtil.getModelResource(name);
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
	
	public static String getSchemaJSON() {
		return "{" + getCategoriesJSON() + ",\n" + getEnumSchemaJSON() + ",\n" + getModelSchemaJSON() + "\n}";
	}
}
