# SQLite memory store — complete setup runbook

A project memory store: SQLite for storage, FTS5 for keyword search, sqlite-vec for
semantic search. The knowledge is committed with the repo — as a diffable text export, not
the binary DB (section 9) — so anyone who clones it inherits the context.

This file is self-contained. Everything needed to stand this up in another project is
here — exact commands, the design constraints behind each choice, and the failure modes
that are non-obvious. You should not need to re-derive anything.

---

## 1. File inventory

`Commit?` is about this project's repo. `Port?` is whether to copy it when standing the
store up in a *different* project (section 4) — the two are not the same question, and
copying a `Port? No` file is how one project ends up carrying another's state.

| File | Role | Commit? | Port? |
|---|---|---|---|
| `memory.db` | **Store of record.** Memories, links, FTS5 index, raw embeddings. Stock SQLite — no extension needed to read. | **No** — gitignored here; the text export is committed instead (section 9) | **No** — each project builds its own |
| `memories.sql` | **The committed form of the memories** — text dump of `memories`/`links`/`memory_files`, written by `export.ps1`. Diffable and mergeable, unlike the binary. | **Yes** | **No** — each project exports its own |
| `export.ps1` | Writes `memories.sql`; `-Verify` proves it round-trips into a temp DB | Yes | Yes |
| `schema.sql` | Schema. Idempotent; safe to re-run on an existing DB. | Yes | Yes |
| `mem.ps1` | Memories: `set` `get` `search` `list` `delete` `index` `todo` `supersede` `deprecate` `revive` `files` | Yes | Yes |
| `vec.ps1` | Vector ops: `store` `embed` `index` `search` `status` `provider` | Yes | Yes |
| `memconfig.ps1` | Shared provider-config resolution, dot-sourced by `vec.ps1` and `mine-transcripts.ps1`. **`vec.ps1` throws without it.** | Yes | Yes |
| `import.ps1` | Migrate existing markdown memories into the DB (section 5) | Yes | Yes |
| `migrate.ps1` | Bring an existing `memory.db` up to the current schema. Idempotent; backs up first. | Yes | Yes |
| `mine-transcripts.ps1` | Extract memories from Claude Code session transcripts via an LLM (section 5b) | Yes | Yes |
| `embed-azure.ps1` | Embedding provider — Azure OpenAI | Yes | Yes |
| `embed-ollama.ps1` | Embedding provider — Ollama, local, no API key | Yes | Yes |
| `embed-local.ps1` | Embedding provider — AM7-style local service, no API key | Yes | Yes |
| `hook-session-start.ps1` | SessionStart hook — injects the memory index into context so the lookup is never missed | Yes | Yes |
| `hook-user-prompt.ps1` | UserPromptSubmit hook — searches the store against each prompt (semantic + FTS) and injects hits, plus the end-of-turn write reminder. Logs every invocation to `hook.log`. | Yes | Yes |
| `hook-stop-memory.ps1` | Stop hook — the **write-side gate**. Blocks once per session if files changed but the store did not. `MEMORY_GATE=off` disables. | Yes | Yes |
| `.gitignore` | Excludes `memory.db`, WAL sidecars, `vectors.db`, `embed.config`, credential files. | Yes | Yes |
| `.gitattributes` | Pins `-text` on `memories.sql`/`MEMORY.md`/`*.ps1` so `core.autocrlf` cannot rewrite the committed export (gotcha #31). Store-scoped. | Yes | Yes |
| `SETUP.md` | This file. | Yes | Yes |
| `MEMORY.md` | **Generated** recall index. Never hand-edit. | Yes | No — `mem.ps1 index` writes it |
| `vectors.db` | **Generated** sqlite-vec KNN index. Rebuildable; binary; would conflict on merge. | **No** (gitignored) | No |
| `embed.config` | Provider config for **this machine** (section 7). Optional; the user-level copy usually covers it. | **No** (gitignored) | No |

**Never commit:** API keys, `memory.db` (commit `memories.sql` instead — section 9),
`memory.db-wal`, `memory.db-shm`, `vectors.db`, `embed.config`.

**So `memory.db` is local-only.** Run `export.ps1` before committing, or new memories exist
nowhere shared — and a `git clean -xdf` would take the only copy with it.

Three files outside this directory complete the wiring, all committed:

| File | Role |
|---|---|
| `<root>\CLAUDE.md` | Auto-loaded instruction: use the DB, never write loose `.md` memories |
| `<root>\.claude\settings.json` | `SessionStart` hook (injects the index) + MCP write-tool denials |
| `<root>\.mcp.json` | Registers an `mcp-sqlite` server against `memory.db`. **Not read-only** — see gotcha #25; writes are blocked by `settings.json`, not by the server |

**Four mechanisms make the store actually get used**, in increasing reliability:
the generated `MEMORY.md` header (auto-loaded, but only advisory), `CLAUDE.md` (the
conventional durable instruction), the `SessionStart` and `UserPromptSubmit` hooks
(harness-executed, so they cannot be overlooked), and the `Stop` gate
(`hook-stop-memory.ps1`) — the only one that acts on the **write** side. The first three are
all read-side or advisory; on their own, whether anything ever got *recorded* still came down
to the model choosing to run `mem.ps1`, which is why `memory.db` could sit unchanged through a
whole working session. The gate compares the DB's fingerprint (mtime + row count + latest
`updated`) against a baseline captured at `SessionStart`, and if the git working tree changed
while the DB did not, it returns `decision: "block"` once with instructions to record or to say
explicitly that nothing was worth recording. It blocks **at most once per session**, never
fires on a session that changed no files, is disabled by `MEMORY_GATE=off`, and fails open on
any error — a memory gate must never be able to trap a session. Baselines live in
`.state\<session-id>.json` (gitignored, pruned after 7 days). The hook emits
`hookSpecificOutput.additionalContext` — plain stdout is **not** reliably injected, which
is why it wraps its output in that envelope rather than just running `mem.ps1 list`.

**There is no setting that switches Claude's memory backend to SQLite.** The native
`autoMemoryEnabled` / `autoMemoryDirectory` / `autoDreamEnabled` settings govern the
built-in *file-based* auto-memory, not this store. (`autoMemoryDirectory` is ignored when
set in checked-in project settings, for security.) This store is a convention enforced by
the three mechanisms above.

---

## 2. Design constraints (why it's built this way)

Four decisions, each forced by a real constraint. Changing them re-introduces a bug.

