<#
Shared configuration resolution for the memory store's external providers
(embeddings for vec.ps1, chat for mine-transcripts.ps1).

Dot-source it; it defines functions and does nothing on its own:

    . (Join-Path $MemDir 'memconfig.ps1')

WHY THIS EXISTS
  MEM_EMBED_CMD as a shell variable is lost the moment the shell closes, so semantic
  search silently no-ops in every new session and in hook/non-interactive contexts that
  never ran the setup line. Configuration belongs in a file, not in shell state.

RESOLUTION ORDER (first hit wins, per key)
  1. environment variable            -- per-shell override, still honoured
  2. <memdir>\embed.config           -- this project only (gitignored)
  3. ~\.claude\embed.config          -- per machine; EVERY project inherits it

Step 3 is what makes porting cheap: configure the provider once and a newly ported
project has working semantic search with no extra setup.

FILE FORMAT -- key=value, `#` or `;` comments, blank lines ignored.

    cmd   = powershell -NoProfile -File %MEMDIR%\embed-azure.ps1
    model = text-embedding-ada-002
    AZURE_OPENAI_CONNECTION_FILE = C:\path\outside\the\repo\embedding.txt

  `cmd` and `model` supply MEM_EMBED_CMD / MEM_EMBED_MODEL. EVERY OTHER KEY is exported
  as an environment variable for the provider subprocess, which is how provider-specific
  settings (connection-file paths, OLLAMA_HOST, LOCAL_EMBED_URL) stay out of the scripts
  and out of the repo. An already-set environment variable is never overwritten.

  %MEMDIR% expands to the memory directory of the project being operated on. Always use
  it instead of an absolute path in `cmd`, or a user-level config will keep pointing at
  the project it was written in.

NEVER put an API key in this file. Put the key in a connection file outside the repo and
name that file here. See SETUP.md -> Embedding provider.
#>

Set-StrictMode -Off

$script:MemConfigReserved = @('cmd', 'model')

function Get-MemConfigPath {
  <# The two config locations, project first. Neither is required to exist. #>
  param([string]$MemDir, [ValidateSet('project', 'user')][string]$Scope)

  if ($Scope -eq 'project') {
    if (-not $MemDir) { throw "project scope needs a memory directory" }
    return (Join-Path $MemDir 'embed.config')
  }
  if ($Scope -eq 'user') {
    if (-not $env:USERPROFILE) { throw "cannot resolve user scope: USERPROFILE is not set" }
    return (Join-Path $env:USERPROFILE '.claude\embed.config')
  }

  $paths = @()
  if ($MemDir)          { $paths += (Join-Path $MemDir 'embed.config') }
  if ($env:USERPROFILE) { $paths += (Join-Path $env:USERPROFILE '.claude\embed.config') }
  $paths
}

function Read-MemConfigFile {
  <# Parse one config file. Returns an empty hashtable if it does not exist. #>
  param([Parameter(Mandatory)][string]$Path, [string]$MemDir)

  $cfg = @{}
  if (-not (Test-Path -LiteralPath $Path)) { return $cfg }

  foreach ($line in (Get-Content -LiteralPath $Path)) {
    $t = "$line".Trim()
    if (-not $t -or $t.StartsWith('#') -or $t.StartsWith(';')) { continue }
    $i = $t.IndexOf('=')
    if ($i -lt 1) { continue }
    $k = $t.Substring(0, $i).Trim()
    $v = $t.Substring($i + 1).Trim()
    if (-not $k) { continue }
    # Strip one layer of surrounding quotes, so a value with spaces can be written either way.
    if ($v.Length -ge 2 -and (($v.StartsWith('"') -and $v.EndsWith('"')) -or
                              ($v.StartsWith("'") -and $v.EndsWith("'")))) {
      $v = $v.Substring(1, $v.Length - 2)
    }
    # Literal, case-insensitive expansion. NOT -replace: its replacement string gives `$`
    # special meaning and does not unescape backslashes, which mangles Windows paths.
    if ($MemDir) {
      $i2 = $v.IndexOf('%MEMDIR%', [StringComparison]::OrdinalIgnoreCase)
      while ($i2 -ge 0) {
        $v  = $v.Substring(0, $i2) + $MemDir + $v.Substring($i2 + '%MEMDIR%'.Length)
        $i2 = $v.IndexOf('%MEMDIR%', [StringComparison]::OrdinalIgnoreCase)
      }
    }
    $cfg[$k] = $v
  }
  $cfg
}

