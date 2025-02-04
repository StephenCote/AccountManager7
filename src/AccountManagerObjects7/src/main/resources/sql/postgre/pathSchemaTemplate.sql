DROP FUNCTION IF EXISTS ${name}s_from_leaf CASCADE;
CREATE OR REPLACE FUNCTION ${name}s_from_leaf(IN root_id bigint)
	RETURNS TABLE(leafid bigint, ${name}id bigint, parentid bigint, organizationid bigint)
	AS $$
	WITH RECURSIVE ${name}_tree(leafid,${name}id, parentid, organizationid) AS (
	   SELECT $1 as leafid, R.id as ${name}id, R.parentid, R.organizationid
	      FROM public.${tableName} R WHERE R.id = $1
	   UNION ALL
	   SELECT $1 as leafid, P.id, P.parentid, P.organizationid
	      FROM ${name}_tree RT, public.${tableName} P
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
	      FROM public.${tableName} R WHERE R.id = $1
	   UNION ALL
	   SELECT $1 as leafid, P.id, P.parentid, P.organizationid
	      FROM ${name}_tree RT, public.${tableName} P
	      WHERE RT.parentid = P.id
	)
	select * from ${name}_tree;
	$$ LANGUAGE 'sql';