**a. `MEMORY.md` is written to two places.** Claude Code auto-loads `MEMORY.md` from its
own per-project memory directory (`~/.claude/projects/<slug>/memory/`) at session start,
and loads **nothing** from the repo automatically. So the index is written both to the
repo (travels with the DB, human-readable) and to the harness directory (the copy that
actually gets auto-loaded). `<slug>` is derived from the project path by lowercasing the
drive letter and replacing `:`, `\`, `/` **and spaces** with `-`:

```
c:\Projects\ai\Repos\AI Memory  ->  c--Projects-ai-Repos-AI-Memory
c:\Projects\GitHub\AccountManager7  ->  c--Projects-GitHub-AccountManager7
```

**Spaces are the trap.** Until 2026-08-19 the derivation replaced only `:` and `\`,
producing `c--Projects-ai-Repos-AI Memory` — a directory that does not exist. The
`Test-Path` guard then failed, and the harness copy was **silently never written** for
any project whose path contains a space, so the auto-loaded index was permanently
missing while the repo copy looked perfectly current. `mem.ps1` now tries the
space-replacing slug first and falls back to the old form if that directory exists.

`mem.ps1` computes this itself, so it works for anyone who clones to a different path.
Override with `$env:CLAUDE_MEMORY_INDEX`. If no candidate directory exists, only the repo
copy is written — check for both files after `mem.ps1 index` if auto-loading matters.

**The slug is derived from the store's project root, but the harness derives it from the GIT
REPOSITORY** — and those differ whenever sessions open in a subdirectory. Observed on
AccountManager7 (2026-08-20), which opened sessions in `src\`: transcripts and
`CLAUDE_PROJECT_DIR` said `...-src` while auto-memory was filed under
`c--Projects-GitHub-AccountManager7`. A store at `src\.claude\memory` then derives a directory
that does not exist and writes only the repo copy, reporting success either way.

Three fixes, in order of preference:

1. **Open sessions at the git root.** Both slugs then agree and the whole ambiguity disappears.
   This is what AccountManager7 now does; the store lives at `<repo>\.claude\memory` and every
   wired path is plain (`${CLAUDE_PROJECT_DIR}/.claude/memory/...`, `.claude/memory/memory.db`).
2. **Store at the git root but sessions in the subdirectory.** The slug matches with no code
   change, at the cost of `../` in the hook command and `.mcp.json` — and those break the moment
   a session *does* open at the root, so it is a layout that only works one way.
3. **Walk up to the git root inside `Get-IndexTargets`.** Works regardless of where sessions
   open, but diverges from the reference scripts.

See gotcha #26. Whichever you pick, **`mem.ps1 index` must print two paths.**

**b. Vectors are stored twice, in two tiers.**

| Where | Contents | Needs the extension? |
|---|---|---|
| `memory.db` → `embeddings` | Vectors as plain JSON + precomputed L2 `norm` | **No** |
| `vectors.db` → `vec_memories` | sqlite-vec `vec0` KNN index, cosine | Yes |

A `vec0` virtual table makes a database **unreadable without the matching platform
binary** — that would defeat sharing the DB. So vec0 lives in a separate, derived,
gitignored file, and `memory.db` stays stock SQLite. `vec.ps1 search` uses vec0 when
available and otherwise falls back to pure-SQL cosine over the JSON vectors (JSON1 +
`sqrt`, both standard in official builds). **Both paths return identical scores**, so
semantic search works without the extension; vec0 only makes it fast at scale.

**c. The scripts resolve their own paths.** `mem.ps1` and `vec.ps1` locate `memory.db`
relative to `$PSCommandPath`, so each project directory gets an independent store and a
clone works from any location. Nothing is hardcoded to a machine or user.

**d. SQL goes through a temp file, never through a command-line argument.** Memory bodies
contain quotes, newlines, and `$`; passing them as `sqlite3` arguments through PowerShell
is unreliable. Both scripts write SQL to a temp file and run `.read`. Keep this if you
extend them.

---

## 3. Prerequisites

**`sqlite3` with FTS5, JSON1, and math functions.** Every official build has all three.
Verify:

```powershell
sqlite3 :memory: "SELECT sqlite_version(), json_extract('{\"a\":1}','$.a'), sqrt(9.0);"
```

Installed on this machine at `~/.claude/tools/sqlite/` (**3.53.4**, deliberately **not**
on PATH). To install from scratch — download from <https://sqlite.org/download.html>
(Precompiled Binaries for Windows: `sqlite-tools-win-x64-*.zip`, plus
`sqlite-dll-win-x64-*.zip` only if something links against the DLL):

```powershell
$dest = "$env:USERPROFILE\.claude\tools\sqlite"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Expand-Archive "$env:USERPROFILE\Downloads\sqlite-tools-win-x64-*.zip" -DestinationPath $dest -Force
& "$dest\sqlite3.exe" -version
```

Both scripts resolve `sqlite3` as: `$env:SQLITE3` → PATH → `~/.claude/tools/sqlite/sqlite3.exe`.

**sqlite-vec (optional — enables fast KNN).** A **per-platform native binary**; each
person needs their own OS build from
<https://github.com/asg017/sqlite-vec/releases> (`...-loadable-windows-x86_64`,
`-macos-`, `-linux-`). Installed here: **v0.1.9**.

```powershell
$dest = "$env:USERPROFILE\.claude\tools\sqlite"
$url = "https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-windows-x86_64.tar.gz"
Invoke-WebRequest -Uri $url -OutFile "$env:TEMP\vec.tar.gz" -UseBasicParsing
tar -xzf "$env:TEMP\vec.tar.gz" -C $dest
& "$dest\sqlite3.exe" :memory: ".load $($dest.Replace('\','/'))/vec0" "SELECT vec_version();"
```

Put `vec0.{dll,so,dylib}` beside `sqlite3`, or point `$env:SQLITE_VEC0` at it —
**path without the file extension**, because `.load` appends it. Skipping this is fine;
you get the fallback path.

**Node (optional — only for the MCP server).** `.mcp.json` registers
[`mcp-sqlite`](https://www.npmjs.com/package/mcp-sqlite). Everything else — memories, FTS5,
semantic search, the hook — works with `sqlite3` alone.

**The package name and its command name differ**, which breaks the obvious invocation:
`mcp-sqlite` ships a single bin called **`mcp-sqlite-server`**. So `npx -y mcp-sqlite`
installs the package, finds no command by that name, and **exits 0 printing nothing** — a
dead server indistinguishable from a working one. See gotcha #25. Use either:

```jsonc
// preferred: install once (npm i -g mcp-sqlite), no per-session download
{ "command": "mcp-sqlite-server", "args": ["<path>/memory.db"] }
// portable but npx-dependent, and npx's bin shim was itself unreliable on Windows
{ "command": "npx", "args": ["-y", "--package=mcp-sqlite", "mcp-sqlite-server", "<path>/memory.db"] }
```

It takes the DB path **positionally** (`process.argv[2]`, defaulting to `mydatabase.db`), so
a wrong path yields an empty database rather than an error.

Delete `.mcp.json` to opt out; nothing else depends on it. `uvx` is **not** required (the
Python `mcp-server-sqlite` is a different package; `@modelcontextprotocol/server-sqlite`
does not exist on npm — verified 404).

**Dependency summary:**

| Capability | Needs | If absent |
|---|---|---|
| Memories, links, FTS5 keyword search, `MEMORY.md`, hook | `sqlite3` only | Nothing works — this is the floor |
| Semantic search (stored vectors, SQL cosine) | `sqlite3` + an embedding provider | Keyword search still works |
| Fast KNN | `vec0` platform binary | Falls back to SQL cosine, identical scores |
| Embedding *new* memories | Azure OpenAI key **or** local service | Existing vectors still searchable |
| MCP query tools | Node + `npx` (network on first run) | Use `mem.ps1` / `sqlite3` directly |

---

## 4. Port to a new project (ordered runbook)

### What to copy, and what never to copy

The store is **code + one database**. The code is portable; everything derived or
machine-specific must be left behind or the new project inherits another project's state.

| From `<source>\.claude\memory` | Copy? | Why |
|---|---|---|
| `*.ps1` (all of them) | **Yes** | Includes `memconfig.ps1`, which `vec.ps1` and `mine-transcripts.ps1` both dot-source and will refuse to run without |
| `schema.sql` | **Yes** | Creates the new DB |
| `.gitignore` | **Yes** | Excludes the things below from the new repo too |
| `SETUP.md` | **Yes** | The new project should carry its own runbook |
| `memory.db` | **NO** | The source project's memories. The new project starts empty and builds its own. |
| `MEMORY.md` | **NO** | Generated. `mem.ps1 index` writes it. |
| `vectors.db` | **NO** | Derived binary, and dimension-specific. `vec.ps1 index` rebuilds it. |
| `embed.config` | **NO** | Per-machine provider config. The user-level copy already applies (section 7). |
| `hook.log`, `memory-premigrate-*.db` | **NO** | Local evidence and backups |

Copy by pattern rather than by an enumerated list — a hand-kept list silently goes stale
when a script is added, which is exactly how `memconfig.ps1`, `migrate.ps1`, and
`mine-transcripts.ps1` went missing from an earlier version of this runbook.

### Ordered runbook

```powershell
# 1. Copy the code. Patterns, not a list -- and nothing generated or machine-specific.
$src  = "c:\Projects\ai\Repos\AI Memory\.claude\memory"   # this machine's clone; see below
$root = "<new-project-root>"
$dst  = "$root\.claude\memory"
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item "$src\*.ps1","$src\schema.sql","$src\.gitignore","$src\SETUP.md" -Destination $dst

# 2. Create the database. Note the inner single quotes: sqlite3 splits a dot-command
#    argument on whitespace, so an unquoted path containing a space fails with the
#    bare usage message "Usage: .read FILE" and creates nothing.
$sq = "$env:USERPROFILE\.claude\tools\sqlite\sqlite3.exe"
& $sq "$dst\memory.db" ".read '$($dst.Replace('\','/'))/schema.sql'"

# 3. Generate the index (writes the repo copy, plus the harness copy when that dir exists).
& powershell -NoProfile -File "$dst\mem.ps1" index

# 4. Wire it up so the store is actually used (see section 1 for why all three).
#    CLAUDE.md is a TEMPLATE, not a file to copy verbatim -- it names this project.
#    Write a new one for the target project, or copy and rewrite every reference.
#    It must go at the PROJECT ROOT: a CLAUDE.md under src\ is not auto-loaded for
#    a session started at the root.
Copy-Item "c:\Projects\ai\Repos\AI Memory\.mcp.json" -Destination "$root\.mcp.json"  # omit to skip MCP
# Merge these into $root\.claude\settings.json (do NOT overwrite existing keys):
#   hooks.SessionStart -> command "powershell",
#     args ["-NoProfile","-File",".claude\\memory\\hook-session-start.ps1"]
#   permissions.deny   -> mcp__memory-db__{create_record,update_records,delete_records}
#   permissions.ask    -> mcp__memory-db__query
# The hook path is relative to the session's working directory, so start sessions at
# the project root -- the same place CLAUDE.md has to live.

# 5. Write a first memory.
& powershell -NoProfile -File "$dst\mem.ps1" set -Name project-setup `
    -Description "one-line summary used for recall relevance" `
    -Type project -BodyFile <path>

