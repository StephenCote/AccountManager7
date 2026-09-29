#!/usr/bin/env bash
#
# ============================================================================
#  am7-docker-up.sh - build and start the self-contained AM7 Docker stack
#  (Service7 + Ux752 + a dedicated pgvector), then report first-run setup.
#
#  Linux/macOS port of am7-docker-up.bat. Same flags, same compose project
#  (am7test), same compose file. Runbook this automates: src/aiDocs/dockerDevSetup.md
#
#  Usage (flags may be combined, in any order):
#    ./am7-docker-up.sh                 build (online Maven) and start
#    ./am7-docker-up.sh --no-build      start the existing am7:latest image
#    ./am7-docker-up.sh --llmproxy      ALSO start the LiteLLM + Langfuse sidecars
#                                       (compose profile `llmproxy`: 7 extra
#                                       containers). Off by default - they are
#                                       real overhead and most work does not
#                                       need them.
#    ./am7-docker-up.sh --ollama        ALSO start a containerized Ollama (compose
#                                       profile `ollama`: the `ollama` server plus a
#                                       one-shot `ollama-init` that pulls qwen3:8b,
#                                       goekdenizguelmez/JOSIEFIED-Qwen3:8b and
#                                       nomic-embed-text). IMPLIES --llmproxy, so the
#                                       `*-ctr` LiteLLM aliases route to it and every
#                                       test call is traceable in Langfuse. Also
#                                       points the app's embeddings at the container
#                                       (openai_compat / nomic-embed-text) unless
#                                       AM7_OLLAMA_EMBED=0. Host port 11435 (loopback).
#                                       CPU-only unless AM7_OLLAMA_GPU=nvidia.
#    ./am7-docker-up.sh --app-only      the reverse of --llmproxy-only: start ONLY
#                                       the app + its database (am7 + am7-pg), and
#                                       STOP any LiteLLM/Langfuse/Ollama sidecars
#                                       that are running, so you get the overhead back.
#    ./am7-docker-up.sh --llmproxy-only start ONLY the proxy/observability stack
#                                       (litellm + langfuse + am7-pg, which hosts
#                                       litellmdb). Does NOT build, does not stage
#                                       the Olio seed, and does NOT start the app.
#                                       Use when you want the proxy without the
#                                       Service7/Ux752 overhead.
#    ./am7-docker-up.sh --stop          STOP every container in the am7test
#                                       project (up to 11: app, LiteLLM/Langfuse
#                                       and Ollama sidecars) and exit.
#                                       Nothing is removed: no container, no
#                                       volume, no data. Build and seed are
#                                       skipped. See the note at the --stop
#                                       implementation for why the compose
#                                       profile flag is mandatory here.
#    ./am7-docker-up.sh --start         start those containers back up without
#                                       recreating or rebuilding anything.
#    ./am7-docker-up.sh --prebuilt      build with PREBUILT=1 (host-built WAR;
#                                       use when a TLS proxy blocks Maven Central.
#                                       Run these on the host FIRST:
#                                         mvn -o -pl AccountManagerObjects7,AccountManagerISO42001 install -DskipTests
#                                         mvn -o -pl AccountManagerService7 package -DskipTests)
#
#  HOW THIS DIFFERS FROM THE .bat (deliberate, not drift):
#   1. Every setting below is an environment override (VAR:=default), so you
#      change it per-invocation instead of editing the file:
#        LAN_IP=10.0.0.5 CATALINA_OPTS='-Xms2g -Xmx24g' ./am7-docker-up.sh
#   2. LAN_IP is AUTO-DETECTED; the .bat hardcodes the Windows box's
#      192.168.1.39. It must end up being the EXACT origin your browser shows
#      (scheme+host+port), because Tomcat's CorsFilter 403s any POST whose
#      Origin is not in CORS_ALLOWED_ORIGINS - which looks like "login returns a
#      null response" with nothing in the server log, since the filter rejects
#      the request before the app ever runs. If autodetect picks the wrong NIC,
#      set LAN_IP explicitly.
#   3. The setup-token path honours AM7_DATA_DIR (the .bat hardcodes
#      src/docker-data), and is read via sudo -n when Docker created the bind
#      mount root-owned - which on Linux it does.
#   4. `docker compose` (v2 plugin) is preferred, `docker-compose` (v1) is the
#      fallback.
#   5. It calls assemble-seed.sh, not assemble-seed.bat.
#
#  ON ARM64 (DGX Spark, Apple silicon): every base image this stack builds from
#  publishes a linux/arm64 manifest - verified 2026-09-17 with
#  `docker manifest inspect` on maven:3.9-eclipse-temurin-26, node:24-alpine,
#  eclipse-temurin:26-jre-alpine and pgvector/pgvector:0.8.6-pg18-trixie - so a
#  native build works with no --platform flag. Do NOT reuse a docker-data/pg
#  directory that was initialised by an x86 Postgres: the data directory is not
#  portable across architectures and Postgres will refuse to start on it.
#
#  ON THE DGX SPARK SPECIFICALLY: the compose defaults point the LLM/media
#  sidecar URLs (OLLAMA_API_BASE, EMBEDDING_SERVER, FACE/TAG/VOICE_*) at
#  192.168.1.42 - which IS the Spark. A container reaching its own host by LAN
#  IP works on native Linux, so those defaults are usually fine as-is; override
#  them in src/.env if the address moves. SD_SERVER still points at the
#  192.168.1.39 Windows box.
# ============================================================================
#
# ---------------------------------------------------------------------------
# Re-exec under bash. `sh am7-docker-up.sh` bypasses the shebang, and on
# Debian/Ubuntu /bin/sh is dash, which cannot run this script: measured in
# debian:trixie-slim it fails three ways - `set -o pipefail` (older dash;
# 0.5.12+ added it, so this one is version-dependent), `${BASH_SOURCE[0]}`
# -> "Bad substitution", and the `PROFILE_ARGS=()` array literal ->
# 'Syntax error: "(" unexpected'. Rather than emit a confusing partial
# failure, hand the whole thing to bash.
#
# EVERYTHING ABOVE THIS GUARD MUST BE POSIX sh - no arrays, no BASH_SOURCE,
# no `set -o pipefail`. `${BASH_VERSION:-}` is POSIX-valid, which is why the
# test is written that way.
# ---------------------------------------------------------------------------
if [ -z "${BASH_VERSION:-}" ]; then
  if command -v bash >/dev/null 2>&1; then
    exec bash "$0" "$@"
  fi
  echo "ERROR: this script requires bash (it uses arrays and 'set -o pipefail')," >&2
  echo "       and no bash was found on PATH. Install it, or run under bash:" >&2
  echo "         sudo apt install bash" >&2
  exit 1
