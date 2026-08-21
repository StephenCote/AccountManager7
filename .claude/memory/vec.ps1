<#
Semantic (vector) search over the memory store.

  vec.ps1 store  -Name slug -Vector '[0.1,...]' -Model voyage-3   # store a precomputed embedding
  vec.ps1 embed  -Name slug                                       # embed via $env:MEM_EMBED_CMD, then store
  vec.ps1 embed  -All                                             # embed everything missing or stale
  vec.ps1 index                                                   # (re)build the sqlite-vec index
  vec.ps1 search -Query "..." [-K 5] [-Force fallback|vec]        # semantic search
  vec.ps1 status                                                  # coverage: missing / stale / indexed
  vec.ps1 provider                                                # show the configured provider
  vec.ps1 provider -Set azure|ollama|local|"<cmd>" [-Scope user]  # persist it (see memconfig.ps1)

The provider is resolved by memconfig.ps1: $env:MEM_EMBED_CMD, then <memdir>\embed.config,
then ~\.claude\embed.config. Prefer `provider -Set ... -Scope user` over exporting the
variable by hand -- a shell variable is gone next session, and every ported project
inherits the user-level file.

Two tiers, by design:
  * memory.db  -> `embeddings` table, plain JSON. Readable with stock sqlite3.
  * vectors.db -> sqlite-vec `vec0` KNN index. Derived, rebuildable, needs vec0 extension.
Search uses the vec0 index when the extension loads, and otherwise falls back to
pure-SQL cosine over the JSON vectors, so semantic search still works for anyone
who does not have the platform binary.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory, Position = 0)]
  [ValidateSet('store','embed','index','search','status','provider')]
  [string]$Command,

  [string]$Name,
  [string]$Vector,
  [string]$Model,
  [string]$Query,
  [int]$K = 5,
  [switch]$All,
  [ValidateSet('fallback','vec')]
  [string]$Force,

  # provider only
  [string]$Set,
  [ValidateSet('project','user')]
  [string]$Scope = 'user'
)

$ErrorActionPreference = 'Stop'

# sqlite3 emits UTF-8. PowerShell decodes a native command's stdout using [Console]::OutputEncoding,
# which on a default Windows console is a legacy code page (437/1252) -- so every non-ASCII character
# round-tripped out of the DB came back double-encoded. That is why MEMORY.md rendered em-dashes as
# mojibake while the DB itself was clean: the damage happened on READ, between sqlite3 and PowerShell,
# before anything was written. Guarded: setting it throws when stdout is a redirected pipe.
try {
  [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
  $OutputEncoding = [Console]::OutputEncoding
} catch { }   # throws when stdout/stdin is a redirected pipe (i.e. when launched from a hook)

$MemDir  = Split-Path -Parent $PSCommandPath
$Db      = Join-Path $MemDir 'memory.db'
$VecDb   = Join-Path $MemDir 'vectors.db'
$ToolDir = Join-Path $env:USERPROFILE '.claude\tools\sqlite'

# Provider resolution lives in memconfig.ps1 so mine-transcripts.ps1 shares it.
$ConfigLib = Join-Path $MemDir 'memconfig.ps1'
if (-not (Test-Path $ConfigLib)) {
  throw "memconfig.ps1 is missing from $MemDir. Copy it alongside vec.ps1 -- see SETUP.md section 4."
}
. $ConfigLib

function Resolve-Sqlite {
  if ($env:SQLITE3) { return $env:SQLITE3 }
  $onPath = Get-Command sqlite3 -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }
  $local = Join-Path $ToolDir 'sqlite3.exe'
  if (Test-Path $local) { return $local }
  throw "sqlite3 not found. See SETUP.md."
}
$Sqlite = Resolve-Sqlite

