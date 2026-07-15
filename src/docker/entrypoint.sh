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
: "${CORS_ALLOWED_ORIGINS:=http://localhost:8899,http://localhost,http://localhost:8080,http://localhost:8888,https://localhost:8899,https://localhost,https://localhost:8443,https://localhost:8888,https://192.168.1.12:8899,https://192.168.1.12:8443}"

# cors.support.credentials is hardcoded true in web.xml.template; combined
# with a wildcard origin that's the classic dangerous CORS misconfiguration.
case ",$CORS_ALLOWED_ORIGINS," in
  *,'*',*|*,'*') echo "entrypoint.sh: refusing CORS_ALLOWED_ORIGINS containing '*' (cors.support.credentials=true makes this unsafe)" >&2; exit 1 ;;
esac

export DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD SESSION_STORE_PATH \
  STORE_PATH DATAGEN_PATH VAULT_PATH VAULT_CREDENTIAL_PATH \
  TASK_SERVER TASK_API_KEY SD_SERVER FACE_SERVER TAG_SERVER \
  VOICE_TTS_SERVER VOICE_STT_SERVER EMBEDDING_SERVER CORS_ALLOWED_ORIGINS

APP_DIR="$CATALINA_HOME/webapps/${APP_CONTEXT}"

mkdir -p "$STORE_PATH" "$DATAGEN_PATH" "$VAULT_PATH" "$VAULT_CREDENTIAL_PATH" "$SESSION_STORE_PATH"

envsubst '$DB_HOST $DB_PORT $DB_NAME $DB_USER $DB_PASSWORD $SESSION_STORE_PATH' \
  < "$APP_DIR/META-INF/context.xml.template" > "$APP_DIR/META-INF/context.xml"

envsubst '$STORE_PATH $DATAGEN_PATH $VAULT_PATH $VAULT_CREDENTIAL_PATH $TASK_SERVER $TASK_API_KEY $SD_SERVER $FACE_SERVER $TAG_SERVER $VOICE_TTS_SERVER $VOICE_STT_SERVER $EMBEDDING_SERVER $CORS_ALLOWED_ORIGINS' \
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

exec /usr/bin/supervisord -c /etc/supervisord.conf
