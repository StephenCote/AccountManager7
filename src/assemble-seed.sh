#!/usr/bin/env bash
#
# assemble-seed.sh — copy the Olio corpus from YOUR staging location into the docker-data
# bind-mount so the Dockerized Service7 (docker-compose.test.yml) loads Olio reference data
# WITHOUT baking the corpus into the image. RUN THIS BEFORE `docker-compose ... up`.
#
# The staging location is yours to build and owns the content: unzip seedData.7z somewhere,
# add whatever extra corpus you want (location grids, WordNet dictionary), and point
# SEED_STAGING at it. This script does NOT read ./src/seedData — it only mirrors
# $SEED_STAGING -> $SEED_TARGET.
#
# WHERE SEED_STAGING COMES FROM: an inline or exported SEED_STAGING wins; otherwise it is read
#   from src/.env (the same file docker-compose loads). bash does NOT auto-load .env the way
#   compose does, so this script reads it itself. Either works:
#     cd src && SEED_STAGING=c:/projects/data ./assemble-seed.sh      # inline
#     # ...or set  SEED_STAGING=c:/projects/data  in src/.env, then just:
#     cd src && ./assemble-seed.sh
#
# WHY volume-copy and not a Dockerfile COPY:
#   `docker build` ships the ENTIRE build context (src/) to the daemon as a tar. The TEST
#   compose stack instead bind-mounts ${AM7_DATA_DIR:-./docker-data}/am7 -> /data/am7 (see
#   docker-compose.test.yml), so writing straight into docker-data/am7/datagen makes the
#   corpus visible to the container with no image rebuild and no tar.
#
# SAFETY: two TOP-LEVEL entries are pruned and NEVER copied even if the staging dir contains
#   them — `am7/` (a live database dir) and `vault/` (secrets). This makes it safe to point
#   SEED_STAGING at a dir that also holds those (e.g. c:/projects/data).
#
# COLORS need not be seeded: ColorUtil loads olio/colors.json from the Objects7 jar classpath.
#
# TWO SWITCHES:
#   base corpus — copied whenever you run this script
#   location    — LARGE; only with --with-location (or SEED_INCLUDE_LOCATION=1)
#
# Usage:
#   cd src && SEED_STAGING=c:/projects/data ./assemble-seed.sh [--with-location] [--dry-run]
#   cd src && ./assemble-seed.sh [--with-location] [--dry-run]      # SEED_STAGING from src/.env
# Env (inline/exported value wins; otherwise read from src/.env):
#   SEED_STAGING  REQUIRED — your populated corpus staging dir (inline or in src/.env)
#   AM7_DATA_DIR  compose data root       (default: ./docker-data, matches compose)
#   SEED_TARGET   explicit datagen target (default: $AM7_DATA_DIR/am7/datagen)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

# Read a single KEY=value from src/.env (last non-commented match), surrounding quotes stripped.
env_file_get() {
  [ -f "$ENV_FILE" ] || return 0
  local line val
  line="$(grep -E "^[[:space:]]*$1=" "$ENV_FILE" 2>/dev/null | tail -n1 || true)"
  [ -n "$line" ] || return 0
  val="${line#*=}"
  val="${val%\"}"; val="${val#\"}"
  val="${val%\'}"; val="${val#\'}"
  printf '%s' "$val"
}

