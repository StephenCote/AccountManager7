# Magic8 - Immersive Session Platform

## Overview

Magic8 is a full-screen immersive experience platform combining biometric-responsive visuals, audio synthesis, AI image generation, LLM-driven session orchestration, and session recording. Built as a Mithril.js application with IIFE-wrapped modules, loaded via dynamic script injection.

### Core Capabilities

- **Full-screen immersive experience** with auto-hiding controls
- **Real-time biometric theming** - facial emotion analysis drives color palette
- **LLM session director** - polls an LLM to dynamically adjust audio, visuals, labels, images, and opacity
- **Binaural beats and isochronic tones** with configurable 3-point frequency sweeps
- **AI image generation** via Stable Diffusion img2img from camera captures
- **Scripted text and voice sequences** loaded from server objects
- **Multi-effect visual canvas** - particles, spiral, mandala, tunnel with theme-responsive colors
- **Composite video recording** with server-side storage
- **Chat integration** - launchable from chat with context handoff

---

## Architecture

### Module Structure

```
magic8/
├── Magic8App.js                  Main orchestrator (Mithril component)
├── Magic8.md                     This document
├── index.js                      Public API, script loader
│
├── ai/
│   └── SessionDirector.js        LLM-powered session orchestration
│
├── audio/
│   ├── AudioEngine.js            Audio context, binaural, isochronic, voice
│   └── VoiceSequenceManager.js   TTS sequence playback
│
├── components/
│   ├── AudioVisualizerOverlay.js  AudioMotionAnalyzer frequency overlay
│   ├── BiometricOverlay.js        Floating animated text labels
│   ├── ControlPanel.js            Auto-hiding session controls
│   ├── HypnoCanvas.js             Multi-effect visual animation canvas
│   ├── HypnoticTextDisplay.js     Fading centered text overlay
│   └── SessionConfigEditor.js     Full configuration UI
│
├── generation/
│   └── ImageGenerationManager.js  SD img2img from camera captures
│
├── state/
│   └── BiometricThemer.js         Emotion-to-theme with smooth lerp
│
├── text/
│   └── TextSequenceManager.js     Text sequence loading and cycling
│
├── utils/
│   ├── DynamicImageGallery.js     Image gallery with AI image splicing
│   └── FullscreenManager.js       Fullscreen API with iOS fallback
│
└── video/
    └── SessionRecorder.js         Composite canvas + audio recording
```

### Script Loading

Scripts are loaded dynamically via `index.js` which provides `Magic8.getScriptPaths(basePath)` returning files in dependency order. The loader injects `<script>` tags sequentially, then mounts `Magic8App` as a Mithril component.

All modules use the IIFE pattern:
```javascript
(function() {
    class MyClass { ... }
    if (typeof module !== 'undefined' && module.exports) module.exports = MyClass;
    if (typeof window !== 'undefined') {
        window.Magic8 = window.Magic8 || {};
        window.Magic8.MyClass = MyClass;
    }
}());
```

---

## Session Lifecycle

### Phases

1. **Config** - `SessionConfigEditor` renders setup UI. User configures audio, images, text, voice, recording, director, and display options. Configs are saved to `~/Magic8/Configs` as `data.data` objects with base64-encoded JSON in `dataBytesStore`.

2. **Running** - `Magic8App._initSession()` initializes all subsystems from config:
   - AudioEngine (binaural beats, isochronic tones, volume)
   - BiometricThemer + camera capture loop
   - DynamicImageGallery (loads from media groups, starts cycling)
   - ImageGenerationManager (SD config, camera capture interval)
   - TextSequenceManager (loads from note/data objects)
   - VoiceSequenceManager (TTS synthesis and playback)
   - SessionDirector (LLM polling loop)
   - SessionRecorder (composite canvas recording)
   - FullscreenManager
   - Ambient breathing animation (opacity oscillation)

3. **Paused** - Subsystems paused via `_togglePause()`. Text, voice, images, isochronic, breathing, and director all pause/resume.

4. **Exit** - `_handleExit()` finalizes recording (saves to server), runs `_cleanup()` (disposes all subsystems), then navigates back.

### Cleanup Safety

`_cleanup()` is guarded against double-call (from both `_handleExit` and `onremove`). Recording finalization runs before cleanup to ensure the video blob is captured before audio tracks are disposed.

---

## Visual Layer Stack

Layers are composited in DOM order (lowest z-index first):

| Z | Layer | Component | Opacity Control |
|---|-------|-----------|-----------------|
| 0 | Background color | Inline style | `currentTheme.bg` |
| 1 | Background images | `.magic8-bg-container` | `ambientOpacity * layerOpacity.images` |
| 5 | Visual effects | `HypnoCanvas` | `(ambient + 0.4) * layerOpacity.visuals` |
| 7 | Audio visualizer | `AudioVisualizerOverlay` | `visualizerOpacity * layerOpacity.visualizer` (screen blend) |
| 10 | Dark overlay | Inline div | Fixed 30% black |
| 12 | Hypnotic text | `HypnoticTextDisplay` | CSS transition fade |
| 15 | Floating labels | `BiometricOverlay` | `layerOpacity.labels` |
| 20 | Recording indicator | `RecordingIndicator` | Fixed |
| 30 | Control panel | `ControlPanel` | Auto-hide after 5s |
| 40 | Debug console | Conditional | Only when `director.testMode` |