fi

# No `set -e`: every failure below is checked explicitly so it can print a
# useful message, and several steps (the --app-only `stop`, seed staging) are
# allowed to fail without aborting the run.
set -uo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- Overridable settings (export, or prefix inline, to change) -------------
# LAN_IP: the address other machines reach this host on. Feeds the LAN URL and
# the CORS allow-list only.
if [ -z "${LAN_IP:-}" ]; then
  LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
  [ -n "$LAN_IP" ] || LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [ -n "$LAN_IP" ] || LAN_IP="127.0.0.1"
fi

# APP_PORT: display + CORS only. The published port is hardcoded "9443:8443" in
# docker-compose.test.yml, so changing this does NOT remap it.
: "${APP_PORT:=9443}"

# Tomcat JVM options. CATALINA_OPTS (not JAVA_OPTS) is the right place for
# memory/GC flags: catalina.sh applies it only to start/run, while JAVA_OPTS is
# also passed to the `stop` command. docker/setenv.sh APPENDS to JAVA_OPTS, so
# neither var is clobbered - but keep the JAAS setting out of here and let
# setenv.sh own it.
#
# With no -Xmx, the JVM uses ergonomics: max heap = 1/4 of the memory the
# CONTAINER can see. docker-compose.test.yml forwards this as
# CATALINA_OPTS: ${CATALINA_OPTS:--Xms1g -Xmx8g}, so a value here OVERRIDES that
# compose default; pass CATALINA_OPTS= (empty) to take the compose default.
: "${CATALINA_OPTS:=-Xms1g -Xmx8g}"

# Args passed to assemble-seed.sh in step [2/4]. --with-location also stages the
# large location grids (needed for the geo/map corpus); clear for base corpus only.
: "${SEED_ARGS:=--with-location}"

# Postgres tuning for am7-pg. Each is a ${VAR:-default} in the compose file, so
# exporting one here OVERRIDES the compose default and leaving it unset takes it.
#
# MOST are BAKED AT BUILD TIME (docker/postgres/Dockerfile), so changing one only
# takes effect on a build - i.e. NOT with --no-build. To retune a RUNNING stack
# without a rebuild, drop a .conf into docker/postgres/conf.d and run
# `select pg_reload_conf();` - see docker/postgres/conf.d/README.md.
#
# EXCEPTION - PG_SHM_SIZE is NOT a build ARG. It maps to the service's shm_size:,
# a container-CREATE setting: compose applies it by recreating the container,
# with no build, and it works with --no-build. Do not rebuild chasing an shm change.
#
# BUDGET THESE TOGETHER WITH CATALINA_OPTS: both draw on the same host RAM.
# shared_buffers is pinned, non-reclaimable. work_mem is charged PER memory node
# PER backend, then multiplied by hash_mem_multiplier (2) and by the parallel
# worker count (leader + 4) - so it is the dangerous one. PG_SHM_SIZE must stay
# above work_mem x 2 x 5, or large parallel hash joins die with "could not resize
# shared memory segment ... No space left".
#
# Left unset on purpose - the compose defaults (2GB / 128MB / 2gb) ARE the tuned
# values. Export to dial down for a smaller machine:
#   PG_SHARED_BUFFERS=512MB PG_WORK_MEM=16MB PG_SHM_SIZE=512m ./am7-docker-up.sh
# ----------------------------------------------------------------------------

PROJECT="${PROJECT:-am7test}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.test.yml}"

# Every optional compose profile this project defines. Lifecycle actions (--stop,
# --start, ps, the `down` advice) must name ALL of them: compose only acts on
# services in an ACTIVE profile, so a missing entry here silently orphans that
# profile's containers. Add to this list whenever a profile is added to the file.
ALL_PROFILES=(--profile llmproxy --profile ollama)

