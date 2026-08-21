<#
Mine Claude Code session transcripts into memories.

Claude Code writes every session to ~/.claude/projects/<slug>/<session-id>.jsonl.
That is where most of a project's hard-won knowledge actually lives -- decisions,
gotchas, dead ends -- and none of it reaches the memory store on its own. This reads
those transcripts, asks an LLM to extract durable facts, and writes them as memories
tagged with the session they came from.

  mine-transcripts.ps1 -DryRun                 # what would be mined (no LLM calls, no writes)
  mine-transcripts.ps1                         # mine this project's unmined sessions
  mine-transcripts.ps1 -SessionId <id>         # one session
  mine-transcripts.ps1 -Slug c--Projects-...   # another project's transcripts
  mine-transcripts.ps1 -Limit 3 -Preview       # extract and print, still no writes
  mine-transcripts.ps1 -Force                  # re-mine sessions already recorded

Extraction runs on Azure OpenAI chat -- the same connection AccountManager7 uses --
so no Anthropic key is needed. Config resolution matches embed-azure.ps1:
  $env:AZURE_OPENAI_CHAT_ENDPOINT / _KEY, else $env:AZURE_OPENAI_CHAT_CONNECTION_FILE,
  else the AM7 "GPT 5.6 Terra Connection.txt".
  $env:AZURE_OPENAI_CHAT_DEPLOYMENT  default gpt-4.1

Nothing is written until you drop -DryRun/-Preview. Every created memory records its
origin_session_id, so a suspicious fact can always be traced back to its conversation.
#>
[CmdletBinding()]
param(
  [string]$Slug,
  [string]$SessionId,
  [int]$Limit = 0,
  [int]$MaxChars = 90000,
  [string]$Deployment,
  [switch]$DryRun,
  [switch]$Preview,
  [switch]$Force,
  [switch]$ForeignOk,          # allow -Slug from another project to write into THIS store
  # Cosine at/above which a candidate counts as a duplicate. Calibrated against
  # ada-002 on 2026-08-19: outright restatements of an existing memory scored
  # 0.92-0.93, overlapping-but-distinct facts 0.83-0.89. 0.88 catches the
  # restatements without discarding genuinely new detail. Re-tune per embedding
  # model -- these numbers do not transfer (nomic-embed-text scores differently).
  [double]$DedupThreshold = 0.88
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

$MemDir      = Split-Path -Parent $PSCommandPath
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MemDir)
$Db          = Join-Path $MemDir 'memory.db'

# Chat and embedding connection settings come from embed.config (shared resolver), so
# they persist across sessions instead of living in whatever shell happened to run this.
if (Test-Path (Join-Path $MemDir 'memconfig.ps1')) {
  . (Join-Path $MemDir 'memconfig.ps1')
  Import-MemProviderEnv -MemDir $MemDir
}

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

function Esc([string]$s) { if ($null -eq $s) { '' } else { $s.Replace("'", "''") } }

