# AccountManager7 — repo root

> **Interim file.** It exists because sessions now open at the repo root, and only a root
> `CLAUDE.md` is auto-loaded. It deliberately contains **nothing but the memory-store
> instruction** — the project's real orientation, module map, build/test commands, and the
> mandatory conduct rules still live in [`src/CLAUDE.md`](src/CLAUDE.md) and
> [`src/.claude/rules/`](src/.claude/rules/). **Read `src/CLAUDE.md` before doing any work in
> this repo.** When the rest of the harness config is migrated up, fold that file into this
> one and delete this note.

## MANDATORY: project memory is a database, not files

Durable project knowledge lives in a **SQLite store at `.claude/memory/memory.db`**.
`.claude/memory/SETUP.md` is the self-contained runbook.

**Search it before assuming something is unrecorded.** The two searches answer different
questions — reach for the semantic one when the query's wording probably won't match the
memory's wording:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" list
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" search -Query "<fts5 keywords>"
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\vec.ps1"  search -Query "<plain english>"
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" get -Name <slug>
```

`-ExecutionPolicy Bypass` is not optional here: every policy scope on this machine is
`Undefined`, so the effective policy is **Restricted** and a bare `powershell -File` fails with
a `SecurityError` while still **exiting 0** — it looks like a command that did nothing.

**Writing.** Always through `mem.ps1 set` — it derives `[[wikilinks]]`, updates the FTS index,
and regenerates `MEMORY.md`. Then embed, then export.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" set -Name <kebab-slug> `
    -Description "one line, used for recall relevance" `
    -Type <user|feedback|project|reference> -BodyFile <path>
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\vec.ps1"    embed -All
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\export.ps1"
```

Hard rules:
- **Never write a loose `.md` memory file**, and never hand-edit the generated `MEMORY.md`. The
  file-based memories were migrated into the DB on 2026-08-20; files are no longer the store.
- **Never write to the DB through the MCP server or raw SQL** — a direct INSERT bypasses
  wikilink derivation and index regeneration. The `memory-db` server is *not* read-only despite
  what `SETUP.md` says: it exposes `create_record`/`update_records`/`delete_records`, which
  `.claude/settings.json` denies, plus a `query` tool that runs **arbitrary SQL** and so could
  write. `query` is gated by `permissions.ask` — approve it only for reads.
- Prefer `-BodyFile` over `-Body` — a body containing a quote breaks `powershell -File` parsing.
- When a fact stops being true, mark it rather than deleting it: `mem.ps1 supersede -Name <old>
  -By <new>` / `mem.ps1 deprecate -Name <slug>`.
- **`memory.db` is gitignored; the committed form is `memories.sql`.** Run `export.ps1` after
  writing memories — nothing regenerates it automatically, so skipping it leaves the knowledge
  local-only, and `git clean -xdf` would erase it.

Embeddings come from Ollama `bge-m3` (1024 dims) on the DGX Spark at `192.168.1.42`, configured
once at user scope in `~\.claude\embed.config` so every project inherits it. `bge-m3` rather
than `nomic-embed-text` because its 8192-token window fits document-shaped memories; nomic
rejects anything past its 2048 outright. Semantic search degrades gracefully without a
provider — keyword search and the index need nothing but `sqlite3`.
