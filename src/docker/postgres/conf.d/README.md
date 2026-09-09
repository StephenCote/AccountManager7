# Runtime Postgres override directory

Bind-mounted read-only over `/etc/postgresql/conf.d` in the `am7-pg` container
(`src/docker-compose.test.yml`). Anything dropped here as `*.conf` overrides the
values baked into the image by `../Dockerfile`, **without a rebuild**.

The baked `am7-tuning.conf` ends with `include_dir = '/etc/postgresql/conf.d'`,
so these files are read after everything else in that file and win over it.

**Two things still outrank a file here**, lowest to highest:

1. `$PGDATA/postgresql.auto.conf` — written by `ALTER SYSTEM SET`. It is parsed
   *after* the entire main config file including every `include_dir`, so a
   single `ALTER SYSTEM` from any superuser session silently defeats both a
   drop-in here and a `--build-arg` rebuild. It also lives in the bind-mounted
   data directory, so it survives image rebuilds with no trace in the repo. This
   is the usual reason an override here "doesn't take".
2. compose `command:` `-c key=value` flags.

> The commands below are PowerShell. `../../setup/dockerNotes.txt` uses cmd.exe
> `^` line continuations instead — the two are not interchangeable.

```powershell
# Override, then apply with NO restart (context=user / sighup settings only)
Set-Content .\conf.d\zz-local.conf "work_mem = 256MB`nrandom_page_cost = 2.0"
docker exec am7-pg psql -U am7user -d am72db -c "select pg_reload_conf();"
docker exec am7-pg psql -U am7user -d am72db -c "select name,setting,sourcefile from pg_settings where name='work_mem';"

# Revert
Remove-Item .\conf.d\zz-local.conf
docker exec am7-pg psql -U am7user -d am72db -c "select pg_reload_conf();"
```

Reloadable with `pg_reload_conf()` — no restart:
`work_mem`, `maintenance_work_mem`, `autovacuum_work_mem`, `effective_cache_size`,
`random_page_cost`, `effective_io_concurrency`, `maintenance_io_concurrency`,
`max_parallel_workers_per_gather`, `max_parallel_maintenance_workers`,
`max_parallel_workers`, `max_wal_size`, `min_wal_size`.

Needs a container **restart**: `shared_buffers`, `max_connections`,
`shared_preload_libraries`.

## Drift check — run this before trusting a benchmark or filing a perf bug

Files here are git-ignored by design, so a stale override never shows in
`git status`, never appears in the compose file, and silently makes one machine's
(or CI's) database behave differently from everyone else's. Ask the server what
is actually in force rather than reading this directory:

```powershell
docker exec am7-pg psql -U am7user -d am72db -c "select name, setting, sourcefile from pg_settings where sourcefile like '/etc/postgresql/conf.d/%';"
```

Zero rows = the baked defaults are in force. Any row is a local deviation.

Note this does **not** catch `ALTER SYSTEM SET`, which is parsed *after* this
directory and therefore outranks it. To see that too, widen the check:

```powershell
docker exec am7-pg psql -U am7user -d am72db -c "select name, setting, source, sourcefile from pg_settings where source not in ('default','configuration file') or sourcefile like '%conf.d%' or sourcefile like '%auto.conf%';"
```

Two cautions:

- A typo here takes the cluster down on the **next restart**, and there is no
  build-time check to catch it. Always `pg_reload_conf()` first — a reload
  *rejects* a bad value into the log without killing a running server, so it is
  a free syntax check before you restart.
- For a change that should be permanent, prefer a `--build-arg` in
  `docker-compose.test.yml` so it is tracked in git. This directory is for
  machine-local dialing (e.g. a smaller laptop), and `*.conf` here is
  git-ignored for exactly that reason.
