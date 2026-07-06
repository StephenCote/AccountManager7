#!/usr/bin/env python3
"""
Fetch the Claude Code documentation pages needed to build the loop-engineering system.

Usage:
    python fetch_claude_docs.py

Requires:
    pip install requests    (only dependency; uses stdlib otherwise)

What it does:
    - Tries several known URL patterns for each doc page (docs.claude.com and
      code.claude.com, with and without a trailing .md).
    - Saves whatever it successfully downloads into ./claude_docs/<slug>.md
    - Writes ./claude_docs/_ALL.md  = every page concatenated (easy to paste back).
    - Prints a summary table of what worked.

After running, either:
    (a) paste the contents of claude_docs/_ALL.md back into the chat, or
    (b) connect the folder containing claude_docs/ so I can read it directly.
"""

import os
import sys
import time

try:
    import requests
except ImportError:
    sys.exit("Please run:  pip install requests   then re-run this script.")

# Doc pages I want, by slug. Order = priority.
SLUGS = [
    "hooks",              # hook events + JSON structure (most error-prone)
    "hooks-guide",        # practical hook examples
    "cli-reference",      # all CLI flags: -p, --output-format, --permission-mode, etc.
    "slash-commands",     # custom command frontmatter + $ARGUMENTS
    "sub-agents",         # subagent frontmatter + behavior
    "settings",           # settings.json + permissions allow/deny
    "iam",                # permission rules / tool patterns
    "memory",             # CLAUDE.md locations + @import syntax
    "headless",           # non-interactive usage
    "sdk",                # Agent SDK / programmatic loops
    "github-actions",     # @claude GitHub integration
    "common-workflows",   # explore-plan-code, TDD loop best practices
    # --- second batch: migration / VS Code / packaging / SDK ---
    "commands",           # built-in slash commands incl. /init details
    "permissions",        # permission rule syntax (Bash(...), Edit(...))
    "permission-modes",   # default/acceptEdits/plan/dontAsk/bypassPermissions
    "large-codebases",    # monorepos, claudeMdExcludes, per-dir CLAUDE.md
    "skills",             # skills (commands merged into skills)
    "plugins",            # packaging the loop system as a plugin
    "plugins-reference",  # plugin manifest / hooks.json / user_config
    "vs-code",            # VS Code extension behavior
    "ide-integrations",   # fallback name for IDE/VS Code page
    "tools-reference",    # Bash tool behavior, background tasks
    "agent-sdk/overview", # custom programmatic loop (Agent SDK)
    "agent-sdk/python",   # Python SDK
    "agent-sdk/typescript", # TypeScript SDK
]

# URL templates tried in order for each slug. {s} = slug.
# Bare /en/{s} templates are included so slugs outside the claude-code path
# (e.g. "agent-sdk/overview") and pages that live at /en/{s} still resolve.
URL_TEMPLATES = [
    "https://docs.claude.com/en/docs/claude-code/{s}.md",
    "https://docs.claude.com/en/docs/claude-code/{s}",
    "https://docs.claude.com/en/{s}.md",
    "https://docs.claude.com/en/{s}",
    "https://code.claude.com/docs/en/{s}.md",
    "https://code.claude.com/docs/en/{s}",
    "https://docs.anthropic.com/en/docs/claude-code/{s}.md",
    "https://docs.anthropic.com/en/docs/claude-code/{s}",
]

HEADERS = {
    "User-Agent": "Mozilla/5.0 (compatible; doc-fetch/1.0)",
    "Accept": "text/markdown, text/html;q=0.9, */*;q=0.8",
}

OUT_DIR = os.path.join(os.getcwd(), "claude_docs")


def fetch(url: str):
    try:
        r = requests.get(url, headers=HEADERS, timeout=30, allow_redirects=True)
        if r.status_code == 200 and r.text.strip():
            return r.text
    except requests.RequestException:
        pass
    return None


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    results = []
    combined = []

    for slug in SLUGS:
        text = None
        used = None
        for tmpl in URL_TEMPLATES:
            url = tmpl.format(s=slug)
            text = fetch(url)
            if text:
                used = url
                break
            time.sleep(0.3)

        if text:
            safe = slug.replace("/", "__")  # e.g. agent-sdk/overview -> agent-sdk__overview
            path = os.path.join(OUT_DIR, f"{safe}.md")
            with open(path, "w", encoding="utf-8") as f:
                f.write(f"<!-- source: {used} -->\n\n{text}")
            combined.append(f"\n\n{'='*80}\n# PAGE: {slug}\n# source: {used}\n{'='*80}\n\n{text}")
            results.append((slug, "OK", used))
            print(f"[ OK ] {slug:18s} <- {used}")
        else:
            results.append((slug, "FAIL", "-"))
            print(f"[FAIL] {slug:18s} (no URL pattern worked)")

    if combined:
        all_path = os.path.join(OUT_DIR, "_ALL.md")
        with open(all_path, "w", encoding="utf-8") as f:
            f.write("".join(combined))
        print(f"\nCombined file written to: {all_path}")

    ok = sum(1 for _, s, _ in results if s == "OK")
    print(f"\nDone. {ok}/{len(SLUGS)} pages fetched into: {OUT_DIR}")
    if ok < len(SLUGS):
        print("For any FAILs, the doc may have moved. You can browse")
        print("https://docs.claude.com/en/docs/claude-code and tell me the correct slug.")


if __name__ == "__main__":
    main()