### Director Opacity Control

The SessionDirector LLM can adjust layer opacity for `labels`, `images`, `visualizer`, and `visuals` (clamped 0.15-1.0). Changes transition gradually over ~20 seconds using ease-in-out quadratic easing via `requestAnimationFrame`.

### Ambient Breathing

An oscillating opacity effect (`_breatheLoop`) cycles between 0.3 and 0.8 over a configurable period (default 8s), applied to background images and visual effects layers.

---

## Subsystem Details

### AudioEngine (`audio/AudioEngine.js`)

Singleton `AudioContext` management with:

- **Binaural beats**: Stereo oscillator pair with 3-point sweep cycle (sweepStart Hz → sweepTrough Hz → sweepEnd Hz). Left ear gets base frequency, right ear gets base + beat frequency. DC blocking filter on output.
- **Isochronic tones**: Amplitude-modulated mono tone at configurable pulse rate.
- **Voice synthesis**: Cached audio sources via `/rest/voice/{profileId}` API. Content hashed for deduplication.
- **Master gain**: All audio routed through `masterGain` → `MediaStreamAudioDestinationNode` for recording.
- **Analyser**: `AnalyserNode` (fftSize 2048) for `AudioVisualizerOverlay`.

### SessionDirector (`ai/SessionDirector.js`)

LLM-powered session orchestration:

1. **Initialization**: Finds `~/Chat` directory, loads "Open Chat" config template, creates chat request with session-specific name (`"Magic8 Director - {sessionName}"`).

2. **System prompt**: Instructs the LLM to act as a session director, describing available controls (audio, visuals, labels, images, opacity) with current session state.

3. **Polling loop**: At configurable interval (default 60s), gathers session state (biometric data, emotion transitions, audio config, labels, image gen stats, layer opacity) and sends to LLM.

4. **Directive parsing**: Extracts JSON from LLM response using:
   - Strict `JSON.parse`
   - Fallback lenient JS-object parsing (adds quotes to unquoted keys)
   - Truncated JSON repair (fixes trailing commas, unbalanced braces)

5. **Validated directive fields**:
   ```javascript
   {
     audio: { sweepStart, sweepTrough, sweepEnd, isochronicEnabled,
              isochronicBeatFreq, volume },
     visuals: { effects: [...], breathePeriod },
     labels: { add: [...], remove: [...], clear: bool },
     images: { generate: bool, prompt: string },
     opacity: { labels, images, visualizer, visuals },  // 0.15-1.0
     voiceInjection: { text: string }
   }
   ```

6. **Diagnostics**: Multi-pass test mode sends 4 emotional states (neutral, happy+early, anxious+mid, calm+late) and validates LLM responses.

### ImageGenerationManager (`generation/ImageGenerationManager.js`)

Manages SD img2img generation:

- Captures camera frames as base64 PNG
- Builds `olio.sd.config` entities from either saved config ID or inline parameters
- Submits generation jobs via `am7client.create()`
- Polls for completion, saves generated images to session-named subgroups under `~/Magic8/Generated`
- Limits concurrent pending jobs (max 2)
- Generated images splice into `DynamicImageGallery` rotation

**Inline SD config fields**: model, refinerModel, sampler, steps, cfgScale, clipSkip, width, height, prompt, negativePrompt, strength, seed, hiresFix, hiresSteps, hiresScale, style dropdown.

### BiometricThemer (`state/BiometricThemer.js`)

Converts facial analysis data to RGB themes:

- **Emotion mapping**: neutral, happy, sad, angry, fear, surprise, disgust each map to bg/accent/glow/particle RGB triplets
- **Blending**: Weighted blend by emotion intensity scores + race/gender color modulation
- **Smooth transitions**: Per-frame lerp (smoothing factor ~0.05) via `requestAnimationFrame`
- **Emotion tracking**: Tracks last 3 dominant emotions for transition detection (reported to SessionDirector)

### SessionRecorder (`video/SessionRecorder.js`)

Records sessions as WebM video:

- **Composite capture**: A hidden compositing canvas draws all visual layers each frame:
  1. Background color fill
  2. Background images (cover-style via `_drawCover`, with crossfade)
  3. HypnoCanvas content (`drawImage` from `.hypno-canvas`)
  4. Audio visualizer canvas (screen blend mode)
  5. Dark overlay
  6. Text labels (read from `.bio-label` DOM positions, drawn as canvas text)
- **Audio**: Combined with video via `MediaStreamAudioDestinationNode`
- **Codec**: Auto-detects supported MIME type (vp9+opus preferred)
- **Server save**: Converts blob to base64, creates `data.data` object in `~/Magic8/Recordings` with session name + timestamp filename
- **Max duration**: Configurable (default 30 min)

