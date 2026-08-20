<#
Memory store helper. SQLite is the store of record; MEMORY.md is generated from it.

  mem.ps1 set    -Name slug -Description "..." -Type project -Body "..."   (or -BodyFile path)
  mem.ps1 get    -Name slug
  mem.ps1 search -Query "fts query"
  mem.ps1 list   [-Type project]
  mem.ps1 delete -Name slug
  mem.ps1 index                      # regenerate MEMORY.md
  mem.ps1 todo                       # [[links]] pointing at memories not yet written
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory, Position = 0)]
  [ValidateSet('set','get','search','list','delete','index','todo','supersede','deprecate','revive','files')]
  [string]$Command,

  [string]$Name,
  [string]$Description,
  [ValidateSet('user','feedback','project','reference')]
  [string]$Type,
  [string]$Body,
  [string]$BodyFile,
  [string]$Query,

  [string]$By,          # supersede: the memory that replaces -Name
  [string]$Add,         # files: associate a file path with -Name
  [string]$Remove,      # files: drop an association
  [string]$File,        # files: reverse lookup -- which memories concern this file
  [switch]$All          # list/search: include deprecated and superseded
)

$ErrorActionPreference = 'Stop'

$MemDir      = Split-Path -Parent $PSCommandPath              # <project>\.claude\memory
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MemDir)
$Db          = Join-Path $MemDir 'memory.db'
# Resolve sqlite3: PATH first (so a fresh clone works with any install), then the local
# tools dir this project happened to use, then $env:SQLITE3 as an explicit override.
function Resolve-Sqlite {
  if ($env:SQLITE3) {
    if (Test-Path $env:SQLITE3) { return $env:SQLITE3 }
    throw "`$env:SQLITE3 is set to '$env:SQLITE3' but that path does not exist"
  }
  $onPath = Get-Command sqlite3 -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }
  $local = Join-Path $env:USERPROFILE '.claude\tools\sqlite\sqlite3.exe'
  if (Test-Path $local) { return $local }
  throw @"
sqlite3 not found. Install it (https://sqlite.org/download.html) and either put it on PATH
or set `$env:SQLITE3 to the executable. See SETUP.md next to this script. Any build with FTS5
works; the database itself is a portable single file.
"@
}
$Sqlite = Resolve-Sqlite

# MEMORY.md is written to the project (shared with the DB) and, when present, to the harness
# memory dir -- only that copy is auto-loaded into context at session start. The harness slug is
# derived from the project path, so this keeps working for anyone who clones the project:
#   c:\Projects\ai\Repos\AI Memory  ->  c--Projects-ai-Repos-AI-Memory
# Lowercase the drive letter, then replace ':', '\', '/' AND SPACES with '-'. Spaces matter:
# omitting them produced a slug matching no directory, so the auto-loaded copy was silently
# never written for any project whose path contains a space. $env:CLAUDE_MEMORY_INDEX overrides.
function Get-IndexTargets {
  $targets = @(Join-Path $MemDir 'MEMORY.md')
  if ($env:CLAUDE_MEMORY_INDEX) { return $targets + $env:CLAUDE_MEMORY_INDEX }

  $normalized = $ProjectRoot.Substring(0, 1).ToLower() + $ProjectRoot.Substring(1)
  $projects   = Join-Path $env:USERPROFILE '.claude\projects'
  $candidates = @(
    ($normalized -replace '[:\\/ ]', '-'),   # current rule
    ($normalized -replace '[:\\]', '-')      # pre-fix rule, in case a dir exists under it
  ) | Select-Object -Unique

  foreach ($slug in $candidates) {
    $harness = Join-Path $projects "$slug\memory"
    if (Test-Path $harness) { return $targets + (Join-Path $harness 'MEMORY.md') }
  }
  $targets
}

function Esc([string]$s) { if ($null -eq $s) { '' } else { $s.Replace("'", "''") } }

# Run SQL via a temp file so nothing has to survive shell-level quoting.
#
# The PRAGMA is not decoration. `foreign_keys` is a per-CONNECTION setting that defaults to OFF,
# so schema.sql setting it once at creation time had no effect on any later connection -- which
# made every `ON DELETE CASCADE` in the schema silently inert. `delete` then left orphans behind
# in links/embeddings, and the orphaned links rows got exported into memories.sql pointing at
# memories that no longer existed. It has to be re-asserted per connection, i.e. here.
function Invoke-Sql {
  param([string]$Sql, [string[]]$SqliteArgs = @())
  $f = Join-Path ([System.IO.Path]::GetTempPath()) ("mem-" + [guid]::NewGuid().ToString('N') + ".sql")
  try {
    [System.IO.File]::WriteAllText($f, "PRAGMA foreign_keys=ON;`n" + $Sql, (New-Object System.Text.UTF8Encoding $false))
    & $Sqlite @SqliteArgs $Db ".read $($f -replace '\\','/')"
    if ($LASTEXITCODE -ne 0) { throw "sqlite3 exited $LASTEXITCODE" }
  } finally {
    Remove-Item $f -Force -ErrorAction SilentlyContinue
  }
}