# --ollama knobs (all overridable):
#   AM7_OLLAMA_EMBED  1 (default) points the app's embeddings at the container:
#                     EMBEDDING_SERVER=http://ollama:11434/v1/embeddings,
#                     EMBEDDING_TYPE=openai_compat, EMBEDDING_MODEL=nomic-embed-text.
#                     0 leaves the compose defaults (the LAN embedApiMini on .42:8123).
#                     An explicitly exported EMBEDDING_* always wins over both.
#   AM7_OLLAMA_GPU    nvidia layers docker-compose.ollama-gpu.yml (device reservation).
#                     Unset = CPU only, which is all Docker Desktop can offer on an
#                     AMD iGPU host.
#   OLLAMA_HOST_PORT  host loopback port the container publishes (default 11435, so a
#                     host Ollama on 11434 - the `-local` aliases - can coexist).
: "${AM7_OLLAMA_EMBED:=1}"
: "${AM7_OLLAMA_GPU:=}"
: "${OLLAMA_HOST_PORT:=11435}"
export OLLAMA_HOST_PORT

# Honour AM7_DATA_DIR the same way the compose file does (default ./docker-data).
AM7_DATA_ROOT="${AM7_DATA_DIR:-$SRC_DIR/docker-data}"
STORE_DIR="$AM7_DATA_ROOT/am7/store"
# Records which embedding provider a data volume was last booted with. See the
# sticky-provider WARN below.
EMBED_MARKER="$AM7_DATA_ROOT/.embedding-provider"

# CORS_ALLOWED_ORIGINS REPLACES the compose default, so list every origin -
# appending is not a thing. localhost + 127.0.0.1 + the LAN IP, http and https.
CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-https://localhost:$APP_PORT,http://localhost:$APP_PORT,https://127.0.0.1:$APP_PORT,http://127.0.0.1:$APP_PORT,http://localhost:8899,https://localhost:8899,https://$LAN_IP:$APP_PORT,http://$LAN_IP:$APP_PORT}"
export CORS_ALLOWED_ORIGINS CATALINA_OPTS

usage() {
  cat <<'USAGE'

 am7-docker-up.sh [--no-build | --prebuilt] [--llmproxy | --llmproxy-only | --app-only] [--ollama]
 am7-docker-up.sh --stop | --start

   --no-build        start the existing am7:latest image
   --prebuilt        build with PREBUILT=1 (host-built WAR)
   --llmproxy        also start the LiteLLM + Langfuse sidecars (7 containers)
   --ollama          also start a containerized Ollama + one-shot model pull
                     (2 containers; IMPLIES --llmproxy). Publishes 127.0.0.1:11435.
                     Points the app's embeddings at it unless AM7_OLLAMA_EMBED=0.
   --llmproxy-only   start ONLY the sidecars - no app, no build, no seed
                     (with --ollama, the Ollama pair is included)
   --app-only        start ONLY the app + its DB, and STOP any running sidecars
   --stop            STOP every container in the am7test project and exit.
                     Does NOT remove them or touch any data.
   --start           start those stopped containers back up, without recreating
                     or rebuilding anything. The counterpart to --stop.

 Flags may be combined, e.g.:  ./am7-docker-up.sh --no-build --ollama
 --stop and --start are exclusive - they ignore every other flag.

 Settings are environment overrides, not file edits:
   LAN_IP  APP_PORT  CATALINA_OPTS  SEED_ARGS  SEED_STAGING  AM7_DATA_DIR
   PG_SHARED_BUFFERS  PG_WORK_MEM  PG_EFFECTIVE_CACHE_SIZE  PG_SHM_SIZE
   AM7_OLLAMA_EMBED  AM7_OLLAMA_GPU  OLLAMA_HOST_PORT  OLLAMA_PULL_MODELS
 e.g.  LAN_IP=10.0.0.5 CATALINA_OPTS='-Xms2g -Xmx24g' ./am7-docker-up.sh --no-build

 Runbook: src/aiDocs/dockerDevSetup.md sections 12-13

USAGE
}

# ---- Arg parsing: a loop, not a chain of tests, so flags are combinable -----
BUILD_FLAG="--build"
PREBUILT_BUILD=""
PROFILE_ARGS=()
EXTRA_FILE_ARGS=()
ENVFILE_ARGS=()
LLMPROXY=""
OLLAMA=""
LLMPROXY_ONLY=""
APP_ONLY=""
LIFECYCLE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build)       BUILD_FLAG="" ;;
    --prebuilt)       BUILD_FLAG=""; PREBUILT_BUILD=1 ;;
    --llmproxy)       LLMPROXY=1 ;;
    --llmproxy-only)  LLMPROXY=1; LLMPROXY_ONLY=1; BUILD_FLAG="" ;;
    # --ollama IMPLIES --llmproxy: the point of the container is that the `*-ctr`
    # LiteLLM aliases route to it and every test call lands in Langfuse, so a
    # proxy-less variant would be a third routing shape nothing needs.
    --ollama)         OLLAMA=1; LLMPROXY=1 ;;
    --app-only)       APP_ONLY=1 ;;
    --stop)           LIFECYCLE=stop ;;
    --start)          LIFECYCLE=start ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "ERROR: unknown flag '$1'" >&2; usage; exit 1 ;;
  esac
  shift
done

# Profiles are ADDITIVE - assembled after the loop so flag order does not matter.
[ -n "$LLMPROXY" ] && PROFILE_ARGS+=(--profile llmproxy)
[ -n "$OLLAMA" ]   && PROFILE_ARGS+=(--profile ollama)

if [ -n "$APP_ONLY" ] && [ -n "$LLMPROXY_ONLY" ]; then
  echo "ERROR: --app-only and --llmproxy-only are opposites; pick one." >&2
  exit 1
fi
if [ -n "$APP_ONLY" ] && [ -n "$OLLAMA" ]; then
  echo "ERROR: --app-only stops every sidecar, including Ollama; it cannot be combined with --ollama." >&2
  exit 1
fi

