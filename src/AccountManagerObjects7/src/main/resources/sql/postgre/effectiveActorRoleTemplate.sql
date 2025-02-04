DROP VIEW IF EXISTS effective${name}ActorRoles CASCADE;
DROP MATERIALIZED VIEW IF EXISTS effective${name}ActorRoles CASCADE;
CREATE MATERIALIZED VIEW effective${name}ActorRoles as
WITH result AS(
select R.id, R.parentid, roles_to_leaf(R.id) ats, R.organizationid
FROM a7_auth_role_0_1 R
)
select distinct '${modelName}' as model, CASE WHEN RP.participantmodel = '${modelName}' THEN U1.id WHEN RP.participantmodel = 'auth.group' AND U2.id > 0 THEN U2.id ELSE -1 END as id,(R.ats).leafid as effectiveRoleId,(R.ats).roleid as baseRoleId,R.organizationid from result R
JOIN a7_auth_role_system_participation_0_1 RP ON RP.participationid = (R.ats).roleid and (RP.participantmodel = 'auth.group' or RP.participantmodel = '${modelName}')
LEFT JOIN ${tableName} U1 on U1.id = RP.participantid and RP.participantmodel = '${modelName}'
LEFT JOIN a7_auth_group_system_participation_0_1 gp2 on gp2.participationid = RP.participantid and RP.participantmodel = 'auth.group' and gp2.participantmodel = '${modelName}'
LEFT JOIN ${tableName} U2 on U2.id = gp2.participantid AND U2.organizationid = gp2.organizationid;