switch ($Command) {

  'set' {
    if (-not $Name) { throw "-Name is required" }
    if ($BodyFile) {
      if (-not (Test-Path $BodyFile)) { throw "-BodyFile not found: $BodyFile" }
      $Body = [System.IO.File]::ReadAllText($BodyFile)
    }
    $exists = (Invoke-Sql "SELECT 1 FROM memories WHERE name='$(Esc $Name)';") -join ''

    if (-not $exists) {
      foreach ($req in 'Description','Type','Body') {
        if (-not (Get-Variable $req -ValueOnly)) { throw "-$req is required when creating a new memory" }
      }
    }

    $sets = @()
    if ($Description) { $sets += "description='$(Esc $Description)'" }
    if ($Type)        { $sets += "type='$(Esc $Type)'" }
    if ($Body)        { $sets += "body='$(Esc $Body)'" }

    if ($exists) {
      $sql = "UPDATE memories SET $($sets -join ', ') WHERE name='$(Esc $Name)';"
    } else {
      $sql = @"
INSERT INTO memories(name, description, type, body)
VALUES ('$(Esc $Name)', '$(Esc $Description)', '$(Esc $Type)', '$(Esc $Body)');
"@
    }

    # Re-derive [[links]] from the stored body.
    $sql += "`nDELETE FROM links WHERE src='$(Esc $Name)';"
    Invoke-Sql $sql

    $stored = (Invoke-Sql "SELECT body FROM memories WHERE name='$(Esc $Name)';") -join "`n"
    $linkSql = ''
    foreach ($m in [regex]::Matches($stored, '\[\[([^\]]+)\]\]')) {
      $dst = $m.Groups[1].Value.Trim()
      # A real target is a kebab slug. This rejects prose that merely looks like a
      # link -- '[[...]]', '[[word]]' -- which otherwise lands in `todo` as a phantom
      # memory to write. Documenting the trap in a memory body used to trigger it.
      if ($dst -notmatch '^[a-z0-9][a-z0-9._-]*$') { continue }
      if ($dst -and $dst -ne $Name) {
        $linkSql += "INSERT OR IGNORE INTO links(src,dst) VALUES ('$(Esc $Name)','$(Esc $dst)');`n"
      }
    }
    if ($linkSql) { Invoke-Sql $linkSql }

    if ($exists) { "updated: $Name" } else { "created: $Name" }
    & $PSCommandPath index | Out-Null
  }

  'get' {
    if (-not $Name) { throw "-Name is required" }
    Invoke-Sql @"
.mode line
SELECT name, type, description, created_at, updated_at, body
FROM memories WHERE name='$(Esc $Name)';
"@
  }

  'search' {
    if (-not $Query) { throw "-Query is required" }
    Invoke-Sql @"
.mode list
.separator ' | '
SELECT m.name, m.type, m.description,
       CASE WHEN m.status='active' THEN ''
            ELSE '[' || m.status || COALESCE(' -> ' || m.superseded_by, '') || ']' END
FROM memories_fts f
JOIN memories m ON m.rowid = f.rowid
WHERE memories_fts MATCH '$(Esc $Query)'
  $(if (-not $All) { "AND m.status='active'" })
ORDER BY bm25(memories_fts, 2.0, 5.0, 1.0)
LIMIT 20;
"@
  }

  'list' {
    # Superseded/deprecated memories are hidden by default: a stale fact resurfacing
    # in recall is worse than not seeing it. -All includes them, flagged.
    $conds = @()
    if ($Type)   { $conds += "type='$(Esc $Type)'" }
    if (-not $All) { $conds += "status='active'" }
    $where = if ($conds) { 'WHERE ' + ($conds -join ' AND ') } else { '' }
    Invoke-Sql @"
.mode list
.separator ' | '
SELECT name, type, description, updated_at,
       CASE WHEN status='active' THEN ''
            ELSE '[' || status || COALESCE(' -> ' || superseded_by, '') || ']' END
FROM memories $where
ORDER BY type, name;
"@
  }

  'supersede' {
    if (-not $Name -or -not $By) { throw "-Name (the outdated memory) and -By (its replacement) are both required" }
    if ($Name -eq $By) { throw "a memory cannot supersede itself" }
    foreach ($n in @($Name, $By)) {
      if (-not ((Invoke-Sql ".mode list`nSELECT 1 FROM memories WHERE name='$(Esc $n)';") -join '')) {
        throw "no such memory: $n"
      }
    }
    Invoke-Sql @"
UPDATE memories SET status='superseded', superseded_by='$(Esc $By)' WHERE name='$(Esc $Name)';
"@ | Out-Null
    "superseded: $Name -> $By"
    & $PSCommandPath index | Out-Null
  }

  'deprecate' {
    if (-not $Name) { throw "-Name is required" }
    Invoke-Sql "UPDATE memories SET status='deprecated', superseded_by=NULL WHERE name='$(Esc $Name)';" | Out-Null
    "deprecated: $Name (still stored and searchable with -All; not in the index)"
    & $PSCommandPath index | Out-Null
  }

  'revive' {
    if (-not $Name) { throw "-Name is required" }
    Invoke-Sql "UPDATE memories SET status='active', superseded_by=NULL WHERE name='$(Esc $Name)';" | Out-Null
    "revived: $Name"
    & $PSCommandPath index | Out-Null
  }

  'files' {
    if ($File) {
      # Reverse lookup: what do we know that concerns this file?
      Invoke-Sql @"
.mode list
.separator ' | '
SELECT m.name, m.type, m.description
FROM memory_files f JOIN memories m ON m.name = f.name
WHERE f.file_path = '$(Esc $File)' AND m.status='active'
ORDER BY m.name;
"@
      break
    }
    if (-not $Name) { throw "-Name (with -Add/-Remove) or -File (reverse lookup) is required" }
    if ($Add) {
      Invoke-Sql "INSERT OR IGNORE INTO memory_files(name,file_path) VALUES ('$(Esc $Name)','$(Esc $Add)');" | Out-Null
      "associated: $Name -> $Add"
    }
    if ($Remove) {
      Invoke-Sql "DELETE FROM memory_files WHERE name='$(Esc $Name)' AND file_path='$(Esc $Remove)';" | Out-Null
      "removed: $Name -> $Remove"
    }
    Invoke-Sql @"
.mode list
SELECT '  ' || file_path FROM memory_files WHERE name='$(Esc $Name)' ORDER BY file_path;
"@
  }

  'delete' {
    if (-not $Name) { throw "-Name is required" }
    Invoke-Sql "DELETE FROM memories WHERE name='$(Esc $Name)';"
    "deleted: $Name"
    & $PSCommandPath index | Out-Null
    # links/embeddings cascade now that FKs are enforced per connection, but vectors.db is a
    # SEPARATE database file that no foreign key can reach. Its now-stale vector would keep
    # consuming a KNN slot on every search until the index is rebuilt, so rebuild it here.
    # Best-effort: a missing vec0 extension must not turn a successful delete into a failure.
    $vec = Join-Path $MemDir 'vec.ps1'
    if (Test-Path $vec) {
      try { & $vec index | Out-Null }
      catch { "warning: vectors.db not rebuilt ($($_.Exception.Message)). Run: vec.ps1 index" }
    }
  }

  'todo' {
    Invoke-Sql @"
.mode list
.separator ' <- '
SELECT l.dst, group_concat(l.src, ', ')
FROM links l LEFT JOIN memories m ON m.name = l.dst
WHERE m.name IS NULL
GROUP BY l.dst ORDER BY l.dst;
"@
  }

  'index' {
    $rows = Invoke-Sql @"
.mode list
.separator '`t'
SELECT type, name, description FROM memories WHERE status='active' ORDER BY type, name;
"@
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# Memory index')
    $lines.Add('')
    $lines.Add('Generated from `memory.db` by `mem.ps1 index` -- do not edit by hand.')
    $lines.Add("Store of record: ``$Db`` (lives with the project, so it can be shared).")
    $lines.Add('To read or write memories, and for setup notes see `SETUP.md` alongside the DB:')
    $lines.Add('')
    $lines.Add('```')
    $lines.Add("powershell -NoProfile -File `"$MemDir\mem.ps1`" get    -Name <slug>")
    $lines.Add("powershell -NoProfile -File `"$MemDir\mem.ps1`" search -Query `"<fts query>`"")
    $lines.Add("powershell -NoProfile -File `"$MemDir\mem.ps1`" set    -Name <slug> -Description `"...`" -Type <t> -Body `"...`"")
    $lines.Add('```')
    $lines.Add('')

    $lastType = $null
    foreach ($r in $rows) {
      if (-not $r) { continue }
      $p = $r -split "`t", 3
      if ($p.Count -lt 3) { continue }
      if ($p[0] -ne $lastType) {
        $lines.Add('')
        $lines.Add("## $($p[0])")
        $lastType = $p[0]
      }
      $lines.Add("- ``$($p[1])`` -- $($p[2])")
    }
    if ($null -eq $lastType) { $lines.Add('_No memories stored yet._') }

    $enc = New-Object System.Text.UTF8Encoding $false
    $n = @($rows | Where-Object { $_ }).Count
    foreach ($out in Get-IndexTargets) {
      $dir = Split-Path -Parent $out
      if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
      [System.IO.File]::WriteAllLines($out, $lines, $enc)
      "index written: $out ($n memories)"
    }
  }
}