# vec0 extension path, without the platform file extension (.load appends it).
function Get-Vec0Base {
  if ($env:SQLITE_VEC0) { return $env:SQLITE_VEC0.Replace('\', '/') }
  foreach ($ext in 'dll', 'so', 'dylib') {
    $p = Join-Path $ToolDir "vec0.$ext"
    if (Test-Path $p) { return ($p -replace "\.$ext$", '').Replace('\', '/') }
  }
  $null
}

function Test-Vec0 {
  $base = Get-Vec0Base
  if (-not $base) { return $false }
  $out = & $Sqlite ":memory:" ".load $base" "SELECT vec_version();" 2>&1
  return ($LASTEXITCODE -eq 0 -and "$out" -match 'v\d')
}

function Esc([string]$s) { if ($null -eq $s) { '' } else { $s.Replace("'", "''") } }

function Invoke-Sql {
  param([string]$Sql, [string]$Target = $Db, [switch]$LoadVec)
  $f = Join-Path ([System.IO.Path]::GetTempPath()) ("vec-" + [guid]::NewGuid().ToString('N') + ".sql")
  try {
    # foreign_keys is per-connection and defaults to OFF, so schema.sql's one-time setting is
    # inert here and `ON DELETE CASCADE` on `embeddings` never fires. See mem.ps1's Invoke-Sql.
    $prelude = "PRAGMA foreign_keys=ON;`n"
    if ($LoadVec) {
      $base = Get-Vec0Base
      if (-not $base) { throw "vec0 extension not found. Set `$env:SQLITE_VEC0 or see SETUP.md." }
      $prelude = ".load $base`n" + $prelude
    }
    [System.IO.File]::WriteAllText($f, $prelude + $Sql, (New-Object System.Text.UTF8Encoding $false))
    $out = & $Sqlite $Target ".read $($f.Replace('\','/'))"
    if ($LASTEXITCODE -ne 0) { throw "sqlite3 exited $LASTEXITCODE" }
    $out
  } finally {
    Remove-Item $f -Force -ErrorAction SilentlyContinue
  }
}

function Get-Sha256([string]$text) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    ($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($text)) |
      ForEach-Object { $_.ToString('x2') }) -join ''
  } finally { $sha.Dispose() }
}

# The text that actually gets embedded: description carries the recall signal, body the detail.
function Get-EmbedText([string]$slug) {
  $rows = Invoke-Sql @"
.mode list
.separator '`t'
SELECT description, body FROM memories WHERE name='$(Esc $slug)';
"@
  $joined = ($rows | Where-Object { $_ }) -join "`n"
  if (-not $joined) { throw "no such memory: $slug" }
  $joined.Replace("`t", "`n")
}

function Parse-Vector([string]$json) {
  $v = $json | ConvertFrom-Json
  if ($v -isnot [array]) { throw "vector must be a JSON array of numbers" }
  [double[]]$v
}

function Save-Embedding([string]$slug, [double[]]$v, [string]$model, [string]$sha) {
  $norm = [math]::Sqrt(($v | ForEach-Object { $_ * $_ } | Measure-Object -Sum).Sum)
  if ($norm -eq 0) { throw "refusing to store a zero vector for $slug" }
  $json = '[' + (($v | ForEach-Object { $_.ToString([cultureinfo]::InvariantCulture) }) -join ',') + ']'
  Invoke-Sql @"
INSERT INTO embeddings(name, model, dim, vec, norm, content_sha256)
VALUES ('$(Esc $slug)', '$(Esc $model)', $($v.Length), '$(Esc $json)', $($norm.ToString([cultureinfo]::InvariantCulture)), '$(Esc $sha)')
ON CONFLICT(name) DO UPDATE SET
  model=excluded.model, dim=excluded.dim, vec=excluded.vec,
  norm=excluded.norm, content_sha256=excluded.content_sha256,
  created_at=datetime('now');
"@ | Out-Null
  "embedded: $slug (dim=$($v.Length), model=$model)"
}

# Provider hook. Anthropic has no embeddings endpoint, so the provider is yours: any
# command that reads text on stdin and prints a JSON array. Configured, not hardcoded.
function Invoke-EmbedCmd([string]$text) {
  $p = Resolve-EmbedProvider -MemDir $MemDir
  if (-not $p.Cmd) {
    throw @"
No embedding provider configured. Persist one so it survives this shell:
  vec.ps1 provider -Set azure   -Scope user      # or ollama / local / "<any command>"
That writes ~\.claude\embed.config, which every project inherits.
For one shell only: `$env:MEM_EMBED_CMD = 'python embed.py'
See SETUP.md -> Embedding provider.
"@
  }
  # Non-reserved config keys become environment variables for the provider subprocess,
  # which is how connection-file paths and hosts reach it without living in the scripts.
  Import-MemProviderEnv -MemDir $MemDir
  $out = ($text | & cmd /c $p.Cmd) -join ''
  if ($LASTEXITCODE -ne 0) { throw "embedding provider failed (exit $LASTEXITCODE): $out" }
  Parse-Vector $out
}

switch ($Command) {

  'store' {
    if (-not $Name -or -not $Vector) { throw "-Name and -Vector are required" }
    if (-not $Model) { $Model = 'unknown' }
    Save-Embedding $Name (Parse-Vector $Vector) $Model (Get-Sha256 (Get-EmbedText $Name))
  }

  'embed' {
    $targets = @()
    if ($All) {
      # Deletes cascade into `embeddings` now, but a DB written before FKs were enforced can
      # still hold rows whose memory is gone. The staleness scan below cannot see them -- it
      # walks `memories`, so an embedding with no memory is never visited -- yet they still get
      # copied into vectors.db, where each one silently eats a KNN result slot.
      $orphanSql = "SELECT name FROM embeddings WHERE name NOT IN (SELECT name FROM memories)"
      $orphans = @(Invoke-Sql ".mode list`n$orphanSql;" | Where-Object { $_ })
      if ($orphans) {
        Invoke-Sql "DELETE FROM embeddings WHERE name NOT IN (SELECT name FROM memories);"
        "pruned $($orphans.Count) orphaned embedding(s): $($orphans -join ', ')"
      }
      # Missing or stale: no row, or the embedded text has changed since.
      foreach ($slug in (Invoke-Sql ".mode list`nSELECT name FROM memories ORDER BY name;")) {
        if (-not $slug) { continue }
        $stored = (Invoke-Sql ".mode list`nSELECT content_sha256 FROM embeddings WHERE name='$(Esc $slug)';") -join ''
        if ($stored -ne (Get-Sha256 (Get-EmbedText $slug))) { $targets += $slug }
      }
      # A prune with nothing to re-embed still leaves vectors.db holding the pruned vectors,
      # so it must not take the early exit that skips the index rebuild.
      if (-not $targets) {
        if ($orphans) { & $PSCommandPath index } else { "nothing to embed - all embeddings current" }
        break
      }
    } else {
      if (-not $Name) { throw "-Name or -All is required" }
      $targets = @($Name)
    }
    # Record which model produced these vectors: 'unknown' rows make a later
    # dimension mismatch much harder to diagnose. Config supplies it if env does not.
    if (-not $Model) {
      $rp = Resolve-EmbedProvider -MemDir $MemDir
      $Model = if ($rp.Model) { $rp.Model } else { 'unknown' }
    }
    foreach ($slug in $targets) {
      $text = Get-EmbedText $slug
      Save-Embedding $slug (Invoke-EmbedCmd $text) $Model (Get-Sha256 $text)
    }
    & $PSCommandPath index
  }

  'index' {
    if (-not (Test-Vec0)) {
      "vec0 extension unavailable - skipping index build (search will use the SQL cosine fallback)"
      break
    }
    $dims = @(Invoke-Sql ".mode list`nSELECT DISTINCT dim FROM embeddings;" | Where-Object { $_ })
    if (-not $dims) { "no embeddings stored yet - nothing to index"; break }
    if ($dims.Count -gt 1) {
      throw "embeddings have mixed dimensions ($($dims -join ', ')). Re-embed everything with one model: vec.ps1 embed -All"
    }
    $dim = [int]$dims[0]
    Remove-Item $VecDb -Force -ErrorAction SilentlyContinue
    $m = $Db.Replace('\', '/')
    Invoke-Sql -Target $VecDb -LoadVec @"
ATTACH DATABASE '$m' AS mem;
CREATE VIRTUAL TABLE vec_memories USING vec0(
  memory_name TEXT PRIMARY KEY,
  embedding float[$dim] distance_metric=cosine
);
INSERT INTO vec_memories(memory_name, embedding)
  SELECT name, vec FROM mem.embeddings;
"@ | Out-Null
    $n = (Invoke-Sql -Target $VecDb -LoadVec ".mode list`nSELECT count(*) FROM vec_memories;") -join ''
    "index built: $VecDb ($n vectors, dim=$dim, cosine)"
  }

  'search' {
    if (-not $Query) { throw "-Query is required" }
    $qv = Invoke-EmbedCmd $Query
    $qjson = '[' + (($qv | ForEach-Object { $_.ToString([cultureinfo]::InvariantCulture) }) -join ',') + ']'

    $useVec = if ($Force -eq 'vec') { $true }
              elseif ($Force -eq 'fallback') { $false }
              else { (Test-Vec0) -and (Test-Path $VecDb) }

    if ($useVec) {
      $m = $Db.Replace('\', '/')
      # Over-fetch from the index, THEN join, THEN limit. vec0 applies `k` inside its own scan,
      # so asking it for exactly $K and inner-joining afterwards means any vector whose memory
      # is gone (vectors.db is a separate file and can lag memory.db) burns one of the $K slots
      # and the caller silently gets fewer rows than they asked for.
      $overfetch = $K * 3 + 10
      Invoke-Sql -Target $VecDb -LoadVec @"
.mode list
.separator ' | '
ATTACH DATABASE '$m' AS mem;
SELECT round(1.0 - h.d, 4) AS similarity, h.nm, m.type, m.description
FROM (SELECT memory_name AS nm, distance AS d
      FROM vec_memories
      WHERE embedding MATCH '$(Esc $qjson)' AND k = $overfetch) h
JOIN mem.memories m ON m.name = h.nm
ORDER BY h.d
LIMIT $K;
"@
    } else {
      # Pure-SQL cosine over the JSON vectors: no extension required.
      $qn = [math]::Sqrt(($qv | ForEach-Object { $_ * $_ } | Measure-Object -Sum).Sum)
      Invoke-Sql @"
.mode list
.separator ' | '
WITH q(i, v) AS (SELECT key, value FROM json_each('$(Esc $qjson)')),
     dot AS (
       SELECT e.name AS nm, sum(q.v * je.value) AS d, e.norm AS nrm
       FROM embeddings e
       JOIN json_each(e.vec) je
       JOIN q ON q.i = je.key
       GROUP BY e.name
     )
SELECT round(dot.d / (dot.nrm * $($qn.ToString([cultureinfo]::InvariantCulture))), 4) AS similarity,
       dot.nm, m.type, m.description
FROM dot JOIN memories m ON m.name = dot.nm
ORDER BY similarity DESC
LIMIT $K;
"@
    }
  }

  'provider' {
    if ($Set) {
      # Friendly names map to the shipped providers. Anything else is used verbatim,
      # so a custom script or interpreter still works.
      $known = @{
        # Quote the path: %MEMDIR% often contains a space (e.g. "...\AI Memory\...").
        azure  = @{ cmd = 'powershell -NoProfile -File "%MEMDIR%\embed-azure.ps1"';  model = 'text-embedding-ada-002' }
        ollama = @{ cmd = 'powershell -NoProfile -File "%MEMDIR%\embed-ollama.ps1"'; model = 'nomic-embed-text' }
        local  = @{ cmd = 'powershell -NoProfile -File "%MEMDIR%\embed-local.ps1"';  model = 'local' }
      }
      $vals = @{}
      if ($known.ContainsKey($Set.ToLower())) {
        $preset = $known[$Set.ToLower()]   # NOT $k: that is the [int]$K parameter, case-insensitively
        $vals['cmd']   = $preset.cmd
        $vals['model'] = if ($Model) { $Model } else { $preset.model }
      } else {
        $vals['cmd'] = $Set
        if ($Model) { $vals['model'] = $Model }
        if ($Scope -eq 'user' -and $Set -match '[\\/]' -and $Set -notlike '*%MEMDIR%*') {
          Write-Warning "user-scope command has a path but no %MEMDIR% token, so other projects will run this project's copy"
        }
      }
      $path = Set-MemConfigValue -Path (Get-MemConfigPath -MemDir $MemDir -Scope $Scope) -Values $vals
      "wrote $Scope config: $path"
      foreach ($kk in ($vals.Keys | Sort-Object)) { "  $kk = $($vals[$kk])" }
      ""
    }

    $p = Resolve-EmbedProvider -MemDir $MemDir
    "embed command : $(if ($p.Cmd) { $p.Cmd } else { 'NOT CONFIGURED' })"
    "embed model   : $(if ($p.Model) { $p.Model } else { 'unset (embeddings will record model=unknown)' })"
    "resolved from : $(if ($p.From) { $p.From } else { 'nothing - see SETUP.md -> Embedding provider' })"
    ""
    "config files searched, first match wins:"
    foreach ($cp in (Get-MemConfigPath -MemDir $MemDir)) {
      "  $(if (Test-Path $cp) { '[found]  ' } else { '[absent] ' })$cp"
    }
    $extra = @((Get-MemConfig -MemDir $MemDir).Values.Keys | Where-Object { $_ -notin @('cmd','model') } | Sort-Object)
    if ($extra) {
      ""
      "exported to the provider subprocess: $($extra -join ', ')"
    }
  }

  'status' {
    $p = Resolve-EmbedProvider -MemDir $MemDir
    "vec0 extension : $(if (Test-Vec0) { 'available (' + (Get-Vec0Base) + ')' } else { 'NOT available - fallback cosine only' })"
    "vector index   : $(if (Test-Path $VecDb) { $VecDb } else { 'not built' })"
    "embed provider : $(if ($p.Cmd) { "$($p.Cmd)  [from $($p.From)]" } else { 'not configured - run: vec.ps1 provider -Set azure -Scope user' })"
    ""
    Invoke-Sql @"
.mode list
.separator ' | '
SELECT 'memories', count(*) FROM memories
UNION ALL SELECT 'embedded', count(*) FROM embeddings
UNION ALL SELECT 'models', COALESCE(group_concat(DISTINCT model), '-') FROM embeddings
UNION ALL SELECT 'dims', COALESCE(group_concat(DISTINCT dim), '-') FROM embeddings;
"@
    ""
    "unembedded:"
    Invoke-Sql @"
.mode list
SELECT '  ' || m.name FROM memories m
LEFT JOIN embeddings e ON e.name = m.name
WHERE e.name IS NULL ORDER BY m.name;
"@
  }
}