# 6. Migrate any existing markdown memories (section 5). Dry-run first.
& powershell -NoProfile -File "$dst\import.ps1" -From "$env:USERPROFILE\.claude\projects\<slug>\memory" -DryRun

# 7. Embeddings. If a user-level provider is already configured (section 7), there is
#    NOTHING to set up -- the new project inherits it. Just embed:
& powershell -NoProfile -File "$dst\vec.ps1" provider     # confirm what resolves
& powershell -NoProfile -File "$dst\vec.ps1" embed -All
```

`$src` above is this machine's clone.

Steps 1–3 are the floor and need only `sqlite3`. Steps 4, 6, and 7 are each optional and
independent. **After step 4, verify the hook before trusting it:**

```powershell
# Should print JSON containing hookSpecificOutput.additionalContext, and exit 0.
'{}' | powershell -NoProfile -File "$dst\hook-session-start.ps1"
```

The hook only fires on a *new* session, and Claude Code's settings watcher only watches
directories that already had a settings file when the session started — so after creating
`.claude\settings.json` for the first time, open `/hooks` once or restart to load it.

---

## 5. Migrating existing markdown memories

If the project already has memory **files**, porting the scripts is not enough — the
new DB would start empty while the real knowledge sits in files, which defeats the
point. `import.ps1` migrates them.

```powershell
$mem  = ".claude\memory"
$from = "$env:USERPROFILE\.claude\projects\<slug>\memory"   # slug per section 2a

# 1. ALWAYS dry-run first. Parses and reports; writes nothing.
& powershell -NoProfile -File "$mem\import.ps1" -From $from -DryRun

# 2. Import. Existing names are skipped, so this is safe to re-run.
& powershell -NoProfile -File "$mem\import.ps1" -From $from

# 3. Review, then embed if semantic search is configured.
& powershell -NoProfile -File "$mem\mem.ps1" list
& powershell -NoProfile -File "$mem\mem.ps1" todo      # unresolved [[links]] from the files
& powershell -NoProfile -File "$mem\vec.ps1" embed -All
```

It parses the standard one-memory-per-file format (frontmatter `name`,
`description`, `metadata.type`; body below), extracts `[[wikilinks]]` into the
`links` table, and regenerates the index. `MEMORY.md` is always skipped — it's
generated output, not a memory.

**Link targets are normalized to memory names on import.** A file-based store links by
*filename* while identity comes from the frontmatter `name`, and nothing forces those to
agree — on AccountManager7 the names were kebab-case but the links were underscored
filenames, so 30 of 31 links pointed at nothing and the graph arrived dangling on a
"successful" import. `_`→`-` is rewritten only when the hyphenated form is a real memory
name; a target with no counterpart is left as a genuine unresolved link. **Always check
`mem.ps1 todo` after importing** — that is the only place this shows up. See gotcha #27.

**Only memory-shaped files are imported.** A file qualifies if its frontmatter has
`node_type: memory`, a `name:`, or a `description:`. Everything else is reported and
skipped, because **a docs folder is not a memory store**. Measured on
AccountManager7: 367 `.md` files across `aiDocs`, `claude_docs`, `agents`, `rules`,
`archive`, and npm package READMEs under `node_modules` — **none** are memories; its
8 real memories live in the Claude Code memory directory. Pointing `-From` at
`src\aiDocs` finds 22 documents and imports zero, including a 337 KB `chatRefactor.md`
and a 266 KB `KnownIssues.md`. Importing either as one row would bury the atomic facts
the store exists to hold and wreck both FTS and semantic ranking.

For a design doc, write a memory that **points at it**:

```powershell
& powershell -NoProfile -File "$mem\mem.ps1" set -Name pageindex-design -Type reference `
    -Description "PageIndex embedding/vector design lives in src/aiDocs/PageIndexDesign.md" `
    -BodyFile pointer.md
```

`import.ps1` is non-recursive by design, so a mistaken `-From` cannot walk into
`node_modules`. Other flags: `-Overwrite` (replace existing rows), `-Archive` (move
imported sources to `<dir>\imported\` — nothing is moved or deleted otherwise),
`-IncludePlain` (force-import documents; rarely right). Bodies over 8,000 characters
draw a warning that they read like documents rather than memories.

Types outside the schema's four (`user`/`feedback`/`project`/`reference`) are remapped
via a documented alias table — `fact`/`note` → `reference`, `decision`/`insight`/
`discovery` → `project`, `preference` → `user`, `correction`/`guidance` → `feedback` —
and every remap is printed rather than applied silently. Duplicate slugs across files
are flagged before they can collapse into one row.

### 5b. Mining session transcripts

Markdown files are not the only source of stranded knowledge — and usually not the
biggest one. Claude Code writes **every session** to
`~/.claude/projects/<slug>/<session-id>.jsonl`, and that is where most decisions,
gotchas, and dead ends actually live. Measured 2026-08-19: **12.9 MB across 7 sessions**
for AccountManager7-src, 3.4 MB for another project. None of it reaches the store on its own.

```powershell
$mem = ".claude\memory"
& powershell -NoProfile -File "$mem\mine-transcripts.ps1" -DryRun     # what would be mined
& powershell -NoProfile -File "$mem\mine-transcripts.ps1" -Preview    # extract + print, no writes
& powershell -NoProfile -File "$mem\mine-transcripts.ps1"             # write them
& powershell -NoProfile -File "$mem\mine-transcripts.ps1" -Limit 2    # a couple at a time
```

**How it works.** Each transcript is condensed (prose plus a compact tool/file trace;
`tool_result` blocks are dropped — they dominate by volume and rarely hold a durable
fact), truncated head+tail to `-MaxChars` (default 90k, keeping the tail where decisions
land), then sent to Azure OpenAI chat for structured extraction. A 3.3 MB transcript
condenses to ~1.2 MB and ships ~88 KB. Sessions already processed are recorded in
`mined_sessions` and skipped unless `-Force`.

**Extraction runs on the AM7 Azure chat connection**, so no Anthropic key is needed:
`AZURE_OPENAI_CHAT_ENDPOINT`/`_KEY`, else `AZURE_OPENAI_CHAT_CONNECTION_FILE`, else the
`GPT 5.6 Terra Connection.txt`. Deployment via `AZURE_OPENAI_CHAT_DEPLOYMENT`
(default `gpt-4.1`).

**Semantic dedup.** An LLM will name an existing fact differently
(`ollama-embedding-head-behavior` vs an existing `ollama-embedding-host`), which walks
straight past name-collision checks. Each candidate is embedded and compared against the
store; anything at/above `-DedupThreshold` is skipped with the match and score reported.
Requires `MEM_EMBED_CMD`; without it dedup is skipped and says so. See gotcha #20 for
threshold calibration.

**It refuses to mine a foreign project by default.** The miner writes to the DB beside it,
so `-Slug <other-project>` would file another project's facts here. That is blocked unless
`-ForeignOk`; `-DryRun`/`-Preview` are exempt so you can still look. To mine another
project properly, copy `.claude\memory` there and run it from inside.

> **Two cautions.** Extracted memories are **LLM-generated** — spot-check them; each
> carries `origin_session_id` so any claim can be traced to its conversation. And the
> transcript is sent to the configured LLM **in full**, so it may contain secrets that
> appeared in the session. The extraction prompt forbids copying secrets into output, but
> that governs the response, not the request. Do not point this at a provider you would
> not show the raw transcript to.

## 6. Usage

```powershell
$mem = ".claude\memory"

# Create or update. Description/Type required only on create; omit to patch.
& powershell -NoProfile -File "$mem\mem.ps1" set -Name my-slug `
    -Description "one-line summary" -Type project -Body $body   # or -BodyFile path.md

& powershell -NoProfile -File "$mem\mem.ps1" get    -Name my-slug
& powershell -NoProfile -File "$mem\mem.ps1" search -Query "sqlite OR recall"  # FTS5 syntax
& powershell -NoProfile -File "$mem\mem.ps1" list   -Type feedback
& powershell -NoProfile -File "$mem\mem.ps1" delete -Name my-slug
& powershell -NoProfile -File "$mem\mem.ps1" index                 # regenerate both MEMORY.md
& powershell -NoProfile -File "$mem\mem.ps1" todo                  # unresolved [[links]]

# Lifecycle -- prefer these over delete when a fact stops being true.
& powershell -NoProfile -File "$mem\mem.ps1" supersede -Name old-slug -By new-slug
& powershell -NoProfile -File "$mem\mem.ps1" deprecate -Name stale-slug
& powershell -NoProfile -File "$mem\mem.ps1" revive    -Name slug
& powershell -NoProfile -File "$mem\mem.ps1" list -All             # include non-active, flagged

# Files a memory concerns
& powershell -NoProfile -File "$mem\mem.ps1" files -Name slug -Add "src/thing.java"
& powershell -NoProfile -File "$mem\mem.ps1" files -File "src/thing.java"   # reverse lookup

# After pulling newer scripts, bring an existing DB up to the current schema.
& powershell -NoProfile -File "$mem\migrate.ps1" -DryRun
& powershell -NoProfile -File "$mem\migrate.ps1"

& powershell -NoProfile -File "$mem\vec.ps1" status
& powershell -NoProfile -File "$mem\vec.ps1" embed  -All           # embed missing/stale
& powershell -NoProfile -File "$mem\vec.ps1" index                 # rebuild vec0 index
& powershell -NoProfile -File "$mem\vec.ps1" search -Query "how are memories stored" -K 5
& powershell -NoProfile -File "$mem\vec.ps1" store  -Name s -Vector '[...]' -Model m
```