if [ -n "$OLLAMA" ]; then
  # GPU opt-in: an override file rather than an always-on device reservation,
  # because `deploy.resources.reservations.devices` makes `up` FAIL outright on a
  # host without the NVIDIA runtime.
  case "$AM7_OLLAMA_GPU" in
    "")      ;;
    nvidia)  EXTRA_FILE_ARGS=(-f docker-compose.ollama-gpu.yml) ;;
    *) echo "ERROR: AM7_OLLAMA_GPU='$AM7_OLLAMA_GPU' - only 'nvidia' (or unset) is supported." >&2; exit 1 ;;
  esac
  # Container embeddings for the APP. Each is `: "${VAR:=default}"`, so a value the
  # caller already exported wins; the compose ${VAR:-...} defaults are what these
  # replace. The URL is the compose SERVICE name - resolvable only inside the
  # am7-test-net network, which is where Tomcat runs.
  if [ "$AM7_OLLAMA_EMBED" != "0" ]; then
    : "${EMBEDDING_SERVER:=http://ollama:11434/v1/embeddings}"
    : "${EMBEDDING_TYPE:=openai_compat}"
    : "${EMBEDDING_MODEL:=nomic-embed-text}"
    export EMBEDDING_SERVER EMBEDDING_TYPE EMBEDDING_MODEL
  fi
fi

# The embedding provider this `up` will hand the app, as compose will see it:
# exported value, else the compose default. Space-separated "server type model"
# ('-' for no model) - the .bat writes the same marker and a '|' would be parsed
# as a pipe by cmd's echo. (Values that live only in src/.env are not visible
# here, so a .env override makes this marker approximate - it is a WARN, not a gate.)
EMBED_EFFECTIVE="${EMBEDDING_SERVER:-http://192.168.1.42:8123} ${EMBEDDING_TYPE:-local} ${EMBEDDING_MODEL:--}"

case "$LIFECYCLE" in
  stop|start)
    if [ -n "$APP_ONLY" ] || [ -n "$LLMPROXY_ONLY" ] || [ -n "$PREBUILT_BUILD" ]; then
      echo "ERROR: --$LIFECYCLE is a whole-project lifecycle action; it cannot be combined" >&2
      echo "       with --app-only, --llmproxy-only or --prebuilt." >&2
      exit 1
    fi
    ;;
esac

cd "$SRC_DIR" || { echo "ERROR: cannot cd to \"$SRC_DIR\"" >&2; exit 1; }

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "ERROR: $COMPOSE_FILE not found in \"$PWD\"." >&2
  echo "       This script must sit in the src/ directory next to the compose files." >&2
  exit 1
fi

# ---- Resolve the compose command: v2 plugin preferred, v1 binary fallback ----
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "ERROR: neither 'docker compose' (v2 plugin) nor 'docker-compose' (v1) is available." >&2
  echo "       On Debian/Ubuntu: sudo apt install docker-compose-plugin" >&2
  exit 1
fi

# The profile's secrets/overrides live in a git-ignored env file. --env-file
# feeds docker-compose ${VAR} INTERPOLATION only; anything litellm must see in
# its own process is additionally declared in the service environment: block.
# Absent file = compose defaults, which are the throwaway test values.
if [ "${#PROFILE_ARGS[@]}" -gt 0 ] && [ -f "$SRC_DIR/volatile/llmproxy.env" ]; then
  ENVFILE_ARGS=(--env-file ./volatile/llmproxy.env)
fi

# Corporate TLS interception (Microsoft Global Secure Access on this network):
# with the VPN up, every HTTPS site is re-signed by a CA the ollama and litellm
# images do not trust, so `ollama pull` and LiteLLM->Azure fail with x509 errors.
# If a CA bundle is present, layer docker-compose.extra-ca.yml, which mounts it
# into both. Measured 2026-09-29: pulls that failed with "certificate signed by
# unknown authority" succeeded immediately once the file was mounted. See
# aiDocs/dockerDevSetup.md section 13.6 for how to produce the PEM.
CA_FILE_ARGS=()
if [ -n "${AM7_EXTRA_CA_FILE:-}" ]; then
  [ -f "$AM7_EXTRA_CA_FILE" ] || { echo "ERROR: AM7_EXTRA_CA_FILE='$AM7_EXTRA_CA_FILE' does not exist." >&2; exit 1; }
  export AM7_EXTRA_CA_FILE
  CA_FILE_ARGS=(-f docker-compose.extra-ca.yml)
elif [ -f "$SRC_DIR/volatile/extra-ca.pem" ]; then
  CA_FILE_ARGS=(-f docker-compose.extra-ca.yml)
fi

# One place that assembles the invariant part of every compose call. The
# ${arr[@]+"${arr[@]}"} form is the empty-array-safe expansion under `set -u`
# (bash 3.2 on macOS errors on a bare "${arr[@]}" when the array is empty).
dc() {
  "${COMPOSE[@]}" -p "$PROJECT" -f "$COMPOSE_FILE" \
    ${EXTRA_FILE_ARGS[@]+"${EXTRA_FILE_ARGS[@]}"} \
    ${CA_FILE_ARGS[@]+"${CA_FILE_ARGS[@]}"} \
    ${ENVFILE_ARGS[@]+"${ENVFILE_ARGS[@]}"} \
    ${PROFILE_ARGS[@]+"${PROFILE_ARGS[@]}"} "$@"
}
# Same, but with EVERY profile active - for whole-project actions (stop/start/ps/
# the sidecar stop in --app-only) that must reach containers regardless of which
# flags THIS invocation was given. The CA override rides along so its one-shot
# litellm-ca-init service is part of the project model (else `ps`/`down` treat
# it as an orphan); the GPU override does not, because it is an `up` concern.
dc_all() {
  "${COMPOSE[@]}" -p "$PROJECT" -f "$COMPOSE_FILE" \
    ${CA_FILE_ARGS[@]+"${CA_FILE_ARGS[@]}"} \
    ${ENVFILE_ARGS[@]+"${ENVFILE_ARGS[@]}"} \
    "${ALL_PROFILES[@]}" "$@"
}

