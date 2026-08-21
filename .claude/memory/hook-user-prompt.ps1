<#
UserPromptSubmit hook: auto-searches project memory on every user message and injects
the top matching memories as additional context. This makes memory retrieval automatic
rather than dependent on the model remembering to search.

Reads the user prompt from stdin (JSON), runs semantic + FTS keyword searches, injects
hits. Fails open: any error silently exits 0 with no output so the session continues.

Registered in .claude/settings.json under hooks.UserPromptSubmit.
#>
$ErrorActionPreference = 'SilentlyContinue'

function Write-Envelope([string]$text) {
    @{
        hookSpecificOutput = @{
            hookEventName     = 'UserPromptSubmit'
            additionalContext = $text
        }
    } | ConvertTo-Json -Depth 5 -Compress
}

try {
    # Read stdin — Claude Code sends the user prompt as JSON
    $raw = [Console]::In.ReadToEnd()
    $payload = $raw | ConvertFrom-Json -ErrorAction Stop

    # Extract prompt text; field name varies by version
    $prompt = ''
    if ($payload.prompt)   { $prompt = "$($payload.prompt)" }
    if (-not $prompt -and $payload.message) { $prompt = "$($payload.message)" }
    if (-not $prompt) { exit 0 }

    $memDir = Join-Path (Split-Path -Parent $PSCommandPath) '.'
    $db     = Join-Path $memDir 'memory.db'
    if (-not (Test-Path $db)) { exit 0 }

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

    # ── Unconditional write reminder ───────────────────────────────────────────
    [void]$sb.AppendLine('MEMORY OBLIGATION: After completing any non-trivial task this turn, write findings')
    [void]$sb.AppendLine('  to memory.db via mem.ps1 set + vec.ps1 embed -All + export.ps1.')
    [void]$sb.AppendLine('  The user monitors memory.db; an unchanged DB signals you did not use the system.')

    Write-Envelope $sb.ToString()
    exit 0
}
catch {
    exit 0   # always fail open
}
