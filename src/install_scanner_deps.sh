#!/usr/bin/env bash
#
# install_scanner_deps.sh — install dependencies for repo_scanner.py on Ubuntu/WSL.
#
# Tiers:
#   core        Python analysis (lizard/radon/bandit) — always needed.
#   secrets     gitleaks (git-history secret scan).
#   sast        semgrep (Java + JS SAST), retire.js (vulnerable bundled JS libs).
#   java        PMD + OWASP Dependency-Check (need JDK; Dependency-Check needs an NVD key).
#   csharp      .NET SDK + Security Code Scan (only if/when C# is added).
#
# Python packages go in a dedicated virtualenv (Debian/Ubuntu Python is
# "externally managed", PEP 668, so system 'pip install' is blocked). Run the
# scanner with that venv's interpreter — see the "Next" hints printed at the end.
#
# Usage:
#   ./install_scanner_deps.sh                 # core + secrets + sast + java
#   ./install_scanner_deps.sh --all           # everything, including csharp
#   ./install_scanner_deps.sh core sast       # only the named tiers
#   NVD_API_KEY=xxxx ./install_scanner_deps.sh java   # also seed the NVD feed
#   SCANNER_VENV=~/.venv-scanner ./install...  # override venv location
#
set -euo pipefail

# ── Pinned versions (bump as needed) ───────────────────────────────────────────
PMD_VERSION="7.7.0"
DC_VERSION="11.1.0"
GITLEAKS_VERSION="8.21.2"
DOTNET_CHANNEL="8.0"
SCANNER_VENV="${SCANNER_VENV:-$HOME/.venv-scanner}"

# ── Tier selection ─────────────────────────────────────────────────────────────
TIERS=()
for arg in "$@"; do
  case "$arg" in
    --all)  TIERS=(core secrets sast java csharp) ;;
    core|secrets|sast|java|csharp) TIERS+=("$arg") ;;
    -h|--help)
      awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
# Default tiers when none specified.
if [ "${#TIERS[@]}" -eq 0 ]; then TIERS=(core secrets sast java); fi

has_tier() { printf '%s\n' "${TIERS[@]}" | grep -qx "$1"; }
have()     { command -v "$1" >/dev/null 2>&1; }
log()      { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi

# ── apt packages (per tier) ─────────────────────────────────────────────────────
APT_PKGS=(curl unzip ca-certificates)
has_tier core   && APT_PKGS+=(python3 python3-pip python3-venv)
has_tier sast   && APT_PKGS+=(nodejs npm)
has_tier java   && APT_PKGS+=(default-jdk maven)
has_tier csharp && APT_PKGS+=(dotnet-sdk-${DOTNET_CHANNEL})

log "Installing apt packages: ${APT_PKGS[*]}"
$SUDO apt-get update -y
$SUDO apt-get install -y "${APT_PKGS[@]}"

# ── Python virtualenv (avoids PEP 668 'externally-managed-environment') ──────────
VENV_PY=""
if has_tier core || has_tier sast; then
  if [ ! -x "$SCANNER_VENV/bin/python" ]; then
    log "Creating Python virtualenv at $SCANNER_VENV"
    python3 -m venv "$SCANNER_VENV"
  else
    log "Reusing existing virtualenv at $SCANNER_VENV"
  fi
  VENV_PY="$SCANNER_VENV/bin/python"
  "$VENV_PY" -m pip install --upgrade pip -q
fi

# ── core: Python analysis libraries ─────────────────────────────────────────────
if has_tier core; then
  log "Installing Python analysis libs (lizard, radon, bandit) into venv"
  "$VENV_PY" -m pip install --upgrade lizard radon bandit
fi

# ── sast: semgrep + retire.js ────────────────────────────────────────────────────
if has_tier sast; then
  log "Installing semgrep (multi-language SAST) into venv"
  "$VENV_PY" -m pip install --upgrade semgrep
  log "Installing retire.js (vulnerable bundled JS libraries)"
  $SUDO npm install -g retire
fi

# ── secrets: gitleaks ────────────────────────────────────────────────────────────
if has_tier secrets; then
  if have gitleaks; then
    log "gitleaks already installed: $(gitleaks version 2>/dev/null || true)"
  else
    log "Installing gitleaks ${GITLEAKS_VERSION}"
    arch="$(uname -m)"; case "$arch" in x86_64) gl_arch=x64 ;; aarch64|arm64) gl_arch=arm64 ;; *) gl_arch=x64 ;; esac
    curl -fsSL "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_${gl_arch}.tar.gz" \
      | $SUDO tar -xz -C /usr/local/bin gitleaks
  fi