`set` and `delete` regenerate the index automatically. `embed` rebuilds the vector index.
Pass multi-line bodies as a here-string (`@'...'@`) or `-BodyFile`. Force a search path
with `-Force vec` / `-Force fallback`.

**Memory types** (enforced by a schema CHECK): `user` | `feedback` | `project` | `reference`.

**Any platform / no PowerShell.** The DB is the interface — `mem.ps1` is convenience only:

```sh
cd .claude/memory
sqlite3 memory.db "SELECT type, name, description FROM memories ORDER BY type, name;"
sqlite3 memory.db "SELECT m.name, m.description FROM memories_fts f \
  JOIN memories m ON m.rowid = f.rowid WHERE memories_fts MATCH 'sqlite' \
  ORDER BY bm25(memories_fts, 2.0, 5.0, 1.0) LIMIT 20;"
sqlite3 memory.db "SELECT body FROM memories WHERE name = 'my-slug';"
```

Raw SQL writes skip two things `mem.ps1 set` does: parsing `[[wikilinks]]` into `links`,
and regenerating `MEMORY.md`. Run `mem.ps1 index` afterwards. A POSIX `mem.sh` port does
not exist yet; it would be a small job (the scripts are just SQL string-building).

---

## 7. Embedding provider

**Anthropic has no embeddings endpoint** — the Claude API routes everything through
`/v1/messages`, with only Batches, Files, Token Counting, and Models alongside. So
embeddings come from elsewhere.

The contract is unchanged: **any command that reads text on stdin and prints a JSON array
of floats on stdout.** What changed on 2026-08-19 is *where that command is configured*.

### Configure it once, not once per shell

`$env:MEM_EMBED_CMD` works but is **shell state** — gone on exit, absent from every new
session, and absent from hook and non-interactive contexts that never ran the setup line.
The symptom is silent: `vec.ps1 embed` throws "no provider", new memories go unembedded,
and semantic search quietly answers from a stale index. Persist it instead:

```powershell
$mem = ".claude\memory"
& powershell -NoProfile -File "$mem\vec.ps1" provider -Set azure -Scope user    # or ollama / local
& powershell -NoProfile -File "$mem\vec.ps1" provider                           # show what resolves
```

That writes `~\.claude\embed.config`. Resolution order, first hit wins per key:

1. `$env:MEM_EMBED_CMD` / `$env:MEM_EMBED_MODEL` — still honoured, for a one-off override
2. `<memdir>\embed.config` — this project only (gitignored)
3. `~\.claude\embed.config` — **per machine; every project inherits it**

Level 3 is what makes porting cheap: a newly created store has working semantic search
with no provider setup at all. `memconfig.ps1` implements this and both `vec.ps1` and
`mine-transcripts.ps1` use it.

The file is `key=value`. `cmd` and `model` supply the two `MEM_EMBED_*` values; **every
other key is exported as an environment variable for the provider subprocess**, which is
how connection-file paths and hosts stay out of the scripts:

```ini
cmd   = powershell -NoProfile -File "%MEMDIR%\embed-azure.ps1"
model = text-embedding-ada-002
AZURE_OPENAI_CONNECTION_FILE      = C:\path\outside\any\repo\embedding.txt
AZURE_OPENAI_CHAT_CONNECTION_FILE = C:\path\outside\any\repo\chat.txt
```

`%MEMDIR%` expands to the memory directory of the project being operated on — **always
use it** in a user-level config, or every project will run one project's copy of the
provider script. **Quote the path**: `%MEMDIR%` routinely contains a space. An existing
environment variable is never overwritten, and **no key ever holds a secret** — name a
connection file outside the repo instead.

### Azure OpenAI

Config resolution, in order — **the key is never stored in this repo**:

1. `$env:AZURE_OPENAI_ENDPOINT` + `$env:AZURE_OPENAI_KEY`
2. `$env:AZURE_OPENAI_CONNECTION_FILE`, which `embed.config` can set for you

There is deliberately **no hardcoded third fallback**. It used to default to
`C:\Projects\GitHub\AccountManager7\volatile\connections\embedding.txt`, which meant this
project's embeddings silently depended on an unrelated project's working tree — and broke
for anyone without it. Name the file in `embed.config` instead.

Connection files may be either shape:

```
# plain (4 lines)                      # or JSON
https://<resource>.openai.azure.com    { "serverUrl": "https://...",
text-embedding-ada-002                   "apiKey": "..." }
api-version=2023-05-15
key: <key>
```

The plain form also supplies deployment and api-version. Env vars override the file:
`AZURE_OPENAI_EMBED_DEPLOYMENT`, `AZURE_OPENAI_API_VERSION`, `AZURE_OPENAI_EMBED_DIMENSIONS`.

### Ollama (local, no API key) — verified working

```powershell
& powershell -NoProfile -File "$mem\vec.ps1" provider -Set ollama -Scope user
```

Then add the host to the same config file, since it is machine-specific:

```ini
OLLAMA_HOST        = 192.168.1.42:11434   # bare host:port is normalized; default localhost:11434
OLLAMA_EMBED_MODEL = nomic-embed-text     # overrides `model` if both are set
```

Best fit when the store is widely shared: no key to distribute, nothing per-teammate.
Verified against `192.168.1.42` on 2026-08-19 — `nomic-embed-text` returns **768 dims**,
already **L2-normalized** (norm = 1.0), and semantically sound (cos 0.936 for similar text
vs 0.227 for unrelated).

```powershell
ollama pull nomic-embed-text     # 262 MB, 768 dims
# alternatives: mxbai-embed-large (1024), all-minilm (384), bge-m3 (1024)
```

Ollama has **two** embedding APIs and the script tries both: newer `/api/embed`
(`{model,input}`) then older `/api/embeddings` (`{model,prompt}`).

**A chat model cannot embed** — see gotcha #15. Only pull models built for embeddings.

### AM7-style local service (no API key)

```powershell
& powershell -NoProfile -File "$mem\vec.ps1" provider -Set local -Scope user
```

```ini
LOCAL_EMBED_URL = http://localhost:8123     # POSTs {content} to /generate_embedding
```

The service AccountManager7 uses via `test.embedding.type=local`. Not running as of
2026-08-19 on either `localhost:8123` or `192.168.1.42:8123`.

### Choosing between them

| Provider | Dims | Context | Key? | Notes |
|---|---|---|---|---|
| Azure `text-embedding-ada-002` | 1536 | 8191 | Yes | Highest quality here; key lives outside the repo |
| **Ollama `bge-m3`** | **1024** | **8192** | No | **What AccountManager7 uses.** Local, free, offline. The large window is the point — see gotcha #28 |
| Ollama `nomic-embed-text` | 768 | 2048 | No | Smaller/faster, but rejects any memory over ~2048 tokens outright |
| Ollama `mxbai-embed-large` / `all-minilm` | 1024 / 384 | 512 / 256 | No | Windows too small for most real memories |
| AM7 local service | varies | — | No | Only if that service is running |

