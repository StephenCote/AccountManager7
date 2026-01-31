# Magic8 - Immersive Session Platform

## Overview

Magic8 is a full-screen immersive experience platform combining biometric-responsive visuals, audio synthesis, AI image generation, LLM-driven session orchestration, and session recording. Built as a Mithril.js application with IIFE-wrapped modules, loaded via dynamic script injection.

### Core Capabilities

- **Full-screen immersive experience** with auto-hiding controls
- **Real-time biometric theming** - facial emotion analysis drives color palette
- **LLM session director** - polls an LLM to dynamically adjust audio, visuals, labels, images, opacity, and mood
- **Named brainwave & solfeggio frequency presets** - therapeutic audio combinations with multi-point sweeps
- **Binaural beats and isochronic tones** with configurable multi-point frequency sweeps across brainwave bands
- **AI image generation** via Stable Diffusion img2img from camera captures
- **Scripted text and voice sequences** loaded from server objects, with LLM-only mode
- **Multi-effect visual canvas** - particles, spiral, mandala, tunnel, hypnoDisc with theme-responsive colors
- **Mood ring mode** - emotion-colored button with simplified LLM mood tracking
- **Test mode emojis** - large emoji display for detected/suggested emotion and gender
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
│   └── SessionDirector.js        LLM-powered session orchestration + mood ring mode
│
├── audio/
│   ├── AudioEngine.js            Audio context, binaural, isochronic, voice, frequency presets
│   └── VoiceSequenceManager.js   TTS sequence playback
│
├── components/
│   ├── AudioVisualizerOverlay.js  AudioMotionAnalyzer frequency overlay
│   ├── BiometricOverlay.js        Floating animated text labels
│   ├── ControlPanel.js            Auto-hiding session controls
│   ├── HypnoCanvas.js             Multi-effect visual animation canvas (incl. hypnoDisc)
│   ├── HypnoticTextDisplay.js     Fading centered text overlay
│   ├── MoodEmojiDisplay.js        Large emoji display for test mode (emotion + gender)
│   ├── MoodRingButton.js          Top-right mood ring toggle button
│   └── SessionConfigEditor.js     Full configuration UI with preset selectors
│
├── generation/
│   └── ImageGenerationManager.js  SD img2img from camera captures
│
├── state/
│   └── BiometricThemer.js         Emotion-to-theme with smooth lerp + emoji maps
│
├── text/
│   └── TextSequenceManager.js     Text sequence loading, cycling, and injection
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

1. **Config** - `SessionConfigEditor` renders setup UI. User configures audio (with preset/band selectors), images, text, voice, recording, director, and display options. Configs are saved to `~/Magic8/Configs` as `data.data` objects with base64-encoded JSON in `dataBytesStore`.

2. **Running** - `Magic8App._initSession()` initializes all subsystems from config:
   - AudioEngine (binaural beats via presets/bands/raw Hz, isochronic tones, volume)
   - BiometricThemer + camera capture loop
   - DynamicImageGallery (loads from media groups, starts cycling)
   - ImageGenerationManager (SD config, camera capture interval)
   - TextSequenceManager (loads from note/data objects, or empty for LLM-only mode)
   - VoiceSequenceManager (TTS synthesis and playback, or empty for LLM-only mode)
   - SessionDirector (LLM polling loop, supports mood ring mode)
   - SessionRecorder (composite canvas recording)
   - FullscreenManager
   - Ambient breathing animation (opacity oscillation)

3. **Paused** - Subsystems paused via `_togglePause()`. Text, voice, images, isochronic, breathing, and director all pause/resume.

4. **Exit** - `_handleExit()` finalizes recording (saves to server), runs `_cleanup()` (disposes all subsystems), then navigates back.

### LLM-Only Mode

When `director.enabled` is true but `text.sourceObjectId` or `voice.sourceObjectId` are not set, empty TextSequenceManager and VoiceSequenceManager instances are created. LLM-injected labels and voice lines populate these managers, which auto-start playback on first content.

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
| 45 | Mood emojis | `MoodEmojiDisplay` | Test mode only, crossfade on change |
| 50 | Mood ring button | `MoodRingButton` | When biometrics or director enabled |

### Director Opacity Control

The SessionDirector LLM can adjust layer opacity for `labels`, `images`, `visualizer`, and `visuals` (clamped 0.15-1.0). Changes transition gradually over ~20 seconds using ease-in-out quadratic easing via `requestAnimationFrame`.

### Ambient Breathing

An oscillating opacity effect (`_breatheLoop`) cycles between 0.3 and 0.8 over a configurable period (default 8s), applied to background images and visual effects layers.

---

## Subsystem Details

### AudioEngine (`audio/AudioEngine.js`)

Singleton `AudioContext` management with:

