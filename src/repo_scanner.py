#!/usr/bin/env python3
"""
repo_scanner.py — Comprehensive code quality & security scanner
Run from the root of any repository (or pass --root PATH).

Tuned for the AccountManager7 monorepo: Java + JavaScript code, plus a large
amount of model definitions / configuration in JSON, XML and .properties files.
Dependency *source* (node_modules, target/, dist/) is excluded from code
analysis, but dependency *manifests* (pom.xml, package.json, lockfiles) are
inventoried and — with --online — checked against the OSV vulnerability DB.

Outputs:
  - scan_report.md      Human-readable report
  - scan_report.json    MCP-formatted payload for AI service submission

Usage:
  python repo_scanner.py [--root PATH] [--online] [--no-bandit]

Dependencies (auto-installed if missing):
  pip install bandit radon
"""

import os, sys, json, re, subprocess, hashlib, datetime, collections, pathlib, textwrap
import argparse, urllib.request, urllib.error, shutil, tempfile
import xml.etree.ElementTree as ET

# ── Auto-install light deps (pure-Python analysis) ─────────────────────────────
def ensure(pkg, import_as=None):
    try:
        __import__(import_as or pkg)
        return True
    except ImportError:
        try:
            subprocess.check_call([sys.executable, "-m", "pip", "install", pkg, "-q"])
            return True
        except Exception:
            return False

HAVE_RADON  = ensure("radon")
HAVE_BANDIT = ensure("bandit")
HAVE_LIZARD = ensure("lizard")
if HAVE_RADON:
    import radon.metrics as radon_mi
    import radon.raw     as radon_raw
if HAVE_LIZARD:
    import lizard

# ── Config ─────────────────────────────────────────────────────────────────────
SKIP_DIRS = {
    # VCS / tooling
    ".git", ".svn", ".hg", ".idea", ".settings", ".gradle", ".vscode",
    # Python
    "__pycache__", ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache",
    # JS dependency source / build output
    "node_modules", "dist", "build", ".next", ".nuxt", "coverage", ".nyc_output",
    "bower_components",
    # Java dependency source / build output
    "target", "bin", "out", "classes",
    # misc vendored deps
    "vendor", "third_party", "thirdparty",
    # runtime data / generated output (not source) — also avoids scanning
    # credential material that lives in the working tree
    ".vault", "logs", "cache", "tmp",
}

# Real source code — eligible for complexity / dangerous-call analysis.
CODE_EXTS = {".py", ".js", ".ts", ".jsx", ".tsx", ".java", ".go", ".rb",
             ".php", ".cs", ".cpp", ".c", ".h", ".rs", ".swift", ".kt",
             ".scala", ".sh", ".bash", ".zsh", ".ps1", ".sql"}

# Configuration / data — scanned for secrets and inventoried, but not for code
# complexity. This is where this repo keeps its model defs, keys and creds.
CONFIG_EXTS = {".json", ".xml", ".properties", ".yml", ".yaml", ".env",
               ".ini", ".conf", ".config", ".bat", ".cmd", ".toml"}

# Files that almost always contain only generated / vendored data — skip even if
# they have a scanned extension (avoids drowning the report in lockfile noise).
# scan_report.* are this tool's own output: scanning them re-matches prior
# findings and amplifies counts on every run.
SKIP_FILENAMES = {"package-lock.json", "yarn.lock", "pnpm-lock.yaml",
                  "scan_report.json", "scan_report.md"}

# The scanner's own source contains the very pattern strings it searches for;
# skip it so it doesn't flag itself.
SELF_PATH = pathlib.Path(__file__).resolve()

# Value-based secret patterns. These match the *value*, so they fire regardless
# of how the key is named or whether key and value are on the same line (e.g.
# XML <param-name>/<param-value> pairs).
VALUE_SECRET_PATTERNS = [
    (r'eyJ[A-Za-z0-9_=-]{6,}\.[A-Za-z0-9_=-]{6,}\.[A-Za-z0-9_=-]{4,}', "JWT/JWS Token", "HIGH"),
    (r'-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----',     "Private Key",   "HIGH"),
    (r'AKIA[0-9A-Z]{16}',                                                "AWS Access Key ID", "HIGH"),
    (r'gh[pousr]_[A-Za-z0-9]{36,}',                                      "GitHub Token",  "HIGH"),
    (r'xox[baprs]-[A-Za-z0-9-]{10,}',                                    "Slack Token",   "HIGH"),
    (r'(?i)(?:jdbc:)?(?:mysql|postgresql|postgres|mongodb(?:\+srv)?|redis|amqp)://[^@\s/"\']+:[^@\s/"\']+@', "Connection String with Credentials", "HIGH"),
]

# Key/value secret patterns — the secret is the value following a suspicious key.
# Works for `key=value`, `key: value`, `"key": "value"`. The value group (2) is
# what gets redacted in the report.
KV_SECRET_PATTERNS = [
    (r'(?i)\b(aws_secret_access_key)\b\s*[:=]\s*["\']?([A-Za-z0-9/+=]{40})',          "AWS Secret Key", "HIGH"),
    (r'(?i)\b(password|passwd|pwd)\b\s*[:=]\s*["\']?([^"\'\s,}{<>]{4,})',              "Hardcoded Password", "MEDIUM"),
    (r'(?i)\b(secret|secret[_-]?key|client[_-]?secret)\b\s*[:=]\s*["\']?([^"\'\s,}{<>]{8,})', "Secret", "HIGH"),
    (r'(?i)\b(api[_-]?key|apikey|access[_-]?token|auth[_-]?token|bearer)\b\s*[:=]\s*["\']?([^"\'\s,}{<>]{12,})', "API Key / Token", "HIGH"),
    # High-entropy value tied to a security-ish key (curbs base64 false positives).
    (r'(?i)\b(\w*(?:key|secret|token|cred|pass|auth)\w*)\b\s*[:=]\s*["\']?([A-Za-z0-9+/]{32,}={0,2})["\']?', "High-Entropy Credential", "MEDIUM"),
]

# Values that look like a secret pattern but are clearly placeholders / refs.
SECRET_FALSE_POSITIVES = re.compile(
    r'^\s*$|\$\{|%[A-Za-z0-9_]+%|\{\{|<.*>|x{6,}|\*{4,}|changeme|example|placeholder|your[-_]?|dummy|sample|^n/?a$|^null$|^true$|^false$|^undefined$|^none$',
    re.IGNORECASE)

# File types where an unquoted value is real config data (vs. a code reference).
PROPS_LIKE_EXTS = {".properties", ".env", ".ini", ".conf", ".config",
                   ".bat", ".cmd", ".yml", ".yaml", ".toml"}

