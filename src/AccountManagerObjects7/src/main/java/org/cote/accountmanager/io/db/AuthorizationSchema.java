package org.cote.accountmanager.io.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.util.ResourceUtil;

public class AuthorizationSchema {
	public static final Logger logger = LogManager.getLogger(AuthorizationSchema.class);
	
	private static String getPathSchemaTemplate(ConnectionEnumType ct) {
		return ResourceUtil.getInstance().getResource("sql/" + ct.toString().toLowerCase() + "/pathSchemaTemplate.sql");
	}
	
	private static String getEffectiveRoleTemplate(ConnectionEnumType ct) {
		return ResourceUtil.getInstance().getResource("sql/" + ct.toString().toLowerCase() + "/effectiveRoleTemplate.sql");
	}
	
	private static String getEffectiveActorRoleTemplate(ConnectionEnumType ct) {
		return ResourceUtil.getInstance().getResource("sql/" + ct.toString().toLowerCase() + "/effectiveActorRoleTemplate.sql");
	}

	private static String getEffectiveGroupObjectEntitlementTemplate(ConnectionEnumType ct) {
		return ResourceUtil.getInstance().getResource("sql/" + ct.toString().toLowerCase() + "/effectiveGroupObjectEntitlementTemplate.sql");
	}
	
	private static Pattern modelNamePat = Pattern.compile("\\$\\{modelName\\}");
	private static Pattern modelTableJoinPat = Pattern.compile("\\$\\{modelTableJoin\\}");
	private static Pattern functionNamePat = Pattern.compile("\\$\\{name\\}");
	private static Pattern tableNamePat = Pattern.compile("\\$\\{tableName\\}");
	
