# ============================================================================
#  AM7 Postgres image -- pgvector base + AM7 performance tuning baked in at
#  BUILD time, overridable at build time (ARGs), at runtime (conf.d bind
#  mount) and per-invocation (compose `command:` -c flags).
#
#  Built by src/docker-compose.test.yml as service `am7-pg`:
#      docker compose -p am7test -f docker-compose.test.yml build am7-pg
#
#  Its own build context (src/docker/postgres/) rather than src/: the app
#  image already owns src/Dockerfile with context src/, and src/.dockerignore
#  excludes setup/ and aiDocs/ -- so a file under src/setup/ is not even
#  reachable from that context. A .dockerignore is resolved at the context
#  ROOT, so src/.dockerignore does not apply here.
# ============================================================================

# Base image. Pinned FULLY (pgvector patch + PG major + distro), not a floating
# `pg18` tag: the drift this change cleans up came from an unpinned
# `pgvector/pgvector` in setup/dockerNotes.txt silently floating to a new major
# while every compose file stayed on pg17. Overridable for a deliberate bump:
#     docker compose ... build --build-arg PG_IMAGE=pgvector/pgvector:0.8.6-pg17-trixie am7-pg
ARG PG_IMAGE=pgvector/pgvector:0.8.6-pg18-trixie
FROM ${PG_IMAGE}

# ---------------------------------------------------------------------------
# Pin the data directory to the pg17-era path.
#
# NOT cosmetic, and NOT safe to drop. The base images disagree:
#     pgvector:pg17           PGDATA=/var/lib/postgresql/data
#     pgvector:0.8.6-pg18-*   PGDATA=/var/lib/postgresql/18/docker
# docker-compose.test.yml bind-mounts the volume at /var/lib/postgresql/data.
# On a pg18 base WITHOUT this line, initdb writes to the 18/docker path --
# outside the bind mount -- so the "persistent" database lives in the
# container's writable layer and is destroyed on the next recreate, with no
# error and no warning. It also keeps the `include` path in the tuning
# template stable across majors.
# ---------------------------------------------------------------------------
ENV PGDATA=/var/lib/postgresql/data

# ---------------------------------------------------------------------------
# Tuning defaults. Every one is overridable per build with --build-arg, and
# docker-compose.test.yml surfaces each as ${VAR:-default} so it can also be
# set from the environment or am7-docker-up.bat.
#
# Sized for the measured ~31.16 GiB Docker VM that concurrently hosts this
# container, the dev `postgres` container, and Tomcat at -Xmx8g. See the
# rationale comments in am7-tuning.conf.template before changing any of them.
# ---------------------------------------------------------------------------
ARG PG_SHARED_BUFFERS=2GB
ARG PG_WORK_MEM=128MB
ARG PG_MAINTENANCE_WORK_MEM=512MB
ARG PG_AUTOVACUUM_WORK_MEM=256MB
ARG PG_EFFECTIVE_CACHE_SIZE=8GB
ARG PG_RANDOM_PAGE_COST=1.1
ARG PG_MAX_PARALLEL_WORKERS_PER_GATHER=4
ARG PG_MAX_PARALLEL_MAINTENANCE_WORKERS=2
ARG PG_MAX_PARALLEL_WORKERS=8
ARG PG_EFFECTIVE_IO_CONCURRENCY=200
ARG PG_MAINTENANCE_IO_CONCURRENCY=200
ARG PG_MAX_CONNECTIONS=200
ARG PG_MAX_WAL_SIZE=4GB
ARG PG_MIN_WAL_SIZE=1GB
# UNQUOTED on purpose -- the template supplies the surrounding single quotes.
# Docker strips quotes from ARG defaults anyway, so quoting here would only
# make a standalone `docker build` and a `docker compose build` disagree.
# A multi-library override is a plain comma list: PG_SHARED_PRELOAD_LIBRARIES=pg_stat_statements,auto_explain
ARG PG_SHARED_PRELOAD_LIBRARIES=pg_stat_statements

# Left root-owned and world-readable ON PURPOSE -- do NOT add a
# `chown postgres:postgres /etc/postgresql`. The postmaster runs as `postgres`
# and only needs to READ its config; its one runtime config write is
# ALTER SYSTEM, which targets $PGDATA/postgresql.auto.conf in the DATA
# directory, not here. Making the config directory writable by the server's own
# runtime user buys nothing and hands a compromised `postgres` session a
# restart-persistent foothold (rewrite shared_preload_libraries and wait for
# `restart: unless-stopped`). Note Postgres enforces ownership/mode on the data
# directory and on SSL keys, but performs NO permission check on config files,
# so nothing else catches this for you.
COPY am7-tuning.conf.template /etc/postgresql/am7-tuning.conf.template

