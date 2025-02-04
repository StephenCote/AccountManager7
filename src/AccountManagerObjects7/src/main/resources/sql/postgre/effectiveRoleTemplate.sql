DROP VIEW IF EXISTS effective${name}Roles CASCADE;
DROP MATERIALIZED VIEW IF EXISTS effective${name}Roles CASCADE;
CREATE MATERIALIZED VIEW effective${name}Roles as
WITH result AS(
select R.id,R.type, R.parentid,roles_to_leaf(R.id) ats,R.organizationid
FROM a7_auth_role_0_1 R  
)
select distinct GP.participationid as id, ${modelName} as name, GP.participationModel as model, (R.ats).leafid as effectiveRoleId,(R.ats).roleid as baseRoleId,GP.permissionId, R.organizationid from result R
JOIN ${tableName} GP ON GP.participantid = (R.ats).leafid and GP.participantmodel = 'auth.role'
${modelTableJoin};