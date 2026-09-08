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
# Env:
#   SEED_STAGING  REQUIRED — your populated corpus staging dir
#   AM7_DATA_DIR  compose data root       (default: ./docker-data, matches compose)
#   SEED_TARGET   explicit datagen target (default: $AM7_DATA_DIR/am7/datagen)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SEED_STAGING="${SEED_STAGING:-}"
SEED_STAGING="${SEED_STAGING//\\//}"     # normalise Windows backslashes
SEED_STAGING="${SEED_STAGING%/}"

AM7_DATA_DIR="${AM7_DATA_DIR:-$SCRIPT_DIR/docker-data}"
SEED_TARGET="${SEED_TARGET:-$AM7_DATA_DIR/am7/datagen}"

INCLUDE_LOCATION="${SEED_INCLUDE_LOCATION:-0}"
DRY_RUN=0
for a in "$@"; do
  case "$a" in
    --with-location) INCLUDE_LOCATION=1 ;;
    --dry-run)       DRY_RUN=1 ;;
    -h|--help)       sed -n '2,34p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "assemble-seed.sh: unknown arg: $a" >&2; exit 2 ;;
  esac
done

if [ -z "$SEED_STAGING" ]; then
  echo "assemble-seed.sh: SEED_STAGING is required — point it at your populated corpus staging dir" >&2
  echo "  e.g.  cd src && SEED_STAGING=c:/projects/data ./assemble-seed.sh" >&2
  exit 1
fi
if [ ! -d "$SEED_STAGING" ]; then
  echo "assemble-seed.sh: SEED_STAGING is not a directory: $SEED_STAGING" >&2; exit 1
fi

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
echo "assemble-seed.sh: staging  = $SEED_STAGING"
echo "assemble-seed.sh: target   = $SEED_TARGET"
echo "assemble-seed.sh: location = $([ "$INCLUDE_LOCATION" = 1 ] && echo included || echo excluded)"
echo "assemble-seed.sh: pruned top-level am7/ and vault/ (never copied)"
echo "assemble-seed.sh: copied $copied file(s), skipped $skipped$([ "$DRY_RUN" = 1 ] && echo ' (dry-run)')"
exit 0
