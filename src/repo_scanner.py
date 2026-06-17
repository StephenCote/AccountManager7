#!/usr/bin/env python3
"""
repo_scan.py — Comprehensive code quality & security scanner
Run from the root of any repository.

Outputs:
  - scan_report.md      Human-readable report
  - scan_report.json    MCP-formatted payload for AI service submission

Dependencies (auto-installed if missing):
  pip install bandit radon pygments
"""

import os, sys, json, re, subprocess, hashlib, datetime, collections, pathlib, textwrap

# ── Auto-install light deps ────────────────────────────────────────────────────
def ensure(pkg, import_as=None):
    try:
        __import__(import_as or pkg)
    except ImportError:
        subprocess.check_call([sys.executable, "-m", "pip", "install", pkg, "-q"])

for pkg, imp in [("bandit", None), ("radon", None), ("pygments", None)]:
    ensure(pkg, imp)

import radon.complexity as radon_cc
import radon.metrics    as radon_mi
import radon.raw        as radon_raw

# ── Config ─────────────────────────────────────────────────────────────────────
SKIP_DIRS   = {"node_modules", ".git", "__pycache__", ".venv", "venv",
               "dist", "build", ".next", ".nuxt", "coverage", ".nyc_output"}
CODE_EXTS   = {".py", ".js", ".ts", ".jsx", ".tsx", ".java", ".go", ".rb",
               ".php", ".cs", ".cpp", ".c", ".h", ".rs", ".swift", ".kt",
               ".scala", ".sh", ".bash", ".zsh", ".ps1", ".sql"}
SECRET_PATTERNS = [
    (r'(?i)(api[_-]?key|apikey)\s*[:=]\s*["\']?([A-Za-z0-9_\-]{16,})',   "API Key"),
    (r'(?i)(secret[_-]?key|secret)\s*[:=]\s*["\']?([A-Za-z0-9_\-]{16,})', "Secret Key"),
    (r'(?i)(password|passwd|pwd)\s*[:=]\s*["\']([^"\']{6,})["\']',         "Hardcoded Password"),
    (r'(?i)(token)\s*[:=]\s*["\']?([A-Za-z0-9_\-\.]{20,})',               "Token"),
    (r'(?i)(aws_access_key_id)\s*[:=]\s*([A-Z0-9]{20})',                   "AWS Access Key"),
    (r'(?i)(aws_secret_access_key)\s*[:=]\s*([A-Za-z0-9/+=]{40})',        "AWS Secret"),
    (r'-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----',              "Private Key"),
    (r'(?i)(jdbc|mysql|postgresql|mongodb)://[^@\s]+:[^@\s]+@',            "DB Connection String"),
    (r'(?i)Authorization:\s*Bearer\s+([A-Za-z0-9_\-\.]{20,})',            "Bearer Token"),
    (r'(?:^|[^a-zA-Z0-9])([A-Za-z0-9+/]{40})(?:[^a-zA-Z0-9=]|$)',        "Possible Base64 Secret (40+ chars)"),
]
DANGEROUS_CALLS = {
    "py":  ["eval(", "exec(", "os.system(", "subprocess.call(", "pickle.loads(",
            "__import__(", "compile(", "globals()[", "locals()["],
    "js":  ["eval(", "Function(", "innerHTML =", "document.write(",
            "setTimeout(", "setInterval(", "dangerouslySetInnerHTML"],
    "generic": ["TODO", "FIXME", "HACK", "XXX", "NOSONAR"],
}

ROOT = pathlib.Path(".").resolve()
NOW  = datetime.datetime.utcnow().isoformat() + "Z"

# ── Helpers ────────────────────────────────────────────────────────────────────
def walk_code_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fname in filenames:
            p = pathlib.Path(dirpath) / fname
            if p.suffix.lower() in CODE_EXTS:
                yield p

def rel(p):
    try:    return str(p.relative_to(ROOT))
    except: return str(p)

def read_safe(p):
    try:
        return p.read_text(encoding="utf-8", errors="replace")
    except Exception:
        return ""

def file_hash(p):
    try:    return hashlib.md5(p.read_bytes()).hexdigest()[:8]
    except: return "?"

