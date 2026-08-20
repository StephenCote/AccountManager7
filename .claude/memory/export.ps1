<#
Export the memory store to a reviewable TEXT file, and optionally prove it restores.

`memory.db` is gitignored here: ~90% of it is derived (the FTS5 index and the JSON embedding
vectors), it is an unmergeable binary, and every memory write rewrites most of it -- so
committing it adds a fresh ~600 KB blob to history for what may be one changed sentence, and
two branches that both add memories conflict irreconcilably. Measured 2026-08-20 on
AccountManager7: 600 KB binary vs 72 KB of text for the same 23 memories, of which only 52 KB
is actual knowledge.

What gets committed instead is this export: line-diffable, so a reviewer can SEE what the
assistant claims to have learned -- the right control for LLM-authored content.

  export.ps1                # write memories.sql
  export.ps1 -Verify        # ...then rebuild it into a temp DB and check it round-trips
  export.ps1 -Path other.sql

Embeddings are deliberately EXCLUDED: they are derived from the text, and they are specific to
one model and dimension (bge-m3/1024 here), so a committed copy goes stale the moment the
provider changes. Rebuild them after a restore with `vec.ps1 embed -All`.

Restore is documented in SETUP.md section 9. It is NOT a plain `.read` of a full `.dump` --
see gotcha #1: a dump of an FTS5 database emits INSERTs against FTS shadow tables that fail,
leaving all rows and no search index.
#>
[CmdletBinding()]
param(
  [string]$Path,
  [switch]$Verify
)

$ErrorActionPreference = 'Stop'

$MemDir = Split-Path -Parent $PSCommandPath
$Db     = Join-Path $MemDir 'memory.db'
if (-not $Path) { $Path = Join-Path $MemDir 'memories.sql' }

# Same resolution order as mem.ps1 / vec.ps1, so a fresh clone works with any install.
function Resolve-Sqlite {
  if ($env:SQLITE3) {
    if (Test-Path $env:SQLITE3) { return $env:SQLITE3 }
    throw "`$env:SQLITE3 is set to '$env:SQLITE3' but that path does not exist"
  }
  $onPath = Get-Command sqlite3 -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }
  $local = Join-Path $env:USERPROFILE '.claude\tools\sqlite\sqlite3.exe'
  if (Test-Path $local) { return $local }
  throw "sqlite3 not found. See SETUP.md section 3."
}
$Sqlite = Resolve-Sqlite
if (-not (Test-Path $Db)) { throw "memory.db not found at $Db" }

# Only the tables that hold authored state. `embeddings` is derived and model-specific;
# the FTS5 tables cannot be dumped at all (gotcha #1) and are rebuilt from schema.sql.
$Tables = @('memories', 'links', 'memory_files')

# Fold the WAL in first, or the export can miss writes that are still sitting in the
# -wal sidecar -- which is also gitignored, so they would exist nowhere committed.
& $Sqlite $Db "PRAGMA wal_checkpoint(TRUNCATE);" | Out-Null

# Let sqlite3 write the file ITSELF via .output, rather than capturing stdout and
# re-encoding it in PowerShell. Capturing would run the text through the console output
# encoding (mangling the em-dashes and arrows these memories are full of) and `>` in
# PowerShell 5.1 adds a UTF-8 BOM, which makes the first line of a later `.read` fail with
# `near ".": syntax error` -- gotcha #14. Note the inner quotes: a dot-command argument is
# split on whitespace, so an unquoted path containing a space silently writes nothing
# (gotcha #24).
$outPath = $Path.Replace('\', '/')
$args = @(".output '$outPath'", ".dump $($Tables -join ' ')", ".output stdout")
& $Sqlite $Db @args
if ($LASTEXITCODE -ne 0) { throw "sqlite3 exited $LASTEXITCODE while dumping" }
if (-not (Test-Path $Path)) { throw "export produced no file at $Path" }

$counts = @{}
foreach ($t in $Tables) {
  $counts[$t] = [int](& $Sqlite $Db "SELECT count(*) FROM $t;")
}
$size = [math]::Round((Get-Item $Path).Length / 1KB, 1)

"exported : $Path  ($size KB)"
"tables   : " + (($Tables | ForEach-Object { "$_=$($counts[$_])" }) -join ', ')
"excluded : embeddings (derived, model-specific -- rebuild with vec.ps1 embed -All)"

if (-not $Verify) {
  "verify   : skipped (pass -Verify to prove it round-trips)"
  return
}

# An export nobody has restored is a backup nobody has -- and gotcha #1 makes a
# plausible-looking dump restore to "all rows, no search index". So actually do it.
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("memexport-" + [guid]::NewGuid().ToString('N') + ".db")
try {
  & $Sqlite $tmp ".read '$outPath'"                              | Out-Null
  & $Sqlite $tmp ".read '$($MemDir.Replace('\','/'))/schema.sql'" | Out-Null
  & $Sqlite $tmp "INSERT INTO memories_fts(memories_fts) VALUES('rebuild');" | Out-Null

  $integrity = (& $Sqlite $tmp "PRAGMA integrity_check;")
  $ok = ($integrity -eq 'ok')
  foreach ($t in $Tables) {
    $n = [int](& $Sqlite $tmp "SELECT count(*) FROM $t;")
    if ($n -ne $counts[$t]) { $ok = $false; "  MISMATCH $t : $n restored vs $($counts[$t]) exported" }
  }
  # The index is the part gotcha #1 silently loses, so assert it actually searches.
  $ftsRows = [int](& $Sqlite $tmp "SELECT count(*) FROM memories_fts;")
  if ($ftsRows -ne $counts['memories']) { $ok = $false; "  MISMATCH memories_fts : $ftsRows vs $($counts['memories'])" }

  # integrity_check validates page structure, not referential integrity: it passes happily on a
  # dump whose links rows point at memories that no longer exist -- which is precisely what an
  # uncascaded delete used to produce, and it reached this file unnoticed. Row counts match in
  # that case too, since the orphan is a real row. foreign_key_check is the only thing that
  # catches it, so a dump with dangling children must not be reported as a good backup.
  $fkViolations = @(& $Sqlite $tmp "PRAGMA foreign_key_check;" | Where-Object { $_ })
  if ($fkViolations) {
    $ok = $false
    "  ORPHANED ROWS ($($fkViolations.Count)) - child rows whose parent memory is gone:"
    $fkViolations | Select-Object -First 10 | ForEach-Object { "    $_" }
  }

  if ($ok) { "verify   : OK - restored cleanly, integrity=$integrity, no orphans, FTS index rebuilt ($ftsRows rows)" }
  else     { throw "round-trip verification FAILED - do not rely on $Path" }
}
finally {
  Remove-Item $tmp -Force -ErrorAction SilentlyContinue
  Remove-Item "$tmp-wal", "$tmp-shm" -Force -ErrorAction SilentlyContinue
}
