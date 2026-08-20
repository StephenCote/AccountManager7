<#
Local embedding-service provider for vec.ps1 — the same service AccountManager7
uses via test.embedding.{type,server} (LLMServiceEnumType.LOCAL).

Contract: reads text on stdin, prints a JSON array of floats on stdout.
  $env:MEM_EMBED_CMD    = 'powershell -NoProfile -File <memdir>\embed-local.ps1'
  $env:MEM_EMBED_MODEL  = 'local'
  $env:LOCAL_EMBED_URL  = 'http://localhost:8123'   # optional, this is the default

Needs no API key, which is why it suits a store meant to be shared: a teammate
who can reach the service can rebuild embeddings without credentials.
#>
[CmdletBinding()]
param([string]$Text)

$ErrorActionPreference = 'Stop'

if (-not $Text) { $Text = [Console]::In.ReadToEnd() }
if ([string]::IsNullOrWhiteSpace($Text)) { throw "no input text to embed" }

$base = if ($env:LOCAL_EMBED_URL) { $env:LOCAL_EMBED_URL.TrimEnd('/') } else { 'http://localhost:8123' }
$uri  = "$base/generate_embedding"

try {
  $resp = Invoke-RestMethod -Method Post -Uri $uri `
            -Headers @{ 'Content-Type' = 'application/json' } `
            -Body (@{ content = $Text } | ConvertTo-Json -Compress) -TimeoutSec 120
} catch {
  throw @"
Local embedding service unreachable at $uri ($($_.Exception.Message)).
Start the service, or point `$env:LOCAL_EMBED_URL at a running instance, or switch
to the Azure provider (embed-azure.ps1). See SETUP.md -> Semantic search.
"@
}

$vec = $resp.embedding
if (-not $vec) { throw "response contained no 'embedding' field: $($resp | ConvertTo-Json -Depth 4 -Compress)" }

'[' + (($vec | ForEach-Object { ([double]$_).ToString('G17', [cultureinfo]::InvariantCulture) }) -join ',') + ']'