function Get-MemConfig {
  <#
  Merge project over user config. Returns @{ Values = hashtable; Source = hashtable }
  where Source records which file supplied each key, for `vec.ps1 provider`.
  #>
  param([string]$MemDir)

  $values = @{}
  $source = @{}
  foreach ($p in (Get-MemConfigPath -MemDir $MemDir)) {
    $c = Read-MemConfigFile -Path $p -MemDir $MemDir
    foreach ($k in $c.Keys) {
      if ($values.ContainsKey($k)) { continue }   # first file wins: project over user
      $values[$k] = $c[$k]
      $source[$k] = $p
    }
  }
  @{ Values = $values; Source = $source }
}

function Import-MemProviderEnv {
  <#
  Export every non-reserved config key into the process environment so provider
  subprocesses inherit it. An existing environment variable always wins.
  #>
  param([string]$MemDir)

  $cfg = Get-MemConfig -MemDir $MemDir
  foreach ($k in $cfg.Values.Keys) {
    if ($script:MemConfigReserved -contains $k.ToLower()) { continue }
    if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($k))) { continue }
    [Environment]::SetEnvironmentVariable($k, $cfg.Values[$k])
  }
}

function Resolve-EmbedProvider {
  <#
  The embedding command and model actually in effect.
  Returns @{ Cmd; Model; From } -- From names where Cmd came from, or $null if unset.
  #>
  param([string]$MemDir)

  if ($env:MEM_EMBED_CMD) {
    return @{ Cmd = $env:MEM_EMBED_CMD; Model = $env:MEM_EMBED_MODEL; From = 'env:MEM_EMBED_CMD' }
  }

  $cfg = Get-MemConfig -MemDir $MemDir
  if ($cfg.Values.ContainsKey('cmd')) {
    $model = if ($env:MEM_EMBED_MODEL)        { $env:MEM_EMBED_MODEL }
             elseif ($cfg.Values['model'])    { $cfg.Values['model'] }
             else                             { $null }
    return @{ Cmd = $cfg.Values['cmd']; Model = $model; From = $cfg.Source['cmd'] }
  }

  @{ Cmd = $null; Model = $env:MEM_EMBED_MODEL; From = $null }
}

function Set-MemConfigValue {
  <#
  Write keys into one config file, preserving keys already there.
  Comments are not preserved -- the file is regenerated from parsed values.
  #>
  param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][hashtable]$Values
  )

  $existing = Read-MemConfigFile -Path $Path        # no -MemDir: keep %MEMDIR% unexpanded
  foreach ($k in $Values.Keys) {
    if ($null -eq $Values[$k] -or $Values[$k] -eq '') { $existing.Remove($k) }
    else { $existing[$k] = $Values[$k] }
  }

  $dir = Split-Path -Parent $Path
  if ($dir -and -not (Test-Path -LiteralPath $dir)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
  }

  $lines = @(
    '# Memory store provider configuration. Managed by `vec.ps1 provider`.',
    '# key=value. %MEMDIR% expands to the memory directory of the current project.',
    '# NEVER put an API key here -- name a connection file outside the repo instead.',
    ''
  )
  foreach ($k in ($existing.Keys | Sort-Object)) { $lines += "$k = $($existing[$k])" }

  [System.IO.File]::WriteAllLines($Path, $lines, (New-Object System.Text.UTF8Encoding $false))
  $Path
}
