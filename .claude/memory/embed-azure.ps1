<#
Azure OpenAI embedding provider for vec.ps1.

Contract: reads text on stdin, prints a JSON array of floats on stdout.
  $env:MEM_EMBED_CMD   = 'powershell -NoProfile -File <memdir>\embed-azure.ps1'
  $env:MEM_EMBED_MODEL = 'text-embedding-ada-002'

Config resolution (the key is NEVER stored in this repo):
  1. $env:AZURE_OPENAI_ENDPOINT + $env:AZURE_OPENAI_KEY
  2. $env:AZURE_OPENAI_CONNECTION_FILE

There is deliberately no hardcoded third fallback: an absolute path baked into this
script silently tied one project's embeddings to another project's working tree. Name
the connection file in embed.config instead, which sets the variable for you:

  AZURE_OPENAI_CONNECTION_FILE = C:\path\outside\any\repo\embedding.txt

Connection files may be either of two shapes:
  * JSON  -- fields .serverUrl and .apiKey
  * plain -- 4 lines: endpoint / deployment / api-version=X / key: X

Individual env vars override whatever the file supplies:
  $env:AZURE_OPENAI_EMBED_DEPLOYMENT   deployment name
  $env:AZURE_OPENAI_API_VERSION        API version
  $env:AZURE_OPENAI_EMBED_DIMENSIONS   request a reduced vector width (v3 models only;
                                       ada-002 ignores it and always returns 1536)
#>
[CmdletBinding()]
param([string]$Text)

$ErrorActionPreference = 'Stop'

# vec.ps1 already exports embed.config into the environment before calling a provider,
# but this script is also meant to be runnable on its own, so resolve config here too.
# Tolerant of a missing memconfig.ps1: env vars alone are still a complete config.
$MyDir = Split-Path -Parent $PSCommandPath
if (Test-Path (Join-Path $MyDir 'memconfig.ps1')) {
  . (Join-Path $MyDir 'memconfig.ps1')
  Import-MemProviderEnv -MemDir $MyDir
}

if (-not $Text) { $Text = [Console]::In.ReadToEnd() }
if ([string]::IsNullOrWhiteSpace($Text)) { throw "no input text to embed" }

# ada-002 and text-embedding-3-* accept ~8191 tokens. Guard on characters as a coarse
# proxy so an oversized memory fails loudly here rather than as an opaque 400.
$maxChars = 28000
if ($Text.Length -gt $maxChars) {
  Write-Warning "input is $($Text.Length) chars; truncating to $maxChars for the embedding request"
  $Text = $Text.Substring(0, $maxChars)
}