echo "============================================================"
echo " AM7 Docker stack"
echo "   project      : $PROJECT"
echo "   compose file : $COMPOSE_FILE"
echo "   compose cmd  : ${COMPOSE[*]}"
echo "   app URL      : https://localhost:$APP_PORT"
echo "   LAN URL      : https://$LAN_IP:$APP_PORT"
echo "   postgres     : localhost:15433 (inspect only)"
echo "   data dir     : $AM7_DATA_ROOT"
echo "   CATALINA_OPTS: $CATALINA_OPTS"
if [ -n "$LLMPROXY" ]; then
  echo "   llmproxy     : ON  - litellm http://127.0.0.1:4000/ui/  langfuse http://127.0.0.1:3001"
elif [ -n "$APP_ONLY" ]; then
  echo "   llmproxy     : off - and any running sidecars will be STOPPED (--app-only)"
else
  echo "   llmproxy     : off (pass --llmproxy to start the LiteLLM/Langfuse sidecars)"
fi
if [ "${#CA_FILE_ARGS[@]}" -gt 0 ]; then
  echo "   extra CA     : ${AM7_EXTRA_CA_FILE:-./volatile/extra-ca.pem} -> ollama + litellm (docker-compose.extra-ca.yml)"
fi
if [ -n "$OLLAMA" ]; then
  echo "   ollama       : ON  - http://127.0.0.1:$OLLAMA_HOST_PORT  gpu=${AM7_OLLAMA_GPU:-cpu-only}"
  echo "                  litellm aliases: qwen3:8b-ctr  qwen3:8b-jos-ctr  nomic-embed-text-ctr"
  if [ "$AM7_OLLAMA_EMBED" != "0" ]; then
    echo "   embeddings   : $EMBEDDING_SERVER  type=$EMBEDDING_TYPE  model=$EMBEDDING_MODEL"
  else
    echo "   embeddings   : compose defaults (AM7_OLLAMA_EMBED=0)"
  fi
else
  echo "   ollama       : off (pass --ollama for a containerized Ollama; implies --llmproxy)"
fi
echo "============================================================"
echo

# STICKY EMBEDDING PROVIDER - WARN, do not refuse. embedding.server is DB-BACKED
# (a /System system.connection row seeded from EMBEDDING_SERVER on the FIRST boot
# of a data volume and re-read from the DB afterwards), while embedding.type and
# embedding.model are BOOT-PINNED from the env every start. So changing provider
# on an existing volume gives you a new type/model against the OLD stored URL, and
# vectors already in the fixed-width common.vectorExt.embedding column carry no
# provenance. On this disposable test volume the fix is a reset; on anything else,
# know before you switch. (architecture.md "Config: DB-backed vs boot-pinned".)
if [ -z "$LIFECYCLE" ] && [ -z "$LLMPROXY_ONLY" ] && [ -r "$EMBED_MARKER" ]; then
  EMBED_PREVIOUS="$(head -n1 "$EMBED_MARKER" 2>/dev/null | tr -d '\r\n')"
  if [ -n "$EMBED_PREVIOUS" ] && [ "$EMBED_PREVIOUS" != "$EMBED_EFFECTIVE" ]; then
    echo "  WARNING: embedding provider differs from the one this data dir last booted with."
    echo "           was : $EMBED_PREVIOUS"
    echo "           now : $EMBED_EFFECTIVE            (server type model)"
    echo "           embedding.server is DB-backed and will NOT follow EMBEDDING_SERVER on an"
    echo "           existing volume; type/model WILL. Existing vectors keep their old width/"
    echo "           provenance. Reset the volume (dockerDevSetup.md section 7) or repoint the"
    echo "           /System embedding connection by hand if this switch is intentional."
    echo
  fi
fi