**Pick the model for its context window, not just its dimensions.** A memory that exceeds the
window is not embedded at all (gotcha #28), so it silently drops out of semantic search while
still appearing in `mem.ps1 list` and FTS — the store looks complete and recall is quietly worse.

Switching provider changes the dimension, so run `vec.ps1 embed -All` afterwards —
the `vec0` index is fixed-width and refuses a mixed set.

### Staleness and dimensions

Each `embeddings` row stores a `content_sha256` of the embedded text (description + body),
so `embed -All` re-embeds only what changed and skips the rest.

**Dimensions must be uniform.** `vec0` tables are created at a fixed width, so
`vec.ps1 index` refuses a mixed-dimension set and tells you to re-embed. After switching
provider or model, run `vec.ps1 embed -All`.

---

## 8. Gotchas — verified, non-obvious, and expensive to rediscover

| # | Trap | What actually happens / what to do |
|---|---|---|
| 1 | **`.dump` does not round-trip an FTS5 database** | A plain `.dump` emits `INSERT INTO sqlite_master` for FTS5 shadow tables, which fails with `table sqlite_master may not be modified`. The restore silently ends up with **all rows but no search index**. Use the procedure in section 9. |
| 2 | **Azure embeddings live on a different resource than chat** | Do not assume one resource serves both. |
| 3 | **404 vs 400 on an embeddings call** | `404` = no deployment by that name. `400` = the deployment exists but can't embed (a `gpt-*` chat model, or `ada-002` sent a `dimensions` parameter). Different fixes; `embed-azure.ps1` names both explicitly. |
| 4 | **A model in the catalog is not a deployment** | `/openai/models` lists what's *available to deploy* (`text-embedding-3-small` shows `embeddings: True` there). `/openai/deployments` lists what actually exists. Only the second can be called. |
| 5 | **`ada-002` ignores `dimensions`** | Always returns 1536. Only `text-embedding-3-*` honors the parameter (AM7 requests 768 to fit its `common.vectorExt.embedding` column). |
| 6 | **`[[word]]` in prose registers as a link** | The link parser can't tell prose from intent. Writing "re-derives `[[links]]`" creates an unresolved link to a memory named `links`. Mitigated since 2026-08-19: all three writers (`mem.ps1`, `import.ps1`, `mine-transcripts.ps1`) reject targets that aren't kebab slugs (`^[a-z0-9][a-z0-9._-]*$`), so doubled brackets around prose or an ellipsis no longer create phantoms. A single lowercase word still will — check `mem.ps1 todo` after writing. Note a memory *documenting* this trap can trigger it; describe the syntax in words instead. |
| 7 | **`.load` path takes no file extension** | `.load .../vec0`, not `vec0.dll` — `.load` appends the platform suffix itself. |
| 8 | **WAL sidecars before committing** | Fold the WAL in first: `sqlite3 memory.db "PRAGMA wal_checkpoint(TRUNCATE);"`. `.gitignore` covers the sidecars. |
| 9 | **Committing `vectors.db` causes merge conflicts** | It's a derived binary. Gitignored; rebuild with `vec.ps1 index`. |
| 10 | **Editing `MEMORY.md` by hand** | It is overwritten on the next `set`/`delete`/`index`. Edit memories, not the index. |
| 11 | **PowerShell 5.1: no `&&`/`\|\|`, no ternary** | Use `;` and `if ($?) { }`. Also: an inline `-replace '\\','/'` inside some commands can trip path guards — assign to a variable with `.Replace()` first. |
| 12 | **A search that returns plausible-but-wrong ranking** | Confirm the embedding model is real. A stub/hash embedder produces confident nonsense — it ranked "where are the tools installed" against the wrong memory, which a real model gets right. |
| 13 | **`-Body` fails on text containing quotes** | `powershell -NoProfile -File mem.ps1 ... -Body $text` re-parses the argument, so a body containing `"` dies with *"A positional parameter cannot be found that accepts argument ..."*. Write the body to a file and use **`-BodyFile`** — that path exists for exactly this. Only affects the outer `powershell -File` invocation; the SQL layer handles quotes fine. |
| 14 | **`Out-File -Encoding utf8` writes a BOM that breaks `.read`** | In PowerShell 5.1, `Out-File -Encoding utf8` prepends a byte-order mark, and `sqlite3`'s `.read` then fails on the first line with `near ".": syntax error` — the dot-command is invisible behind the BOM. The shipped scripts avoid it by writing SQL with `[System.IO.File]::WriteAllText(..., New-Object System.Text.UTF8Encoding $false)`. Keep that if you extend them; use it for any ad-hoc `.sql` file too. |
| 15 | **An Ollama chat model returns 501, not a clear "wrong model" error** | Verified against `qwen3:8b`: `/api/embed` → **501**, `/api/embeddings` → **500**. The model exists but has no embedding head. A *missing* model returns **404** instead. Don't read either as a connection failure — `embed-ollama.ps1` names all three cases. Only pull models built for embeddings (`nomic-embed-text`, `mxbai-embed-large`, `all-minilm`). |
| 16 | **SQLite has no `ADD COLUMN IF NOT EXISTS`** | So `schema.sql` being idempotent for *tables* is not enough — new columns on an existing DB need `migrate.ps1`, which checks `pragma_table_info` per column. Verified: `ALTER TABLE ... ADD COLUMN` **does** accept `CHECK(...)` and `REFERENCES`, and the CHECK genuinely enforces afterwards. It cannot add `PRIMARY KEY`/`UNIQUE`, or `NOT NULL` without a default. |
| 17 | **`SessionStart` does not fire when a session resumes** | Restarting Claude Code and continuing an existing conversation reloads config (MCP servers reconnect) but does **not** re-run `SessionStart` — verified by searching the session transcript: the hook's output appeared only from manual test invocations, and no injection record exists at all. So a hook added mid-session stays dormant until a genuinely new session. Transcripts contain no record of hook execution either, which is why `hook-session-start.ps1` appends to `hook.log` — otherwise "fired" and "never ran" are indistinguishable. |
| 18 | **`gpt-5.x` and `gpt-4.1` need different token parameters** | `gpt-4.1` wants `max_tokens`; `gpt-5.x` rejects that and requires `max_completion_tokens`. Both mismatches surface as a bare **HTTP 400**, so there is no single payload that works across deployments — changing `AZURE_OPENAI_CHAT_DEPLOYMENT` can break a miner that worked yesterday. Verified 2026-08-19: `gpt-5.6-terra` 400s on `max_tokens`, `gpt-4.1` 400s on the newer name. `mine-transcripts.ps1` therefore probes rather than guessing — newer parameter first, then falls back — and keeps the fallback deliberately narrow: it retries only when the status is 400 **and** the parameter it just tried was `max_completion_tokens` **and** the body matches `max_completion_tokens\|unsupported\|unrecognized`. Every other 400 (content filter), plus 404 and 429, throws immediately, so a genuine failure is never buried under a pointless retry. That probe is only implementable because #19 made the error body readable in the first place. |
| 19 | **`Invoke-RestMethod` error bodies are often unreadable in PS 5.1** | Azure puts the actual cause (content filter, bad parameter, token limit) in the response body, but reading it from a thrown `Invoke-RestMethod` frequently yields an empty string — turning every failure into a blank `HTTP 400`. This cost real time on a transient miner failure. Use `System.Net.Http.HttpClient` and read `Content.ReadAsStringAsync()` when the body matters; `mine-transcripts.ps1` does. |
| 20 | **Semantic dedup thresholds do not transfer between models** | Calibrated for `ada-002`: outright restatements of an existing memory score **0.92–0.93**, overlapping-but-distinct facts **0.83–0.89**. Hence the 0.88 default. `nomic-embed-text` (768 dims) has a different distribution — re-measure before trusting a number. A too-high threshold silently admits near-duplicates, which is how a store degrades. |
| 21 | **A project path containing a space broke the auto-loaded index, silently** | The harness slug replaces `:`, `\`, `/` **and spaces** with `-`. Deriving it without the space rule yields a directory that does not exist, the `Test-Path` guard fails, and `MEMORY.md` is written **only** to the repo — where nothing auto-loads it. `mem.ps1 index` still reports success for the copy it did write, so the failure is invisible. Verified on `c:\Projects\ai\Repos\AI Memory`, whose harness dir sat empty. Section 2a. |
| 22 | **A local `$k` silently assigns to the `[int]$K` parameter** | PowerShell variables are **case-insensitive**, so inside a script declaring `[int]$K = 5`, writing `$k = @{...}` binds to that typed parameter and throws *"Cannot convert System.Collections.Hashtable to System.Int32"* — reported as an `ArgumentTransformationMetadataException` against the **script**, which reads like a caller error rather than a bad local assignment. `vec.ps1` declares `-K`, so never use `$k` as a scratch variable there. |
| 23 | **`MEM_EMBED_CMD` as shell state fails silently and permanently** | It vanishes with the shell, so every new session, hook, and non-interactive run has no provider: new memories go unembedded and semantic search answers from a stale index while looking healthy. Persist with `vec.ps1 provider -Set <p> -Scope user`. Also: quote `%MEMDIR%` in the command — the path routinely contains a space, and an unquoted `-File C:\...\AI Memory\...` fails with *"Processing -File 'C:\Projects\ai\Repos\AI' failed"*. |
| 24 | **`sqlite3` dot-commands split their argument on whitespace** | `.read C:/path with space/schema.sql` does not fail with an error about the path — it prints the bare usage line `Usage: .read FILE`, exits **0** on some paths, and creates nothing. Quote the argument *inside* the dot-command: `".read '$path/schema.sql'"`. This broke step 2 of the port runbook for any project directory containing a space. |
| 25 | **The MCP server is NOT read-only, and `npx -y mcp-sqlite` silently runs nothing** | Two separate errors this doc asserted for months. (a) The server exposes **8** tools: `db_info`, `list_tables`, `get_table_schema`, `read_records` — plus `create_record`, `update_records`, `delete_records`, and a `query` tool that executes **arbitrary SQL**. Read-only-ness comes entirely from `settings.json` denying the three write tools; `query` can still write, so gate it with `permissions.ask` and approve it only for reads. (b) The package's only bin is `mcp-sqlite-server`, so `npx -y mcp-sqlite` installs it, matches no command, and **exits 0 with no output** — which looks exactly like a healthy stdio server waiting for input. Verified 2026-08-20 on AccountManager7: `initialize` got no reply through `npx` in any form, while the same server invoked directly returned `serverInfo` and all 8 tools. Probe a new MCP config by piping an `initialize` request in and requiring a JSON reply. |
| 26 | **The harness auto-memory slug follows the GIT REPO, not the session directory or the store** | Distinct from #21, same silent outcome. Claude Code keys transcripts and `CLAUDE_PROJECT_DIR` to the session's working directory, but auto-loads memory from the **repository** slug. Observed on AccountManager7, which opened sessions in `src\`: `MEMORY.md` had to land under `c--Projects-GitHub-AccountManager7`, while a store at `src\.claude\memory` derives `...-src` — a directory that does not exist. `mem.ps1 index` then writes only the repo copy **and still reports success**. Cleanest fix is to open sessions at the git root so both slugs agree (what AccountManager7 does now); see section 2a for the alternatives. **Always confirm `index` prints TWO paths.** |
| 27 | **A file-based store links by FILENAME; the DB keys on the frontmatter `name`** | Nothing forces the two to agree, and when they don't, `import.ps1` records link targets that match no memory — the graph arrives 100% dangling while the import reports success. Measured on AccountManager7: every file's `name:` was kebab-case (`feedback-scope-discipline`) but its body linked the underscored filename (`[[feedback_bytestore_access]]`), so 30 of 31 links dangled. `import.ps1` now rewrites `_`→`-` **only** when the hyphenated form is a real memory name, leaving true unresolved links alone. Check `mem.ps1 todo` after any import. |
| 28 | **An embedding model REJECTS oversize input rather than clipping it, and `truncate: true` does not help** | `nomic-embed-text` (2048-token window) answers HTTP **400** `the input length exceeds the context length` on `/api/embed` and **500** on `/api/embeddings`; passing `truncate: true` changes nothing. So one document-shaped memory can fail a whole `embed -All`. Tokens per character vary far too much to pre-empt with a character cap — 13,000 chars of a repeated word embeds fine while 10,517 chars of prose-plus-code does not. Prefer a large-window model: **`bge-m3`, 8192 tokens, 1024 dims**, which took the 10.5 KB memory whole. Switching model changes the dimension, so run `vec.ps1 embed -All` (the `vec0` index is fixed-width). And note #20: dedup thresholds are per-model, so 0.88 is *not* calibrated for `bge-m3`. |
| 29 | **The SessionStart hook injected tooling warnings as if they were memories** | A child `powershell.exe` surfaces its warning/error streams as ordinary stdout to the caller, so any `Write-Warning` inside `mem.ps1` arrived as a line like `WARNING: ...` and was injected **at index 0, ahead of the real rows** — the first "memory" the model read was fabricated. Dropping the `2>&1` redirect does not fix it; the warning comes through regardless. `hook-session-start.ps1` now filters by row shape (`name \| type \| description \| updated`, 3+ pipes) and re-reports anything else under an explicit "these are NOT memories" heading, so a real tooling fault stays visible instead of being laundered into context. |
| 30 | **A default Windows execution policy blocks `powershell -File`, and an interactive test can mask it** | With every scope `Undefined` (the stock state — verify with `Get-ExecutionPolicy -List`), the effective policy is **Restricted**, so `powershell -NoProfile -File hook.ps1` dies with `SecurityError ... running scripts is disabled on this system` and **exits 0**, emitting nothing on stdout. A `SessionStart` hook registered that way never runs and reports nothing. Worse, it can appear verified: a host that already applies a bypass (an agent's PowerShell tool, an IDE terminal with a profile) runs the same script fine, so testing there proves nothing about how the harness will spawn it. Verified on AccountManager7 2026-08-20 — the hook passed repeatedly through a bypassing host and failed from `cmd`/bash. Put **`-ExecutionPolicy Bypass`** in the registered command, and test hooks through the same non-interactive path the harness uses. (Setting `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once is the machine-wide alternative, but the flag makes the config work on a fresh clone with no setup.) |
| 31 | **`core.autocrlf` can rewrite the committed export** | The text export is raw UTF-8: `.dump` escapes *control* characters with `unistr()` but emits ordinary non-ASCII literally. With `core.autocrlf=true` and no `.gitattributes`, git auto-detects `memories.sql` as text and converts its line endings on checkout — mutating the artifact that is now the only committed copy of the memories. A store-scoped `.gitattributes` pinning `memories.sql -text diff` prevents the conversion while keeping the file diffable. |
| 32 | **`PRAGMA foreign_keys` is per-CONNECTION, so declaring it in `schema.sql` is inert** | It defaults to **OFF**, and setting it once at creation time governs only that one connection — every later `sqlite3` invocation reverts. So all four `ON DELETE CASCADE` / `SET NULL` clauses in section 10 were dead letters, and `mem.ps1 delete` (a bare `DELETE FROM memories`) left orphans behind in `links` and `embeddings`, plus any `superseded_by` still pointing at the deleted row. The orphaned `links` rows were then **exported into `memories.sql`** — the only committed copy — as edges pointing at memories that no longer exist. Nothing flagged it: `PRAGMA integrity_check` validates page structure, not references, and the export's row-count check passes because an orphan *is* a real row (verified: 34 links exported, 34 restored, check green). Confirmed on AccountManager7 2026-08-20 — `PRAGMA foreign_keys` returned `0` on a fresh connection to a DB whose schema declares cascades. Both writers now prepend `PRAGMA foreign_keys=ON;` to every batch (`Invoke-Sql` in `mem.ps1` and `vec.ps1`), `export.ps1 -Verify` runs **`PRAGMA foreign_key_check`** against the restored copy, and `embed -All` prunes orphaned `embeddings` rows that a pre-fix DB may already hold. Audit any existing store with `sqlite3 memory.db "PRAGMA foreign_key_check;"` — empty output is clean. See #33 for the half of this that foreign keys cannot fix. |
| 33 | **No foreign key reaches `vectors.db`, and one stale vector silently shrinks every search** | `vectors.db` is a **separate database file**, so #32's fix cannot cascade into it: deleting a memory removes its `embeddings` row while the vector itself survives in the `vec0` index until `vec.ps1 index` rebuilds. That is not just dead weight. `vec0` applies its `k` limit **inside its own scan**, so the previous `... AND k = $K` followed by an inner join to `mem.memories` let each orphan vector claim one of the `$K` slots and then disappear in the join — a `-K 5` search returned **4** rows, with no error and nothing to suggest a row was missing. `mem.ps1 delete` now rebuilds the index (best-effort: a missing `vec0` extension degrades to a warning rather than failing an otherwise-good delete), and `search` over-fetches `$K*3+10`, joins, *then* `LIMIT $K`. If a search ever returns fewer rows than `-K` for no clear reason, run `vec.ps1 index` and compare `vec_memories` against `embeddings` — the counts must agree. |