### DynamicImageGallery (`utils/DynamicImageGallery.js`)

Image gallery with AI splicing:

- Loads base images from server media groups via search query
- Random shuffle with crossfade cycling (configurable interval)
- Generated images splice into rotation at next-in-queue position
- `showImmediate()` for instant display of newly generated images
- Tracks current/previous image for crossfade transitions
- `onImageChange` callback drives view updates

### VoiceSequenceManager (`audio/VoiceSequenceManager.js`)

Text-to-speech playback:

- Loads text lines from note or data objects
- Pre-synthesizes all lines via AudioEngine voice API
- Sequential playback with random inter-clip pauses (2-5s default)
- `injectLine()` for mid-sequence insertion (used by SessionDirector voice injection)
- Progress tracking with `onLineChange` callback

---

## Session Configuration

### Config Schema (stored as base64 JSON in `data.data.dataBytesStore`)

```javascript
{
  name: "My Session",
  display: {
    fullscreen: true,
    bgColor: "#141420",
    controlsAutoHide: 5000
  },
  biometrics: {
    enabled: true,
    updateInterval: 500,
    smoothingFactor: 0.1,
    themeEnabled: true
  },
  audio: {
    binauralEnabled: true,
    baseFreq: 200,
    sweepStart: 8,        // Start beat Hz
    sweepTrough: 2,       // Trough beat Hz
    sweepEnd: 8,          // End beat Hz
    sweepDurationMin: 5,
    isochronicEnabled: false,
    isochronicBeatFreq: 10,
    isochronicToneFreq: 200,
    volume: 0.5,
    visualizerEnabled: true,
    visualizerOpacity: 0.35,
    visualizerGradient: "prism",
    visualizerMode: 2
  },
  images: {
    baseGroups: ["objectId1", "objectId2"],
    cycleInterval: 5000,
    includeGenerated: true
  },
  visuals: {
    effects: ["particles", "spiral", "mandala", "tunnel"],
    effectDuration: 30000,
    breathePeriod: 8
  },
  imageGeneration: {
    enabled: true,
    sdConfigId: "objectId" | null,
    sdInline: {
      model: "", refinerModel: "", sampler: "euler_a",
      steps: 30, cfgScale: 7, clipSkip: 1,
      width: 768, height: 768,
      prompt: "", negativePrompt: "", strength: 0.65,
      seed: -1, hiresFix: false, hiresSteps: 10, hiresScale: 1.5
    },
    captureInterval: 30000,
    maxPendingJobs: 2
  },
  text: {
    enabled: true,
    sourceObjectId: "objectId",
    sourceType: "note" | "data",
    displayDuration: 5000,
    loop: true
  },
  voice: {
    enabled: false,
    sourceObjectId: null,
    sourceType: "note",
    voiceProfileId: null,
    loop: true
  },
  recording: {
    enabled: false,
    autoStart: true,       // Auto-start when session begins
    maxDurationMin: 30
  },
  director: {
    enabled: false,
    command: "",           // User's session intent/theme text
    intervalMs: 60000,
    testMode: false
  }
}
```

### Server-Side Folder Structure

```
~/Magic8/
├── Configs/          Session config data.data objects
├── SDConfigs/        Stable Diffusion config data.data objects
├── TextSequences/    Text sequence notes and data objects
├── VoiceSequences/   Voice sequence notes and data objects
├── Generated/        AI-generated images (session-named subgroups)
│   └── {SessionName} {timestamp}/
├── Captures/         Camera capture reference images
└── Recordings/       Session video recordings (WebM)
```

---

## Chat Integration

### Launch from Chat

```javascript
Magic8.launchFromChat({
    chatConfigId: chatCfg?.objectId,
    chatHistory: chatCfg?.history?.messages,
    systemCharacter: chatCfg?.system,
    userCharacter: chatCfg?.user,
    instanceId: inst?.model?.objectId
});
```

Context is serialized to `sessionStorage('magic8Context')` and Magic8App reads it on init. On exit, return context is written to `sessionStorage('magic8Return')` for the chat view to pick up.

### Direct Launch

```javascript
Magic8.launch();                           // Fresh config
Magic8.launchWithConfig(sessionConfigId);  // Load saved config
```

---

## Key Design Patterns

- **IIFE modules** with `window.Magic8` namespace (no ES6 imports)
- **Mithril.js components** with `oninit`/`oncreate`/`onupdate`/`onremove`/`view` lifecycle
- **Callback-based events** (`onThemeChange`, `onDirective`, `onImageChange`, etc.)
- **Double-call guards** on cleanup (`_cleaned` flag)
- **Base64 dataBytesStore** for config persistence via `am7model.newPrimitive("data.data")` + `page.createObject()`
- **Graceful degradation** (iOS fullscreen fallback, MIME type detection, optional subsystems)
- **rAF-based animations** for opacity transitions, breathing, biometric theme lerp