# Path / URL / numeric values are not credentials.
_NOT_SECRET_VALUE = re.compile(
    r'^[A-Za-z]:[\\/]|^[\\/]|^\.{0,2}[\\/]|^[a-z][a-z0-9+.-]*://|^\d+(\.\d+)*$',
    re.IGNORECASE)

def looks_like_secret_value(v):
    """Heuristic: does this value plausibly hold a credential (vs. a word/path)?"""
    v = (v or "").strip().strip('"\'')
    if len(v) < 8 or SECRET_FALSE_POSITIVES.search(v) or _NOT_SECRET_VALUE.search(v):
        return False
    if v.isalpha() and len(v) < 20:      # plain word like "AccountUsers"
        return False
    has_alpha = bool(re.search(r'[A-Za-z]', v))
    has_other = bool(re.search(r'[0-9_+/=.\-]', v))
    return (has_alpha and has_other) or len(v) >= 16

# Security-relevant dangerous calls (not just style smells).
DANGEROUS_CALLS = {
    ".py":  ["eval(", "exec(", "os.system(", "subprocess.call(", "subprocess.Popen(",
             "pickle.loads(", "yaml.load(", "__import__(", "compile(", "shell=True"],
    "js":   ["eval(", "Function(", ".innerHTML", "document.write(", "dangerouslySetInnerHTML",
             "child_process", ".exec(", "new Function("],
    ".java":["Runtime.getRuntime().exec", "new ProcessBuilder", ".printStackTrace(",
             "setAccessible(true)", "TrustAllCerts", "X509TrustManager", "HostnameVerifier",
             "MessageDigest.getInstance(\"MD5\")", "MessageDigest.getInstance(\"SHA-1\")",
             "new Random(", "Statement.execute", "ObjectInputStream", ".readObject("],
}
SMELL_MARKERS = ["TODO", "FIXME", "HACK", "XXX", "NOSONAR"]

# Languages lizard can compute cyclomatic complexity for.
LIZARD_EXTS = {".py", ".js", ".ts", ".jsx", ".tsx", ".java", ".go", ".rb",
               ".php", ".cs", ".cpp", ".c", ".h", ".rs", ".swift", ".kt", ".scala"}

# Insecure-configuration rules: (regex, label, severity, recommendation).
# Tuned to the kinds of settings this repo actually carries (TLS off, broad
# CORS, schema resets, default creds, debug flags).
INSECURE_CONFIG_RULES = [
    (r'(?i)(?:ssl|tls|cert)[._-]?(?:verification|verify|validation)[._-]?(?:disabled|disable)\s*[:=>]+\s*"?true',
     "TLS/SSL verification disabled", "HIGH",
     "Enable certificate verification; never disable in production."),
    (r'(?i)rejectUnauthorized\s*:\s*false',
     "Node TLS verification disabled (rejectUnauthorized:false)", "HIGH",
     "Remove rejectUnauthorized:false; use a proper CA bundle."),
    # Case-sensitive: Python's bool literal is capital-F 'False'. Lowercasing
    # would match unrelated Java/JS `boolean verify = false;` flags.
    (r'(?<![\w.])verify\s*=\s*False\b',
     "Python TLS verification disabled (verify=False)", "HIGH",
     "Do not pass verify=False to requests; supply a CA bundle."),
    (r'(?i)(?:TrustAllCerts|X509TrustManager|ALLOW_ALL_HOSTNAME|NoopHostnameVerifier)',
     "Permissive TLS trust manager / hostname verifier", "HIGH",
     "Use the default trust manager and hostname verification."),
    (r'(?i)(?:cors\.allowed\.origins|access-control-allow-origin)[^\n]*[:=>]+[^\n]*\*',
     "CORS allows all origins (*)", "MEDIUM",
     "Restrict allowed origins to an explicit allowlist."),
    (r'(?i)cors\.support\.credentials\s*[:=>]+\s*"?true',
     "CORS credentials enabled (review with allowed origins)", "MEDIUM",
     "Do not combine credentialed CORS with broad/localhost origin lists."),
    (r'(?i)(?:db|database|schema)[._-]?(?:reset|drop)\s*[:=>]+\s*"?true',
     "Database reset/drop enabled", "MEDIUM",
     "Disable schema reset/drop outside of disposable test environments."),
    (r'(?i)hibernate\.hbm2ddl\.auto\s*[:=>]+\s*"?(?:create|create-drop)',
     "Hibernate auto-DDL create/create-drop", "MEDIUM",
     "Use 'validate' or 'none' in shared/production environments."),
    (r'(?i)\bdebug\s*[:=>]+\s*"?true',
     "Debug mode enabled", "LOW",
     "Disable debug flags in production builds."),
    (r'(?i)(?:user(?:name)?|login)\s*[:=>]+\s*"?(?:admin|root|sa)\b',
     "Default/administrative account in config", "LOW",
     "Avoid committing default admin accounts; use provisioned accounts."),
    (r'(?i)(?:cookie|session)[^\n]*secure\s*[:=>]+\s*"?false',
     "Cookie 'secure' flag disabled", "MEDIUM",
     "Set secure (and httpOnly) flags on session cookies."),
    (r'(?i)httpOnly\s*[:=>]+\s*"?false',
     "Cookie 'httpOnly' flag disabled", "LOW",
     "Enable httpOnly to mitigate XSS cookie theft."),
]

NOW = datetime.datetime.now(datetime.timezone.utc).isoformat()

# ── Helpers ────────────────────────────────────────────────────────────────────
def iter_files(root):
    """Yield (path, kind) for code and config files, skipping dep/build dirs."""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fname in filenames:
            p = pathlib.Path(dirpath) / fname
            ext = p.suffix.lower()
            if fname in SKIP_FILENAMES or p.resolve() == SELF_PATH:
                continue
            if ext in CODE_EXTS:
                yield p, "code"
            elif ext in CONFIG_EXTS:
                yield p, "config"

def rel(root, p):
    try:    return str(p.relative_to(root))
    except Exception: return str(p)

def read_safe(p):
    try:    return p.read_text(encoding="utf-8", errors="replace")
    except Exception: return ""

def file_hash(p):
    try:    return hashlib.md5(p.read_bytes()).hexdigest()[:8]
    except Exception: return "?"

def lang_from_ext(ext):
    return {".py":"python",".js":"javascript",".ts":"typescript",
            ".jsx":"javascript",".tsx":"typescript",".java":"java",
            ".go":"go",".rb":"ruby",".php":"php",".cs":"csharp",
            ".cpp":"cpp",".c":"c",".h":"c",".rs":"rust",
            ".swift":"swift",".kt":"kotlin",".sh":"shell",".ps1":"powershell",
            ".bash":"shell",".sql":"sql",".json":"json",".xml":"xml",
            ".properties":"properties",".yml":"yaml",".yaml":"yaml",
            ".bat":"batch",".cmd":"batch",".toml":"toml",".ini":"ini"}.get(ext.lower(), "other")

