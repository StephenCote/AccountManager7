DROP VIEW IF EXISTS effectiveGroup${name}ObjectEntitlements CASCADE;
DROP MATERIALIZED VIEW IF EXISTS effectiveGroup${name}ObjectEntitlements CASCADE;
CREATE MATERIALIZED VIEW effectiveGroup${name}ObjectEntitlements as
select distinct EI.model, P.id as actorId, P.name as actorName, R2.id as effectiveRoleId, R2.name as effectiveRoleName, R2.type as effectiveRoleType, R.id as baseRoleId, R.name as baseRoleName, R.type as baseRoleType, PR.id as permissionId, PR.name as permissionName, ER.id as groupId, ER.name as groupName, GO.model as objectModel, GO.id as objectId, GO.name as objectName from effective${name}ActorRoles EI
inner join a7_auth_role_0_1 R on R.id = EI.effectiveRoleId
inner join a7_auth_role_0_1 R2 on R2.id = EI.baseRoleId
inner join ${tableName} P on EI.id = P.id
inner join effectiveRoles ER on ER.effectiveroleid = EI.effectiveroleid AND ER.model = 'auth.group'
inner join a7_auth_permission_0_1 PR on PR.id = ER.permissionid
inner join groupedObjects GO on GO.groupId = ER.id;