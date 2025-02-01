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

public class AuthorizationSchema {
	public static final Logger logger = LogManager.getLogger(AuthorizationSchema.class);
	
	private static String pathSchemaTemplate = """
DROP FUNCTION IF EXISTS ${name}s_from_leaf CASCADE;
CREATE OR REPLACE FUNCTION ${name}s_from_leaf(IN root_id bigint)
	RETURNS TABLE(leafid bigint, ${name}id bigint, parentid bigint, organizationid bigint)
	AS $$
	WITH RECURSIVE ${name}_tree(leafid,${name}id, parentid, organizationid) AS (
	   SELECT $1 as leafid, R.id as ${name}id, R.parentid, R.organizationid
	      FROM ${tableName} R WHERE R.id = $1
	   UNION ALL
	   SELECT $1 as leafid, P.id, P.parentid, P.organizationid
	      FROM ${name}_tree RT, ${tableName} P
	      WHERE RT.${name}id = P.parentid
	)
	select * from ${name}_tree;
	$$ LANGUAGE 'sql';
DROP FUNCTION IF EXISTS ${name}s_to_leaf CASCADE;
CREATE OR REPLACE FUNCTION ${name}s_to_leaf(IN root_id bigint)
	RETURNS TABLE(leafid bigint, ${name}id bigint, parentid bigint, organizationid bigint)
	AS $$
	WITH RECURSIVE ${name}_tree(leafid,${name}id, parentid, organizationid) AS (
	   SELECT $1 as leafid, R.id as ${name}id, R.parentid, R.organizationid
	      FROM ${tableName} R WHERE R.id = $1
	   UNION ALL
	   SELECT $1 as leafid, P.id, P.parentid, P.organizationid
	      FROM ${name}_tree RT, ${tableName} P
	      WHERE RT.parentid = P.id
	)
	select * from ${name}_tree;
	$$ LANGUAGE 'sql';""";
	
	private static String effectiveRoleTemplate = """
	DROP VIEW IF EXISTS effective${name}Roles CASCADE;
create or replace view effective${name}Roles as
WITH result AS(
select R.id,R.type, R.parentid,roles_to_leaf(R.id) ats,R.organizationid
FROM a7_auth_role_0_1 R  
)
select distinct GP.participationid as id, ${modelName} as name, GP.participationModel as model, (R.ats).leafid as effectiveRoleId,(R.ats).roleid as baseRoleId,GP.permissionId, R.organizationid from result R
JOIN ${tableName} GP ON GP.participantid = (R.ats).leafid and GP.participantmodel = 'auth.role'
${modelTableJoin}
;""";
	
	private static String effectiveActorRoleTemplate = """
DROP VIEW IF EXISTS effective${name}ActorRoles CASCADE;
CREATE OR REPLACE VIEW effective${name}ActorRoles as
WITH result AS(
select R.id, R.parentid, roles_to_leaf(R.id) ats, R.organizationid
FROM a7_auth_role_0_1 R
)
select distinct '${modelName}' as model, CASE WHEN RP.participantmodel = '${modelName}' THEN U1.id WHEN RP.participantmodel = 'auth.group' AND U2.id > 0 THEN U2.id ELSE -1 END as id,(R.ats).leafid as effectiveRoleId,(R.ats).roleid as baseRoleId,R.organizationid from result R
JOIN a7_auth_role_system_participation_0_1 RP ON RP.participationid = (R.ats).roleid and (RP.participantmodel = 'auth.group' or RP.participantmodel = '${modelName}')
LEFT JOIN ${tableName} U1 on U1.id = RP.participantid and RP.participantmodel = '${modelName}'
LEFT JOIN a7_auth_group_system_participation_0_1 gp2 on gp2.participationid = RP.participantid and RP.participantmodel = 'auth.group' and gp2.participantmodel = '${modelName}'
LEFT JOIN ${tableName} U2 on U2.id = gp2.participantid AND U2.organizationid = gp2.organizationid;""";