def lang_from_ext(ext):
    return {".py":"python",".js":"javascript",".ts":"typescript",
            ".jsx":"javascript",".tsx":"typescript",".java":"java",
            ".go":"go",".rb":"ruby",".php":"php",".cs":"csharp",
            ".cpp":"cpp",".c":"c",".h":"c",".rs":"rust",
            ".swift":"swift",".kt":"kotlin",".sh":"shell",
            ".bash":"shell",".sql":"sql"}.get(ext.lower(), "other")

# ── Scanners ───────────────────────────────────────────────────────────────────
def scan_secrets(path, content):
    hits = []
    for i, line in enumerate(content.splitlines(), 1):
        for pat, label in SECRET_PATTERNS:
            if re.search(pat, line):
                # Redact the actual value from the report
                redacted = re.sub(r'(["\'])[^"\']{4,}(["\'])', r'\g<1>***REDACTED***\g<2>', line.strip())
                hits.append({"line": i, "type": label, "preview": redacted[:120]})
    return hits

def scan_dangerous(path, content):
    hits = []
    ext  = path.suffix.lower()
    checks = DANGEROUS_CALLS.get("py" if ext == ".py" else "js" if ext in (".js",".ts",".jsx",".tsx") else "generic", [])
    checks += DANGEROUS_CALLS["generic"]
    for i, line in enumerate(content.splitlines(), 1):
        for pat in checks:
            if pat in line:
                hits.append({"line": i, "pattern": pat, "preview": line.strip()[:120]})
    return hits

def scan_complexity(path, content):
    if path.suffix != ".py":
        return [], None
    try:
        blocks  = radon_cc.cc_visit(content)
        mi_score = radon_mi.mi_visit(content, True)
        funcs   = [{"name": b.name, "complexity": b.complexity,
                    "rank": radon_cc.cc_rank(b.complexity),
                    "line": b.lineno} for b in blocks]
        return funcs, round(mi_score, 2)
    except Exception:
        return [], None

def scan_raw_metrics(path, content):
    if path.suffix != ".py":
        lines = content.splitlines()
        blank = sum(1 for l in lines if not l.strip())
        return {"loc": len(lines), "sloc": len(lines) - blank, "blank": blank, "comments": 0}
    try:
        r = radon_raw.analyze(content)
        return {"loc": r.loc, "sloc": r.sloc, "blank": r.blank, "comments": r.comments}
    except Exception:
        lines = content.splitlines()
        return {"loc": len(lines), "sloc": len(lines), "blank": 0, "comments": 0}

def run_bandit(root):
    """Run bandit on the whole repo (Python only)."""
    try:
        result = subprocess.run(
            ["bandit", "-r", str(root), "-x", "node_modules,venv,.venv",
             "-f", "json", "-q"],
            capture_output=True, text=True, timeout=120
        )
        data = json.loads(result.stdout or "{}")
        return data.get("results", []), data.get("metrics", {})
    except Exception as e:
        return [], {"error": str(e)}