# Normalise any Windows path to the MSYS form Git-Bash coreutils handle reliably.
#   c:\projects\data  ->  /c/projects/data
#   c:/projects/data  ->  /c/projects/data
#   /c/projects/data  ->  /c/projects/data (unchanged)
# We do this for BOTH source and target so find/cp/mkdir never see a mixed set of
# path conventions (a c:/ source with a /c/ dest relies on cp's drive-letter path
# heuristic, which varies across Git-for-Windows builds and can fail).
to_msys_path() {
  local p="${1//\\//}"                       # backslashes -> forward slashes
  case "$p" in
    [A-Za-z]:/*)                             # drive-letter form -> /<drive>/...
      local drive rest
      drive="$(printf '%s' "${p%%:*}" | tr '[:upper:]' '[:lower:]')"
      rest="${p#*:/}"
      p="/$drive/$rest"
      ;;
  esac
  printf '%s' "$p"
}

# Inline/exported value wins; otherwise fall back to src/.env.
SEED_STAGING_SRC=""
if [ -n "${SEED_STAGING:-}" ]; then
  SEED_STAGING_SRC="environment"
else
  SEED_STAGING="$(env_file_get SEED_STAGING)"
  [ -n "$SEED_STAGING" ] && SEED_STAGING_SRC="src/.env"
fi
SEED_STAGING="$(to_msys_path "$SEED_STAGING")"
SEED_STAGING="${SEED_STAGING%/}"

AM7_DATA_DIR="${AM7_DATA_DIR:-$(env_file_get AM7_DATA_DIR)}"
AM7_DATA_DIR="${AM7_DATA_DIR:-$SCRIPT_DIR/docker-data}"
AM7_DATA_DIR="$(to_msys_path "$AM7_DATA_DIR")"
SEED_TARGET="$(to_msys_path "${SEED_TARGET:-$AM7_DATA_DIR/am7/datagen}")"

INCLUDE_LOCATION="${SEED_INCLUDE_LOCATION:-$(env_file_get SEED_INCLUDE_LOCATION)}"
INCLUDE_LOCATION="${INCLUDE_LOCATION:-0}"
DRY_RUN=0
for a in "$@"; do
  case "$a" in
    --with-location) INCLUDE_LOCATION=1 ;;
    --dry-run)       DRY_RUN=1 ;;
    -h|--help)       sed -n '2,42p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "assemble-seed.sh: unknown arg: $a" >&2; exit 2 ;;
  esac
done

if [ -z "$SEED_STAGING" ]; then
  echo "assemble-seed.sh: SEED_STAGING is required — point it at your populated corpus staging dir" >&2
  echo "  set it inline:   cd src && SEED_STAGING=c:/projects/data ./assemble-seed.sh" >&2
  echo "  or in src/.env:  SEED_STAGING=c:/projects/data   (then just: ./assemble-seed.sh)" >&2
  [ -f "$ENV_FILE" ] || echo "  (note: $ENV_FILE does not exist — copy .env.example to .env)" >&2
  exit 1
fi
if [ ! -d "$SEED_STAGING" ]; then
  echo "assemble-seed.sh: SEED_STAGING is not a directory: $SEED_STAGING" >&2; exit 1
fi

# Print resolution up front (before the traversal) so a misconfigured run is obvious immediately.
echo "assemble-seed.sh: staging = $SEED_STAGING${SEED_STAGING_SRC:+ (from $SEED_STAGING_SRC)}"
echo "assemble-seed.sh: target  = $SEED_TARGET"

copied=0
skipped=0

top_segment() { printf '%s' "${1%%/*}"; }
is_location()  { case "$1" in location|location/*) return 0 ;; *) return 1 ;; esac; }
is_readme()    { case "$(basename "$1")" in README.md|readme.txt) return 0 ;; *) return 1 ;; esac; }
is_excluded()  { case "$(top_segment "$1")" in am7|vault) return 0 ;; *) return 1 ;; esac; }

copy_file() {  # $1 = src file, $2 = dest file
  if [ "$DRY_RUN" = 1 ]; then echo "  COPY    $1 -> $2"; return; fi
  mkdir -p "$(dirname "$2")"
  cp -f "$1" "$2"
}

# Prune the top-level am7/ and vault/ dirs so their contents are never traversed or copied.
while IFS= read -r f; do
  rel="${f#"$SEED_STAGING"/}"
  if is_excluded "$rel"; then skipped=$((skipped+1)); continue; fi   # belt-and-suspenders
  if is_readme "$rel";   then skipped=$((skipped+1)); continue; fi
  if is_location "$rel" && [ "$INCLUDE_LOCATION" != 1 ]; then
    echo "  skip (location off): $rel"; skipped=$((skipped+1)); continue
  fi
  copy_file "$f" "$SEED_TARGET/$rel"; copied=$((copied+1))
done < <(find "$SEED_STAGING" \
            -path "$SEED_STAGING/am7"   -prune -o \
            -path "$SEED_STAGING/vault" -prune -o \
            -type f -print | sort)

echo
echo "assemble-seed.sh: location = $([ "$INCLUDE_LOCATION" = 1 ] && echo included || echo excluded)"
echo "assemble-seed.sh: pruned top-level am7/ and vault/ (never copied)"
echo "assemble-seed.sh: copied $copied file(s), skipped $skipped$([ "$DRY_RUN" = 1 ] && echo ' (dry-run)')"
exit 0