def redact(value):
    if value is None:
        return ""
    v = value.strip().strip('"\'')
    if len(v) <= 6:
        return "***"
    return f"{v[:3]}...{v[-2:]} ({len(v)} chars)"

# ── Scanners ───────────────────────────────────────────────────────────────────
def scan_secrets(path, content):
    """Return list of secret findings with redacted previews."""
    hits = []
    ext = path.suffix.lower()
    config_kv = ext in PROPS_LIKE_EXTS
    lines = content.splitlines()
    for i, line in enumerate(lines, 1):
        if len(line) > 5000:   # skip minified blobs
            continue
        for pat, label, sev in VALUE_SECRET_PATTERNS:
            m = re.search(pat, line)
            if m:
                hits.append({"line": i, "type": label, "severity": sev,
                             "preview": redact(m.group(0))})
        for pat, label, sev in KV_SECRET_PATTERNS:
            m = re.search(pat, line)
            if not m:
                continue
            val = m.group(2)
            if SECRET_FALSE_POSITIVES.search(val):
                continue
            quoted = m.start(2) > 0 and line[m.start(2) - 1] in "\"'"
            high_entropy = label == "High-Entropy Credential"
            # An unquoted value in code/JSON is a variable reference (e.g.
            # `password: testInfo.testPassword`), not a literal secret. Only
            # treat unquoted values as secrets in .properties/.env-style config
            # (or when the value itself is clearly high-entropy).
            if not quoted and not config_kv and not high_entropy:
                continue
            hits.append({"line": i, "type": label, "severity": sev,
                         "key": m.group(1), "preview": redact(val)})
    # XML <param-name>/<param-value> (and generic name/value) pairing
    if ext == ".xml":
        hits += scan_xml_pairs(content)
    # De-dup by (line, type)
    seen, out = set(), []
    for h in hits:
        k = (h["line"], h["type"])
        if k not in seen:
            seen.add(k); out.append(h)
    return out

XML_PAIR_RE = re.compile(
    r'<param-name>\s*([^<]+?)\s*</param-name>\s*<param-value>\s*([^<]*?)\s*</param-value>',
    re.IGNORECASE | re.DOTALL)
# Strong secret indicators as whole tokens (avoids matching "amauthrole").
SECRET_KEY_RE = re.compile(
    r'(?i)(?:^|[._\-])(api[._\-]?key|apikey|secret|password|passwd|pwd|'
    r'access[._\-]?key|auth[._\-]?token|access[._\-]?token|private[._\-]?key|'
    r'client[._\-]?secret|credential)(?:$|[._\-])')

def scan_xml_pairs(content):
    hits = []
    for m in XML_PAIR_RE.finditer(content):
        name, value = m.group(1).strip(), m.group(2).strip()
        if SECRET_KEY_RE.search(name) and looks_like_secret_value(value):
            line = content[:m.start()].count("\n") + 1
            hits.append({"line": line, "type": f"Config secret ({name})",
                         "severity": "HIGH", "key": name, "preview": redact(value)})
    return hits

def scan_dangerous(path, content):
    """Security-relevant calls + style smells, kept separate."""
    ext = path.suffix.lower()
    if ext in (".js", ".ts", ".jsx", ".tsx"):
        checks = DANGEROUS_CALLS["js"]
    else:
        checks = DANGEROUS_CALLS.get(ext, [])
    danger, smells = [], []
    for i, line in enumerate(content.splitlines(), 1):
        s = line.strip()
        for pat in checks:
            if pat in line:
                danger.append({"line": i, "pattern": pat, "preview": s[:120]})
        for pat in SMELL_MARKERS:
            if pat in line:
                smells.append({"line": i, "pattern": pat, "preview": s[:120]})
    return danger, smells

def cc_rank(cc):
    return ("A" if cc <= 5 else "B" if cc <= 10 else "C" if cc <= 20
            else "D" if cc <= 30 else "E" if cc <= 40 else "F")

def scan_complexity(path, content):
    """Cyclomatic complexity (lizard, multi-language) + Python maintainability."""
    ext = path.suffix.lower()
    funcs, mi = [], None
    if HAVE_LIZARD and ext in LIZARD_EXTS:
        try:
            info = lizard.analyze_file.analyze_source_code(path.name, content)
            funcs = [{"name": fn.name, "complexity": fn.cyclomatic_complexity,
                      "rank": cc_rank(fn.cyclomatic_complexity),
                      "line": fn.start_line, "nloc": fn.nloc,
                      "params": fn.parameter_count} for fn in info.function_list]
        except Exception:
            funcs = []
    if HAVE_RADON and ext == ".py":
        try:
            mi = round(radon_mi.mi_visit(content, True), 2)
        except Exception:
            mi = None
    return funcs, mi

def scan_insecure_config(path, content):
    """Flag known-insecure configuration settings."""
    hits = []
    for i, line in enumerate(content.splitlines(), 1):
        if len(line) > 5000:
            continue
        for pat, label, sev, rec in INSECURE_CONFIG_RULES:
            if re.search(pat, line):
                hits.append({"line": i, "issue": label, "severity": sev,
                             "recommendation": rec, "preview": line.strip()[:140]})
    return hits

def validate_structured(path, content):
    """Well-formedness check for JSON / XML. Returns error dict or None."""
    ext = path.suffix.lower()
    if not content.strip():
        return None
    try:
        if ext == ".json":
            json.loads(content)
        elif ext == ".xml":
            ET.fromstring(content)
        else:
            return None
    except (json.JSONDecodeError, ET.ParseError) as e:
        return {"format": ext.lstrip("."), "error": str(e)[:200]}
    except Exception:
        return None
    return None

def scan_raw_metrics(path, content):
    if HAVE_RADON and path.suffix == ".py":
        try:
            r = radon_raw.analyze(content)
            return {"loc": r.loc, "sloc": r.sloc, "blank": r.blank, "comments": r.comments}
        except Exception:
            pass
    lines = content.splitlines()
    blank = sum(1 for l in lines if not l.strip())
    return {"loc": len(lines), "sloc": len(lines) - blank, "blank": blank, "comments": 0}

def run_bandit(root):
    if not HAVE_BANDIT:
        return [], {"skipped": "bandit not available"}
    try:
        result = subprocess.run(
            [sys.executable, "-m", "bandit", "-r", str(root),
             "-x", ",".join(SKIP_DIRS), "-f", "json", "-q"],
            capture_output=True, text=True, timeout=180)
        data = json.loads(result.stdout or "{}")
        return data.get("results", []), data.get("metrics", {})
    except Exception as e:
        return [], {"error": str(e)}