# Daemon reachability. NOTE: `docker compose version` above proves nothing about
# the daemon - it only prints the CLI plugin's own version and never connects. So
# this is the first call that actually touches dockerd.
#
# Print what Docker ACTUALLY said. An earlier version of this check swallowed the
# error into /dev/null and printed a guess ("is Docker running?"), which sent
# someone chasing a healthy daemon and a group membership that was already
# correct. The CLI's own message distinguishes the three real causes; a guess
# cannot.
docker_diag="$(docker version 2>&1)"
if [ $? -ne 0 ]; then
  echo "ERROR: the Docker CLI could not complete 'docker version'." >&2
  echo "       Docker's own output:" >&2
  printf '%s\n' "$docker_diag" \
    | grep -iE 'error|cannot|denied|refused|timed out|no such file|not found' \
    | sed 's/^/         /' >&2 \
    || printf '%s\n' "$docker_diag" | tail -n 5 | sed 's/^/         /' >&2
  echo >&2
  echo "       Environment as this script sees it:" >&2
  echo "         DOCKER_HOST    = ${DOCKER_HOST:-<unset>}" >&2
  echo "         DOCKER_CONTEXT = ${DOCKER_CONTEXT:-<unset>}  (active: $(docker context show 2>/dev/null || echo '?'))" >&2
  echo "         ALL_PROXY      = ${ALL_PROXY:-<unset>}" >&2
  echo "         HTTPS_PROXY    = ${HTTPS_PROXY:-<unset>}" >&2
  echo >&2
  case "$docker_diag" in
    *"permission denied"*|*"Permission denied"*)
      echo "       'permission denied' on the socket = a GROUP problem, and almost always a" >&2
      echo "       STALE SESSION rather than a missing membership. \`groups <name>\` reads the" >&2
      echo "       group DATABASE; what matters is the credentials this shell was started" >&2
      echo "       with. Compare the two:" >&2
      echo "         id -nG            # THIS shell's groups - the one that counts" >&2
      echo "         getent group docker" >&2
      echo "       If 'docker' is in getent but not in \`id -nG\`, re-login or:  newgrp docker" >&2
      ;;
    *"Cannot connect"*|*"cannot connect"*|*"connection refused"*|*"no such file"*)
      echo "       'cannot connect' = the CLI is dialling the wrong endpoint, or the daemon" >&2
      echo "       is down. systemctl says it is up, so check the endpoint:" >&2
      echo "         docker context ls          # a non-default context overrides the socket" >&2
      echo "         ls -l /var/run/docker.sock" >&2
      echo "       Also unset ALL_PROXY/HTTPS_PROXY for the CLI if either is set above." >&2
      ;;
    *)
      echo "       Check the daemon and the endpoint:" >&2
      echo "         systemctl status docker" >&2
      echo "         docker context ls" >&2
      ;;
  esac
  exit 1
fi

# --stop / --start: whole-project lifecycle, no build, no seed, no data touched.
#
# THE PROFILE FLAGS HERE (via dc_all = ALL_PROFILES) ARE LOAD-BEARING, not decoration.
# Compose only acts on services in an ACTIVE profile, and a profile is active only
# when named. So the obvious `docker compose -p am7test -f docker-compose.test.yml
# stop` reaches just 2 of the containers and silently leaves the LiteLLM/Langfuse
# and Ollama sidecars running. Measured 2026-09-17 with `stop --dry-run` against all
# 9 then-defined containers: without the flag it stopped only am7test-am7-1 and
# am7-pg. Passing profiles is additive - the non-profile services stay active - so
# one call naming every profile covers the whole project (up to 11 containers now).
#
# `stop`/`start`, never `down`: the containers and their volumes survive, so a
# --start brings the stack back in seconds with no rebuild and no re-seed.
if [ -n "$LIFECYCLE" ]; then
  if [ "$LIFECYCLE" = "stop" ]; then
    echo "[1/2] Stopping every container in project '$PROJECT' (app + sidecars) ..."
  else
    echo "[1/2] Starting the stopped containers in project '$PROJECT' ..."
  fi
  # STOP_GRACE: compose's default stop timeout is 10s, after which it SIGKILLs
  # (that is what an `Exited (137)` in the status table means).
  #
  # Measured 2026-09-17, ONE run per setting against all 9 running - observations,
  # not a controlled benchmark, and litellm's result was not stable across them:
  #
  #   grace | wall | Exited(137), i.e. SIGKILLed
  #   ------+------+----------------------------------------------------------
  #    10s  |   5s | am7, litellm, clickhouse, langfuse-web, langfuse-worker
  #    20s  |  45s | litellm, langfuse-web, langfuse-worker
  #    45s  |  55s | langfuse-web, langfuse-worker
  #
  # Two things to understand before retuning this:
  #  - Wall time is NOT one grace period. Compose stops in dependency waves and
  #    each wave gets its own timeout, so the total is roughly the sum across
  #    waves - which is why 20s costs 45s, not 20s.
  #  - langfuse-web and langfuse-worker ignore SIGTERM at EVERY grace tested.
  #    They are always killed; raising the grace only makes you wait longer for
  #    the same outcome.
  #
  # 20s is the default because it is the cheapest setting at which the two
  # containers that actually hold AM7 state - am7 (Tomcat: DB connections, vault)
  # and am7-pg - both reach Exited (0). litellm is a stateless proxy and
  # clickhouse holds only Langfuse observability data, so their exit codes are
  # cosmetic here.
  #   STOP_GRACE=10 ... --stop   fast (~5s), SIGKILLs Tomcat
  #   STOP_GRACE=45 ... --stop   ~55s, also gets litellm + clickhouse clean
  #
  # `start` takes no timeout, so only pass it for `stop`.
  if [ "$LIFECYCLE" = "stop" ]; then
    dc_all stop -t "${STOP_GRACE:-20}"
  else
    dc_all start
  fi
  lifecycle_rc=$?
  echo
  echo "[2/2] Status:"
  dc_all ps -a
  echo
  echo "============================================================"
  if [ "$lifecycle_rc" -ne 0 ]; then
    echo " WARNING: compose $LIFECYCLE reported a non-zero exit ($lifecycle_rc)."
    echo " Check the status table above before assuming it worked."
  elif [ "$LIFECYCLE" = "stop" ]; then
    echo " Stopped. Nothing was removed - no container, volume or byte of data."
    echo " Bring it back:  ./am7-docker-up.sh --start"
  else
    echo " Started. No container was recreated and nothing was rebuilt."
    echo " App: https://localhost:$APP_PORT   LAN: https://$LAN_IP:$APP_PORT"
  fi
  echo "============================================================"
  exit "$lifecycle_rc"