function Read-ConnectionFile([string]$path) {
  $raw = (Get-Content $path -Raw).Trim()

  if ($raw.StartsWith('{')) {
    $j = $raw | ConvertFrom-Json
    if (-not ($j.serverUrl -and $j.apiKey)) { return $null }
    return @{
      Endpoint   = ([string]$j.serverUrl).Trim().TrimEnd('/')
      Key        = [string]$j.apiKey
      Deployment = $null   # JSON form carries no deployment
      ApiVersion = $null
    }
  }

  # Plain form: endpoint / deployment / api-version=X / key: X (blank lines ignored)
  $lines = @(Get-Content $path | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
  if ($lines.Count -lt 2) { return $null }
  $endpoint = $lines[0].TrimEnd('/')
  if ($endpoint -notmatch '^https?://') { return $null }

  $deployment = $null; $apiVersion = $null; $key = $null
  foreach ($l in $lines[1..($lines.Count - 1)]) {
    if     ($l -match '^key\s*:\s*(.+)$')      { $key        = $Matches[1].Trim() }
    elseif ($l -match '^api-?version\s*=\s*(.+)$') { $apiVersion = $Matches[1].Trim() }
    elseif (-not $deployment)                  { $deployment = $l }
  }
  if (-not $key) { return $null }

  @{ Endpoint = $endpoint; Key = $key; Deployment = $deployment; ApiVersion = $apiVersion }
}

function Resolve-AzureConfig {
  $cfg = $null

  if ($env:AZURE_OPENAI_ENDPOINT -and $env:AZURE_OPENAI_KEY) {
    $cfg = @{
      Endpoint = $env:AZURE_OPENAI_ENDPOINT.TrimEnd('/')
      Key = $env:AZURE_OPENAI_KEY; Deployment = $null; ApiVersion = $null
    }
  } else {
    $candidates = @()
    if ($env:AZURE_OPENAI_CONNECTION_FILE) { $candidates += $env:AZURE_OPENAI_CONNECTION_FILE }

    foreach ($c in $candidates) {
      if ($c -and (Test-Path $c)) {
        $parsed = Read-ConnectionFile $c
        if ($parsed) { $cfg = $parsed; break }
      }
    }
  }

  if (-not $cfg) {
    throw @"
No Azure OpenAI embedding config found. Durable fix -- add this line to
~\.claude\embed.config (or <memdir>\embed.config) so every session picks it up:
  AZURE_OPENAI_CONNECTION_FILE = C:\path\outside\any\repo\embedding.txt
The connection file is JSON with .serverUrl/.apiKey, or 4 plain lines:
endpoint / deployment / api-version=X / key: X.

For one shell only, set instead:
  `$env:AZURE_OPENAI_ENDPOINT = 'https://<resource>.openai.azure.com'
  `$env:AZURE_OPENAI_KEY      = '<key>'
Never commit the key -- see SETUP.md -> Embedding provider.
"@
  }

  # Explicit env vars win over the file.
  if ($env:AZURE_OPENAI_EMBED_DEPLOYMENT) { $cfg.Deployment = $env:AZURE_OPENAI_EMBED_DEPLOYMENT }
  if ($env:AZURE_OPENAI_API_VERSION)      { $cfg.ApiVersion = $env:AZURE_OPENAI_API_VERSION }
  if (-not $cfg.Deployment) { $cfg.Deployment = 'text-embedding-3-small' }
  if (-not $cfg.ApiVersion) { $cfg.ApiVersion = '2023-05-15' }
  $cfg
}

$cfg  = Resolve-AzureConfig
$uri  = "$($cfg.Endpoint)/openai/deployments/$($cfg.Deployment)/embeddings?api-version=$($cfg.ApiVersion)"

$payload = @{ input = $Text }
if ($env:AZURE_OPENAI_EMBED_DIMENSIONS) {
  $payload['dimensions'] = [int]$env:AZURE_OPENAI_EMBED_DIMENSIONS
}

try {
  $resp = Invoke-RestMethod -Method Post -Uri $uri `
            -Headers @{ 'api-key' = $cfg.Key; 'Content-Type' = 'application/json' } `
            -Body ($payload | ConvertTo-Json -Compress) -TimeoutSec 120
} catch {
  $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
  $detail = ''
  try { $detail = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch {}

  # The two failures that look identical from the outside, named explicitly.
  if ($code -eq 404) {
    throw "HTTP 404: no deployment named '$($cfg.Deployment)' on $($cfg.Endpoint). Check the deployment name, or set `$env:AZURE_OPENAI_EMBED_DEPLOYMENT. $detail"
  }
  if ($code -eq 400) {
    throw "HTTP 400: deployment '$($cfg.Deployment)' exists but rejected the embeddings call -- a chat model (gpt-*) cannot produce embeddings, and ada-002 rejects the 'dimensions' parameter. $detail"
  }
  throw "Azure OpenAI embeddings failed (HTTP $code) at $uri : $detail"
}

$vec = $resp.data[0].embedding
if (-not $vec) { throw "response contained no embedding: $($resp | ConvertTo-Json -Depth 4 -Compress)" }

'[' + (($vec | ForEach-Object { ([double]$_).ToString('G17', [cultureinfo]::InvariantCulture) }) -join ',') + ']'