# ── Third-party tools (run only if the binary is on PATH) ──────────────────────
def _run(cmd, timeout=600):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def _which(name):
    """Locate a tool on PATH, or alongside the running interpreter.

    The latter covers venv-installed CLIs (e.g. semgrep in a scanner venv) that
    aren't on PATH unless the venv is activated."""
    p = shutil.which(name)
    if p:
        return p
    bindir = pathlib.Path(sys.executable).parent
    for cand in (bindir / name, bindir / f"{name}.exe"):
        if cand.exists():
            return str(cand)
    return None

def _has_files(root, *exts):
    """True if any non-skipped file under root has one of the given extensions."""
    exts = {e.lower() for e in exts}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        if any(pathlib.Path(f).suffix.lower() in exts for f in filenames):
            return True
    return False

def find_csharp_projects(root):
    """Locate .sln / .csproj files (skipping dep/build dirs)."""
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            if pathlib.Path(f).suffix.lower() in (".sln", ".csproj"):
                found.append(pathlib.Path(dirpath) / f)
    return found

def run_semgrep(root):
    """Multi-language SAST (Java + JS/TS). Uses the registry 'auto' ruleset."""
    exe = _which("semgrep")
    if not exe:
        return {"status": "not installed"}
    try:
        r = _run([exe, "scan", "--json", "--quiet", "--config", "auto",
                  "--exclude", "node_modules", "--exclude", "target",
                  "--exclude", "dist", str(root)], timeout=900)
        data = json.loads(r.stdout or "{}")
        findings = [{
            "rule": f.get("check_id"),
            "path": rel(root, pathlib.Path(f.get("path", ""))),
            "line": f.get("start", {}).get("line"),
            "severity": f.get("extra", {}).get("severity", "INFO"),
            "message": (f.get("extra", {}).get("message", "") or "")[:240],
        } for f in data.get("results", [])]
        return {"status": "ok", "count": len(findings), "findings": findings[:200]}
    except Exception as e:
        return {"status": f"error: {e}"}

def run_gitleaks(root):
    """Scan git history for committed secrets (catches rotated-but-committed)."""
    exe = _which("gitleaks")
    if not exe:
        return {"status": "not installed"}
    if not (root / ".git").exists():
        return {"status": "not a git repo"}
    out = pathlib.Path(tempfile.gettempdir()) / "gitleaks_report.json"
    try:
        _run([exe, "detect", "--no-banner", "--redact",
              "--report-format", "json", "--report-path", str(out),
              "-s", str(root)], timeout=600)
        data = json.loads(out.read_text(encoding="utf-8", errors="replace")) if out.exists() else []
        findings = [{
            "rule": f.get("RuleID"), "file": f.get("File"),
            "line": f.get("StartLine"), "commit": (f.get("Commit") or "")[:10],
            "description": (f.get("Description") or "")[:160],
        } for f in data]
        return {"status": "ok", "count": len(findings), "findings": findings[:200]}
    except Exception as e:
        return {"status": f"error: {e}"}

def run_retirejs(root):
    """Detect known-vulnerable bundled JS libraries (frontend)."""
    exe = _which("retire")
    if not exe:
        return {"status": "not installed"}
    out = pathlib.Path(tempfile.gettempdir()) / "retire_report.json"
    try:
        _run([exe, "--outputformat", "json", "--outputpath", str(out),
              "--path", str(root)], timeout=600)
        data = json.loads(out.read_text(encoding="utf-8", errors="replace")) if out.exists() else {}
        findings = []
        for entry in (data.get("data", []) if isinstance(data, dict) else data):
            for res in entry.get("results", []):
                for v in res.get("vulnerabilities", []):
                    ids = v.get("identifiers", {})
                    findings.append({
                        "file": rel(root, pathlib.Path(entry.get("file", ""))),
                        "component": res.get("component"), "version": res.get("version"),
                        "severity": v.get("severity", "unknown"),
                        "ids": ids.get("CVE") or ([ids.get("summary")] if ids.get("summary") else []),
                    })
        return {"status": "ok", "count": len(findings), "findings": findings[:200]}
    except Exception as e:
        return {"status": f"error: {e}"}

def _priority_to_sev(p):
    try:    p = int(p)
    except Exception: return "LOW"
    return "HIGH" if p <= 2 else "MEDIUM" if p == 3 else "LOW"

def run_pmd(root):
    """PMD source-level Java analysis (PMD 7 CLI). No compiled classes needed."""
    exe = _which("pmd")
    if not exe:
        return {"status": "not installed"}
    if not _has_files(root, ".java"):
        return {"status": "no Java sources"}
    out = pathlib.Path(tempfile.gettempdir()) / "pmd_report.json"
    try:
        # PMD exits 4 when violations are found — that is success, not an error.
        _run([exe, "check", "-d", str(root), "--no-cache", "-f", "json",
              "-r", str(out), "-R", "rulesets/java/quickstart.xml"], timeout=900)
        data = json.loads(out.read_text(encoding="utf-8", errors="replace")) if out.exists() else {}
        findings = []
        for fe in data.get("files", []):
            for v in fe.get("violations", []):
                findings.append({
                    "file": rel(root, pathlib.Path(fe.get("filename", ""))),
                    "line": v.get("beginline"),
                    "rule": v.get("rule"), "ruleset": v.get("ruleset"),
                    "severity": _priority_to_sev(v.get("priority")),
                    "message": (v.get("description", "") or "")[:240],
                })
        return {"status": "ok", "count": len(findings), "findings": findings[:200]}
    except Exception as e:
        return {"status": f"error: {e}"}

def run_dependency_check(root):
    """OWASP Dependency-Check: NVD CVE matching for jars / manifests (network)."""
    binname = next((_which(b) for b in ("dependency-check", "dependency-check.sh",
                                        "dependency-check.bat") if _which(b)), None)
    if not binname:
        return {"status": "not installed"}
    outdir = pathlib.Path(tempfile.mkdtemp(prefix="depcheck_"))
    try:
        _run([binname, "--scan", str(root), "--format", "JSON", "--out", str(outdir),
              "--disableNodeAudit"], timeout=1800)
        report = outdir / "dependency-check-report.json"
        data = json.loads(report.read_text(encoding="utf-8", errors="replace")) if report.exists() else {}
        findings = []
        for dep in data.get("dependencies", []):
            for v in dep.get("vulnerabilities", []) or []:
                findings.append({
                    "file": dep.get("fileName"),
                    "name": v.get("name"),
                    "severity": (v.get("severity") or "unknown").upper(),
                    "message": (v.get("description", "") or "")[:200],
                })
        return {"status": "ok", "count": len(findings), "findings": findings[:200]}
    except Exception as e:
        return {"status": f"error: {e}"}