---

## 9. Backup, export, restore

```powershell
# Backup: safe while in use, preserves the FTS index
sqlite3 memory.db ".backup memory-backup.db"

# Integrity
sqlite3 memory.db "PRAGMA integrity_check;"
```

`memory.db` is a single portable file — copying it is the simplest move between machines.

### The committed artifact is the TEXT export, not the binary

**In this project `memory.db` is gitignored and `memories.sql` is committed.** Measured
2026-08-20 on AccountManager7 (23 memories): the binary was 600 KB of which only **52 KB** was
actual knowledge — the rest is the FTS5 index and JSON embedding vectors, both derived — while
the text dump of the authored tables was **69 KB**. Three reasons the text wins:

- **It merges.** SQLite binaries do not. Two branches that both add a memory conflict with no
  resolution except discarding one side's memories.
- **It diffs.** A reviewer can *see* what went into the assistant's memory, line by line. For
  LLM-authored content that is the control that matters.
- **It doesn't bloat history.** Every memory write rewrites most of the binary, so committing
  it adds a fresh ~600 KB blob per commit for what may be one changed sentence.

```powershell
powershell -NoProfile -File "$mem\export.ps1"           # write memories.sql
powershell -NoProfile -File "$mem\export.ps1" -Verify   # ...and prove it round-trips
```

