<#
Stop hook: the memory system's WRITE-side enforcement.

Why this exists. Everything else in this store is read-side: SessionStart injects the index,
UserPromptSubmit injects search hits and a written reminder. Nothing ever made a write happen,
so "did project knowledge get recorded this session" depended entirely on the model choosing to
run mem.ps1 -- which is exactly the failure the user kept observing as "memory.db never changes".
A reminder in the prompt is advisory; a Stop hook that returns decision=block is not.

Contract:
  - Blocks AT MOST ONCE per session. A gate that can block repeatedly can trap a session.
  - Only fires when the session actually did work (the git working tree changed). A read-only
    or conversational session has nothing to record and is not gated.
  - Never blocks when $env:MEMORY_GATE is 'off'.
  - Fails OPEN on every error. A broken gate must not be able to prevent finishing.

Baseline state per session lives in .state\<session-id>.json, written on first sight.
Registered in .claude/settings.json under hooks.Stop, ahead of the verification gate.
#>
$ErrorActionPreference = 'SilentlyContinue'

$MemDir = Split-Path -Parent $PSCommandPath
$Db     = Join-Path $MemDir 'memory.db'
$State  = Join-Path $MemDir '.state'
$Log    = Join-Path $MemDir 'hook.log'

function Write-Log([string]$m) {
  try {
    Add-Content -LiteralPath $Log -Encoding utf8 `
      -Value ("{0}  Stop/memory-gate: {1}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $m)
  } catch { }
}

function Get-Fingerprint {
  # Two independent signals: the file's write time AND the row count. mtime alone is fooled by
  # a WAL checkpoint or a read that touches the file; count alone misses an update-in-place.
  $mtime = $null; $count = $null
  if (Test-Path $Db) { $mtime = (Get-Item $Db).LastWriteTimeUtc.ToString('o') }
  try {
    $sqlite = (Get-Command sqlite3 -ErrorAction SilentlyContinue).Source
    if (-not $sqlite) {
      $local = Join-Path $env:USERPROFILE '.claude\tools\sqlite\sqlite3.exe'
      if (Test-Path $local) { $sqlite = $local }
    }
    if ($sqlite -and $mtime) {
      $count = (& $sqlite $Db "SELECT COUNT(*) || '/' || COALESCE(MAX(updated),'') FROM memories;" 2>$null | Out-String).Trim()
    }
  } catch { }
  @{ mtime = $mtime; count = $count }
}

function Get-TreeSignature {
  # Cheap proxy for "did this session change anything". Uses the git root, so it is unaffected
  # by which subdirectory the session is running in.
  try {
    $root = Split-Path -Parent (Split-Path -Parent $MemDir)
    $porc = (& git -C $root status --porcelain 2>$null | Out-String)
    $sha  = [System.Security.Cryptography.SHA1]::Create().ComputeHash(
              [System.Text.Encoding]::UTF8.GetBytes($porc))
    return ([BitConverter]::ToString($sha) -replace '-','')
  } catch { return $null }
}

try {
  if ($env:MEMORY_GATE -eq 'off') { exit 0 }

  # Read stdin only when it is actually a pipe. ReadToEnd() on a non-redirected
  # console blocks forever, and a hook that blocks just burns its whole timeout.
  $raw = if ([Console]::IsInputRedirected) { [Console]::In.ReadToEnd() } else { '' }
  $payload = $null
  try { $payload = $raw | ConvertFrom-Json } catch { }

  # Never re-block a stop that this hook (or the verification gate) already blocked.
  if ($payload -and $payload.stop_hook_active) { exit 0 }

  $sid = if ($payload -and $payload.session_id) { "$($payload.session_id)" } else { 'unknown' }
  if (-not (Test-Path $State)) { New-Item -ItemType Directory -Path $State -Force | Out-Null }
  $file = Join-Path $State "$sid.json"

  $now  = Get-Fingerprint
  $tree = Get-TreeSignature

  if (-not (Test-Path $file)) {
    # First Stop we have seen for this session and no SessionStart baseline: record and allow.
    # Blocking here would be guessing -- we have nothing to compare against.
    @{ mtime = $now.mtime; count = $now.count; tree = $tree; blocked = $false } |
      ConvertTo-Json -Compress | Set-Content -LiteralPath $file -Encoding utf8
    Write-Log "no baseline for session $sid; recorded and allowed"
    exit 0
  }

  $base = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json

  if ($base.blocked) { Write-Log "already blocked once this session; allowing"; exit 0 }

  $dbChanged   = ($now.mtime -ne $base.mtime) -or ($now.count -ne $base.count)
  $treeChanged = ($tree -ne $null) -and ($base.tree -ne $null) -and ($tree -ne $base.tree)

  if ($dbChanged) { Write-Log "memory.db changed this session; allowing"; exit 0 }
  if (-not $treeChanged) { Write-Log "no working-tree change; nothing to record; allowing"; exit 0 }

  # Work happened, nothing was recorded. Block exactly once.
  @{ mtime = $base.mtime; count = $base.count; tree = $base.tree; blocked = $true } |
    ConvertTo-Json -Compress | Set-Content -LiteralPath $file -Encoding utf8
  Write-Log "BLOCKED session $sid -- tree changed, memory.db did not"

  $reason = @'
This session changed files but wrote nothing to the project memory store, so the knowledge
gained is about to be lost. Before finishing, do ONE of these two things:

1. Record what was learned -- a decision made, a gotcha found, a correction received, or a
   status change. From the repo root:
     powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" set -Name <kebab-slug> -Description "one line for recall" -Type <user|feedback|project|reference> -BodyFile <path>
     powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\vec.ps1" embed -All
     powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\export.ps1"
   Update or supersede an existing memory rather than adding a near-duplicate.

2. Or say plainly, in one sentence, that nothing here was worth recording and why.

Do not write a memory that merely restates the diff. Only durable knowledge -- something that
would change how the next session behaves -- belongs in the store. This gate blocks once per
session; it will not block again.
'@

  @{ decision = 'block'; reason = $reason } | ConvertTo-Json -Depth 3 -Compress
  exit 0
}
catch {
  Write-Log "error, failing open: $($_.Exception.Message)"
  exit 0
}
