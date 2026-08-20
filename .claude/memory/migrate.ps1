<#
Bring an existing memory.db up to the current schema.

  migrate.ps1 [-DryRun]

schema.sql is idempotent for TABLES (CREATE TABLE IF NOT EXISTS) but SQLite has no
ADD COLUMN IF NOT EXISTS, so new columns on an existing `memories` table need this.
Safe to re-run: every step is checked against PRAGMA table_info first.

Run after pulling a newer version of these scripts. Fresh databases created from
schema.sql do not need it.
#>
[CmdletBinding()]
param([switch]$DryRun)

$ErrorActionPreference = 'Stop'

$MemDir = Split-Path -Parent $PSCommandPath
$Db     = Join-Path $MemDir 'memory.db'

function Resolve-Sqlite {
  if ($env:SQLITE3) { return $env:SQLITE3 }
  $onPath = Get-Command sqlite3 -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }
  $local = Join-Path $env:USERPROFILE '.claude\tools\sqlite\sqlite3.exe'
  if (Test-Path $local) { return $local }
  throw "sqlite3 not found. See SETUP.md section 3."
}
$Sqlite = Resolve-Sqlite

if (-not (Test-Path $Db)) { throw "memory.db not found at $Db" }

function Invoke-Sql([string]$Sql) {
  $f = Join-Path ([System.IO.Path]::GetTempPath()) ("mig-" + [guid]::NewGuid().ToString('N') + ".sql")
  try {
    [System.IO.File]::WriteAllText($f, $Sql, (New-Object System.Text.UTF8Encoding $false))
    $out = & $Sqlite $Db ".read $($f.Replace('\','/'))"
    if ($LASTEXITCODE -ne 0) { throw "sqlite3 exited $LASTEXITCODE" }
    $out
  } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

function Get-Columns([string]$table) {
  @(Invoke-Sql ".mode list`nSELECT name FROM pragma_table_info('$table');" | Where-Object { $_ })
}
function Test-Table([string]$table) {
  ((Invoke-Sql ".mode list`nSELECT 1 FROM sqlite_master WHERE type='table' AND name='$table';") -join '') -eq '1'
}

# column name -> DDL fragment. Order matters only for readability.
$wanted = [ordered]@{
  'status'            = "TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','deprecated','superseded'))"
  'superseded_by'     = "TEXT REFERENCES memories(name) ON DELETE SET NULL ON UPDATE CASCADE"
  'origin_session_id' = "TEXT"
  'source_path'       = "TEXT"
  'extra_metadata'    = "TEXT"
}

$existing = Get-Columns 'memories'
if (-not $existing) { throw "table 'memories' not found in $Db - is this the right database?" }

$steps = @()
foreach ($col in $wanted.Keys) {
  if ($existing -notcontains $col) {
    $steps += "ALTER TABLE memories ADD COLUMN $col $($wanted[$col]);"
  }
}
if (-not (Test-Table 'memory_files')) {
  $steps += @"
CREATE TABLE memory_files (
  name      TEXT NOT NULL REFERENCES memories(name) ON DELETE CASCADE ON UPDATE CASCADE,
  file_path TEXT NOT NULL,
  PRIMARY KEY (name, file_path)
);
"@
}
if (-not (Test-Table 'mined_sessions')) {
  $steps += @"
CREATE TABLE mined_sessions (
  session_id       TEXT PRIMARY KEY,
  transcript_path  TEXT,
  transcript_bytes INTEGER,
  model            TEXT,
  memories_created INTEGER NOT NULL DEFAULT 0,
  summary          TEXT,
  mined_at         TEXT NOT NULL DEFAULT (datetime('now'))
);
"@
}
$steps += "CREATE INDEX IF NOT EXISTS memory_files_path ON memory_files(file_path);"
$steps += "CREATE INDEX IF NOT EXISTS memories_status  ON memories(status);"
$steps += "CREATE INDEX IF NOT EXISTS memories_session ON memories(origin_session_id);"

"database : $Db"
"columns  : $($existing -join ', ')"
""

$alters = @($steps | Where-Object { $_ -match '^(ALTER|CREATE TABLE)' })
if (-not $alters) {
  "schema is already current - only idempotent index creation to run."
} else {
  "pending structural changes:"
  foreach ($s in $alters) { "  $(($s -split "`n")[0])" }
}
""

if ($DryRun) { "DRY RUN - nothing written."; return }

# A schema change is the one operation worth a backup first.
$backup = Join-Path $MemDir ("memory-premigrate-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".db")
Invoke-Sql "PRAGMA wal_checkpoint(TRUNCATE);" | Out-Null
& $Sqlite $Db ".backup $($backup.Replace('\','/'))"
if ($LASTEXITCODE -ne 0) { throw "backup failed - refusing to migrate" }
"backup   : $backup"

Invoke-Sql (($steps -join "`n")) | Out-Null

$after = Get-Columns 'memories'
"columns  : $($after -join ', ')"
"integrity: $((Invoke-Sql 'PRAGMA integrity_check;') -join ', ')"
"migrated. Delete the backup once you have verified the store."