function Invoke-Sql([string]$Sql) {
  $f = Join-Path ([System.IO.Path]::GetTempPath()) ("mine-" + [guid]::NewGuid().ToString('N') + ".sql")
  try {
    [System.IO.File]::WriteAllText($f, $Sql, (New-Object System.Text.UTF8Encoding $false))
    $out = & $Sqlite $Db ".read $($f.Replace('\','/'))"
    if ($LASTEXITCODE -ne 0) { throw "sqlite3 exited $LASTEXITCODE" }
    $out
  } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

if (-not (Invoke-Sql ".mode list`nSELECT 1 FROM sqlite_master WHERE type='table' AND name='mined_sessions';")) {
  throw "table 'mined_sessions' is missing. Run migrate.ps1 first."
}

# ---- locate transcripts --------------------------------------------------

# Harness slug: lowercase the drive, then ':', '\', '/' AND SPACES all become '-'.
# Omitting the space rule yields a directory that does not exist, which made this script
# fail outright for any project whose path contains one. See SETUP.md gotcha #21.
$normalized = $ProjectRoot.Substring(0,1).ToLower() + $ProjectRoot.Substring(1)
$slugCandidates = @(
  ($normalized -replace '[:\\/ ]', '-'),
  ($normalized -replace '[:\\]', '-')      # pre-fix rule, if a dir exists under it
) | Select-Object -Unique

$ownSlug = $slugCandidates[0]
foreach ($cand in $slugCandidates) {
  if (Test-Path (Join-Path $env:USERPROFILE ".claude\projects\$cand")) { $ownSlug = $cand; break }
}
if (-not $Slug) { $Slug = $ownSlug }

# Mining another project's transcripts writes ITS facts into THIS store. That is
# almost always wrong -- AccountManager7 knowledge does not belong in another project's memory.db.
# Copy the scripts into that project and mine there instead.
if ($Slug -ne $ownSlug -and -not $ForeignOk -and -not $DryRun -and -not $Preview) {
  throw @"
Refusing to mine a foreign project's transcripts into this store.

  this store : $Db
               (own slug: $ownSlug)
  requested  : $Slug

Those memories would describe a different project. Either run the miner from inside
that project (copy .claude\memory there, per SETUP.md section 4), or pass -ForeignOk
if you genuinely intend the facts to land here. -Preview and -DryRun are unaffected.
"@
}

$projDir = Join-Path $env:USERPROFILE ".claude\projects\$Slug"
if (-not (Test-Path $projDir)) { throw "no transcript directory for slug '$Slug' ($projDir)" }

$files = @(Get-ChildItem $projDir -Filter *.jsonl -File -ErrorAction SilentlyContinue)
if ($SessionId) { $files = @($files | Where-Object { $_.BaseName -eq $SessionId }) }
if (-not $files) { "no .jsonl transcripts found in $projDir"; return }

$mined = @{}
foreach ($r in (Invoke-Sql ".mode list`nSELECT session_id FROM mined_sessions;")) {
  if ($r) { $mined[$r] = $true }
}

$targets = @()
foreach ($f in ($files | Sort-Object LastWriteTime -Descending)) {
  $already = $mined.ContainsKey($f.BaseName)
  if ($already -and -not $Force) { continue }
  $targets += $f
}
if ($Limit -gt 0) { $targets = @($targets | Select-Object -First $Limit) }

"slug        : $Slug"
"transcripts : $($files.Count) found, $($mined.Count) already mined"
"to process  : $($targets.Count)$(if ($Force) { ' (-Force: re-mining)' })"
"deployment  : $(if ($Deployment) { $Deployment } elseif ($env:AZURE_OPENAI_CHAT_DEPLOYMENT) { $env:AZURE_OPENAI_CHAT_DEPLOYMENT } else { 'gpt-4.1' })"
""
foreach ($f in $targets) {
  "  $($f.BaseName)  $([math]::Round($f.Length/1MB,2)) MB  $($f.LastWriteTime.ToString('yyyy-MM-dd HH:mm'))"
}
""
if (-not $targets) { "nothing to mine."; return }
if ($DryRun) { "DRY RUN - no LLM calls, nothing written."; return }

# ---- transcript -> condensed text ----------------------------------------

# Tool payloads dominate a transcript by volume and carry little durable meaning.
# Keep the human/assistant prose, plus a compact trace of which tools touched which
# files, and drop the rest.
function Convert-Transcript([string]$path) {
  $sb = New-Object System.Text.StringBuilder
  foreach ($line in [System.IO.File]::ReadLines($path)) {
    if (-not $line.Trim()) { continue }
    $rec = $null
    try { $rec = $line | ConvertFrom-Json } catch { continue }
    if ($rec.type -notin @('user','assistant')) { continue }
    $content = $rec.message.content
    if (-not $content) { continue }

    $role = $rec.type
    if ($content -is [string]) {
      [void]$sb.AppendLine("[$role] $content")
      continue
    }
    foreach ($b in $content) {
      switch ($b.type) {
        'text' {
          $tx = "$($b.text)".Trim()
          if ($tx) { [void]$sb.AppendLine("[$role] $tx") }
        }
        'tool_use' {
          $hint = ''
          foreach ($k in 'file_path','path','command','pattern','query') {
            if ($b.input.$k) { $hint = "$k=$($b.input.$k)"; break }
          }
          if ($hint.Length -gt 200) { $hint = $hint.Substring(0,200) }
          [void]$sb.AppendLine("[tool] $($b.name) $hint")
        }
        # tool_result deliberately omitted -- huge, and rarely the source of a durable fact.
      }
    }
  }
  $sb.ToString()
}

# Oldest-first would truncate away the conclusions. Keep the tail, which is where
# decisions land, plus the opening for framing.
function Limit-Text([string]$text, [int]$max) {
  if ($text.Length -le $max) { return $text }
  $head = [int]($max * 0.25)
  $tail = $max - $head
  return $text.Substring(0, $head) +
         "`n`n...[middle truncated: $($text.Length - $max) chars]...`n`n" +
         $text.Substring($text.Length - $tail)
}

# ---- Azure chat ----------------------------------------------------------

function Resolve-ChatConfig {
  if ($env:AZURE_OPENAI_CHAT_ENDPOINT -and $env:AZURE_OPENAI_CHAT_KEY) {
    return @{ Endpoint = $env:AZURE_OPENAI_CHAT_ENDPOINT.TrimEnd('/'); Key = $env:AZURE_OPENAI_CHAT_KEY }
  }
  # No hardcoded fallback: an absolute path here tied this script to one machine's
  # unrelated project. Name the connection file in embed.config instead.
  $cands = @()
  if ($env:AZURE_OPENAI_CHAT_CONNECTION_FILE) { $cands += $env:AZURE_OPENAI_CHAT_CONNECTION_FILE }
  foreach ($c in $cands) {
    if ($c -and (Test-Path $c)) {
      $j = Get-Content $c -Raw | ConvertFrom-Json
      if ($j.serverUrl -and $j.apiKey) { return @{ Endpoint = ([string]$j.serverUrl).TrimEnd('/'); Key = [string]$j.apiKey } }
    }
  }
  throw @"
No Azure OpenAI chat config. Durable fix -- add to ~\.claude\embed.config:
  AZURE_OPENAI_CHAT_CONNECTION_FILE = C:\path\outside\any\repo\chat-connection.txt
Or set AZURE_OPENAI_CHAT_ENDPOINT + AZURE_OPENAI_CHAT_KEY for this shell only.
Never commit the key. Note embeddings and chat are usually DIFFERENT Azure resources.
"@
}

$chat = Resolve-ChatConfig
$dep  = if ($Deployment) { $Deployment }
        elseif ($env:AZURE_OPENAI_CHAT_DEPLOYMENT) { $env:AZURE_OPENAI_CHAT_DEPLOYMENT }
        else { 'gpt-4.1' }
$apiVersion = if ($env:AZURE_OPENAI_CHAT_API_VERSION) { $env:AZURE_OPENAI_CHAT_API_VERSION } else { '2025-04-01-preview' }

$EXTRACTION_PROMPT = @'
You are reading a transcript of a coding session between a developer and an AI assistant.
Extract only DURABLE PROJECT KNOWLEDGE - facts that will still matter weeks from now and
are not obvious from reading the code itself.

Extract:
- Decisions and the REASONING behind them (why this approach over the alternative)
- Constraints, gotchas, and failure modes discovered the hard way
- Environment facts: hosts, endpoints, ports, paths, versions, deployment names
- Explicit user preferences or corrections about how work should be done

Do NOT extract:
- Narration of what was done ("created a file", "ran the tests")
- Anything obvious from the code or from git history
- Transient state ("the test is currently failing")
- Secrets. Never include an API key, token, or password in any field.

Return ONLY valid JSON, no markdown fences:
{
  "summary": "one sentence on what this session accomplished",
  "memories": [
    {
      "name": "short-kebab-case-slug",
      "description": "one line, used for search relevance",
      "type": "user|feedback|project|reference",
      "body": "The fact, with enough context to act on. For feedback/project, include a **Why:** line. Use markdown.",
      "files": ["path/touched", "..."]
    }
  ]
}

type: user = who the developer is / their preferences; feedback = guidance on how to work,
including corrections; project = ongoing work, goals, constraints; reference = pointers to
external resources, endpoints, tooling locations.

Convert relative dates to absolute. At most 8 memories; fewer is better. If the session
produced no durable knowledge, return {"summary":"...","memories":[]}.
'@

function Invoke-Extraction([string]$transcript) {
  $body = @{
    messages = @(
      @{ role = 'system'; content = 'You extract durable engineering knowledge and reply with strict JSON only.' },
      @{ role = 'user';   content = "$EXTRACTION_PROMPT`n`n---`nTRANSCRIPT:`n$transcript" }
    )
  }
  $uri = "$($chat.Endpoint)/openai/deployments/$dep/chat/completions?api-version=$apiVersion"

  # HttpClient, not Invoke-RestMethod: in PowerShell 5.1 the error body of a failed
  # Invoke-RestMethod is frequently unreadable, which turns every Azure 400 into a
  # blank "HTTP 400" with no cause. Azure puts the actual reason (content filter,
  # bad parameter, token limit) in that body, so it has to be readable.
  Add-Type -AssemblyName System.Net.Http

  # gpt-4.1 wants max_tokens; gpt-5.x rejects it and wants max_completion_tokens.
  # Verified 2026-08-19: gpt-5.6-terra 400s on max_tokens, gpt-4.1 400s on the newer one.
  $lastDetail = ''
  foreach ($tokenParam in @('max_completion_tokens','max_tokens')) {
    $attempt = $body.Clone()
    $attempt[$tokenParam] = 8000
    $json = $attempt | ConvertTo-Json -Depth 6

    $hc = New-Object System.Net.Http.HttpClient
    try {
      $hc.Timeout = [TimeSpan]::FromSeconds(600)
      $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $uri)
      $req.Headers.Add('api-key', $chat.Key)
      $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
      $resp = $hc.SendAsync($req).GetAwaiter().GetResult()
      $rb   = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      $code = [int]$resp.StatusCode

      if ($code -eq 200) { return ($rb | ConvertFrom-Json) }

      $lastDetail = $rb
      # An unsupported token parameter is the one 400 worth retrying.
      if ($code -eq 400 -and $tokenParam -eq 'max_completion_tokens' -and
          $rb -match "(?i)max_completion_tokens|unsupported|unrecognized") { continue }
      if ($code -eq 404) {
        throw "HTTP 404: no chat deployment '$dep' on $($chat.Endpoint). Set AZURE_OPENAI_CHAT_DEPLOYMENT. $rb"
      }
      if ($code -eq 400 -and $rb -match '(?i)content_filter|content management policy|jailbreak') {
        throw "HTTP 400 content filter on '$dep'. A transcript can trip Azure's filters (it is full of instructions and error text). Try -MaxChars smaller, or a different deployment. Body: $rb"
      }
      if ($code -eq 429) {
        throw "HTTP 429 rate limited on '$dep'. Retry later or lower -Limit. $rb"
      }
      throw "chat completion failed (HTTP $code) on '$dep': $rb"
    } finally {
      $hc.Dispose()
    }
  }
  throw "chat completion rejected both token parameters on '$dep'. Last response: $lastDetail"
}