- **Binaural beats**: Stereo oscillator pair with multi-point sweep cycle. Left ear gets base frequency, right ear gets base + beat frequency. DC blocking filter on output.
- **Named presets**: `startBinauralFromPreset(name)` uses combinations of solfeggio carrier frequencies and brainwave band transitions
- **Band-based configuration**: `startBinauralFromBands({startBand, troughBand, endBand, secondTransition, thirdTransition, baseFreq})`
- **Multi-point sweep**: `startBinauralMultiPoint({baseFreq, waypoints, sweepDurationMin})` schedules N-segment linear ramps across waypoints
- **Isochronic tones**: Amplitude-modulated mono tone at configurable pulse rate
- **Voice synthesis**: Cached audio sources via `/rest/voice/{profileId}` API. Content hashed for deduplication
- **Master gain**: All audio routed through `masterGain` → `MediaStreamAudioDestinationNode` for recording
- **Analyser**: `AnalyserNode` (fftSize 2048) for `AudioVisualizerOverlay`
- **Band tracking**: `currentBandLabel` updated during sweep, `getBandForFrequency(hz)` maps Hz to band name

#### Brainwave Bands (`AudioEngine.BRAINWAVE_BANDS`)

| Band | Range (Hz) | Mid | Description |
|------|-----------|-----|-------------|
| Delta | 0.5 - 4 | 2 | Deep dreamless sleep, healing, unconscious |
| Theta | 4 - 7.5 | 5.5 | Hypnosis, deep meditation, REM, subconscious |
| Alpha-Theta | 7 - 8 | 7.5 | Visualization, mind programming, conscious creation |
| Alpha | 7.5 - 14 | 10 | Relaxation, learning, imagination, intuition |
| Beta | 14 - 40 | 20 | Alertness, logic, focus, can cause stress |
| Gamma | 40 - 100 | 40 | High-level processing, bursts of insight |

#### Solfeggio Carrier Frequencies (`AudioEngine.SOLFEGGIO_FREQS`)

| Hz | Name | Description |
|----|------|-------------|
| 174 | Foundation | Pain relief, sense of security |
| 285 | Restoration | Healing tissue, energy field repair |
| 396 | Liberation | Releasing guilt and fear |
| 417 | Change | Facilitating change, undoing situations |
| 432 | Spiritual Tuning | Natural alignment, spiritual attunement |
| 528 | Love | Transformation, love frequency, DNA repair |
| 639 | Connection | Heart opening, relationships, harmonizing |
| 741 | Expression | Self-expression, solutions, creative awakening |
| 852 | Intuition | Third eye activation, spiritual sight |
| 963 | Divine | Crown chakra, pineal gland, divine consciousness |

#### Named Frequency Presets (`AudioEngine.FREQUENCY_PRESETS`)

| Preset | Carrier | Bands | Description |
|--------|---------|-------|-------------|
| Goddess Energy | 432 Hz | alpha → alphaTheta → theta | Spiritual femininity, divine feminine through meditative states |
| Heart Opening | 639 Hz | alpha → theta → alpha | Connection, compassion, relational harmony |
| Love Frequency | 528 Hz | alpha → alphaTheta → alpha | Transformation, cellular healing, unconditional love |
| Deep Healing | 174 Hz | theta → delta → theta | Physical restoration, deep regeneration |
| Letting Go | 396 Hz | alpha → theta → alphaTheta | Releasing guilt, fear, and emotional blockages |
| Transformation | 417 Hz | alpha → theta → theta | Facilitating change, breaking old patterns |
| Creative Flow | 741 Hz | alpha → alphaTheta → alpha | Self-expression, artistic solutions, inspired work |
| Third Eye Awakening | 852 Hz | alpha → theta → alphaTheta | Intuition, spiritual sight, inner knowing |
| Crown Connection | 963 Hz | alpha → theta → gamma | Divine consciousness, universal awareness |
| Regeneration | 285 Hz | theta → delta → theta | Deep tissue repair, energy field restoration |
| Deep Hypnosis | 432 Hz | alpha → alphaTheta → delta | Hypnotic induction, subconscious programming |
| Lucid Dreaming | 528 Hz | alpha → theta → delta | Conscious dream state, REM activation |

### SessionDirector (`ai/SessionDirector.js`)

LLM-powered session orchestration:

1. **Initialization**: Finds `~/Chat` directory, loads "Open Chat" config template, creates chat request with session-specific name (`"Magic8 Director - {sessionName}"`).

2. **System prompt**: Instructs the LLM to act as a session director, describing available controls (audio presets/bands, visuals, labels, images, opacity, suggestMood) with current session state.

3. **Polling loop**: At configurable interval (default 60s), gathers session state (biometric data, emotion transitions, audio band label, labels, image gen stats, layer opacity) and sends to LLM.

4. **Directive parsing**: Extracts JSON from LLM response using:
   - Strict `JSON.parse`
   - Fallback lenient JS-object parsing (adds quotes to unquoted keys)
   - Truncated JSON repair (fixes trailing commas, unbalanced braces)

