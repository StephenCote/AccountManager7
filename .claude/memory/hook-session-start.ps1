<#
SessionStart hook: injects the project memory index into context so the DB lookup
is never missed.

Prints the JSON envelope Claude Code reads for context injection:
  {"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"..."}}

Plain stdout is NOT reliably injected -- additionalContext is the documented channel,
which is why this wraps the output instead of just running mem.ps1 directly.

Registered in .claude/settings.json under hooks.SessionStart. Fails open: if anything
goes wrong it emits an empty context rather than blocking the session.
#>
$ErrorActionPreference = 'Stop'

function Write-Envelope([string]$text) {
  # Durable proof of firing. A hook that silently does nothing is the worst failure
  # mode -- transcripts contain no record of SessionStart injection, so without this
  # there is no way to tell "fired" from "never ran". Gitignored.
  try {
    $log = Join-Path (Split-Path -Parent $PSCommandPath) 'hook.log'
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    Add-Content -LiteralPath $log -Value "$stamp  SessionStart fired, injected $($text.Length) chars" -Encoding utf8
  } catch { }   # never let logging break the hook

  # ConvertTo-Json handles escaping of quotes, newlines, and backslashes.
  @{
    hookSpecificOutput = @{
      hookEventName    = 'SessionStart'
      additionalContext = $text
    }
  } | ConvertTo-Json -Depth 5 -Compress
}

try {
  $memDir = Split-Path -Parent $PSCommandPath
  $db     = Join-Path $memDir 'memory.db'

  if (-not (Test-Path $db)) {
    # Auto-bootstrap: if schema.sql is present, create the DB rather than just
    # telling the model to do it manually. Also restore memories.sql if it exists.
    $schema  = Join-Path $memDir 'schema.sql'
    $export  = Join-Path $memDir 'memories.sql'
    $sqlite  = $null

    $onPath = Get-Command sqlite3 -ErrorAction SilentlyContinue
    if ($onPath) { $sqlite = $onPath.Source }
    $local = Join-Path $env:USERPROFILE '.claude\tools\sqlite\sqlite3.exe'
    if (-not $sqlite -and (Test-Path $local)) { $sqlite = $local }

    if ($sqlite -and (Test-Path $schema)) {
      try {
        & $sqlite $db ".read $($schema.Replace('\','/'))" | Out-Null
        if (Test-Path $export) {
          & $sqlite $db ".read $($export.Replace('\','/'))" | Out-Null
          & $sqlite $db "INSERT INTO memories_fts(memories_fts) VALUES('rebuild');" | Out-Null
        }
        # DB created -- fall through to normal flow below
      } catch {
        Write-Envelope "memory.db bootstrap failed: $($_.Exception.Message). Create manually: sqlite3 memory.db `".read schema.sql`" (see SETUP.md section 4)."
        exit 0
      }
    } else {
      Write-Envelope "Project memory store is configured at $memDir but memory.db does not exist yet. Create it with: sqlite3 memory.db `".read schema.sql`" (see SETUP.md section 4)."
      exit 0
    }
  }

  # -ExecutionPolicy Bypass: the hook is not signed, and the default policy on many
  # machines blocks unsigned scripts run with -File. Bypass is scoped to this child
  # process only; it does not change the machine or user policy. (Gotcha #30 in SETUP.md.)
  $rows = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $memDir 'mem.ps1') list 2>&1
  if ($LASTEXITCODE -ne 0) {
    Write-Envelope "Project memory store at $db could not be read (mem.ps1 list exited $LASTEXITCODE). Investigate before relying on recall."
    exit 0
  }

  # Separate real memory rows from diagnostic noise. A child powershell.exe surfaces its
  # warning/error streams as ordinary output here, so ANY Write-Warning inside mem.ps1
  # arrives as a line like "WARNING: ..." and -- before this filter -- was injected into
  # context as though it were a memory. Verified: such a line lands at index 0, ahead of
  # the real rows, so the first "memory" the model read was fabricated. Dropping the 2>&1
  # redirect does NOT fix it -- the warning comes through anyway -- so filter by shape:
  # mem.ps1 list emits "name | type | description | updated", i.e. 3+ pipes.
  $all    = @($rows | Where-Object { $_ -and "$_".Trim() } | ForEach-Object { "$_" })
  $listed = @($all | Where-Object { ($_ -split '\|').Count -ge 4 })
  $noise  = @($all | Where-Object { ($_ -split '\|').Count -lt 4 })

  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine("PROJECT MEMORY STORE (SQLite) -- $db")
  [void]$sb.AppendLine("")

  if (-not $listed) {
    [void]$sb.AppendLine("No memories recorded yet. Write the first one with mem.ps1 set.")
  } else {
    [void]$sb.AppendLine("$($listed.Count) memory/memories on record (name | type | description | updated):")
    foreach ($r in $listed) { [void]$sb.AppendLine("  $r") }
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("These are summaries only. Read a full body before acting on it:")
    [void]$sb.AppendLine("  powershell -NoProfile -File $memDir\mem.ps1 get -Name <slug>")
    [void]$sb.AppendLine("Search before assuming something is unrecorded:")
    [void]$sb.AppendLine("  mem.ps1 search -Query `"<fts5>`"   (keyword)")
    [void]$sb.AppendLine("  vec.ps1 search -Query `"<natural language>`"   (semantic)")
    [void]$sb.AppendLine("Write new memories with mem.ps1 set (never hand-edit MEMORY.md, never add loose .md files).")
    [void]$sb.AppendLine("Conventions and gotchas: $memDir\SETUP.md")
  }

  # Report anything that did not look like a memory row, clearly labelled as a tooling
  # problem. Silently dropping it would hide a real fault in the store; leaving it mixed
  # in with the rows above would have the model treat it as recorded knowledge.
  if ($noise) {
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("WARNING - memory tooling emitted $($noise.Count) non-memory line(s); the store may need attention. These are NOT memories:")
    foreach ($n in $noise) { [void]$sb.AppendLine("  ! $n") }
  }

  Write-Envelope $sb.ToString()
  exit 0
}
catch {
  # Never block a session because the memory hook failed.
  Write-Envelope "Project memory hook failed: $($_.Exception.Message)"
  exit 0
}