function Parse-Extraction($resp) {
  $text = "$($resp.choices[0].message.content)".Trim()
  if (-not $text) { return $null }
  $text = $text -replace '^```(?:json)?\s*', '' -replace '\s*```$', ''
  try { return $text | ConvertFrom-Json } catch { return $null }
}

# ---- run -----------------------------------------------------------------

$existing = @{}
foreach ($n in (Invoke-Sql ".mode list`nSELECT name FROM memories;")) { if ($n) { $existing[$n] = $true } }

# --- semantic dedup -------------------------------------------------------
# An LLM naming the same fact differently ("ollama-embedding-head-behavior" vs an
# existing "ollama-embedding-host") slips straight past name-collision checks, so
# near-duplicates accumulate. Compare meaning instead, using the embeddings already
# in the store. Requires MEM_EMBED_CMD; without it, dedup is skipped and said so.
$dedupOn = [bool]$env:MEM_EMBED_CMD -and
           ((Invoke-Sql ".mode list`nSELECT count(*) FROM embeddings;") -join '') -ne '0'

function Get-CandidateVector([string]$text) {
  $out = ($text | & cmd /c $env:MEM_EMBED_CMD) -join ''
  if ($LASTEXITCODE -ne 0 -or -not $out) { return $null }
  try { return [double[]]($out | ConvertFrom-Json) } catch { return $null }
}