	private static String effectiveGroupObjectEntitlementTemplate = """
DROP VIEW IF EXISTS effectiveGroup${name}ObjectEntitlements CASCADE;
CREATE OR REPLACE VIEW effectiveGroup${name}ObjectEntitlements as
select distinct EI.model, P.name as actorName, R2.name as effectiveRoleName, R2.type as effectiveRoleType, R.name as baseRoleName, R.type as baseRoleType, PR.name as permissionName, ER.model as objectModel, ER.name as objectName from effective${name}ActorRoles EI
inner join a7_auth_role_0_1 R on R.id = EI.effectiveRoleId
inner join a7_auth_role_0_1 R2 on R2.id = EI.baseRoleId
inner join ${tableName} P on EI.id = P.id
inner join effectiveRoles ER on ER.effectiveroleid = EI.effectiveroleid
inner join a7_auth_permission_0_1 PR on PR.id = ER.permissionid
inner join groupedObjects GO on GO.groupId = ER.id;""";
	

	private static Pattern modelNamePat = Pattern.compile("\\$\\{modelName\\}");
	private static Pattern modelTableJoinPat = Pattern.compile("\\$\\{modelTableJoin\\}");
	private static Pattern functionNamePat = Pattern.compile("\\$\\{name\\}");
	private static Pattern tableNamePat = Pattern.compile("\\$\\{tableName\\}");
	
	public static void createEffectiveRoleViews() {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Effective role function support in " + du.getConnectionType().toString() + " is not currently available.");
			return;
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
			
			String schema = tableNamePat.matcher(effectiveActorRoleTemplate).replaceAll(Matcher.quoteReplacement(tableName));
			schema = modelNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(modelName));
			schema = functionNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(viewName));
			schemas.add(schema);
			actorViews.add("SELECT id, model, effectiveroleid, baseroleid, organizationid FROM effective" + viewName + "ActorRoles");
			
			String schema2 = tableNamePat.matcher(effectiveGroupObjectEntitlementTemplate).replaceAll(Matcher.quoteReplacement(tableName));
			schema2 = modelNamePat.matcher(schema2).replaceAll(Matcher.quoteReplacement(modelName));
			schema2 = functionNamePat.matcher(schema2).replaceAll(Matcher.quoteReplacement(viewName));
			schemas2.add(schema2);
			
			groupedEntitlements.add("SELECT model, actorName, effectiveRoleName, effectiveRoleType, baseRoleName, baseRoleType, permissionName, objectModel, objectName FROM effectiveGroup" + viewName + "ObjectEntitlements");
			
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
				
				String schema = tableNamePat.matcher(effectiveRoleTemplate).replaceAll(Matcher.quoteReplacement(tableName));
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
		if(views.size() > 0) {
			StringBuilder buff = new StringBuilder();
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

			try (Connection con = du.getDataSource().getConnection(); Statement statement = con.createStatement();){
				statement.executeUpdate(buff.toString());
		    }
		    catch(SQLException e) {
		    	logger.error(e);
		    }
		}
	}
	
	public static void createPathFunctions(String modelName) {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		if(du.getConnectionType() != ConnectionEnumType.POSTGRE) {
			logger.error("Path function support in " + du.getConnectionType().toString() + " is not currently available.");
			return;
		}
		
		if(modelName.equals(ModelNames.MODEL_ORGANIZATION)) {
			/// Skip organization
			return;
		}
		
		ModelSchema ms = RecordFactory.getSchema(modelName);
		if(!ms.isAbs() && ms.inherits(ModelNames.MODEL_PARENT)){
			String tableName = du.getTableName(modelName);
			String functionName = modelName.substring(modelName.lastIndexOf(".") + 1);
			
			String schema = tableNamePat.matcher(pathSchemaTemplate).replaceAll(Matcher.quoteReplacement(tableName));
			schema = functionNamePat.matcher(schema).replaceAll(Matcher.quoteReplacement(functionName));
			
		    try (Connection con = du.getDataSource().getConnection(); Statement statement = con.createStatement();){
				statement.executeUpdate(schema);
		    }
		    catch(SQLException e) {
		    	logger.error(e);
		    }

		}
	}
	
}
