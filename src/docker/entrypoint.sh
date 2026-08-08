#!/bin/bash
set -euo pipefail

: "${DB_HOST:?DB_HOST must be set (external Postgres/pgvector host, e.g. host.docker.internal)}"
: "${DB_PORT:=15432}"
: "${DB_NAME:=am72db}"
: "${DB_USER:=am7user}"
: "${DB_PASSWORD:=password}"

: "${STORE_PATH:=/data/am7/store}"
: "${DATAGEN_PATH:=/data/am7/datagen}"
: "${VAULT_PATH:=/data/am7/vault/}"
: "${VAULT_CREDENTIAL_PATH:=/data/am7/vault/credentials/}"
: "${SESSION_STORE_PATH:=/data/am7/sessions}"

: "${TASK_SERVER:=https://localhost:8443/AccountManagerService7}"
# Stale am6-era JWT from the tracked web.xml, kept as the default for
# continuity -- confirmed with Stephen it is not a live credential. Override
# via env var for any deployment where it matters.
: "${TASK_API_KEY:=eyJraWQiOiJhbTY6c3lzdGVtLnVzZXIubm9ybWFsOnB1YmxpYzpzdGV2ZSIsInN1YmplY3RUeXBlIjoic3lzdGVtLnVzZXIiLCJpc3N1ZXJVcm4iOiJhbTY6c3lzdGVtLnVzZXIubm9ybWFsOnB1YmxpYzphZG1pbiIsInNiaSI6dHJ1ZSwiemlwIjoiR1pJUCIsImFsZyI6IkhTMjU2In0.H4sIAAAAAAAA_4WQT0-EMBDFv4qZM10X6B_ojaMnN0ZPmz0UOqxdoUXaGlfjd7egJN68zbzOb-a9foKPLUjwAd8QMvCdm9CDPB6h6ToXbXjyOPv00kxmKx_wNWIi1mYj7wfjbho9Grs1yzicThm49oJduNPpDqt0rSgviWAlEtrzgtSIlJR537KiYizPeeKDe0G7AhW2nNeUEi10RyhnSFSvEyVqLfK-2JeFSICbz8qaDxWMswcVnhN5e4jtYLrFYlwNPF4nXKJek-VxFxd3GRif0oIaufyj76ybRzXIad0g1W8qowLIXLA9LWsuqgzwfVqEiotCrMIlmH-3_XzY1zeUspAAegEAAA.M1_LUd3jxJ6qxExsxXtogd_AL0-aJ0TdPerKO47czgY}"
: "${SD_SERVER:=http://192.168.1.42:7801}"
: "${FACE_SERVER:=http://192.168.1.42:8003}"
: "${TAG_SERVER:=http://192.168.1.42:8000}"
: "${VOICE_TTS_SERVER:=http://192.168.1.42:8001}"
: "${VOICE_STT_SERVER:=http://192.168.1.42:8002}"
: "${EMBEDDING_SERVER:=http://192.168.1.42:8123}"
: "${SD_DEFAULT_MODEL:=}"
# Fallback SD checkpoint when an olio.sd.config carries no model. Empty by default: names are
# per-Swarm-install and a wrong one returns an empty image list rather than an error, so an empty
# value (falling through to the olio.sd.config schema default) is safer than a guess.
: "${HTTP_READ_TIMEOUT:=}"
# Shared HTTP read timeout in seconds; empty uses the 1200s code default. Must exceed the slowest
# legitimate SD generation -- a FLUX.2 multi-reference composite is ~638s on a Strix Halo iGPU, and
# the previous hardcoded 360s aborted the client while the GPU was still working (the image was
# produced on the SD server regardless; only the caller gave up).
: "${CORS_ALLOWED_ORIGINS:=http://localhost:8899,http://localhost,http://localhost:8080,http://localhost:8888,https://localhost:8899,https://localhost,https://localhost:8443,https://localhost:8888,https://192.168.1.12:8899,https://192.168.1.12:8443}"