5. **Validated directive fields**:
   ```javascript
   {
     audio: {
       preset: "goddessEnergy"|"heartOpening"|...,  // Named preset
       // OR manual band selection:
       band: "alpha", troughBand: "theta", endBand: "alpha",
       secondTransition: "alphaTheta", thirdTransition: "delta",
       baseFreq: 432,  // Solfeggio carrier
       // Legacy raw Hz (backwards compatible):
       sweepStart, sweepTrough, sweepEnd,
       isochronicEnabled, isochronicRate, isochronicBand
     },
     visuals: { effect: "particles"|"spiral"|"mandala"|"tunnel"|"hypnoDisc", transitionDuration },
     labels: { add: [...], remove: [...] },
     generateImage: { description, style, imageAction, denoisingStrength, cfg },
     opacity: { labels, images, visualizer, visuals },  // 0.15-1.0
     voiceLine: "short sentence for TTS",
     suggestMood: { emotion: "happy"|"sad"|..., gender: "Man"|"Woman" },
     commentary: "reasoning (not displayed)"
   }
   ```

6. **Mood ring mode**: When `moodRingMode` is true, uses a trimmed prompt that only requests `suggestMood` + `commentary`, and sends only biometric data + emotion transitions + elapsed time.

7. **Diagnostics**: Multi-pass test mode sends 4 emotional states (neutral, happy+early, anxious+mid, calm+late) and validates LLM responses.

### Label and Voice Interleaving

LLM-generated labels are injected into `TextSequenceManager` at the current playback index via `injectText()`, so they appear one-at-a-time interleaved with pre-loaded text data rather than all displaying simultaneously. Labels are also tracked in `sessionLabels[]` for LLM state reporting.

Voice lines from the LLM are injected via `VoiceSequenceManager.injectLine()` at the current index. Both text and voice sequences persist injected content across loop cycles.

### MoodEmojiDisplay (`components/MoodEmojiDisplay.js`)

Test-mode emoji display:

- Shows two 96px emojis (emotion + gender) at top-left
- Driven by biometric data with LLM override via `suggestMood` directive
- Crossfade animation (300ms fade-out → swap → 300ms fade-in) when values change
- Suggested values show with drop-shadow glow effect

### MoodRingButton (`components/MoodRingButton.js`)

Mood ring toggle button:

- Fixed top-right, z-50, pill-shaped with two 32px emojis
- When enabled: background color from suggested emotion theme accent (rgba 0.6 alpha, 1s CSS transition)
- When disabled: dark semi-transparent background
- Toggle sets `sessionDirector.moodRingMode` for simplified LLM prompting
- Shown when biometrics or director is enabled

### HypnoCanvas — HypnoDisc Effect

Canvas-based translation of the CSS nested-circle spiral technique:

- 50 concentric ring pairs drawn as half-circle arcs (top half + bottom half)
- Even-ranked rings use theme accent color; odd-ranked use inverted color
- Border width decreases toward center (`7 * percentage`), opacity increases (`1 - percentage + 0.3`)
- Unified rotation at ~60s per revolution creates moiré spiral illusion
- Colors respond to biometric theme accent

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
- **Emoji maps**: `emotionEmojis` and `genderEmojis` static maps for UI display
- **Blending**: Weighted blend by emotion intensity scores + race/gender color modulation
- **Smooth transitions**: Per-frame lerp (smoothing factor ~0.05) via `requestAnimationFrame`
- **Emotion tracking**: Tracks last 3 dominant emotions for transition detection (reported to SessionDirector)

### TextSequenceManager (`text/TextSequenceManager.js`)

Text sequence loading and cycling:

- Loads from note objects, data objects, raw strings, or arrays
- `injectText(text, atIndex)` for mid-sequence insertion (used by SessionDirector label interleaving)
- Shuffle mode with Fisher-Yates algorithm
- Configurable display duration and loop behavior
- Auto-start on first injected content in LLM-only mode

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
    preset: "goddessEnergy",    // Named preset (takes priority)
    baseFreq: 432,              // Solfeggio carrier frequency
    startBand: "alpha",         // Named brainwave band
    troughBand: "alphaTheta",
    endBand: "theta",
    sweepStart: 10,             // Raw Hz (legacy/advanced)
    sweepTrough: 7.5,
    sweepEnd: 5.5,
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
    effects: ["particles", "spiral", "mandala", "tunnel", "hypnoDisc"],
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
    sourceObjectId: "objectId",  // null for LLM-only mode
    sourceType: "note" | "data",
    displayDuration: 5000,
    loop: true
  },
  voice: {
    enabled: false,
    sourceObjectId: null,        // null for LLM-only mode
    sourceType: "note",
    voiceProfileId: null,
    loop: true
  },
  recording: {
    enabled: false,
    autoStart: true,
    maxDurationMin: 30
  },
  director: {
    enabled: false,
    command: "",                 // User's session intent/theme text
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
- **Static class definitions** for frequency data (`BRAINWAVE_BANDS`, `SOLFEGGIO_FREQS`, `FREQUENCY_PRESETS`)
- **Preset-first audio configuration** with fallback to named bands then raw Hz
- **Sequential label injection** into text sequence rather than simultaneous overlay display
