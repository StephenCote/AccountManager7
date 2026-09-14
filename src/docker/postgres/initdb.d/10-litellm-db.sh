#!/bin/sh
# Create the `litellm` database alongside am72db in the SAME am7-pg cluster.
#
# WHY IT EXISTS. The LiteLLM proxy has two halves. The half AM7 actually uses --
# chat completions, the model_list, the max_parallel_requests queue -- is driven
# entirely by the committed src/litellm/config.yaml plus the master_key and needs
# no database at all. The OTHER half (the admin UI, virtual keys, per-key spend,
# request logs) is Postgres-backed, and without a DATABASE_URL every UI request
# 500s with "Not connected to DB!". That is the only symptom; the proxy path is
# unaffected.
#
# WHY A SEPARATE DATABASE, NOT A SCHEMA IN am72db. LiteLLM runs Prisma migrations
# and owns its tables outright. Keeping them in their own database means nothing
# it does can collide with, migrate, or drop anything in the AM7 application
# schema, and `pg_dump am72db` stays purely AM7.
#
# WHEN THIS RUNS -- READ THIS BEFORE ASSUMING IT APPLIED. Scripts in
# /docker-entrypoint-initdb.d are executed by the postgres entrypoint ONLY on the
# first initialization of an empty PGDATA. An existing ${AM7_DATA_DIR}/pg will
# NEVER run it. For an already-provisioned cluster, create the database by hand
# (non-destructive, does not touch am72db):
#
#     docker exec am7-pg psql -U am7user -d postgres -c "CREATE DATABASE litellmdb"
#
# The guard below makes it safe either way.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
    SELECT 'CREATE DATABASE litellmdb'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'litellmdb')\gexec
EOSQL

echo "[am7-pg] ensured database 'litellmdb' exists (LiteLLM proxy admin UI / key store)"