def detect_licenses(root):
    """Look for common license files."""
    found = []
    for name in ["LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "license"]:
        p = root / name
        if p.exists():
            first = p.read_text(errors="replace")[:200]
            for ltype in ["MIT","Apache","GPL","BSD","ISC","MPL","LGPL","AGPL","Unlicense","CC0"]:
                if ltype in first:
                    found.append(ltype); break
            else:
                found.append("Custom/Unknown")
    return found or ["Not found"]

def detect_dependencies(root):
    """Collect dependency manifests."""
    manifests = {}
    for name in ["package.json","requirements.txt","Pipfile","pyproject.toml",
                 "go.mod","Gemfile","pom.xml","build.gradle","Cargo.toml"]:
        p = root / name
        if p.exists():
            manifests[name] = p.read_text(errors="replace")[:2000]
    return manifests

# ── Main scan ──────────────────────────────────────────────────────────────────
def main():
    print(f"\n🔍  Scanning {ROOT} …\n")

    files_data   = []
    total_loc    = 0
    total_sloc   = 0
    secret_total = 0
    danger_total = 0
    lang_counts  = collections.Counter()
    high_complex = []

    all_files = list(walk_code_files())
    print(f"   Found {len(all_files)} code files (node_modules excluded)")

    for i, path in enumerate(all_files, 1):
        if i % 50 == 0:
            print(f"   … {i}/{len(all_files)}")

        content  = read_safe(path)
        ext      = path.suffix.lower()
        lang     = lang_from_ext(ext)
        metrics  = scan_raw_metrics(path, content)
        secrets  = scan_secrets(path, content)
        dangers  = scan_dangerous(path, content)
        funcs, mi = scan_complexity(path, content)

        lang_counts[lang] += 1
        total_loc  += metrics["loc"]
        total_sloc += metrics["sloc"]
        secret_total += len(secrets)
        danger_total += len(dangers)

        high_cc = [f for f in funcs if f["complexity"] >= 10]
        if high_cc:
            high_complex.extend([{**f, "file": rel(path)} for f in high_cc])

        files_data.append({
            "path":       rel(path),
            "hash":       file_hash(path),
            "lang":       lang,
            "metrics":    metrics,
            "mi_score":   mi,
            "secrets":    secrets,
            "dangerous":  dangers,
            "complexity": funcs,
        })

    print("   Running Bandit (Python static security analysis) …")
    bandit_results, bandit_metrics = run_bandit(ROOT)
    licenses    = detect_licenses(ROOT)
    deps        = detect_dependencies(ROOT)

    # ── Severity buckets ──────────────────────────────────────────────────────
    sev = {"HIGH": [], "MEDIUM": [], "LOW": []}
    for r in bandit_results:
        sev_key = r.get("issue_severity", "LOW").upper()
        sev.setdefault(sev_key, []).append(r)

    # ── Score (0–100) ─────────────────────────────────────────────────────────
    score = 100
    score -= min(40, len(sev.get("HIGH",[])) * 10)
    score -= min(20, len(sev.get("MEDIUM",[])) * 3)
    score -= min(15, secret_total * 5)
    score -= min(10, len(high_complex) * 2)
    score -= min(5,  danger_total)
    score  = max(0, score)

    grade = "A" if score >= 90 else "B" if score >= 75 else "C" if score >= 60 else "D" if score >= 40 else "F"

    summary = {
        "repo":             str(ROOT),
        "scanned_at":       NOW,
        "total_files":      len(all_files),
        "total_loc":        total_loc,
        "total_sloc":       total_sloc,
        "languages":        dict(lang_counts.most_common()),
        "secret_hits":      secret_total,
        "dangerous_calls":  danger_total,
        "high_complexity_fns": len(high_complex),
        "bandit_high":      len(sev.get("HIGH",[])),
        "bandit_medium":    len(sev.get("MEDIUM",[])),
        "bandit_low":       len(sev.get("LOW",[])),
        "licenses":         licenses,
        "score":            score,
        "grade":            grade,
    }

    # ── Write Markdown report ─────────────────────────────────────────────────
    md = build_markdown(summary, files_data, sev, high_complex, deps, bandit_metrics)
    pathlib.Path("scan_report.md").write_text(md, encoding="utf-8")

    # ── Write MCP JSON payload ────────────────────────────────────────────────
    mcp = build_mcp(summary, files_data, sev, high_complex, deps)
    pathlib.Path("scan_report.json").write_text(json.dumps(mcp, indent=2), encoding="utf-8")

    # ── Terminal summary ──────────────────────────────────────────────────────
    print(f"""
╔══════════════════════════════════════════════╗
║           SCAN COMPLETE                      ║
╠══════════════════════════════════════════════╣
║  Score   : {score}/100  (Grade: {grade})             
║  Files   : {len(all_files)}
║  SLOC    : {total_sloc:,}
║  Secrets : {secret_total}   ← fix immediately if > 0
║  Bandit  : {len(sev.get('HIGH',[]))} HIGH / {len(sev.get('MEDIUM',[]))} MEDIUM / {len(sev.get('LOW',[]))} LOW
║  Complex : {len(high_complex)} functions CC≥10
╠══════════════════════════════════════════════╣
║  📄 scan_report.md   — human report          ║
║  📦 scan_report.json — MCP payload           ║
╚══════════════════════════════════════════════╝
""")

# ── Markdown builder ───────────────────────────────────────────────────────────
def build_markdown(s, files, sev, high_cc, deps, b_metrics):
    lines = []
    A = lines.append

    A(f"# 🔍 Code Quality & Security Report\n")
    A(f"**Repo:** `{s['repo']}`  \n**Scanned:** {s['scanned_at']}  \n**Score:** {s['score']}/100 — Grade **{s['grade']}**\n")
    A("---\n## 📊 Summary\n")
    A(f"| Metric | Value |")
    A(f"|--------|-------|")
    A(f"| Total files scanned | {s['total_files']} |")
    A(f"| Lines of code (LOC) | {s['total_loc']:,} |")
    A(f"| Source lines (SLOC) | {s['total_sloc']:,} |")
    A(f"| Licenses found | {', '.join(s['licenses'])} |")
    A(f"| Secret/credential hits | **{s['secret_hits']}** |")
    A(f"| Dangerous call patterns | {s['dangerous_calls']} |")
    A(f"| High-complexity functions (CC≥10) | {s['high_complexity_fns']} |")
    A(f"| Bandit HIGH severity | **{s['bandit_high']}** |")
    A(f"| Bandit MEDIUM severity | {s['bandit_medium']} |")
    A(f"| Bandit LOW severity | {s['bandit_low']} |")

    A("\n## 🌐 Language Breakdown\n")
    A("| Language | Files |")
    A("|----------|-------|")
    for lang, cnt in sorted(s['languages'].items(), key=lambda x: -x[1]):
        A(f"| {lang} | {cnt} |")

    A("\n## 🔐 Secrets & Credentials\n")
    secret_files = [(f['path'], f['secrets']) for f in files if f['secrets']]
    if not secret_files:
        A("✅ No secret/credential patterns detected.\n")
    else:
        A(f"⚠️  **{s['secret_hits']} potential secret(s) found — review and rotate immediately.**\n")
        for path, hits in secret_files:
            A(f"\n### `{path}`")
            for h in hits:
                A(f"- **Line {h['line']}** [{h['type']}]: `{h['preview']}`")

    A("\n## ⚠️  Dangerous Call Patterns\n")
    danger_files = [(f['path'], f['dangerous']) for f in files if f['dangerous']]
    if not danger_files:
        A("✅ No dangerous call patterns found.\n")
    else:
        for path, hits in danger_files[:30]:  # cap at 30 files
            A(f"\n### `{path}`")
            for h in hits[:10]:
                A(f"- **Line {h['line']}** `{h['pattern']}` → `{h['preview']}`")

    A("\n## 🧠 Bandit Static Analysis (Python)\n")
    for sev_level in ["HIGH", "MEDIUM", "LOW"]:
        items = sev.get(sev_level, [])
        if not items: continue
        A(f"\n### {sev_level} ({len(items)} issues)\n")
        for r in items[:20]:
            A(f"- **{r.get('test_id','?')}** {r.get('issue_text','')}  ")
            A(f"  `{r.get('filename','?')}` line {r.get('line_number','?')}  ")
            A(f"  Confidence: {r.get('issue_confidence','?')}\n")

    A("\n## 🔁 High Complexity Functions (CC ≥ 10)\n")
    if not high_cc:
        A("✅ No high-complexity functions detected.\n")
    else:
        A("| File | Function | Complexity | Rank |")
        A("|------|----------|------------|------|")
        for f in sorted(high_cc, key=lambda x: -x['complexity'])[:40]:
            A(f"| `{f['file']}` | `{f['name']}` | {f['complexity']} | {f['rank']} |")

    A("\n## 📦 Dependency Manifests\n")
    if not deps:
        A("No dependency manifests found at repo root.\n")
    else:
        for name, content in deps.items():
            A(f"\n### `{name}`\n```\n{content[:800]}\n```")

    A("\n## 📁 Per-File Details (top 50 by issue count)\n")
    ranked = sorted(files, key=lambda f: len(f['secrets'])*10 + len(f['dangerous']), reverse=True)
    for f in ranked[:50]:
        issues = len(f['secrets'])*10 + len(f['dangerous'])
        if issues == 0 and not f['secrets'] and not f['dangerous']: continue
        A(f"\n### `{f['path']}`")
        A(f"- Lang: {f['lang']} | LOC: {f['metrics']['loc']} | SLOC: {f['metrics']['sloc']}")
        if f['mi_score'] is not None:
            mi = f['mi_score']
            rank = "A" if mi >= 100 else "B" if mi >= 80 else "C" if mi >= 60 else "D" if mi >= 40 else "F"
            A(f"- Maintainability Index: {mi} ({rank})")
        if f['secrets']:
            A(f"- 🔴 {len(f['secrets'])} secret pattern(s)")
        if f['dangerous']:
            A(f"- ⚠️  {len(f['dangerous'])} dangerous call(s)")

    A("\n---\n*Generated by repo_scan.py*\n")
    return "\n".join(lines)

# ── MCP JSON builder ───────────────────────────────────────────────────────────
def build_mcp(s, files, sev, high_cc, deps):
    """
    MCP-formatted payload suitable for submission to an AI service.
    Follows the pattern: role=user, content blocks with type=text and type=document.
    """
    # Compact per-file issue list (only files with findings)
    file_findings = []
    for f in files:
        if f['secrets'] or f['dangerous'] or any(fn['complexity'] >= 10 for fn in f['complexity']):
            file_findings.append({
                "path":              f['path'],
                "lang":              f['lang'],
                "metrics":           f['metrics'],
                "maintainability":   f['mi_score'],
                "secret_hits":       f['secrets'],
                "dangerous_calls":   f['dangerous'],
                "high_complexity":   [fn for fn in f['complexity'] if fn['complexity'] >= 10],
            })

    bandit_top = sorted(
        sev.get("HIGH", []) + sev.get("MEDIUM", []),
        key=lambda r: (r.get("issue_severity",""), -r.get("line_number", 0))
    )[:50]

    system_prompt = textwrap.dedent("""
        You are a senior software security and quality engineer.
        You have been given a static analysis scan of a code repository.
        Your job is to:
        1. Identify the most critical issues to fix, prioritized by risk.
        2. Suggest concrete remediation steps for each issue class.
        3. Note any architectural or design-level quality concerns.
        4. Provide an executive summary suitable for a CTO/engineering lead.
        5. Recommend tooling or process improvements.
        Be concise, specific, and actionable. Cite file paths and line numbers where available.
    """).strip()

    user_text = textwrap.dedent(f"""
        Please review the following repository scan results and provide your analysis.

        **Repository:** {s['repo']}
        **Scanned at:** {s['scanned_at']}
        **Overall Score:** {s['score']}/100 (Grade: {s['grade']})

        ## Key Metrics
        - Files: {s['total_files']} | LOC: {s['total_loc']:,} | SLOC: {s['total_sloc']:,}
        - Languages: {', '.join(f"{k}({v})" for k,v in s['languages'].items())}
        - Secrets detected: {s['secret_hits']}
        - Dangerous calls: {s['dangerous_calls']}
        - Bandit HIGH: {s['bandit_high']} | MEDIUM: {s['bandit_medium']} | LOW: {s['bandit_low']}
        - High-complexity functions: {s['high_complexity_fns']}
        - Licenses: {', '.join(s['licenses'])}
    """).strip()

    return {
        "mcp_version":   "1.0",
        "schema":        "repo_scan_v1",
        "created_at":    NOW,
        "tool":          "repo_scan.py",
        "messages": [
            {
                "role":    "system",
                "content": system_prompt,
            },
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": user_text,
                    },
                    {
                        "type":      "document",
                        "title":     "summary",
                        "mime_type": "application/json",
                        "data":      s,
                    },
                    {
                        "type":      "document",
                        "title":     "file_findings",
                        "mime_type": "application/json",
                        "data":      file_findings,
                    },
                    {
                        "type":      "document",
                        "title":     "bandit_high_medium",
                        "mime_type": "application/json",
                        "data":      bandit_top,
                    },
                    {
                        "type":      "document",
                        "title":     "high_complexity_functions",
                        "mime_type": "application/json",
                        "data":      high_cc[:50],
                    },
                    {
                        "type":      "document",
                        "title":     "dependency_manifests",
                        "mime_type": "application/json",
                        "data":      {k: v[:500] for k, v in deps.items()},
                    },
                ],
            }
        ],
        # Convenience: flat payload for APIs that don't use messages format
        "flat": {
            "summary":                 s,
            "file_findings":           file_findings,
            "bandit_high_medium":      bandit_top,
            "high_complexity_functions": high_cc[:50],
            "dependency_manifests":    {k: v[:500] for k, v in deps.items()},
        }
    }

if __name__ == "__main__":
    main()