function Find-NearDuplicate([string]$text, [double]$threshold) {
  $v = Get-CandidateVector $text
  if (-not $v) { return $null }
  $norm = [math]::Sqrt(($v | ForEach-Object { $_ * $_ } | Measure-Object -Sum).Sum)
  if ($norm -eq 0) { return $null }
  $json = '[' + (($v | ForEach-Object { $_.ToString([cultureinfo]::InvariantCulture) }) -join ',') + ']'
  $row = (Invoke-Sql @"
.mode list
.separator '|'
WITH q(i, v) AS (SELECT key, value FROM json_each('$(Esc $json)')),
     dot AS (
       SELECT e.name AS nm, sum(q.v * je.value) AS d, e.norm AS nrm
       FROM embeddings e JOIN json_each(e.vec) je JOIN q ON q.i = je.key
       WHERE e.dim = $($v.Length)
       GROUP BY e.name
     )
SELECT nm, round(d / (nrm * $($norm.ToString([cultureinfo]::InvariantCulture))), 4) AS sim
FROM dot ORDER BY sim DESC LIMIT 1;
"@) -join ''
  if (-not $row) { return $null }
  $parts = $row -split '\|'
  if ($parts.Count -lt 2) { return $null }
  if ([double]$parts[1] -ge $threshold) { return @{ Name = $parts[0]; Sim = [double]$parts[1] } }
  $null
}

$totalCreated = 0
foreach ($f in $targets) {
  "--- $($f.BaseName) ---"
  $condensed = Convert-Transcript $f.FullName
  if ($condensed.Length -lt 200) { "  transcript has no usable prose - skipping"; continue }
  $sent = Limit-Text $condensed $MaxChars
  "  condensed $([math]::Round($f.Length/1MB,2))MB -> $([math]::Round($condensed.Length/1KB))KB, sending $([math]::Round($sent.Length/1KB))KB"

  $resp = Invoke-Extraction $sent
  $parsed = Parse-Extraction $resp
  if (-not $parsed) { Write-Warning "  extraction returned unparseable output - skipping"; continue }

  "  summary: $($parsed.summary)"
  $cands = @($parsed.memories)
  if (-not $cands) { "  no durable knowledge found" }

  $created = 0
  foreach ($m in $cands) {
    if (-not ($m.name -and $m.description -and $m.body)) { continue }
    $slugName = ($m.name -replace '[^A-Za-z0-9-]', '-').ToLower().Trim('-')
    if (-not $slugName) { continue }
    $type = if ($m.type -in @('user','feedback','project','reference')) { $m.type } else { 'project' }

    if ($existing.ContainsKey($slugName)) {
      "    skip (name exists): $slugName"
      continue
    }

    if ($dedupOn) {
      $dup = Find-NearDuplicate "$($m.description)`n$($m.body)" $DedupThreshold
      if ($dup) {
        "    skip (near-duplicate of $($dup.Name), sim $($dup.Sim)): $slugName"
        continue
      }
    }

    if ($Preview) {
      "    [preview] $type / $slugName - $($m.description)"
      $created++
      continue
    }

    $sql = @"
INSERT INTO memories(name, description, type, body, origin_session_id, source_path)
VALUES ('$(Esc $slugName)', '$(Esc $m.description)', '$(Esc $type)', '$(Esc $m.body)',
        '$(Esc $f.BaseName)', '$(Esc $f.FullName)');
"@
    foreach ($fp in @($m.files)) {
      if ($fp) { $sql += "`nINSERT OR IGNORE INTO memory_files(name,file_path) VALUES ('$(Esc $slugName)','$(Esc $fp)');" }
    }
    foreach ($lk in [regex]::Matches("$($m.body)", '\[\[([^\]]+)\]\]')) {
      $dst = $lk.Groups[1].Value.Trim()
      # Only kebab slugs are real targets; an LLM writing '[[...]]' in prose must not
      # create a phantom link. See the same guard in mem.ps1.
      if ($dst -notmatch '^[a-z0-9][a-z0-9._-]*$') { continue }
      if ($dst -and $dst -ne $slugName) { $sql += "`nINSERT OR IGNORE INTO links(src,dst) VALUES ('$(Esc $slugName)','$(Esc $dst)');" }
    }
    Invoke-Sql $sql | Out-Null
    $existing[$slugName] = $true
    "    created: $type / $slugName"
    $created++
  }

  if (-not $Preview) {
    Invoke-Sql @"
INSERT INTO mined_sessions(session_id, transcript_path, transcript_bytes, model, memories_created, summary)
VALUES ('$(Esc $f.BaseName)', '$(Esc $f.FullName)', $($f.Length), '$(Esc $dep)', $created, '$(Esc $parsed.summary)')
ON CONFLICT(session_id) DO UPDATE SET
  transcript_bytes=excluded.transcript_bytes, model=excluded.model,
  memories_created=excluded.memories_created, summary=excluded.summary,
  mined_at=datetime('now');
"@ | Out-Null
  }
  $totalCreated += $created
  ""
}

"$totalCreated memory/memories $(if ($Preview) { 'would be created (preview)' } else { 'created' })."
if (-not $Preview -and $totalCreated -gt 0) {
  & (Join-Path $MemDir 'mem.ps1') index | Out-Null
  "index regenerated. Review with 'mem.ps1 list', then 'vec.ps1 embed -All'."
  "Extracted memories are LLM-generated - spot-check them; each carries origin_session_id."
}