# cors.support.credentials is hardcoded true in web.xml.template; combined
# with a wildcard origin that's the classic dangerous CORS misconfiguration.
case ",$CORS_ALLOWED_ORIGINS," in
  *,'*',*|*,'*') echo "entrypoint.sh: refusing CORS_ALLOWED_ORIGINS containing '*' (cors.support.credentials=true makes this unsafe)" >&2; exit 1 ;;
esac

export DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD SESSION_STORE_PATH \
  STORE_PATH DATAGEN_PATH VAULT_PATH VAULT_CREDENTIAL_PATH \
  TASK_SERVER TASK_API_KEY SD_SERVER FACE_SERVER TAG_SERVER \
  VOICE_TTS_SERVER VOICE_STT_SERVER EMBEDDING_SERVER CORS_ALLOWED_ORIGINS \
  SD_DEFAULT_MODEL HTTP_READ_TIMEOUT

APP_DIR="$CATALINA_HOME/webapps/${APP_CONTEXT}"

mkdir -p "$STORE_PATH" "$DATAGEN_PATH" "$VAULT_PATH" "$VAULT_CREDENTIAL_PATH" "$SESSION_STORE_PATH"

envsubst '$DB_HOST $DB_PORT $DB_NAME $DB_USER $DB_PASSWORD $SESSION_STORE_PATH' \
  < "$APP_DIR/META-INF/context.xml.template" > "$APP_DIR/META-INF/context.xml"

envsubst '$STORE_PATH $DATAGEN_PATH $VAULT_PATH $VAULT_CREDENTIAL_PATH $TASK_SERVER $TASK_API_KEY $SD_SERVER $FACE_SERVER $TAG_SERVER $VOICE_TTS_SERVER $VOICE_STT_SERVER $EMBEDDING_SERVER $CORS_ALLOWED_ORIGINS $SD_DEFAULT_MODEL $HTTP_READ_TIMEOUT' \
  < "$APP_DIR/WEB-INF/web.xml.template" > "$APP_DIR/WEB-INF/web.xml"

# Self-signed TLS pair shared by Tomcat (server.xml) and nginx (nginx.conf).
# Persisted under /etc/am7/certs so a mounted volume survives restarts;
# generated fresh on first boot otherwise.
CERT_DIR=/etc/am7/certs
if [ ! -f "$CERT_DIR/server.cert" ] || [ ! -f "$CERT_DIR/server.key" ]; then
  mkdir -p "$CERT_DIR"
  umask 077
  openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
    -keyout "$CERT_DIR/server.key" -out "$CERT_DIR/server.cert" \
    -subj "/CN=am7-container"
  chmod 600 "$CERT_DIR/server.key"
fi

# ---------------------------------------------------------------------------
# First-run setup token.
#
# The admin setup page (POST /rest/setup) cannot require a login -- it runs
# before any credential exists -- so it is gated by this one-shot token AND a
# DB-resident latch in SetupUtil. The token alone is not the security boundary:
# the latch is marker-first and lives in Postgres, so an already-configured
# deployment stays closed even if a fresh token is minted here after a
# /data/am7 volume loss (the "orphan state", where the org keystores are gone
# but the database is intact).
#
# Service7 removes .setup.token and writes .setup.done when setup completes, so
# a configured deployment neither regenerates nor re-advertises a token on
# subsequent boots.
#
# The token is deliberately NOT echoed to stdout. Container stdout is commonly
# shipped to an off-host aggregator (Fluent Bit/Splunk/Datadog) where it lands
# in a searchable index with long retention, read by a far larger population
# than "people who can exec into this container". Since `docker exec` access is
# already root-equivalent, printing only a pointer costs the operator nothing
# and keeps an admin-equivalent bootstrap credential out of log storage.
#
# Supply your own token via AM7_SETUP_TOKEN_FILE (a Docker/K8s secret path),
# which is preferred over passing the value itself: env VALUES are visible via
# `docker inspect`, in /proc/<pid>/environ to every process in the container,
# and in `docker compose config` output -- and are easy to accidentally commit
# in a compose override.
# ---------------------------------------------------------------------------
SETUP_TOKEN_FILE="$STORE_PATH/.setup.token"
SETUP_DONE_FILE="$STORE_PATH/.setup.done"

