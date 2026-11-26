#!/usr/bin/env bash
#
# ============================================================================
#  am7-docker-down.sh - tear down the am7test Docker stack, optionally all its data.
#
#  Linux/macOS port of am7-docker-down.bat. Same flags, same compose project.
#  Counterpart to am7-docker-up.sh. Runbook: src/aiDocs/dockerDevSetup.md
#
#  Usage (flags may be combined, in any order):
#    ./am7-docker-down.sh                remove every am7test container + the network.
#                                        KEEPS all data: the DB volume, the Langfuse and
#                                        Ollama volumes, and docker-data/.
#    ./am7-docker-down.sh --reset        ALSO delete all stack data, for a genuine first
#                                        run on the next am7-docker-up.sh:
#                                          - am7test_am7-test-pg (the AM7 DB + litellmdb)
#                                          - the Langfuse volumes (trace history)
#                                          - the containers' anonymous volumes
#                                          - everything under docker-data/
#                                        Asks you to type RESET first.
#    ./am7-docker-down.sh --with-models  with --reset: ALSO delete am7test_ollama-models.
#    ./am7-docker-down.sh --yes          skip the RESET confirmation prompt.
#    ./am7-docker-down.sh --dry-run      print every action, perform none.
#
#  Why a separate script: the DB volume is `external` in docker-compose.test.yml
#  (2026-10-23), so `docker compose down -v` and deleting the am7test group in
#  Docker Desktop both leave it behind. Wiping docker-data/ without it produces
#  the orphan state (fresh setup token, but /rest/setup/state says initialized and
#  the UI goes straight to sign-in). The store and the DB are a matched pair, so
#  --reset always removes both together.
#
#  Not touched: other compose projects, standalone containers, images.
#  Honours AM7_DATA_DIR the same way the compose file does.
# ============================================================================

set -u

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="${PROJECT:-am7test}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.test.yml}"
DATA_DIR="${AM7_DATA_DIR:-$SRC_DIR/docker-data}"
# Must match the `name:` of the external am7-test-pg volume in docker-compose.test.yml.
PG_VOLUME="am7test_am7-test-pg"
LANGFUSE_VOLUMES=("${PROJECT}_langfuse-clickhouse-data" "${PROJECT}_langfuse-clickhouse-logs" "${PROJECT}_langfuse-minio-data")
MODELS_VOLUME="${PROJECT}_ollama-models"
# Every compose profile - same list as am7-docker-up.sh.
ALL_PROFILES=(--profile llmproxy --profile ollama)

RESET=0; WITH_MODELS=0; ASSUME_YES=0; DRY=0

usage() {
  cat <<'USAGE'
Usage: am7-docker-down.sh [--reset [--with-models] [--yes]] [--dry-run]
  (no flags)      remove the am7test containers + network; keep all data
  --reset         also delete the DB + Langfuse volumes and docker-data/
  --with-models   with --reset, also delete the Ollama model volume
  --yes           do not prompt for RESET
  --dry-run       print the actions without performing them
USAGE
}

for arg in "$@"; do
  case "$arg" in
    --reset)       RESET=1 ;;
    --with-models) WITH_MODELS=1 ;;
    --yes)         ASSUME_YES=1 ;;
    --dry-run)     DRY=1 ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "ERROR: unknown option $arg" >&2; usage >&2; exit 2 ;;
  esac
done

if [ "$WITH_MODELS" = 1 ] && [ "$RESET" = 0 ]; then
  echo "ERROR: --with-models only makes sense together with --reset." >&2
  exit 2
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not reachable. Start Docker and retry." >&2
  exit 1
fi

cd "$SRC_DIR" || exit 1

run() {
  echo "  + $*"
  [ "$DRY" = 1 ] && return 0
  "$@"
}

failed() {
  echo
  echo "ERROR: a step failed - see the output above. The reset may be partial;" >&2
  echo "       re-running am7-docker-down.sh --reset is safe." >&2
  exit 1
}

DEL_VOLUMES=("$PG_VOLUME" "${LANGFUSE_VOLUMES[@]}")
[ "$WITH_MODELS" = 1 ] && DEL_VOLUMES+=("$MODELS_VOLUME")

echo "============================================================"
if [ "$RESET" = 1 ]; then
  echo " FULL RESET of project $PROJECT. This permanently deletes:"
  echo "   - every $PROJECT container and the project network"
  echo "   - volumes: ${DEL_VOLUMES[*]}"
  echo "   - the containers' anonymous volumes"
  echo "   - everything under $DATA_DIR"
  [ -d "$DATA_DIR/pg" ] && echo "     INCLUDING $DATA_DIR/pg - the pre-2026-10-10 DB backup"
  [ "$WITH_MODELS" = 0 ] && echo " Kept: $MODELS_VOLUME (pass --with-models to delete it too)"
else
  echo " Removing every $PROJECT container and the project network."
  echo " All data is kept: volumes and $DATA_DIR."
fi
[ "$DRY" = 1 ] && echo " DRY RUN - nothing will be changed."
echo "============================================================"

if [ "$RESET" = 1 ] && [ "$DRY" = 0 ] && [ "$ASSUME_YES" = 0 ]; then
  read -r -p "Type RESET to continue: " ANSWER || ANSWER=""
  if [ "$ANSWER" != "RESET" ]; then
    echo "Aborted. Nothing was changed."
    exit 1
  fi
fi

echo
echo "[1] Stopping and removing containers ..."
if [ "$RESET" = 1 ]; then
  # rm -v also removes each container's ANONYMOUS volumes, which `down` without -v leaks.
  run docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "${ALL_PROFILES[@]}" rm -s -f -v || failed
fi
# No -v on purpose: it would also delete ollama-models. Named volumes go in step 2.
run docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "${ALL_PROFILES[@]}" down --remove-orphans || failed

if [ "$RESET" = 1 ]; then
  echo
  echo "[2] Removing volumes ..."
  for v in "${DEL_VOLUMES[@]}"; do
    if docker volume inspect "$v" >/dev/null 2>&1; then
      run docker volume rm "$v" || failed
    else
      echo "  $v - not present"
    fi
  done

  echo
  echo "[3] Deleting $DATA_DIR ..."
  case "$DATA_DIR" in
    ""|"/"|"$HOME"|"$HOME/") echo "ERROR: refusing to delete DATA_DIR='$DATA_DIR'" >&2; exit 1 ;;
  esac
  if [ -e "$DATA_DIR" ]; then
    run rm -rf -- "$DATA_DIR" || failed
    run mkdir -p -- "$DATA_DIR" || failed
  else
    echo "  (does not exist - nothing to delete)"
  fi
fi

echo
echo "============================================================"
if [ "$DRY" = 1 ]; then
  echo " Dry run complete. Nothing was changed."
elif [ "$RESET" = 1 ]; then
  echo " Reset complete. Next: ./am7-docker-up.sh (with your usual flags)"
  echo " creates the DB volume, mints a setup token and prints the setup URL."
  echo " Open that URL in a NEW browser tab: an old tab may have cached"
  echo " am7.setupComplete in sessionStorage and will redirect to sign-in."
else
  echo " Down. Data kept. Bring it back:  ./am7-docker-up.sh --no-build"
fi
echo "============================================================"