def parse_sarif(path):
    """Generic SARIF reader — usable by any SARIF-emitting tool."""
    findings = []
    try:
        data = json.loads(pathlib.Path(path).read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return findings
    for run in data.get("runs", []):
        for res in run.get("results", []):
            loc = (res.get("locations") or [{}])[0].get("physicalLocation", {})
            findings.append({
                "rule": res.get("ruleId"),
                "file": loc.get("artifactLocation", {}).get("uri", ""),
                "line": loc.get("region", {}).get("startLine"),
                "severity": (res.get("level") or "warning").upper(),
                "message": (res.get("message", {}) or {}).get("text", "")[:240],
            })
    return findings

def run_dotnet_vuln(root):
    """NuGet vulnerable-package check (built into the .NET SDK; queries GH advisories)."""
    projects = find_csharp_projects(root)
    if not projects:
        return {"status": "no C# projects"}
    exe = _which("dotnet")
    if not exe:
        return {"status": "not installed (dotnet)"}
    findings = []
    # Prefer a solution if present, else iterate project files.
    targets = [p for p in projects if p.suffix == ".sln"] or projects
    for tgt in targets:
        try:
            _run([exe, "restore", str(tgt)], timeout=600)
            r = _run([exe, "list", str(tgt), "package", "--vulnerable",
                      "--include-transitive", "--format", "json"], timeout=600)
            data = json.loads(r.stdout or "{}")
            for proj in data.get("projects", []):
                for fw in proj.get("frameworks", []) or []:
                    for grp in ("topLevelPackages", "transitivePackages"):
                        for pkg in fw.get(grp, []) or []:
                            for v in pkg.get("vulnerabilities", []) or []:
                                findings.append({
                                    "file": rel(root, pathlib.Path(proj.get("path", str(tgt)))),
                                    "name": pkg.get("id"),
                                    "version": pkg.get("resolvedVersion"),
                                    "severity": (v.get("severity") or "unknown").upper(),
                                    "message": v.get("advisoryurl", ""),
                                })
        except Exception as e:
            return {"status": f"error: {e}", "findings": findings}
    return {"status": "ok", "count": len(findings), "findings": findings[:200]}

def run_security_code_scan(root):
    """Security Code Scan — C# SAST (Roslyn). Emits SARIF we parse generically."""
    if not find_csharp_projects(root):
        return {"status": "no C# projects"}
    exe = _which("security-scan")
    if not exe:
        return {"status": "not installed"}
    sln = next((str(p) for p in find_csharp_projects(root) if p.suffix == ".sln"), str(root))
    out = pathlib.Path(tempfile.gettempdir()) / "scs_report.sarif"
    try:
        _run([exe, sln, f"--export={out}"], timeout=900)
        findings = parse_sarif(out) if out.exists() else []
        return {"status": "ok", "count": len(findings), "findings": findings[:200]}
    except Exception as e:
        return {"status": f"error: {e}"}

def run_thirdparty(root):
    return {
        "semgrep":          run_semgrep(root),
        "gitleaks":         run_gitleaks(root),
        "retirejs":         run_retirejs(root),
        "pmd":              run_pmd(root),
        "dependency_check": run_dependency_check(root),
        "dotnet_vuln":      run_dotnet_vuln(root),
        "security_code_scan": run_security_code_scan(root),
    }

# ── Dependency inventory (recursive, all modules) ──────────────────────────────
def strip_ns(tag):
    return tag.split("}", 1)[1] if "}" in tag else tag

def parse_pom(path):
    """Extract Maven coordinates group:artifact -> version (best-effort)."""
    deps = []
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return deps
    props = {}
    for el in root.iter():
        if strip_ns(el.tag) == "properties":
            for c in el:
                if c.text:
                    props[strip_ns(c.tag)] = c.text.strip()
    def resolve(v):
        if not v:
            return v
        m = re.fullmatch(r'\$\{(.+?)\}', v.strip())
        if m and m.group(1) in props:
            return props[m.group(1)]
        return v.strip()
    for dep in root.iter():
        if strip_ns(dep.tag) != "dependency":
            continue
        g = a = ver = None
        for c in dep:
            t = strip_ns(c.tag)
            if t == "groupId":    g = (c.text or "").strip()
            elif t == "artifactId": a = (c.text or "").strip()
            elif t == "version":  ver = resolve(c.text)
        if g and a:
            deps.append({"ecosystem": "Maven", "name": f"{g}:{a}", "version": ver})
    return deps

def parse_package_json(path):
    deps = []
    try:
        data = json.loads(read_safe(path))
    except Exception:
        return deps
    for section in ("dependencies", "devDependencies", "optionalDependencies"):
        for name, ver in (data.get(section) or {}).items():
            deps.append({"ecosystem": "npm", "name": name,
                         "version": re.sub(r'^[~^>=<\s]+', '', str(ver))})
    return deps

def parse_lockfile_versions(path):
    """Resolved npm versions (exact) from package-lock.json for vuln matching."""
    out = {}
    try:
        data = json.loads(read_safe(path))
    except Exception:
        return out
    for key, meta in (data.get("packages") or {}).items():
        if key.startswith("node_modules/") and isinstance(meta, dict) and meta.get("version"):
            out[key.split("node_modules/")[-1]] = meta["version"]
    for name, meta in (data.get("dependencies") or {}).items():   # lock v1
        if isinstance(meta, dict) and meta.get("version"):
            out.setdefault(name, meta["version"])
    return out

def collect_dependencies(root):
    """Walk the tree for manifests, return inventory keyed by manifest path."""
    inventory = {}      # rel manifest path -> [deps]
    lock_versions = {}  # npm name -> exact version
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fname in filenames:
            p = pathlib.Path(dirpath) / fname
            r = rel(root, p)
            if fname == "pom.xml":
                inventory[r] = parse_pom(p)
            elif fname == "package.json":
                inventory[r] = parse_package_json(p)
            elif fname == "package-lock.json":
                lock_versions.update(parse_lockfile_versions(p))
    # Backfill npm exact versions from lockfiles for accurate OSV queries
    for deps in inventory.values():
        for d in deps:
            if d["ecosystem"] == "npm" and d["name"] in lock_versions:
                d["version"] = lock_versions[d["name"]]
    return inventory

def osv_scan(inventory):
    """Query OSV.dev batch API for known vulnerabilities. Network required."""
    queries, index = [], []
    for manifest, deps in inventory.items():
        for d in deps:
            if not d.get("version"):
                continue
            queries.append({"package": {"ecosystem": d["ecosystem"], "name": d["name"]},
                            "version": d["version"]})
            index.append((manifest, d))
    findings = []
    for start in range(0, len(queries), 200):
        chunk = queries[start:start+200]
        try:
            req = urllib.request.Request(
                "https://api.osv.dev/v1/querybatch",
                data=json.dumps({"queries": chunk}).encode(),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                results = json.loads(resp.read()).get("results", [])
        except Exception as e:
            return {"error": f"OSV query failed: {e}", "findings": findings}
        for j, res in enumerate(results):
            vulns = res.get("vulns") or []
            if vulns:
                manifest, d = index[start + j]
                findings.append({"manifest": manifest, "ecosystem": d["ecosystem"],
                                 "name": d["name"], "version": d["version"],
                                 "vuln_ids": [v.get("id") for v in vulns],
                                 "count": len(vulns)})
    return {"findings": findings}

def detect_licenses(root):
    found = []
    for name in ["LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "license"]:
        p = root / name
        if p.exists():
            first = p.read_text(errors="replace")[:300]
            for ltype in ["MIT","Apache","GPL","BSD","ISC","MPL","LGPL","AGPL","Unlicense","CC0"]:
                if ltype in first:
                    found.append(ltype); break
            else:
                found.append("Custom/Unknown")
    return found or ["Not found"]

# ── Main scan ──────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description="Comprehensive repo code & security scanner")
    ap.add_argument("--root", default=".", help="Repository root to scan")
    ap.add_argument("--online", action="store_true",
                    help="Query OSV.dev for dependency vulnerabilities (network)")
    ap.add_argument("--no-bandit", action="store_true", help="Skip Python bandit scan")
    ap.add_argument("--no-thirdparty", action="store_true",
                    help="Skip third-party tools (semgrep/gitleaks/retire) even if installed")
    args = ap.parse_args()

    root = pathlib.Path(args.root).resolve()
    print(f"\n[*] Scanning {root} ...\n")

    files_data = []
    total_loc = total_sloc = secret_total = danger_total = smell_total = 0
    lang_counts = collections.Counter()
    high_complex = []
    secret_sev = collections.Counter()
    config_issues = []          # insecure-configuration findings
    config_sev = collections.Counter()
    validation_errors = []      # malformed JSON / XML

    all_files = list(iter_files(root))
    code_n = sum(1 for _, k in all_files if k == "code")
    print(f"    {len(all_files)} files in scope ({code_n} code, {len(all_files)-code_n} config) "
          f"— dep source (node_modules/target/dist) excluded")

    for i, (path, kind) in enumerate(all_files, 1):
        if i % 100 == 0:
            print(f"    ... {i}/{len(all_files)}")
        content = read_safe(path)
        ext = path.suffix.lower()
        lang = lang_from_ext(ext)
        metrics = scan_raw_metrics(path, content)
        secrets = scan_secrets(path, content)
        cfg = scan_insecure_config(path, content)
        if kind == "code":
            dangers, smells = scan_dangerous(path, content)
            funcs, mi = scan_complexity(path, content)
        else:
            dangers, smells, funcs, mi = [], [], [], None
            verr = validate_structured(path, content)
            if verr:
                validation_errors.append({"path": rel(root, path), **verr})

        lang_counts[lang] += 1
        total_loc  += metrics["loc"]
        total_sloc += metrics["sloc"]
        secret_total += len(secrets)
        danger_total += len(dangers)
        smell_total  += len(smells)
        for s in secrets:
            secret_sev[s.get("severity", "LOW")] += 1
        for c in cfg:
            config_sev[c.get("severity", "LOW")] += 1
            config_issues.append({"path": rel(root, path), **c})

        high_cc = [f for f in funcs if f["complexity"] >= 10]
        if high_cc:
            high_complex.extend([{**f, "file": rel(root, path)} for f in high_cc])

        if secrets or dangers or smells or high_cc:
            files_data.append({
                "path": rel(root, path), "kind": kind, "hash": file_hash(path),
                "lang": lang, "metrics": metrics, "mi_score": mi,
                "secrets": secrets, "dangerous": dangers, "smells": smells,
                "complexity": funcs,
            })

    bandit_results, bandit_metrics = ([], {"skipped": True})
    if not args.no_bandit:
        print("    Running Bandit (Python static security analysis) ...")
        bandit_results, bandit_metrics = run_bandit(root)

    print("    Collecting dependency manifests (all modules) ...")
    inventory = collect_dependencies(root)
    dep_total = sum(len(v) for v in inventory.values())
    osv = {"findings": []}
    if args.online:
        print(f"    Querying OSV.dev for {dep_total} dependencies ...")
        osv = osv_scan(inventory)
    licenses = detect_licenses(root)

    thirdparty = {}
    if not args.no_thirdparty:
        avail = [t for t in ("semgrep", "gitleaks", "retire", "pmd",
                             "dependency-check", "dotnet", "security-scan")
                 if _which(t)]
        print(f"    Third-party tools available: {', '.join(avail) or 'none'}")
        thirdparty = run_thirdparty(root)
    tp_total = sum(v.get("count", 0) for v in thirdparty.values() if isinstance(v, dict))

    sev = {"HIGH": [], "MEDIUM": [], "LOW": []}
    for r in bandit_results:
        sev.setdefault(r.get("issue_severity", "LOW").upper(), []).append(r)

    vuln_deps = len(osv.get("findings", []))

    # ── Score (0–100) ─────────────────────────────────────────────────────────
    score = 100
    score -= min(35, secret_sev.get("HIGH", 0) * 8 + secret_sev.get("MEDIUM", 0) * 3)
    score -= min(25, vuln_deps * 5)
    score -= min(15, len(sev.get("HIGH", [])) * 8)
    score -= min(12, config_sev.get("HIGH", 0) * 4 + config_sev.get("MEDIUM", 0) * 2)
    score -= min(10, len(sev.get("MEDIUM", [])) * 2)
    score -= min(10, len(high_complex))
    score -= min(5,  danger_total)
    score = max(0, score)
    grade = "A" if score >= 90 else "B" if score >= 75 else "C" if score >= 60 else "D" if score >= 40 else "F"

    summary = {
        "repo": str(root), "scanned_at": NOW,
        "total_files": len(all_files), "code_files": code_n,
        "config_files": len(all_files) - code_n,
        "total_loc": total_loc, "total_sloc": total_sloc,
        "languages": dict(lang_counts.most_common()),
        "secret_hits": secret_total,
        "secret_high": secret_sev.get("HIGH", 0),
        "secret_medium": secret_sev.get("MEDIUM", 0),
        "insecure_config": len(config_issues),
        "insecure_config_high": config_sev.get("HIGH", 0),
        "validation_errors": len(validation_errors),
        "dangerous_calls": danger_total, "code_smells": smell_total,
        "high_complexity_fns": len(high_complex),
        "bandit_high": len(sev.get("HIGH", [])),
        "bandit_medium": len(sev.get("MEDIUM", [])),
        "bandit_low": len(sev.get("LOW", [])),
        "dependencies_total": dep_total,
        "vulnerable_dependencies": vuln_deps,
        "osv_checked": bool(args.online),
        "thirdparty_findings": tp_total,
        "thirdparty_status": {k: v.get("status") for k, v in thirdparty.items()},
        "licenses": licenses, "score": score, "grade": grade,
    }

    extras = {"config_issues": config_issues, "validation_errors": validation_errors,
              "thirdparty": thirdparty}
    md = build_markdown(summary, files_data, sev, high_complex, inventory, osv, extras)
    (root / "scan_report.md").write_text(md, encoding="utf-8")
    mcp = build_mcp(summary, files_data, sev, high_complex, inventory, osv, extras)
    (root / "scan_report.json").write_text(json.dumps(mcp, indent=2), encoding="utf-8")

    tp_line = "  ".join(f"{k}:{v.get('count', v.get('status'))}" for k, v in thirdparty.items()) or "skipped"
    print(f"""
==================================================
  SCAN COMPLETE   Score: {score}/100  (Grade: {grade})
--------------------------------------------------
  Files in scope : {len(all_files)} ({code_n} code / {len(all_files)-code_n} config)
  SLOC           : {total_sloc:,}
  Secrets        : {secret_total}  (HIGH {secret_sev.get('HIGH',0)} / MED {secret_sev.get('MEDIUM',0)})  <- rotate if > 0
  Insecure config: {len(config_issues)}  (HIGH {config_sev.get('HIGH',0)})
  Malformed JSON/XML: {len(validation_errors)}
  Bandit         : {len(sev.get('HIGH',[]))} HIGH / {len(sev.get('MEDIUM',[]))} MED / {len(sev.get('LOW',[]))} LOW
  Dependencies   : {dep_total}{'  vulnerable: ' + str(vuln_deps) if args.online else '  (OSV skipped; use --online)'}
  Complexity     : {len(high_complex)} fns CC>=10
  Third-party    : {tp_line}
--------------------------------------------------
  scan_report.md   — human report
  scan_report.json — MCP payload
==================================================
""")

# ── Markdown builder ───────────────────────────────────────────────────────────
def build_markdown(s, files, sev, high_cc, inventory, osv, extras):
    L = []; A = L.append
    A("# Code Quality & Security Report\n")
    A(f"**Repo:** `{s['repo']}`  \n**Scanned:** {s['scanned_at']}  \n"
      f"**Score:** {s['score']}/100 — Grade **{s['grade']}**\n")
    A("---\n## Summary\n")
    A("| Metric | Value |\n|--------|-------|")
    A(f"| Files scanned (code / config) | {s['total_files']} ({s['code_files']} / {s['config_files']}) |")
    A(f"| Lines of code (LOC / SLOC) | {s['total_loc']:,} / {s['total_sloc']:,} |")
    A(f"| Licenses found | {', '.join(s['licenses'])} |")
    A(f"| **Secret/credential hits** | **{s['secret_hits']}** (HIGH {s['secret_high']} / MED {s['secret_medium']}) |")
    A(f"| **Insecure config settings** | **{s['insecure_config']}** (HIGH {s['insecure_config_high']}) |")
    A(f"| Malformed JSON/XML files | {s['validation_errors']} |")
    A(f"| Dependencies inventoried | {s['dependencies_total']} |")
    A(f"| Vulnerable dependencies | {'**' + str(s['vulnerable_dependencies']) + '**' if s['osv_checked'] else 'not checked (use --online)'} |")
    A(f"| Dangerous call patterns | {s['dangerous_calls']} |")
    A(f"| Code smells (TODO/FIXME/...) | {s['code_smells']} |")
    A(f"| High-complexity functions (CC>=10) | {s['high_complexity_fns']} |")
    A(f"| Bandit HIGH / MED / LOW | {s['bandit_high']} / {s['bandit_medium']} / {s['bandit_low']} |")
    A(f"| Third-party findings | {s['thirdparty_findings']} ({', '.join(f'{k}: {v}' for k,v in s['thirdparty_status'].items()) or 'skipped'}) |")

    A("\n## Language Breakdown\n")
    A("| Language | Files |\n|----------|-------|")
    for lang, cnt in sorted(s['languages'].items(), key=lambda x: -x[1]):
        A(f"| {lang} | {cnt} |")

    A("\n## Secrets & Credentials\n")
    secret_files = [(f['path'], f['secrets']) for f in files if f.get('secrets')]
    if not secret_files:
        A("No secret/credential patterns detected.\n")
    else:
        A(f"**{s['secret_hits']} potential secret(s) found — review and rotate.**\n")
        for path, hits in sorted(secret_files,
                                 key=lambda x: -sum(1 for h in x[1] if h.get('severity') == 'HIGH')):
            A(f"\n### `{path}`")
            for h in hits:
                sevtag = h.get("severity", "LOW")
                A(f"- **[{sevtag}] Line {h['line']}** {h['type']}: `{h['preview']}`")

    A("\n## Insecure Configuration\n")
    cfg = extras.get("config_issues", [])
    if not cfg:
        A("No insecure-configuration patterns detected.\n")
    else:
        order = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}
        A("| Severity | Issue | Location | Recommendation |\n|----------|-------|----------|----------------|")
        for c in sorted(cfg, key=lambda x: order.get(x["severity"], 9))[:60]:
            A(f"| {c['severity']} | {c['issue']} | `{c['path']}`:{c['line']} | {c['recommendation']} |")

    A("\n## Malformed JSON / XML\n")
    verr = extras.get("validation_errors", [])
    if not verr:
        A("All scanned JSON/XML files parsed successfully.\n")
    else:
        for v in verr[:50]:
            A(f"- `{v['path']}` ({v['format']}): {v['error']}")

    A("\n## Dependency Vulnerabilities\n")
    if not s['osv_checked']:
        A("OSV check not run. Re-run with `--online` to match dependencies against the OSV vulnerability database.\n")
    elif osv.get("error"):
        A(f"OSV check failed: {osv['error']}\n")
    elif not osv.get("findings"):
        A("No known vulnerabilities found for inventoried dependencies.\n")
    else:
        A("| Ecosystem | Package | Version | Vulns | IDs |\n|-----------|---------|---------|-------|-----|")
        for f in sorted(osv["findings"], key=lambda x: -x["count"]):
            ids = ", ".join(f["vuln_ids"][:5]) + ("…" if len(f["vuln_ids"]) > 5 else "")
            A(f"| {f['ecosystem']} | `{f['name']}` | {f['version']} | {f['count']} | {ids} |")

    A("\n## Dangerous Call Patterns\n")
    danger_files = [(f['path'], f['dangerous']) for f in files if f.get('dangerous')]
    if not danger_files:
        A("No dangerous call patterns found.\n")
    else:
        for path, hits in danger_files[:40]:
            A(f"\n### `{path}`")
            for h in hits[:10]:
                A(f"- **Line {h['line']}** `{h['pattern']}` -> `{h['preview']}`")

    A("\n## Bandit Static Analysis (Python)\n")
    any_bandit = False
    for level in ["HIGH", "MEDIUM", "LOW"]:
        items = sev.get(level, [])
        if not items: continue
        any_bandit = True
        A(f"\n### {level} ({len(items)} issues)\n")
        for r in items[:20]:
            A(f"- **{r.get('test_id','?')}** {r.get('issue_text','')}  ")
            A(f"  `{r.get('filename','?')}` line {r.get('line_number','?')} — confidence {r.get('issue_confidence','?')}")
    if not any_bandit:
        A("No Python issues (or bandit skipped — this is a Java/JS-heavy repo).\n")

    A("\n## Third-Party Tool Findings\n")
    tp = extras.get("thirdparty", {})
    if not tp:
        A("Third-party tools skipped.\n")
    else:
        for tool, res in tp.items():
            status = res.get("status", "?")
            A(f"\n### {tool} — {status}" + (f" ({res.get('count')} findings)" if res.get("count") else ""))
            for f in res.get("findings", [])[:25]:
                loc = f.get("file") or f.get("path") or "?"
                ln = f.get("line", "?")
                desc = f.get("message") or f.get("description") or \
                       f"{f.get('component','')} {f.get('version','')}".strip()
                tag = f.get("severity") or f.get("rule") or ""
                A(f"- [{tag}] `{loc}`:{ln} — {desc}")

    A("\n## High Complexity Functions (CC >= 10)\n")
    if not high_cc:
        A("None detected.\n")
    else:
        A("| File | Function | Complexity | Rank |\n|------|----------|------------|------|")
        for f in sorted(high_cc, key=lambda x: -x['complexity'])[:40]:
            A(f"| `{f['file']}` | `{f['name']}` | {f['complexity']} | {f['rank']} |")

    A("\n## Dependency Inventory\n")
    if not inventory:
        A("No dependency manifests found.\n")
    else:
        for manifest, deps in sorted(inventory.items()):
            A(f"\n### `{manifest}` ({len(deps)} deps)")
            for d in deps[:60]:
                A(f"- {d['ecosystem']}: `{d['name']}` @ {d.get('version') or '?'}")
            if len(deps) > 60:
                A(f"- … and {len(deps)-60} more")

    A("\n---\n*Generated by repo_scanner.py*\n")
    return "\n".join(L)

# ── MCP JSON builder ───────────────────────────────────────────────────────────
def build_mcp(s, files, sev, high_cc, inventory, osv, extras):
    file_findings = [{
        "path": f["path"], "kind": f["kind"], "lang": f["lang"],
        "metrics": f["metrics"], "maintainability": f["mi_score"],
        "secret_hits": f["secrets"], "dangerous_calls": f["dangerous"],
        "high_complexity": [fn for fn in f["complexity"] if fn["complexity"] >= 10],
    } for f in files if f.get("secrets") or f.get("dangerous")
        or any(fn["complexity"] >= 10 for fn in f.get("complexity", []))]

    bandit_top = sorted(sev.get("HIGH", []) + sev.get("MEDIUM", []),
                        key=lambda r: (r.get("issue_severity", ""), -r.get("line_number", 0)))[:50]

    system_prompt = textwrap.dedent("""
        You are a senior software security and quality engineer reviewing a
        static-analysis scan of a Java + JavaScript monorepo that stores model
        definitions and configuration in JSON/XML/.properties files.
        1. Identify the most critical issues, prioritized by risk (lead with
           exposed secrets and vulnerable dependencies).
        2. Give concrete remediation steps per issue class.
        3. Note architectural / design-level quality concerns.
        4. Provide an executive summary for an engineering lead.
        Be concise, specific, actionable. Cite file paths and line numbers.
    """).strip()

    user_text = textwrap.dedent(f"""
        Review the following repository scan results.

        **Repository:** {s['repo']}
        **Scanned at:** {s['scanned_at']}
        **Overall Score:** {s['score']}/100 (Grade: {s['grade']})

        ## Key Metrics
        - Files: {s['total_files']} ({s['code_files']} code / {s['config_files']} config)
        - LOC: {s['total_loc']:,} | SLOC: {s['total_sloc']:,}
        - Languages: {', '.join(f"{k}({v})" for k,v in s['languages'].items())}
        - Secrets: {s['secret_hits']} (HIGH {s['secret_high']} / MED {s['secret_medium']})
        - Insecure config: {s['insecure_config']} (HIGH {s['insecure_config_high']}) | Malformed JSON/XML: {s['validation_errors']}
        - Dependencies: {s['dependencies_total']} | Vulnerable: {s['vulnerable_dependencies']} (OSV checked: {s['osv_checked']})
        - Dangerous calls: {s['dangerous_calls']} | Code smells: {s['code_smells']}
        - Bandit HIGH/MED/LOW: {s['bandit_high']}/{s['bandit_medium']}/{s['bandit_low']}
        - High-complexity functions: {s['high_complexity_fns']}
        - Third-party findings: {s['thirdparty_findings']} ({s['thirdparty_status']})
        - Licenses: {', '.join(s['licenses'])}
    """).strip()

    docs = [
        ("summary", s),
        ("file_findings", file_findings),
        ("insecure_config", extras.get("config_issues", [])),
        ("validation_errors", extras.get("validation_errors", [])),
        ("bandit_high_medium", bandit_top),
        ("high_complexity_functions", high_cc[:50]),
        ("dependency_inventory", inventory),
        ("dependency_vulnerabilities", osv.get("findings", [])),
        ("thirdparty", extras.get("thirdparty", {})),
    ]
    return {
        "mcp_version": "1.0", "schema": "repo_scan_v2", "created_at": NOW,
        "tool": "repo_scanner.py",
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": [{"type": "text", "text": user_text}] + [
                {"type": "document", "title": t, "mime_type": "application/json", "data": d}
                for t, d in docs]},
        ],
        "flat": {t: d for t, d in docs},
    }

if __name__ == "__main__":
    main()