# BACKWARD COMPATIBILITY with deployments provisioned before the setup page existed.
# Those stores have no .setup.done, so without this they would mint and advertise a
# fresh token on EVERY boot -- harmless (the DB latch keeps setup closed) but alarming,
# and it would train operators to ignore the warning.
#
# $STORE_PATH/.jks/<orgId>/keystore.jks is written by Factory.configureOrganizationStore
# when an organization is created, so its presence means this store has already been
# provisioned. Adopt it as completed instead of advertising a first run.
#
# This deliberately does NOT fire in the orphan state (/data/am7 lost, DB intact): the
# keystores are gone there too, so a token IS minted -- correct, because the DB-resident
# latch is what refuses that setup, not the absence of a token.
#
# The "no .setup.token" clause is load-bearing, do not drop it. IOSystem.open() creates the
# three default organizations on the FIRST Tomcat boot, which writes .jks BEFORE setup has
# run. Without this clause, a fresh deployment whose operator restarts the container before
# completing setup (fixing an env var, a crash, Ctrl-C) would be adopted as "complete" on
# boot 2, its token deleted, and setup left permanently unreachable over REST -- while the DB
# latch stayed open, so the UI would keep routing to /setup and every submit would fail. A
# still-present token means "we advertised a first run that nobody finished", which is the
# opposite of an already-provisioned store.
if [ ! -f "$SETUP_DONE_FILE" ] && [ ! -f "$SETUP_TOKEN_FILE" ] && \
   [ -d "$STORE_PATH/.jks" ] && [ -n "$(ls -A "$STORE_PATH/.jks" 2>/dev/null)" ]; then
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) adopted: pre-existing keystores found in $STORE_PATH/.jks" \
    > "$SETUP_DONE_FILE"
  echo "entrypoint.sh: existing provisioned store detected; recorded setup as complete"
  rm -f "$SETUP_TOKEN_FILE"
fi

if [ -f "$SETUP_DONE_FILE" ]; then
  : # setup already completed on this store -- do not mint or advertise a token
else
  if [ -n "${AM7_SETUP_TOKEN_FILE:-}" ]; then
    if [ ! -f "$AM7_SETUP_TOKEN_FILE" ]; then
      echo "entrypoint.sh: AM7_SETUP_TOKEN_FILE=$AM7_SETUP_TOKEN_FILE does not exist" >&2
      exit 1
    fi
    ( umask 077; tr -d '\r\n' < "$AM7_SETUP_TOKEN_FILE" > "$SETUP_TOKEN_FILE" )
    chmod 600 "$SETUP_TOKEN_FILE"
  elif [ ! -f "$SETUP_TOKEN_FILE" ]; then
    ( umask 077; openssl rand -hex 24 > "$SETUP_TOKEN_FILE" )
    chmod 600 "$SETUP_TOKEN_FILE"
  fi

  # A short token is worse than no token: refuse rather than pretend to gate.
  if [ "$(wc -c < "$SETUP_TOKEN_FILE" | tr -d ' ')" -lt 32 ]; then
    echo "entrypoint.sh: refusing a setup token shorter than 32 characters" >&2
    exit 1
  fi

  echo "entrypoint.sh: ***** FIRST-RUN SETUP REQUIRED *****"
  echo "entrypoint.sh: the setup page is at ${AM7_PUBLIC_URL:-https://<host>:<published-port>}/#!/setup"
  echo "entrypoint.sh: read the one-shot token (NOT logged) with:"
  echo "entrypoint.sh:   docker exec <container> cat $SETUP_TOKEN_FILE"
fi

exec /usr/bin/supervisord -c /etc/supervisord.conf
