Setup via API: Add the initial admin credential - note, the credential field is binary, so the value is base64 encoded

curl -X "POST" -H "Content-Type:application/json" -d "{\"model\":\"auth.credential\", \"type\":\"hashed_password\", \"credential\":\"cGFzc3dvcmQ=\"}" http://localhost:8080/AccountManagerService7/rest/setup



pg_stat_statement

https://dbaclass.com/article/monitor-sql-queries-in-postgres-using-pg_stat_statements/

shared_preload_libraries = 'pg_stat_statements'
pg_stat_statements.max=20000
pg_stat_statements.track= top

create extension pg_stat_statements;

SELECT 
round(total_exec_time::numeric, 2) AS total_time,
calls,
round(mean_exec_time::numeric, 2) AS mean,
round((100 * total_exec_time /
sum(total_exec_time::numeric) OVER ())::numeric, 2) AS percentage_cpu,
substring(query, 1, 250) AS query
FROM    pg_stat_statements
ORDER BY total_time DESC
LIMIT 50;

/// Reset
select * from pg_stat_statements_reset();