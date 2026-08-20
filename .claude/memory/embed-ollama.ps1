<#
Ollama embedding provider for vec.ps1 -- fully local, no API key, no cloud.

Contract: reads text on stdin, prints a JSON array of floats on stdout.
  $env:MEM_EMBED_CMD   = 'powershell -NoProfile -File <memdir>\embed-ollama.ps1'
  $env:MEM_EMBED_MODEL = 'nomic-embed-text'

Config:
  $env:OLLAMA_HOST        base URL (default http://localhost:11434). The standard
                          Ollama variable; a bare host:port is accepted too.
  $env:OLLAMA_EMBED_MODEL model name (default nomic-embed-text)

Ollama changed its embedding API, so this tries both:
  1. POST /api/embed      {"model","input"}  -> {"embeddings":[[...]]}   (newer)
  2. POST /api/embeddings {"model","prompt"} -> {"embedding":[...]}      (older)

Setup:
  winget install Ollama.Ollama       (or https://ollama.com/download)
  ollama pull nomic-embed-text       (768 dims; mxbai-embed-large=1024, all-minilm=384)

Dimensions must stay consistent across a store -- switching model means
`vec.ps1 embed -All`. See SETUP.md section 7.
#>
[CmdletBinding()]
param([string]$Text)

$ErrorActionPreference = 'Stop'

if (-not $Text) { $Text = [Console]::In.ReadToEnd() }
if ([string]::IsNullOrWhiteSpace($Text)) { throw "no input text to embed" }

$base = if ($env:OLLAMA_HOST) { $env:OLLAMA_HOST.Trim() } else { 'http://localhost:11434' }
if ($base -notmatch '^https?://') { $base = "http://$base" }   # OLLAMA_HOST is often bare host:port
$base = $base.TrimEnd('/')

$model = if ($env:OLLAMA_EMBED_MODEL) { $env:OLLAMA_EMBED_MODEL }
         elseif ($env:MEM_EMBED_MODEL -and $env:MEM_EMBED_MODEL -ne 'ollama') { $env:MEM_EMBED_MODEL }
         else { 'nomic-embed-text' }

function Get-Detail($err) {
  try { return (New-Object IO.StreamReader($err.Exception.Response.GetResponseStream())).ReadToEnd() } catch { return '' }
}

function Invoke-Ollama {
  param([string]$Path, [hashtable]$Body)
  Invoke-RestMethod -Method Post -Uri "$base$Path" `
    -Headers @{ 'Content-Type' = 'application/json' } `
    -Body ($Body | ConvertTo-Json -Compress) -TimeoutSec 180
}

$vec = $null
$firstError = $null

# 1. Newer /api/embed
try {
  $r = Invoke-Ollama '/api/embed' @{ model = $model; input = $Text }
  if ($r.embeddings -and $r.embeddings.Count -gt 0) { $vec = $r.embeddings[0] }
  elseif ($r.embedding) { $vec = $r.embedding }
} catch {
  $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
  $firstError = "HTTP $code $(Get-Detail $_)".Trim()

  # 0 means the host never answered -- retrying the other path is pointless.
  if ($code -eq 0) {
    throw @"
Ollama unreachable at $base ($($_.Exception.Message)).
Start it (`ollama serve`, or the desktop app), or point `$env:OLLAMA_HOST at a running
instance. Install: https://ollama.com/download then ``ollama pull $model``.
"@
  }
}

# 2. Older /api/embeddings
if (-not $vec) {
  try {
    $r = Invoke-Ollama '/api/embeddings' @{ model = $model; prompt = $Text }
    if ($r.embedding) { $vec = $r.embedding }
    elseif ($r.embeddings -and $r.embeddings.Count -gt 0) { $vec = $r.embeddings[0] }
  } catch {
    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    $detail = Get-Detail $_

    # Both Ollama API generations report a missing model as 404.
    if ($code -eq 404 -or $detail -match '(?i)not found|no such model|try pulling') {
      throw "Ollama has no model named '$model' (HTTP $code). Pull it first: ollama pull $model -- then retry. Installed models: $base/api/tags. $detail"
    }

    # 501 from /api/embed means the model exists but has no embedding head --
    # i.e. a chat/vision model. Verified against qwen3:8b, which returns
    # 501 on /api/embed and 500 on /api/embeddings.
    if ($firstError -match '\b501\b' -or $code -eq 501) {
      throw "Model '$model' exists but does not support embeddings (HTTP 501 on /api/embed, $code on /api/embeddings). Chat and vision models cannot embed. Pull a dedicated embedding model: ollama pull nomic-embed-text (768 dims), mxbai-embed-large (1024), or all-minilm (384)."
    }
    throw "Ollama embeddings failed on both /api/embed ($firstError) and /api/embeddings (HTTP $code): $detail"
  }
}

if (-not $vec) {
  throw "Ollama returned no embedding for model '$model'. Confirm it is an EMBEDDING model (nomic-embed-text, mxbai-embed-large, all-minilm) -- a chat model like llama3 cannot embed."
}

'[' + (($vec | ForEach-Object { ([double]$_).ToString('G17', [cultureinfo]::InvariantCulture) }) -join ',') + ']'
