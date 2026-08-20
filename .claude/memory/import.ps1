<#
Migrate existing markdown memory files into memory.db.

  import.ps1 -From <dir> [-DryRun] [-Overwrite] [-Archive]

Reads the one-memory-per-file format Claude Code writes:

    ---
    name: some-slug
    description: one-line summary
    metadata:
      type: feedback
    ---

    body text, may contain [[wikilinks]]

  -DryRun        Parse and report; write nothing. Run this first on a large set.
  -Overwrite     Replace rows whose name already exists (default: skip them).
  -Archive       After a successful import, move source files to <dir>\imported\.
                 Off by default -- nothing is deleted or moved unless you ask.
  -IncludePlain  Also import .md files that have NO memory frontmatter. Off by
                 default, and usually the wrong thing -- see below.

ONLY MEMORY-SHAPED FILES ARE IMPORTED BY DEFAULT.

A file counts as a memory if its frontmatter carries `node_type: memory`, or a
`name:`, or a `description:`. Everything else -- design docs, READMEs, agent
definitions, rules, notes -- is reported and skipped.

This matters: a repo's docs folder is not a memory store. AccountManager7 has 367
.md files (aiDocs, claude_docs, agents, rules, plus npm package READMEs under
node_modules) and NONE are memories; its 8 real memories live in the Claude Code
memory directory. Importing a 100KB design doc as one row buries the atomic facts
the store exists to hold, and pollutes both FTS and semantic search.

For a design doc, write a memory that POINTS AT it instead:
  mem.ps1 set -Name pageindex-design -Type reference `
    -Description "PageIndex vector/embedding design lives in src/aiDocs/PageIndexDesign.md" ...

Safe to re-run: existing names are skipped unless -Overwrite is passed.
MEMORY.md is always skipped (it is generated output, not a memory).
Non-recursive by design -- it reads one directory, so a stray -From cannot walk
into node_modules.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$From,
  [switch]$DryRun,
  [switch]$Overwrite,
  [switch]$Archive,
  [switch]$IncludePlain
)

# A body longer than this is almost certainly a document, not a memory.
$LargeBodyChars = 8000

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

if (-not (Test-Path $From))  { throw "-From directory not found: $From" }
if (-not (Test-Path $Db))    { throw "memory.db not found at $Db. Create it first: sqlite3 memory.db `".read schema.sql`"" }

function Esc([string]$s) { if ($null -eq $s) { '' } else { $s.Replace("'", "''") } }