	public static void refreshMaterializedViews() {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Path function support in " + du.getConnectionType().toString() + " is not currently available.");
			return;
		}
		String schema = getRefreshMaterializedViewsSchema();
		if(schema != null) {
		    try (Connection con = du.getDataSource().getConnection(); Statement statement = con.createStatement();){
				statement.executeUpdate(schema);
		    }
		    catch(SQLException e) {
		    	logger.error(e);
		    }

		}
	}
	
	public static String getRefreshMaterializedViewsSchema() {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Effective role function support in " + du.getConnectionType().toString() + " is not currently available.");
			return null;
		}
		List<String> refresh = new ArrayList<>();
		List<String> refresh2 = new ArrayList<>();
		String[] actors = new String[] {ModelNames.MODEL_USER, ModelNames.MODEL_ACCOUNT, ModelNames.MODEL_PERSON};
		for(String actor : actors) {
			ModelSchema ms = RecordFactory.getSchema(actor);
			String modelName = ms.getName();
			String viewName = modelName.replaceAll("\\.", "");

			refresh.add("REFRESH MATERIALIZED VIEW effective" + viewName + "ActorRoles;");
			refresh2.add("REFRESH MATERIALIZED VIEW effectiveGroup" + viewName + "ObjectEntitlements;");
		}
		
		ModelNames.MODELS.forEach(m -> {
			ModelSchema ms = RecordFactory.getSchema(m);
			if(!ms.isAbs() && ms.isDedicatedParticipation() || ms.inherits(ModelNames.MODEL_PARTICIPATION)){
				String modelName = ms.getName();
				String viewName = modelName.replaceAll("\\.", "");
				refresh.add("REFRESH MATERIALIZED VIEW effective" + viewName + "Roles;");
			}
		});
		
		return refresh.stream().collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator()
		+ refresh2.stream().collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator();
	}

	public static String getEffectiveRoleSchemas() {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Effective role function support in " + du.getConnectionType().toString() + " is not currently available.");
			return null;
		}

		List<String> groupedEntitlements = new ArrayList<>();
		List<String> groupModels = new ArrayList<>();
		List<String> schemas = new ArrayList<>();
		List<String> schemas2 = new ArrayList<>();
		List<String> views = new ArrayList<>();
		List<String> actorViews = new ArrayList<>();

		String[] actors = new String[] {ModelNames.MODEL_USER, ModelNames.MODEL_ACCOUNT, ModelNames.MODEL_PERSON};
		for(String actor : actors) {
			ModelSchema ms = RecordFactory.getSchema(actor);
			String modelName = ms.getName();
			String tableName = du.getTableName(modelName);
			String viewName = modelName.replaceAll("\\.", "");
			
			String schema = tableNamePat.matcher(getEffectiveActorRoleTemplate(du.getConnectionType())).replaceAll(Matcher.quoteReplacement(tableName));
			schema = modelNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(modelName));
			schema = functionNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(viewName));
			schemas.add(schema);
			actorViews.add("SELECT id, model, effectiveroleid, baseroleid, organizationid FROM effective" + viewName + "ActorRoles");
			
			String schema2 = tableNamePat.matcher(getEffectiveGroupObjectEntitlementTemplate(du.getConnectionType())).replaceAll(Matcher.quoteReplacement(tableName));
			schema2 = modelNamePat.matcher(schema2).replaceAll(Matcher.quoteReplacement(modelName));
			schema2 = functionNamePat.matcher(schema2).replaceAll(Matcher.quoteReplacement(viewName));
			schemas2.add(schema2);
			
			groupedEntitlements.add("SELECT model, actorId, actorName, effectiveRoleId, effectiveRoleName, effectiveRoleType, baseRoleId, baseRoleName, baseRoleType, permissionId, permissionName, groupId, groupName, objectId, objectSchema, objectName FROM effectiveGroup" + viewName + "ObjectEntitlements");
			
		}
		
		ModelNames.MODELS.forEach(m -> {
			ModelSchema ms = RecordFactory.getSchema(m);
			
			if(!ms.isAbs() && ms.inherits(ModelNames.MODEL_DIRECTORY)) {
				
				String name = "'null' as name";
				if(ms.hasField(FieldNames.FIELD_NAME)) {
					name = "name";
				}
				groupModels.add("SELECT '" + ms.getName() + "' as model, id, " + name + ", groupId, organizationId FROM " + du.getTableName(ms.getName()));
			}
			if(!ms.isAbs() && ms.isDedicatedParticipation() || ms.inherits(ModelNames.MODEL_PARTICIPATION)){
				String modelName = ms.getName();
				String tableName = du.getTableName(ms, ModelNames.MODEL_PARTICIPATION);
				
				String viewName = modelName.replaceAll("\\.", "");
				// logger.info("Create effective role view for " + tableName + ", " + viewName);
				
				String schema = tableNamePat.matcher(getEffectiveRoleTemplate(du.getConnectionType())).replaceAll(Matcher.quoteReplacement(tableName));
				schema = functionNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(viewName));
				
				String mname = "NULL";
				String modelJoin = "";
				if(ms.hasField(FieldNames.FIELD_NAME)) {
					mname = "M.name";
					modelJoin = "INNER JOIN " + du.getTableName(modelName) + " M on M.id = GP.participationid";
							
				}
				schema = modelNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(mname));
				schema = modelTableJoinPat.matcher(schema).replaceAll(Matcher.quoteReplacement(modelJoin));
				// logger.info("Creating 'effective" + viewName + "Roles' view");
				schemas.add(schema);
			    views.add("SELECT id, name, model, effectiveroleid, baseroleid, permissionid, organizationid FROM effective" + viewName + "Roles");
			}
		});
		StringBuilder buff = new StringBuilder();
		if(views.size() > 0) {
			buff.append(schemas.stream().collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator());
			buff.append("DROP VIEW IF EXISTS effectiveRoles CASCADE;" + System.lineSeparator());
			buff.append("CREATE OR REPLACE VIEW effectiveRoles as " + System.lineSeparator());
			buff.append(
				views.stream().collect(Collectors.joining(System.lineSeparator() + "UNION ALL" + System.lineSeparator()))
				 + ";"
				+ System.lineSeparator()
			);
			buff.append(";" + System.lineSeparator());

			buff.append("DROP VIEW IF EXISTS effectiveActorRoles CASCADE;" + System.lineSeparator());
			buff.append("CREATE OR REPLACE VIEW effectiveActorRoles as " + System.lineSeparator());
			buff.append(
				actorViews.stream().collect(Collectors.joining(System.lineSeparator() + "UNION ALL" + System.lineSeparator()))
				 + ";"
				+ System.lineSeparator()
			);
			buff.append(";" + System.lineSeparator());

			buff.append("DROP VIEW IF EXISTS groupedObjects CASCADE;" + System.lineSeparator());
			buff.append("CREATE OR REPLACE VIEW groupedObjects as " + System.lineSeparator());
			buff.append(
				groupModels.stream().collect(Collectors.joining(System.lineSeparator() + "UNION ALL" + System.lineSeparator()))
				 + ";"
				+ System.lineSeparator()
			);
			buff.append(";" + System.lineSeparator());

			buff.append(schemas2.stream().collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator());
			
			buff.append("DROP VIEW IF EXISTS groupedEntitlements CASCADE;" + System.lineSeparator());
			buff.append("CREATE OR REPLACE VIEW groupedEntitlements as " + System.lineSeparator());
			buff.append(
				groupedEntitlements.stream().collect(Collectors.joining(System.lineSeparator() + "UNION ALL" + System.lineSeparator()))
				 + ";"
				+ System.lineSeparator()
			);
			buff.append(";" + System.lineSeparator());
		}
		return buff.toString();
	}
	
	public static void createEffectiveRoleViews() {
		createEffectiveRoleViews(getEffectiveRoleSchemas());
	}
	
	public static void createEffectiveRoleViews(String sql) {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Effective role function support in " + du.getConnectionType().toString() + " is not currently available.");
			return;
		}
		try (Connection con = du.getDataSource().getConnection(); Statement statement = con.createStatement();){
			statement.executeUpdate(sql);
	    }
	    catch(SQLException e) {
	    	logger.error(e);
	    }
		
	}
	
	public static String getPathFunctions(String modelName) {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Path function support in " + du.getConnectionType().toString() + " is not currently available.");
			return null;
		}
		
		if(modelName.equals(ModelNames.MODEL_ORGANIZATION)) {
			/// Skip organization
			return null;
		}
		String schema = null;
		ModelSchema ms = RecordFactory.getSchema(modelName);
		if(!ms.isAbs() && ms.inherits(ModelNames.MODEL_PARENT)){
			String tableName = du.getTableName(modelName);
			String functionName = modelName.substring(modelName.lastIndexOf(".") + 1);
			
			schema = tableNamePat.matcher(getPathSchemaTemplate(du.getConnectionType())).replaceAll(Matcher.quoteReplacement(tableName));
			schema = functionNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(functionName));

		}
		return schema;
	}
	
	
	public static void createPathFunctions(String schema) {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Path function support in " + du.getConnectionType().toString() + " is not currently available.");
			return;
		}
		
		if(schema != null) {
		    try (Connection con = du.getDataSource().getConnection(); Statement statement = con.createStatement();){
				statement.executeUpdate(schema);
		    }
		    catch(SQLException e) {
		    	logger.error(e);
		    }

		}
	}
	
}