`export.ps1` checkpoints the WAL first (or the export misses writes still in the gitignored
`-wal` sidecar), dumps only `memories`, `links`, `memory_files`, and lets `sqlite3` write the
file itself via `.output` — capturing stdout in PowerShell would re-encode the em-dashes these
memories are full of, and `>` adds a BOM that breaks the later `.read` (gotchas #14, #24).

**`embeddings` is deliberately excluded.** It is derived from the text *and* specific to one
model and width (`bge-m3`/1024 here), so a committed copy is stale the moment the provider
changes. Rebuild after restore with `vec.ps1 embed -All`.

**Run `export.ps1` before committing.** Nothing does it automatically — `mem.ps1` regenerates
`MEMORY.md` on every write but not the export, so a stale `memories.sql` is the failure mode to
watch for. And since the binary is now gitignored, it is local-only: **`git clean -xdf` deletes
it**, and only what you exported survives.

### Restore

Not a plain `.read` of a full dump — see gotcha #1: dumping an FTS5 database emits INSERTs
against FTS shadow tables that fail, leaving all rows and **no search index**.

```sh
sqlite3 new.db ".read memories.sql"   # authored tables + rows
sqlite3 new.db ".read schema.sql"     # adds FTS5 table + triggers
sqlite3 new.db "INSERT INTO memories_fts(memories_fts) VALUES('rebuild');"
```

Then verify before trusting the copy:

```sh
sqlite3 new.db "PRAGMA integrity_check;"
sqlite3 new.db "SELECT count(*) FROM memories_fts WHERE memories_fts MATCH 'sqlite';"
```

`export.ps1 -Verify` performs exactly this sequence into a temp DB and asserts row counts and
the rebuilt index match, so the export is proven rather than assumed. Rebuild the vector index
separately: `vec.ps1 index` (or `vec.ps1 embed -All` if embeddings are absent).

**Encoding of the export, measured rather than assumed** (3.53.4, 23 memories): the file is
**UTF-8 with no BOM**. `unistr('...')` is used for *control* characters — embedded newlines in
bodies, 23 occurrences — so restoring needs **sqlite 3.45+**, where `unistr()` exists. But
ordinary non-ASCII text is emitted **raw**, not escaped: 138 em-dashes (U+2014) plus arrows
(U+2192) in this store. So the dump is *not* pure ASCII, and anything that re-encodes it
corrupts the memory text — never open and re-save `memories.sql` in an editor that writes
cp1252, and don't add a BOM to it (`.read` then fails on line 1, gotcha #14).

---

## 10. Schema reference

```
memories(name PK, description, type CHECK(user|feedback|project|reference),
         body, created_at, updated_at,
         status CHECK(active|deprecated|superseded) DEFAULT 'active',
         superseded_by -> memories.name ON DELETE SET NULL ON UPDATE CASCADE,
         origin_session_id, source_path, extra_metadata)
links(src -> memories.name ON DELETE CASCADE ON UPDATE CASCADE, dst, PK(src,dst))
memory_files(name -> memories.name ON DELETE CASCADE, file_path, PK(name,file_path))
mined_sessions(session_id PK, transcript_path, transcript_bytes, model,
               memories_created, summary, mined_at)
embeddings(name PK -> memories.name, model, dim, vec JSON, norm, content_sha256, created_at)
memories_fts  -- FTS5 external-content over (name, description, body), porter unicode61
vectors.db: vec_memories USING vec0(memory_name TEXT PK, embedding float[N] distance_metric=cosine)
```

- **`status` / `superseded_by`** exist so a fact that stops being true is *marked*, not
  destroyed — the reversal stays legible. Non-active rows are excluded from `list`,
  `search`, and the generated index unless `-All` is passed, because a stale memory
  resurfacing in recall is worse than not seeing it.
- **Provenance:** `origin_session_id` (from `metadata.originSessionId` on import),
  `source_path` (the file it came from), and `extra_metadata` — JSON holding any
  frontmatter key this schema does not model, so format growth cannot silently lose data.
- **`memory_files`** is indexed both directions, so "what do we know about this file?"
  is a lookup rather than a scan.

- `links.dst` is deliberately **not** a foreign key: an unresolved link marks a memory
  worth writing later. List them with `mem.ps1 todo`.
- The `ON DELETE` clauses above fire **only** on a connection that has enabled enforcement:
  `PRAGMA foreign_keys` is per-connection and defaults to OFF, so the declaration alone
  guarantees nothing. The shipped scripts set it on every batch; an ad-hoc `sqlite3` session
  does not, and a raw `DELETE` there will orphan child rows. Gotcha #32.
- Nothing in this schema constrains `vectors.db` — it is a separate file, rebuilt by
  `vec.ps1 index`, and it can outlive the rows it indexes. Gotcha #33.
- FTS5 stays in sync via `memories_fts_ai` / `_ad` / `_au` triggers; `memories_touch`
  maintains `updated_at`.
- Search ranking weights description above name above body: `bm25(memories_fts, 2.0, 5.0, 1.0)`.
- WAL journal mode is on.

---

## 11. Sharing and version control

### What is committed, and what is local-only

Verified against `.gitignore` and `.gitattributes` in this folder:

| Committed | Local-only (gitignored) |
|---|---|
| `memories.sql` — the export; the only shared copy of the memories | `memory.db` + `-wal`/`-shm` — the working store |
| `MEMORY.md` — the generated index | `vectors.db` + sidecars — derived sqlite-vec index |
| `*.ps1`, `schema.sql`, `SETUP.md` | `hook.log` — local firing evidence |
| `.gitignore`, `.gitattributes` | `embed.config` — per-machine provider config (durable copy lives at `~\.claude\embed.config`) |
| | `memory-premigrate-*.db`, `*-backup.db` — local safety nets |

`memory.db` is **not** committed: roughly 90% of it is derived, git cannot merge it, and every
write rewrites most of the file. Section 9 carries the measurements and the full reasoning.
The consequence that matters day to day: **the binary is local-only, so `git clean -xdf`
deletes it and only what you exported survives.** Run `export.ps1` before committing — nothing
does it for you, and section 12d is how you check whether you did.

The WAL checkpoint that used to be a manual pre-commit step is now automatic: `export.ps1`
runs `PRAGMA wal_checkpoint(TRUNCATE)` before dumping, or the export would miss writes still
sitting in the gitignored `-wal` sidecar.

### Cloning the repo does not give you a working store

There is no `memory.db` until you build one from the export, and there are **no embeddings at
all** — `embeddings` is deliberately excluded from the dump (section 9), because it is derived
from the text *and* specific to one model and vector width.

```powershell
# 1. Rebuild the DB from the committed export. NOT a plain .read of a full dump -- gotcha #1.
sqlite3 memory.db ".read memories.sql"    # authored tables + rows
sqlite3 memory.db ".read schema.sql"      # adds the FTS5 table + triggers
sqlite3 memory.db "INSERT INTO memories_fts(memories_fts) VALUES('rebuild');"

# 2. Keyword search works at this point. Semantic search does NOT until you re-embed locally.
powershell -NoProfile -ExecutionPolicy Bypass -File .claude\memory\vec.ps1 embed -All

# 3. Confirm: counts equal, one model, one dimension, nothing unembedded.
powershell -NoProfile -ExecutionPolicy Bypass -File .claude\memory\vec.ps1 status
```

Step 2 requires a configured provider (section 7) and re-embeds **every** memory, so it costs
real provider calls and minutes. Everything except semantic search — `mem.ps1 search`, `get`,
`list`, the generated index, the SessionStart hook — needs none of it.

### Merges

Two branches that each add a memory produce an ordinary **textual** conflict in
`memories.sql`, usually adjacent `INSERT INTO memories VALUES(...)` lines, and the resolution
is to keep both: memories are independent rows. That is the entire point of committing text —
the binary had no resolution except discarding one side's memories.

After resolving, rebuild the DB from the merged export using the clone steps above rather than
trusting a local `memory.db` that predates the merge, then re-export so file and DB agree
(section 12d). Re-embedding is only needed for the memories that arrived from the other branch.

### Review

`memories.sql` and `MEMORY.md` are pinned `-text diff` in `.gitattributes`, so they stay
diffable and git will not rewrite their line endings on checkout (gotcha #31). A reviewer can
read exactly what entered the assistant's memory, line by line; for LLM-authored content that
is the control that matters. Never re-save `memories.sql` from an editor that writes cp1252 or
adds a BOM — the encoding notes at the end of section 9 explain what that corrupts.

---

## 12. Verifying the store — set up, active, used, exported

Four different questions with four different answers, and none of them is "it looks fine."
A memory store fails *quietly*: every failure mode in section 8 leaves the tooling reporting
success. Assume nothing here without running the command.

### 12a. Is it set up and healthy?

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\vec.ps1" status
```

Reports vec0 availability and path, the index path, the embed provider **and which config
file supplied it**, memory/embedded counts, model, and dimensions. Expected: the two counts
equal, exactly one model, one dimension, and an empty `unembedded:` list. This is where an
unset provider (gotcha #23) surfaces as a named failure instead of as quietly stale search.

Then structural *and* referential integrity — the second pragma only became meaningful once
foreign keys were actually enforced (gotcha #32):

```powershell
sqlite3 ".claude\memory\memory.db" "PRAGMA integrity_check; PRAGMA foreign_key_check;"
```

`ok` followed by no further output is clean. Any `foreign_key_check` row is an orphaned
child row — gotcha #32 explains the cause and the audit. Note `sqlite3` is routinely **not
on PATH**; use `$env:SQLITE3` or `~\.claude\tools\sqlite\sqlite3.exe`.

Add `mem.ps1 todo` for dangling wikilinks and `mem.ps1 list` for the rows themselves.

### 12b. Is it *active* — did the hook actually fire?

`hook.log` is the only evidence that exists:

```powershell
Get-Content ".claude\memory\hook.log" -Tail 5
```

One line per session start. Transcripts contain **no** record of hook execution (gotcha
#17), so without this log "fired" and "never ran" are indistinguishable — which is precisely
why `hook-session-start.ps1` writes it. Gitignored. No line for the current session means
the hook did not run: see the `SessionStart hook never fires` row in section 13, and gotcha
#30 (a Restricted execution policy fails while **exiting 0**).

> **Never compare these timestamps to DB timestamps directly.** `hook.log` is written with
> `Get-Date` (**local time**); `created_at`/`updated_at` default to `datetime('now')`, which
> SQLite evaluates as **UTC**. Verified 2026-08-20: local `15:01` was UTC `20:01`. So a
> hook.log line at `13:56` and a memory written at `18:48` belong to the *same* session, not
> a five-hour gap. Convert before concluding anything is stale.

### 12c. Is the agent actually *using* it?

The honest answer is: partially unverifiable as shipped.

| Question | Evidence available today |
|---|---|
| Was the index injected? | `hook.log` — but that proves only that it was *offered*, not read. |
| Were memories written? | `mem.ps1 list` (`updated_at` column), `git log -- memories.sql`. |
| Were memories **read**? | **Nothing records this.** `get` / `search` leave no trace anywhere. |

The read side is the real gap; close it with the `PostToolUse` hook in 12e.

### 12d. Is the export current?

"Current" means the DB and `memories.sql` agree. Compare **content, not mtimes** — mtime
comparison is vulnerable to WAL leaving the main file behind (gotcha #8) and invites the
UTC/local error in 12b:

```powershell
# DB truth: row count and newest edit
sqlite3 ".claude\memory\memory.db" "SELECT count(*)||' / '||MAX(updated_at) FROM memories;"

# Export truth: row count
(Select-String -Path ".claude\memory\memories.sql" -Pattern '^INSERT INTO memories VALUES').Count
```

The counts must match, and the DB's `MAX(updated_at)` must appear somewhere in the file.
Both signals are needed: the count catches **deletes** (which bump no `updated_at`), the
timestamp catches **edits** (which change no count).

`export.ps1 -Verify` is both the fix and the stronger check — it rewrites the artifact, then
restores it to a temp DB and asserts `integrity_check`, `foreign_key_check`, per-table row
counts, and a rebuilt FTS index. Prefer it to a bare `export.ps1`.

Remember the export is only half the job: **the committed artifact is the text export, not
the binary DB** (section 9, gotcha #31), and `memories.sql` is worthless uncommitted. Verify
it is actually tracked, not merely present:

```powershell
git ls-files --error-unmatch .claude/memory/memories.sql   # non-zero exit = NOT tracked
git status --porcelain .claude/memory/memories.sql          # empty = committed and clean
```

An untracked `memories.sql` is the one file in the store that `git clean -xdf` would erase
permanently, since `memory.db` is gitignored by design.

### 12e. Hooks that close the two gaps

Neither is registered yet. Add to `hooks` in `.claude\settings.json` alongside the existing
`SessionStart` entry.

**Usage logging (12c) — records that the store was read:**

```json
"PostToolUse": [
  { "matcher": "Bash|PowerShell",
    "hooks": [{ "type": "command",
                "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"${CLAUDE_PROJECT_DIR}/.claude/memory/hook-usage-log.ps1\"" }] }
]
```

The script reads the tool-call JSON from stdin, matches `tool_input.command` against
`mem\.ps1|vec\.ps1`, and appends timestamp + verb + slug to a `usage.log`.

**Export drift (12d) — `Stop`, not `SessionEnd`:**

```json
"Stop": [
  { "hooks": [{ "type": "command",
                "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"${CLAUDE_PROJECT_DIR}/.claude/memory/hook-export-check.ps1\"" }] }
]
```

`Stop` fires as the agent finishes responding and can hand a message back to it, so drift
gets fixed in the same turn. `SessionEnd` can only run `export.ps1` after the agent is gone,
cannot `git add`, and cannot report back — turning drift into silent local-only state. Have
the script apply the 12d comparison plus the tracked/clean check, and stay silent unless
something is actually stale.

> Both hook contracts — `PostToolUse` stdin shape and `Stop`'s feedback path — must be
> **proved here, not assumed**. Gotcha #30 is the reason: a hook can pass repeatedly when
> tested from a bypassing host and still never run when the harness spawns it. Register with
> `-ExecutionPolicy Bypass` and test through the same non-interactive path the harness uses.

---

## 13. Troubleshooting

| Symptom | Cause |
|---|---|
| A search returns fewer rows than `-K` | Stale `vectors.db` holding vectors whose memories are gone. Gotcha #33. `vec.ps1 index`, then confirm `vec_memories` and `embeddings` counts agree. |
| `memories.sql` behind `memory.db` | Export never re-run after a write. Section 12d; fix with `export.ps1 -Verify`. |
| `memories.sql` present but not in git | Un-ignored by the store `.gitignore` but never `git add`ed, so the memories exist only locally. Section 12d. |
| `sqlite3 not found` | Not on PATH and not in `~/.claude/tools/sqlite/`. Set `$env:SQLITE3`. |
| `vec0 extension unavailable` on `index` | No platform binary. Harmless — search falls back to SQL cosine. Set `$env:SQLITE_VEC0` (no file extension). |
| `embeddings have mixed dimensions` | Provider or model changed. `vec.ps1 embed -All`. |
| `No embedding provider configured` | Nothing resolved. Run `vec.ps1 provider` to see what was searched, then `vec.ps1 provider -Set azure -Scope user`. Section 7. |
| `memconfig.ps1 is missing from ...` | Ported the store without it. `vec.ps1` dot-sources it and refuses to run. Copy `*.ps1`, not a hand-written list — section 4. |
| Embeddings recorded as `model=unknown` | No `model` in `embed.config` and no `MEM_EMBED_MODEL`. Harmless for search, but it makes a later dimension mismatch hard to diagnose. Set it and re-embed. |
| `Processing -File 'C:\...\AI' failed` | Unquoted provider path with a space in it. Quote `%MEMDIR%` in `embed.config` (gotcha #23). |
| `MEMORY.md` current in the repo but never auto-loaded | Harness copy missing — usually the space-in-path slug bug (gotcha #21). `mem.ps1 index` should report **two** paths. |
| Search returns nothing | Nothing embedded yet (`vec.ps1 status`), or the FTS query uses invalid FTS5 syntax. |
| `MEMORY.md` not auto-loading | The harness copy is missing. Run `mem.ps1 index` and check the slug derivation in section 2a. |
| `table sqlite_master may not be modified` | A plain `.dump` restore. Gotcha #1, section 9. |
| Restored DB has rows but search finds nothing | Same cause — the FTS index wasn't rebuilt. |
| Ranking looks confidently wrong | Verify the real embedding model is in use, not a stub (gotcha #12). |
| SessionStart hook never fires | `.claude\settings.json` was created after the session started — the settings watcher missed it. Open `/hooks` once or restart. Confirm the script itself works first: `'{}' \| powershell -NoProfile -File .claude\memory\hook-session-start.ps1` should exit 0 and print `additionalContext`. |
| Hook runs but nothing appears in context | It printed plain stdout instead of the `hookSpecificOutput.additionalContext` envelope. Only that envelope is reliably injected (gotcha, section 1). |
| MCP `memory-db` tools missing | `.mcp.json` needs approval on first run, or Node/`npx` is unavailable. Both are optional — `mem.ps1` and `sqlite3` are unaffected. |
| MCP write tool refused | Working as designed: `permissions.deny` blocks `create_record`/`update_records`/`delete_records` so writes can't bypass link derivation and index regeneration. Write via `mem.ps1 set`. |
| Miner: `Refusing to mine a foreign project's transcripts` | Working as designed — `-Slug` names another project, whose facts would land in this store. Run it from inside that project, or pass `-ForeignOk`. |
| Miner: `table 'mined_sessions' is missing` | The DB predates the miner. Run `migrate.ps1`. |
| Miner: blank `HTTP 400` | Was gotcha #19 (unreadable error body); now reported in full. If the body mentions `content_filter`, lower `-MaxChars` or change deployment. A transient 400 has been observed on retry-success. |
| Miner creates near-duplicates | `MEM_EMBED_CMD` is unset so dedup was skipped (it says so), or `-DedupThreshold` is too high — see gotcha #20. |
| Miner: `no chat deployment` (404) | Set `AZURE_OPENAI_CHAT_DEPLOYMENT`. `gpt-4.1` is the verified default; embeddings live on a *different* resource (gotcha #2). |
| `import.ps1` imported nothing | Every file lacked memory frontmatter, so all were treated as documents and skipped. That is the guard working (section 5); `-IncludePlain` overrides, but is rarely right. |