# sed, not envsubst: gettext-base is not installed in the base image and
# pulling it in just to expand a template would be a pointless layer.
RUN set -eu; \
    mkdir -p /etc/postgresql/conf.d; \
    sed -e "s|@PGDATA@|${PGDATA}|g" \
        -e "s|@SHARED_BUFFERS@|${PG_SHARED_BUFFERS}|g" \
        -e "s|@WORK_MEM@|${PG_WORK_MEM}|g" \
        -e "s|@MAINTENANCE_WORK_MEM@|${PG_MAINTENANCE_WORK_MEM}|g" \
        -e "s|@AUTOVACUUM_WORK_MEM@|${PG_AUTOVACUUM_WORK_MEM}|g" \
        -e "s|@EFFECTIVE_CACHE_SIZE@|${PG_EFFECTIVE_CACHE_SIZE}|g" \
        -e "s|@RANDOM_PAGE_COST@|${PG_RANDOM_PAGE_COST}|g" \
        -e "s|@MAX_PARALLEL_WORKERS_PER_GATHER@|${PG_MAX_PARALLEL_WORKERS_PER_GATHER}|g" \
        -e "s|@MAX_PARALLEL_MAINTENANCE_WORKERS@|${PG_MAX_PARALLEL_MAINTENANCE_WORKERS}|g" \
        -e "s|@MAX_PARALLEL_WORKERS@|${PG_MAX_PARALLEL_WORKERS}|g" \
        -e "s|@EFFECTIVE_IO_CONCURRENCY@|${PG_EFFECTIVE_IO_CONCURRENCY}|g" \
        -e "s|@MAINTENANCE_IO_CONCURRENCY@|${PG_MAINTENANCE_IO_CONCURRENCY}|g" \
        -e "s|@MAX_CONNECTIONS@|${PG_MAX_CONNECTIONS}|g" \
        -e "s|@MAX_WAL_SIZE@|${PG_MAX_WAL_SIZE}|g" \
        -e "s|@MIN_WAL_SIZE@|${PG_MIN_WAL_SIZE}|g" \
        -e "s|@SHARED_PRELOAD_LIBRARIES@|${PG_SHARED_PRELOAD_LIBRARIES}|g" \
        /etc/postgresql/am7-tuning.conf.template > /etc/postgresql/am7-tuning.conf; \
    rm /etc/postgresql/am7-tuning.conf.template; \
    grep -q '@' /etc/postgresql/am7-tuning.conf \
      && { echo "ERROR: unsubstituted @TOKEN@ left in am7-tuning.conf:"; \
           grep -n '@' /etc/postgresql/am7-tuning.conf; exit 1; } || true

# Overriding CMD is safe with this base: its ENTRYPOINT is docker-entrypoint.sh,
# which branches on `$1 = 'postgres'` and forwards the same args to the
# temporary init server it runs during first-time initdb -- so a first boot and
# a boot against an already-initialized cluster both honour config_file.
#
# hba_file / ident_file are deliberately NOT set: they resolve from PGDATA, not
# from the config file's directory, even with config_file outside PGDATA. One
# less path to keep correct across majors.
#
# FAILURE MODE WORTH KNOWING, because it does not look like a config problem.
# Because config_file is honoured during first-boot init too, a BAD build ARG
# kills the temporary init server AFTER initdb has already written PG_VERSION.
# The entrypoint decides "already initialized" from `[ -s "$PGDATA/PG_VERSION" ]`,
# so every later boot SKIPS initialization -- POSTGRES_USER/POSTGRES_DB are
# never created, and Service7 then fails with what reads exactly like a
# credentials or auth bug. Fixing the ARG alone does NOT recover it; the
# half-initialized directory has to go:
#     docker compose -p am7test -f docker-compose.test.yml down
#     rm -rf ./docker-data/pg ./docker-data/am7      # matched pair
# Cheap way to avoid ever hitting it: after changing a build ARG, check
# `docker logs am7-pg` for a config FATAL before assuming the stack is fine.
CMD ["postgres", "-c", "config_file=/etc/postgresql/am7-tuning.conf"]