function Invoke-Sql {
  param([string]$Sql)
  $f = Join-Path ([System.IO.Path]::GetTempPath()) ("imp-" + [guid]::NewGuid().ToString('N') + ".sql")
  try {
    [System.IO.File]::WriteAllText($f, $Sql, (New-Object System.Text.UTF8Encoding $false))
    $out = & $Sqlite $Db ".read $($f.Replace('\','/'))"
    if ($LASTEXITCODE -ne 0) { throw "sqlite3 exited $LASTEXITCODE" }
    $out
  } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

# The schema constrains type to these four. Anything else is remapped, and the
# remap is reported rather than applied silently.
$ValidTypes = @('user','feedback','project','reference')
$TypeAliases = @{
  'fact' = 'reference'; 'facts' = 'reference'; 'note' = 'reference'; 'notes' = 'reference'
  'decision' = 'project'; 'insight' = 'project'; 'discovery' = 'project'
  'relationship' = 'project'; 'preference' = 'user'; 'correction' = 'feedback'
  'guidance' = 'feedback'; 'memory' = 'reference'
}

function Parse-MemoryFile([System.IO.FileInfo]$file) {
  $raw = [System.IO.File]::ReadAllText($file.FullName)
  $name = $null; $description = $null; $type = $null; $body = $raw
  $isMemory = $false   # frontmatter carried node_type: memory, name:, or description:
  $originSessionId = $null
  $extraMetadata = $null
  $files = @()
  $meta = New-Object 'System.Collections.Specialized.OrderedDictionary'

  # Frontmatter is optional. When absent, fall back to filename + first line.
  if ($raw -match '^\s*---\s*\r?\n') {
    $lines = $raw -split "\r?\n"
    $close = -1
    for ($i = 1; $i -lt $lines.Count; $i++) {
      if ($lines[$i].Trim() -eq '---') { $close = $i; break }
    }
    if ($close -gt 0) {
      # Collect EVERY frontmatter key, then pull the modelled ones out. Anything
      # left over is preserved as JSON rather than silently dropped -- an earlier
      # version parsed only name/description/type and lost originSessionId.
      $inMetadata = $false
      for ($i = 1; $i -lt $close; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*$') { continue }
        # Nested metadata block: indented key: value under `metadata:`
        if ($line -match '^\s*metadata\s*:\s*$' -or $line -match '^\s*metadata\s*:\s*\S') {
          $inMetadata = $true
          continue
        }
        $indented = $line -match '^\s+\S'
        if (-not $indented) { $inMetadata = $false }

        if ($line -match '^\s*([A-Za-z0-9_.-]+)\s*:\s*(.*)$') {
          $key = $Matches[1].Trim()
          $val = $Matches[2].Trim().Trim('"',"'")
          if ($inMetadata) { $meta["metadata.$key"] = $val } else { $meta[$key] = $val }
        }
      }

      # name/description at the top level mark this as a real memory file.
      if ($meta.Contains('name'))        { $name = $meta['name']; $isMemory = $true }
      if ($meta.Contains('description')) { $description = $meta['description']; $isMemory = $true }
      if ($meta.Contains('metadata.node_type') -and $meta['metadata.node_type'] -eq 'memory') { $isMemory = $true }

      # type may sit at either level; the nested one wins (that is where the
      # standard format puts it).
      if ($meta.Contains('type'))          { $type = $meta['type'] }
      if ($meta.Contains('metadata.type')) { $type = $meta['metadata.type'] }

      foreach ($k in @('metadata.originSessionId','originSessionId','metadata.origin_session_id')) {
        if ($meta.Contains($k) -and -not $originSessionId) { $originSessionId = $meta[$k] }
      }

      # Files the memory is about, if the frontmatter listed any.
      foreach ($k in @('metadata.files','files')) {
        if ($meta.Contains($k) -and $meta[$k]) {
          $files += @($meta[$k] -split '[,;]' | ForEach-Object { $_.Trim().Trim('[',']','"',"'") } | Where-Object { $_ })
        }
      }

      # Whatever is left is unmodelled -- keep it verbatim.
      $known = @('name','description','type','metadata.type','metadata.node_type',
                 'metadata.originSessionId','originSessionId','metadata.origin_session_id',
                 'metadata.files','files')
      $leftover = @{}
      foreach ($k in $meta.Keys) { if ($known -notcontains $k) { $leftover[$k] = $meta[$k] } }
      if ($leftover.Count) { $extraMetadata = ($leftover | ConvertTo-Json -Depth 3 -Compress) }
      $body = ($lines[($close + 1)..($lines.Count - 1)] -join "`n").Trim()
    }
  }

  if (-not $name) { $name = [System.IO.Path]::GetFileNameWithoutExtension($file.Name) }

  # No description in frontmatter: use the first non-empty body line, trimmed.
  if (-not $description) {
    $first = ($body -split "\r?\n" | Where-Object { $_.Trim() } | Select-Object -First 1)
    if ($first) {
      $description = ($first -replace '^#+\s*', '').Trim()
      if ($description.Length -gt 200) { $description = $description.Substring(0, 197) + '...' }
    } else {
      $description = $name
    }
  }

  $note = $null
  $rawType = $type
  if (-not $type) {
    $type = 'reference'; $note = "no type in frontmatter -> reference"
  } elseif ($ValidTypes -notcontains $type) {
    $key = $type.ToLower()
    if ($TypeAliases.ContainsKey($key)) {
      $type = $TypeAliases[$key]; $note = "type '$rawType' -> $type"
    } else {
      $type = 'reference'; $note = "unknown type '$rawType' -> reference"
    }
  }

  [pscustomobject]@{
    Name = $name; Description = $description; Type = $type
    Body = $body; File = $file; Note = $note
    Shape = if ($isMemory) { 'memory' } else { 'plain' }
    OriginSessionId = $originSessionId
    ExtraMetadata   = $extraMetadata
    Files           = @($files | Select-Object -Unique)
  }
}

# ---- scan ----------------------------------------------------------------

$files = Get-ChildItem $From -File -Filter *.md |
         Where-Object { $_.Name -ne 'MEMORY.md' }

if (-not $files) { "no .md memory files found in $From (MEMORY.md is skipped by design)"; return }

$parsed = @()
$failed = @()
foreach ($f in $files) {
  try { $parsed += Parse-MemoryFile $f }
  catch { $failed += [pscustomobject]@{ File = $f.Name; Error = $_.Exception.Message } }
}

# Partition memory-shaped files from plain documents. Plain files are skipped
# unless -IncludePlain, so pointing -From at a docs folder is a no-op, not a mess.
$plain  = @($parsed | Where-Object { $_.Shape -eq 'plain' })
if (-not $IncludePlain) {
  $parsed = @($parsed | Where-Object { $_.Shape -eq 'memory' })
}

$existing = @{}
foreach ($n in (Invoke-Sql ".mode list`nSELECT name FROM memories;")) {
  if ($n) { $existing[$n] = $true }
}

# ---- normalize wikilink targets to memory names --------------------------
# A file-based memory store links by FILENAME, but the name that becomes the memory's
# identity comes from frontmatter -- and the two conventions need not agree. Measured on
# AccountManager7 2026-08-20: every file's `name:` was already kebab-case
# (feedback-scope-discipline) while its body linked the underscored filename
# ([[feedback_bytestore_access]]). Imported verbatim, 30 of 31 links pointed at names no
# memory had, so the whole link graph arrived dangling while the import reported success.
#
# So rewrite a target to its hyphenated form when, and only when, that form is a real
# memory name -- in this source set or already stored. An underscored target with no such
# counterpart is left untouched: that is a genuinely unresolved link, not a convention
# mismatch, and silently rewriting it would invent a reference the author never made.
$knownNames = @{}
foreach ($p in $parsed)        { $knownNames[$p.Name] = $true }
foreach ($n in $existing.Keys) { $knownNames[$n] = $true }

$rewrites = 0
foreach ($p in $parsed) {
  if ($p.Body -notmatch '\[\[') { continue }
  $p.Body = [regex]::Replace($p.Body, '\[\[([^\]]+)\]\]', {
    param($m)
    $dst = $m.Groups[1].Value.Trim()
    if ($dst -notmatch '_') { return $m.Value }
    $kebab = $dst.Replace('_', '-')
    if ($knownNames.ContainsKey($kebab)) { $script:rewrites++; return "[[$kebab]]" }
    return $m.Value
  })
}

# Duplicate slugs within the source set would silently collapse on insert.
$dupes = $parsed | Group-Object Name | Where-Object { $_.Count -gt 1 }

"source     : $From"
"found      : $($files.Count) file(s)"
"memories   : $($parsed.Count)  (memory-shaped frontmatter)"
"plain docs : $($plain.Count)$(if ($plain.Count -and -not $IncludePlain) { ' - SKIPPED (pass -IncludePlain to force)' })"
if ($failed.Count) { "parse fail : $($failed.Count)" }
"already in : $(@($parsed | Where-Object { $existing.ContainsKey($_.Name) }).Count)"
if ($rewrites) { "normalized : $rewrites wikilink target(s) from filename form to memory name" }
"mode       : $(if ($DryRun) { 'DRY RUN - nothing will be written' } else { if ($Overwrite) { 'import, overwriting existing' } else { 'import, skipping existing' } })"
""

if ($plain.Count -and -not $IncludePlain) {
  "  skipped as documents (no memory frontmatter):"
  foreach ($p in ($plain | Select-Object -First 10)) {
    "    - $($p.File.Name) ($([math]::Round($p.File.Length/1KB,1)) KB)"
  }
  if ($plain.Count -gt 10) { "    ... and $($plain.Count - 10) more" }
  "  For a document, write a memory that points at it rather than importing it."
  ""
}

foreach ($p in $parsed) {
  if ($p.Body.Length -gt $LargeBodyChars) {
    Write-Warning "$($p.Name) body is $($p.Body.Length) chars -- that reads like a document, not an atomic memory. Consider splitting it, or storing a pointer instead."
  }
}

foreach ($d in $dupes) {
  Write-Warning "duplicate slug '$($d.Name)' in $($d.Count) files: $(($d.Group.File.Name) -join ', ') -- only one will survive"
}
foreach ($f in $failed) { Write-Warning "could not parse $($f.File): $($f.Error)" }

$toWrite = @()
foreach ($p in $parsed) {
  $status =
    if ($existing.ContainsKey($p.Name)) { if ($Overwrite) { 'overwrite' } else { 'skip' } }
    else { 'new' }
  if ($status -ne 'skip') { $toWrite += $p }
  $flag = if ($p.Note) { "  [$($p.Note)]" } else { '' }
  "  $($status.PadRight(9)) $($p.Type.PadRight(9)) $($p.Name)$flag"
}
""

if ($DryRun) {
  "DRY RUN complete - $($toWrite.Count) would be written. Re-run without -DryRun to import."
  return
}

if (-not $toWrite) { "nothing to import."; return }

# ---- write ---------------------------------------------------------------

$sql = New-Object System.Text.StringBuilder
[void]$sql.AppendLine("BEGIN;")
foreach ($p in $toWrite) {
  $origin = if ($p.OriginSessionId) { "'$(Esc $p.OriginSessionId)'" } else { 'NULL' }
  $extra  = if ($p.ExtraMetadata)   { "'$(Esc $p.ExtraMetadata)'" }   else { 'NULL' }
  [void]$sql.AppendLine(@"
INSERT INTO memories(name, description, type, body, origin_session_id, source_path, extra_metadata)
VALUES ('$(Esc $p.Name)', '$(Esc $p.Description)', '$(Esc $p.Type)', '$(Esc $p.Body)',
        $origin, '$(Esc $p.File.FullName)', $extra)
ON CONFLICT(name) DO UPDATE SET
  description=excluded.description, type=excluded.type, body=excluded.body,
  origin_session_id=COALESCE(excluded.origin_session_id, memories.origin_session_id),
  source_path=excluded.source_path,
  extra_metadata=COALESCE(excluded.extra_metadata, memories.extra_metadata);
DELETE FROM links WHERE src='$(Esc $p.Name)';
DELETE FROM memory_files WHERE name='$(Esc $p.Name)';
"@)
  foreach ($m in [regex]::Matches($p.Body, '\[\[([^\]]+)\]\]')) {
    $dst = $m.Groups[1].Value.Trim()
    # Only kebab slugs are real targets. Same guard as mem.ps1 / mine-transcripts.ps1.
    if ($dst -notmatch '^[a-z0-9][a-z0-9._-]*$') { continue }
    if ($dst -and $dst -ne $p.Name) {
      [void]$sql.AppendLine("INSERT OR IGNORE INTO links(src,dst) VALUES ('$(Esc $p.Name)','$(Esc $dst)');")
    }
  }
  foreach ($fp in $p.Files) {
    [void]$sql.AppendLine("INSERT OR IGNORE INTO memory_files(name,file_path) VALUES ('$(Esc $p.Name)','$(Esc $fp)');")
  }
}
[void]$sql.AppendLine("COMMIT;")
Invoke-Sql $sql.ToString() | Out-Null

"imported: $($toWrite.Count) memory/memories"

if ($Archive) {
  $dest = Join-Path $From 'imported'
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  foreach ($p in $toWrite) { Move-Item -LiteralPath $p.File.FullName -Destination $dest -Force }
  "archived $($toWrite.Count) source file(s) to $dest"
} else {
  "source files left in place. Verify the import, then re-run with -Archive to move them aside."
}

& (Join-Path $MemDir 'mem.ps1') index
""
"Next: review with 'mem.ps1 list', check 'mem.ps1 todo' for unresolved [[links]],"
"then 'vec.ps1 embed -All' if semantic search is configured."