fi

# ── java: PMD + OWASP Dependency-Check ───────────────────────────────────────────
if has_tier java; then
  if have pmd; then
    log "pmd already installed: $(pmd --version 2>/dev/null || true)"
  else
    log "Installing PMD ${PMD_VERSION}"
    curl -fsSL "https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip" -o /tmp/pmd.zip
    $SUDO unzip -oq /tmp/pmd.zip -d /opt
    $SUDO ln -sf "/opt/pmd-bin-${PMD_VERSION}/bin/pmd" /usr/local/bin/pmd
    rm -f /tmp/pmd.zip
  fi

  if have dependency-check; then
    log "dependency-check already installed"
  else
    log "Installing OWASP Dependency-Check ${DC_VERSION}"
    curl -fsSL "https://github.com/dependency-check/DependencyCheck/releases/download/v${DC_VERSION}/dependency-check-${DC_VERSION}-release.zip" -o /tmp/dc.zip
    $SUDO unzip -oq /tmp/dc.zip -d /opt
    $SUDO ln -sf /opt/dependency-check/bin/dependency-check.sh /usr/local/bin/dependency-check
    rm -f /tmp/dc.zip
  fi

  # The NVD feed is required before the first real scan and now needs a free API key
  # (https://nvd.nist.gov/developers/request-an-api-key).
  if [ -n "${NVD_API_KEY:-}" ]; then
    log "Seeding the NVD database (one-time, slow)"
    dependency-check --updateonly --nvdApiKey "${NVD_API_KEY}" || \
      echo "NVD update failed — re-run later with a valid NVD_API_KEY." >&2
  else
    echo "Note: set NVD_API_KEY and run 'dependency-check --updateonly --nvdApiKey <KEY>' before first scan." >&2
  fi
fi

# ── csharp: .NET SDK + Security Code Scan (planning ahead; no C# in repo yet) ─────
if has_tier csharp; then
  if have dotnet; then
    log "dotnet SDK present: $(dotnet --version 2>/dev/null || true)"
  else
    echo "dotnet SDK not found — install dotnet-sdk-${DOTNET_CHANNEL} (apt) or via Microsoft's feed." >&2
  fi
  log "Installing Security Code Scan (Roslyn C# SAST) as a global dotnet tool"
  dotnet tool install --global security-scan || \
    dotnet tool update --global security-scan || true
  echo "Ensure ~/.dotnet/tools is on PATH (echo 'export PATH=\"\$PATH:\$HOME/.dotnet/tools\"' >> ~/.bashrc)." >&2
fi

# ── Summary ──────────────────────────────────────────────────────────────────────
# A tool counts as available if it's on PATH or in the scanner venv's bin —
# which is exactly what repo_scanner.py's _which() looks at.
have_tool() { have "$1" || [ -x "$SCANNER_VENV/bin/$1" ]; }

log "Done. Tool availability:"
for t in semgrep gitleaks retire pmd dependency-check dotnet security-scan; do
  if have_tool "$t"; then printf '  \033[32m✓\033[0m %s\n' "$t"; else printf '  \033[31m✗\033[0m %s (not installed)\n' "$t"; fi
done

PYBIN="${VENV_PY:-python3}"
cat <<EOF

Next — run the scanner with the venv interpreter so lizard/radon/bandit and the
venv-installed semgrep are all visible (no activation needed):

  $PYBIN repo_scanner.py --root . --online        # secrets + OSV CVEs + installed tools
  $PYBIN repo_scanner.py --root . --no-thirdparty # offline, Python-only analysis

Or activate the venv first, then use plain 'python':
  source $SCANNER_VENV/bin/activate
EOF