fi

# --app-only: the reverse of --llmproxy-only. `docker compose up` would simply
# leave already-running profile containers alone, so "only the app" has to stop
# them explicitly - that is the whole point of the flag (reclaiming the RAM the
# sidecars hold). `stop`, not `down`: it leaves the containers and their data
# in place so a later --llmproxy / --ollama restarts them in seconds. Naming a
# service that was never created is harmless (compose reports "no such service"
# only for names absent from the FILE, not for uncreated containers).
if [ -n "$APP_ONLY" ]; then
  echo "[0/4] --app-only: stopping any running LiteLLM/Langfuse/Ollama sidecars ..."
  dc_all stop \
    litellm langfuse-web langfuse-worker langfuse-clickhouse langfuse-minio langfuse-redis langfuse-db \
    ollama ollama-init
  echo
fi

# --llmproxy-only: bring up just the proxy/observability side. Naming the three
# services explicitly (rather than relying on the profile alone) keeps the `am7`
# app out of it; compose still pulls in each one's depends_on, so am7-pg (which
# hosts litellmdb), langfuse-db, clickhouse, minio and redis come along.
# langfuse-worker is listed because nothing depends_on it, yet without it the
# ingestion API accepts events and the UI stays permanently empty.
if [ -n "$LLMPROXY_ONLY" ]; then
  SIDECAR_SERVICES=(litellm langfuse-web langfuse-worker)
  if [ -n "$OLLAMA" ]; then
    SIDECAR_SERVICES+=(ollama ollama-init)
    echo "[1/2] Starting the LiteLLM/Langfuse stack + Ollama only (no app, no build, no seed) ..."
  else
    echo "[1/2] Starting the LiteLLM/Langfuse stack only (no app, no build, no seed) ..."
  fi
  if ! dc up -d "${SIDECAR_SERVICES[@]}"; then
    echo "ERROR: 'docker compose up' failed." >&2
    exit 1
  fi
  echo
  echo "[2/2] Status:"
  dc ps
  cat <<EOF

