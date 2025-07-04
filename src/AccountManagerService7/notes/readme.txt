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


/// Adding Vector Extension
https://dev.to/stephenc222/how-to-use-postgresql-to-store-and-query-vector-embeddings-h4b
(https://github.com/pgvector/pgvector)
(https://github.com/pgvector/pgvector#installation-notes---linux-and-mac)
apt update
apt install build-essential git postgresql-server-dev-17
git clone --branch v0.7.4 https://github.com/pgvector/pgvector.git
cd pgvector
make
make install

CREATE EXTENSION if not exists vector;

DROP SEQUENCE IF EXISTS vecttest_id_seq;
CREATE SEQUENCE vecttest_id_seq;

DROP TABLE vecttest;
CREATE TABLE vecttest (
	id bigint not null default nextval('vecttest_id_seq'), objectId varchar(64), modelType varchar(64), content TEXT, embedding vector(384),
	primary key(id)
	
);

/// Vector Index Notes
/// vector dimensions must be defined
/// Ref: https://github.com/prisma/prisma/issues/21850
ALTER TABLE A7_data_vectorModelStore_0_1 ALTER COLUMN embedding TYPE vector(768);
/// Example
/// Ref: https://www.tembo.io/blog/vector-indexes-in-pgvector
CREATE INDEX ON A7_data_vectorModelStore_0_1 USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64)


//// Java setup
Use embedTool.py python script
DEPRECATED - https://djl.ai/extensions/tokenizers/

/// TAG CLOUD QUERY
SELECT COUNT(T.id) AS usage_count, T.id, T.name, (SELECT COUNT(*) FROM a7_data_tag_0_1) AS total_tags, (COUNT(T.id)::numeric / (SELECT COUNT(*) FROM a7_data_tag_system_participation_0_1) * 100) AS usage_percentage
FROM a7_data_tag_0_1 T
INNER JOIN a7_data_tag_system_participation_0_1 TP
ON TP.participationid = T.id
GROUP BY T.id, T.name
ORDER BY usage_count DESC;

