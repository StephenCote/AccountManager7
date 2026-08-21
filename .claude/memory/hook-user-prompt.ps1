<#
UserPromptSubmit hook: auto-searches project memory on every user message and injects
the top matching memories as additional context. This makes memory retrieval automatic
rather than dependent on the model remembering to search.

Reads the user prompt from stdin (JSON), runs semantic + FTS keyword searches, injects
hits. Fails open: any error silently exits 0 with no output so the session continues.

Registered in .claude/settings.json under hooks.UserPromptSubmit.
#>
$ErrorActionPreference = 'SilentlyContinue'

# Claude Code reads hook stdout as UTF-8; without this PowerShell emits the console code page
# and non-ASCII characters in injected memories arrive mangled.
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

# This hook used to log NOTHING, so hook.log showed only SessionStart lines and there was no way
# to distinguish "UserPromptSubmit never fires" from "it fires and finds nothing" -- the two have
# completely different fixes. Log every invocation.
function Write-HookLog([string]$m) {
  try {
    $log = Join-Path (Split-Path -Parent $PSCommandPath) 'hook.log'
    Add-Content -LiteralPath $log -Encoding utf8 `
      -Value ("{0}  UserPromptSubmit: {1}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $m)
  } catch { }
}

function Write-Envelope([string]$text) {
    Write-HookLog "fired, injected $($text.Length) chars"
    @{
        hookSpecificOutput = @{
            hookEventName     = 'UserPromptSubmit'
            additionalContext = $text
        }
    } | ConvertTo-Json -Depth 5 -Compress
}

try {
    # Read stdin — Claude Code sends the user prompt as JSON
    # Read stdin only when it is actually a pipe. ReadToEnd() on a non-redirected
    # console blocks forever, and a hook that blocks just burns its whole timeout.
    $raw = if ([Console]::IsInputRedirected) { [Console]::In.ReadToEnd() } else { '' }
    $payload = $raw | ConvertFrom-Json -ErrorAction Stop

    # Extract prompt text; field name varies by version
    $prompt = ''
    if ($payload.prompt)   { $prompt = "$($payload.prompt)" }
    if (-not $prompt -and $payload.message) { $prompt = "$($payload.message)" }
    if (-not $prompt) { Write-HookLog 'fired, but no prompt field in payload'; exit 0 }

    $memDir = Split-Path -Parent $PSCommandPath
    $db     = Join-Path $memDir 'memory.db'
    if (-not (Test-Path $db)) { Write-HookLog "fired, but memory.db not found at $db"; exit 0 }

    # Use first 300 chars as search query (avoids shell-arg length issues)
    $query = $prompt.Trim().Substring(0, [Math]::Min(300, $prompt.Trim().Length))

    $sb    = New-Object System.Text.StringBuilder
    $found = $false

    # ── Semantic search (vec.ps1) ──────────────────────────────────────────────
    # Gracefully skipped if embedding server (192.168.1.42) is unreachable.
    try {
        $vecOut = & powershell -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $memDir 'vec.ps1') search -Query $query -Top 4 2>&1
        $vecRows = @($vecOut |
            Where-Object { $_ -and "$_".Trim() -notmatch '^(WARNING|ERROR|Invoke|embed|index)' `
                           -and ("$_" -split '\|').Count -ge 3 })
        if ($vecRows.Count -gt 0) {
            [void]$sb.AppendLine('MEMORY SEARCH — semantic matches for this prompt:')
            foreach ($r in $vecRows) { [void]$sb.AppendLine("  $r") }
            $found = $true
        }
    } catch { }

    # ── FTS keyword search (mem.ps1) ──────────────────────────────────────────
    # Extract meaningful words (>3 chars) to build an FTS5 query.
    try {
        $stopWords = @('that','this','what','with','from','have','will','been','your',
                       'they','their','then','when','also','into','just','make','like',
                       'need','want','does','some','more','than','about')
        $words = ($query -split '\W+') |
            Where-Object { $_.Length -gt 3 -and $_ -notmatch '^\d+$' -and $_ -notin $stopWords } |
            Select-Object -Unique -First 5
        if ($words) {
            $ftsQuery = $words -join ' '
            $ftsOut   = & powershell -NoProfile -ExecutionPolicy Bypass `
                -File (Join-Path $memDir 'mem.ps1') search -Query $ftsQuery 2>&1
            $ftsRows = @($ftsOut |
                Where-Object { $_ -and "$_".Trim() -notmatch '^WARNING' `
                               -and ("$_" -split '\|').Count -ge 4 })
            if ($ftsRows.Count -gt 0) {
                $label = if ($found) { 'Also matched by keyword:' } else { 'MEMORY SEARCH — keyword matches:' }
                [void]$sb.AppendLine($label)
                foreach ($r in $ftsRows | Select-Object -First 4) { [void]$sb.AppendLine("  $r") }
                $found = $true
            }
        }
    } catch { }

    if ($found) {
        [void]$sb.AppendLine("  → mem.ps1 get -Name <slug> for full body")
        [void]$sb.AppendLine('')
    }

    # ── Write reminder ─────────────────────────────────────────────────────────
    # Deliberately framed as a condition to evaluate at the END of the turn, not a command to run.
    # The previous wording was an imperative list of memory commands, and it misfired badly: asked
    # to diagnose or repair the memory system, the model would run mem.ps1/vec.ps1/export.ps1 --
    # satisfying the reminder while ignoring the actual request. Enforcement now lives in the Stop
    # hook (hook-stop-memory.ps1), so this text does not need to nag.
    [void]$sb.AppendLine('MEMORY, at the END of this turn only: if you learned something durable -- a decision,')
    [void]$sb.AppendLine('  a gotcha, a correction, a status change -- record it with mem.ps1 set, then vec.ps1')
    [void]$sb.AppendLine('  embed -All and export.ps1. Update or supersede an existing memory over adding a')
    [void]$sb.AppendLine('  near-duplicate. Nothing durable learned? Record nothing.')
    [void]$sb.AppendLine('  This is not the task. If the request concerns the memory system ITSELF -- checking,')
    [void]$sb.AppendLine('  debugging, or repairing it -- then diagnose and FIX it; running these commands is')
    [void]$sb.AppendLine('  not a substitute for that, and is not what was asked.')

    Write-Envelope $sb.ToString()
    exit 0
}
catch {
    Write-HookLog "error, failing open: $($_.Exception.Message)"
    exit 0   # always fail open
}