============================================================
 LiteLLM admin UI : http://127.0.0.1:4000/ui/   (admin / your LITELLM_MASTER_KEY)
 Langfuse UI      : http://127.0.0.1:3001       (LANGFUSE_INIT_USER_EMAIL / _PASSWORD)
 Health           : http://127.0.0.1:4000/health/liveliness

 Point an AM7 system.connection at http://litellm:4000 with dialect=OPENAI_COMPAT
 (http://127.0.0.1:4000 from host-side JUnit). Runbook: aiDocs/dockerDevSetup.md section 12.
EOF
  if [ -n "$OLLAMA" ]; then
    cat <<EOF
 Ollama (container): http://127.0.0.1:$OLLAMA_HOST_PORT/api/tags   aliases: qwen3:8b-ctr qwen3:8b-jos-ctr nomic-embed-text-ctr
 Model pull (multi-GB on first run; the aliases 404 until it finishes):
   ${COMPOSE[*]} -p $PROJECT -f $COMPOSE_FILE ${ALL_PROFILES[*]} logs -f ollama-init
EOF
  fi
  cat <<EOF
 Stop: ${COMPOSE[*]} -p $PROJECT -f $COMPOSE_FILE ${ALL_PROFILES[*]} down
============================================================
EOF
  exit 0
fi

if [ -n "$PREBUILT_BUILD" ]; then
  echo "[1/4] Building image with PREBUILT=1 ..."
  if ! dc build --build-arg PREBUILT=1 am7; then
    echo "ERROR: image build failed." >&2
    exit 1
  fi
else
  echo "[1/4] Skipping explicit build step."
fi

echo
echo "[2/4] Staging Olio seed corpus ..."
# assemble-seed.sh mirrors your SEED_STAGING corpus into docker-data/am7/datagen
# (the /data/am7 -> /data/am7/datagen bind mount) so Olio character generation has
# its reference data. Without this, a fresh stack ships datagen EMPTY and character
# creation fails (KI-70) - see dockerDevSetup.md section 2e. It is idempotent and
# self-skips with a warning when SEED_STAGING is unset, so it is safe to run on
# every `up`. SEED_ARGS controls scope - defaults to --with-location so the grids
# are staged too.
#
# NOTE: SEED_STAGING as committed in src/.env is a WINDOWS path (c:/projects/data).
# On Linux it must point at a real local directory - fix it in src/.env, or export
# SEED_STAGING before running this.
if [ -f "$SRC_DIR/assemble-seed.sh" ]; then
  # shellcheck disable=SC2086  # SEED_ARGS is a deliberate word-split flag list
  if ! bash "$SRC_DIR/assemble-seed.sh" $SEED_ARGS; then
    echo
    echo "  WARNING: seed staging did not complete (SEED_STAGING unset or unreachable, or a copy error)."
    echo "           The stack will still start, but Olio character generation may fail until"
    echo "           the corpus is staged. Set SEED_STAGING in src/.env and re-run, or run"
    echo "           assemble-seed.sh by hand. See dockerDevSetup.md section 2e."
  fi
else
  echo "  WARNING: assemble-seed.sh not found next to this script; skipping seed staging."
fi

echo
echo "[3/4] Starting containers ..."
# --app-only names the two services explicitly; the default (no flag) brings up
# whatever the file defines outside a profile, which today is exactly these two
# plus nothing else. Naming them keeps --app-only honest if a non-profile service
# is ever added.
if [ -n "$APP_ONLY" ]; then
  # The CA override rides along (as in the .bat) so the project model matches the
  # one dc_all just used to stop the sidecars; otherwise compose reports the
  # litellm-ca-init container as an orphan on every --app-only run.
  # shellcheck disable=SC2086  # BUILD_FLAG is either "--build" or nothing
  "${COMPOSE[@]}" -p "$PROJECT" -f "$COMPOSE_FILE" \
    ${CA_FILE_ARGS[@]+"${CA_FILE_ARGS[@]}"} \
    ${ENVFILE_ARGS[@]+"${ENVFILE_ARGS[@]}"} \
    up $BUILD_FLAG -d am7 am7-pg
  up_rc=$?
else
  # shellcheck disable=SC2086
  dc up $BUILD_FLAG -d
  up_rc=$?
fi
if [ "$up_rc" -ne 0 ]; then
  echo "ERROR: 'docker compose up' failed." >&2
  exit 1
fi

# Record the embedding provider this volume was just booted with (feeds the sticky
# WARN above on the next run). Best-effort: a root-owned data dir on Linux makes
# this unwritable, and that must not fail the `up`.
mkdir -p "$AM7_DATA_ROOT" 2>/dev/null || true
printf '%s\n' "$EMBED_EFFECTIVE" > "$EMBED_MARKER" 2>/dev/null || true

echo
echo "[4/4] Status:"
dc ps

if [ -n "$OLLAMA" ]; then
  cat <<EOF

 Ollama container : http://127.0.0.1:$OLLAMA_HOST_PORT/api/tags
 Model pull       : ollama-init pulls ${OLLAMA_PULL_MODELS:-nomic-embed-text qwen3:8b goekdenizguelmez/JOSIEFIED-Qwen3:8b}
                    (multi-GB on first run; the *-ctr LiteLLM aliases 404 until it exits 0). Follow it:
                    ${COMPOSE[*]} -p $PROJECT -f $COMPOSE_FILE ${ALL_PROFILES[*]} logs -f ollama-init
 Aliases          : curl -s -H "Authorization: Bearer \$LITELLM_MASTER_KEY" http://127.0.0.1:4000/v1/models | grep -- -ctr
EOF
fi

# Give the entrypoint a moment to render web.xml and mint the setup token.
sleep 3

# On Linux, Docker creates missing bind-mount host directories as root, so the
# store and its token are typically root-owned. Fall back to sudo only when we
# have to, and with -n so a missing sudo credential fails fast rather than
# blocking this script on a password prompt.
store_exists() {
  [ -e "$1" ] && return 0
  command -v sudo >/dev/null 2>&1 && sudo -n test -e "$1" 2>/dev/null && return 0
  return 1
}
store_read() {
  if [ -r "$1" ]; then
    head -n1 "$1" 2>/dev/null | tr -d '\r\n'
  elif command -v sudo >/dev/null 2>&1; then
    sudo -n head -n1 "$1" 2>/dev/null | tr -d '\r\n'
  fi
}

echo
echo "============================================================"
if store_exists "$STORE_DIR/.setup.done"; then
  echo " Setup already completed on this store (.setup.done present)."
  echo " Sign in at https://localhost:$APP_PORT  (admin / your password, org /Development)"
elif store_exists "$STORE_DIR/.setup.token"; then
  TOKEN="$(store_read "$STORE_DIR/.setup.token")"
  echo " ***** FIRST-RUN SETUP REQUIRED *****"
  echo
  if [ -n "$TOKEN" ]; then
    echo " Open this URL and complete the form:"
    echo
    echo "   https://localhost:$APP_PORT/#!/setup?token=$TOKEN"
    echo
    echo " From another machine on the LAN, use:"
    echo
    echo "   https://$LAN_IP:$APP_PORT/#!/setup?token=$TOKEN"
  else
    # The token file exists but is not readable as this user and sudo would prompt.
    echo " A setup token exists but is not readable as $(id -un). Read it with:"
    echo
    echo "   sudo cat \"$STORE_DIR/.setup.token\""
    echo
    echo " then open:  https://localhost:$APP_PORT/#!/setup?token=<TOKEN>"
  fi
  echo
  echo " Accept the self-signed certificate warning."
  echo " Admin password: use 'password' on this stack - every Playwright helper"
  echo " defaults to it and needs an admin session to provision the test users."
  echo " Hashes cannot be recovered; losing it means a full reset."
else
  echo " No setup token and no completion sentinel yet."
  echo " The app may still be booting. Re-check in a few seconds:"
  echo "   cat \"$STORE_DIR/.setup.token\"        # sudo cat, if root-owned"
  echo " Or watch the log for the FIRST-RUN banner:"
  echo "   ${COMPOSE[*]} -p $PROJECT -f $COMPOSE_FILE logs am7 | grep FIRST-RUN"
fi
echo "============================================================"
echo
echo " Follow the log:   ${COMPOSE[*]} -p $PROJECT -f $COMPOSE_FILE logs -f am7"
echo " Stop (keep all):  ./am7-docker-up.sh --stop     # containers kept, restart with --start"
# NOTE the profile flags on the `down`: without them compose removes only the 2
# non-profile containers and then fails trying to delete a network the sidecars are
# still on. The earlier advice here omitted them. Verified with `down --dry-run`
# 2026-09-17 (llmproxy); ollama added to ALL_PROFILES 2026-09-29.
echo " Remove (keep data volumes):"
echo "                   ${COMPOSE[*]} -p $PROJECT -f $COMPOSE_FILE ${ALL_PROFILES[*]} down"
echo

exit 0
