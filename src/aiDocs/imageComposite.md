Instruction for AI / Script
Generate a composite image of two people in a shared setting using the SwarmUI HTTP API with a Flux Kontext (or Flux.2 / Qwen Image Edit) model.
Required steps:

Obtain a fresh session:textPOST http://localhost:7801/API/GetNewSession
Body: {}
Extract: session_id
Prepare inputs:
Two portrait images (high-quality, front-facing preferred).
Optionally stitch them side-by-side into a single reference image first (recommended for stronger multi-person coherence). Convert images to base64 data URLs if the API requires embedded images.
Write an explicit multi-reference prompt that names the sources, e.g.:“Combine the exact person and face from the left/first reference image with the exact person and face from the right/second reference image. Place both people standing together in [detailed setting description]. Photorealistic, matching lighting, scale, and perspective. Preserve facial identity, hair, and expression precisely. No extra people.”

Call generation:textPOST http://localhost:7801/API/GenerateText2Image
Content-Type: application/json
Body (minimum required fields + image handling):
{
  "session_id": "<session_id from step 1>",
  "images": 1,
  "prompt": "<explicit multi-reference prompt above>",
  "negativeprompt": "blurry faces, deformed, extra people, mismatched lighting, low quality",
  "model": "<exact path or name of Flux Kontext / Flux.2 / Qwen Image Edit model as shown in SwarmUI Models tab>",
  "width": 1024,          // or 1152 / 1280 for two-person scenes
  "height": 768,          // adjust for desired aspect (prefer landscape or 3:2)
  "cfgscale": 2.5,        // keep low (1.0–3.5) for edit models
  "steps": 24,
  "seed": -1,
  "donotsave": false
  // Image references: inspect a successful UI generation in browser DevTools Network tab
  // to capture the exact parameter names SwarmUI uses for prompt images
  // (commonly data-URL strings or paths under the image-prompting keys).
  // Prefer embedding as base64 data:image/png;base64,... if the endpoint accepts them.
}
Handle response:
On success the reply contains an "images" array with relative View paths.
Fetch the final image via GET http://localhost:7801/<path>.
If "error_id": "invalid_session_id" appears, repeat step 1 and retry once.

Quality controls (apply automatically):
Prefer a stitched side-by-side reference over two separate images when possible.
Use low CFG and 20–28 steps.
After generation, optionally run a second pass focused on faces only (same API, prompt that references the previous output + “refine faces only, preserve identity”).
If identity drifts, increase reference strength or switch to a Flux.2 multi-reference capable checkpoint.


Do not use SDXL + IP-Adapter/ReVision as the primary method; reserve it only as a fallback.
Do not rely on generic text-to-image without image references.
Always capture the precise image-parameter keys from a working UI session if the payload above is incomplete for the installed SwarmUI version.
This is the minimal, reliable programmatic path for two-portrait + setting composites in SwarmUI.