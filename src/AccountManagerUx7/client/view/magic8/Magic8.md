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
- **Interactive director mode** - LLM asks questions, user responds via text input; responses feed back to LLM on next tick
- **Mood ring mode** - emotion-colored button in control bar with crossfade animation, LLM glow, and both emotion+gender emojis
- **Test mode emojis** - large emoji display for detected/suggested emotion and gender
- **Test mode diagnostics** - per-tick debug console with LLM raw output, parse results, interactive state tracking
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

4. **Audio Off** - `_toggleAudio()` stops binaural beats, stops isochronic tones, pauses voice sequence, and sets master gain to 0 (belt-and-suspenders muting). Re-enabling restarts all audio sources and resumes voice playback. Voice auto-start from LLM injection is also gated by `audioEnabled`.

5. **Exit** - `_handleExit()` finalizes recording (saves to server), runs `_cleanup()` (disposes all subsystems), then navigates back.

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
| 30 | Control panel | `ControlPanel` | Auto-hide after 5s. Includes mood ring toggle with crossfade emojis + mood-colored bg |
| 40 | Debug console | Conditional | Only when `director.testMode` |
| 45 | Mood emojis | `MoodEmojiDisplay` | Test mode only, crossfade on change |
| 190 | Toast notifications | Floating cards | Top-right, auto-dismiss |
| 200 | Interactive overlay | askUser text input | Shown when `_pendingQuestion` is set, bottom of screen |

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
- **Isochronic tones**: Amplitude-modulated mono tone at configurable pulse rate. LLM can set `isochronicBand` by brainwave band name (e.g. "theta" resolves to 5.5 Hz pulsing)
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

2. **System prompt**: Loaded from `media/prompts/magic8DirectorPrompt.json` template (fetched via HTTP, cached in static `_cachedTemplate`). Instructs the LLM to act as a session director, describing available controls (audio presets/bands, visuals, labels, images, opacity, voiceLine, suggestMood, askUser, progressPct) with examples for calm, energetic, and interactive states. Tokens `${command}` and `${imageTags}` are substituted at runtime.

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
       isochronicEnabled, isochronicRate, isochronicBand,  // Band name resolves to mid Hz
       volume: 0.0-1.0  // Master audio level; any audio directive auto-enables audio
     },
     visuals: { effect: "particles"|"spiral"|"mandala"|"tunnel"|"hypnoDisc", transitionDuration },
     labels: { add: ["1-3 word phrase"], remove: ["exact label text"] },
     generateImage: { description, style, imageAction, denoisingStrength, cfg, tags: "tag1+tag2" },
     opacity: { labels, images, visualizer, visuals },  // 0.15-1.0, transitions over ~20s
     voiceLine: "short sentence for TTS",
     suggestMood: { emotion: "happy"|"sad"|..., gender: "Man"|"Woman", genderPct: 0-100 },
     askUser: { question: "short question" },  // Interactive mode only; pauses director tick
     progressPct: 0-100,  // Session progress estimate toward stated intent
     commentary: "reasoning (not displayed)"
   }
   ```

6. **Mood ring integration**: When `moodRingEnabled` is reported in session state, the director adds "Mood Ring: ACTIVE" to the state message. The system prompt instructs the LLM to always include `suggestMood` when mood ring is active. The full director prompt is always used (no separate mood ring prompt).

7. **Error resilience**: Consecutive LLM failures are counted; log output reduces after 3 failures (every 5th logged) to avoid console spam. The tick loop continues running through transient failures.

8. **Interactive mode (askUser)**: When `director.interactive` is enabled, the LLM can include `askUser` directives to pose questions to the user. The session flow is:
   1. LLM sends `{"askUser": {"question": "..."}, "voiceLine": "...", ...}`
   2. `_applyDirective` stores the question, pauses the director tick cycle
   3. Question is spoken aloud via voiceLine (LLM should include both)
   4. A text input overlay renders at screen bottom (z-index 200) with a Send button
   5. User types a response and submits (Enter key or Send button)
   6. Response is captured, director resumes with an immediate tick
   7. Next `_gatherDirectorState` includes `userResponse: "their answer"`
   8. `_formatStateMessage` adds `User Response: "..."` line for the LLM to see and react to

   **Escalating prompts**: A `_ticksSinceAskUser` counter tracks how many ticks pass without an askUser directive. The state message escalates:
   - Ticks 0-1: `Interactive: ENABLED`
   - Ticks 2-3: `Interactive: ENABLED — Include an askUser directive to check in with the user.`
   - Ticks 4+: `Interactive: ENABLED — You MUST include askUser...` with inline JSON example

   This progressive escalation is necessary to coax local LLMs into producing the directive.

9. **Tick debug callback**: `onTickDebug` fires after each tick with `{tickNum, stateMessage, rawContent, directive}`. In test mode, Magic8App logs interactive state lines, raw LLM output (truncated), and parse results to the debug console.

10. **Diagnostics**: Multi-pass test mode sends 5 emotional states (neutral, happy+early, anxious+mid, fear+grounding, interactive+response) and validates LLM responses. Coverage checks include labels, audio, visuals, and askUser directives.

### Label and Voice Interleaving

LLM-generated labels are injected into `TextSequenceManager` at the current playback index via `injectText()`, so they appear one-at-a-time interleaved with pre-loaded text data rather than all displaying simultaneously. Labels are also tracked in `sessionLabels[]` for LLM state reporting. The LLM is instructed to keep labels to **1-3 words** (e.g. "deep calm", "warm glow", "breathe").

The `BiometricOverlay` displays at most **2 floating labels** at a time — the one fading out and the one fading in — preventing visual clutter from accumulated labels.

Voice lines from the LLM are injected via `VoiceSequenceManager.injectLine()` at the current index. Both text and voice sequences persist injected content across loop cycles.

### MoodEmojiDisplay (`components/MoodEmojiDisplay.js`)

Test-mode emoji display:

- Shows two 96px emojis (emotion + gender) at top-left
- Driven by biometric data with LLM override via `suggestMood` directive
- Crossfade animation (300ms fade-out → swap → 300ms fade-in) when values change
- Suggested values show with drop-shadow glow effect

### Mood Ring in ControlPanel

The mood ring toggle is integrated into the bottom control bar (not rendered as a separate top-right button). Features:

- Shows both emotion and gender emojis with crossfade animation (300ms fade-out → swap → fade-in)
- LLM-suggested emotions display with white drop-shadow glow effect
- Background color from mood (rgba with 0.6 alpha, 1s CSS transition on background-color and border-color)
- When disabled: standard gray control button appearance
- Toggling mood ring sets `moodRingEnabled` in session state (reported to SessionDirector via state provider)
- The SessionDirector uses the **full Magic8 prompt** (not a stripped-down mood ring prompt) and adds "Mood Ring: ACTIVE" to state messages when enabled, instructing the LLM to include `suggestMood` directives
- `genderPct` (0-100) drives a translucent color gradient bar: 0 = fully female (pink), 100 = fully male (blue). LLM can push gender energy independently of detected gender.

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

**Image tags**: LLM can specify `tags: "tag1+tag2"` in `generateImage` directives. Tags use `+` for AND logic — `"landscape+ethereal"` finds images tagged with both. If no match, falls back to camera-based generation. Available tags are passed to the LLM via the `${imageTags}` token in the prompt template.

**Style defaults (`_fillStyleDefaults`)**: When `randomImageConfig` omits style-specific fields, `_fillStyleDefaults` fills them with random values from `_SD_FALLBACKS` to prevent `(null)` in server-built prompts. Supports: photograph, movie, selfie, anime, portrait, comic, digitalArt, fashion, vintage, art styles. If a `refinerModel` is configured, `hires` mode is forced on.

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
- Also loads from session-specific `~/Magic8/Generated/{sessionName}` subgroup for session replay (previously generated images appear on re-launch)
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
    captureInterval: 5,       // Camera face analysis interval (seconds, 2-30)
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
    testMode: false,
    imageTags: "",               // Comma-separated tags for generateImage tag search
    interactive: false           // Enable askUser Q&A with the user
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
│   └── {SessionName}/    Stable name (no timestamp) for session replay
├── Captures/         Camera capture reference images
│   └── {SessionName}/
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
- **Base64 dataBytesStore** for config persistence via `am7model.newPrimitive("data.data")` + `page.createObject()`. Must set `compressionType: 'none'` when writing `dataBytesStore` to prevent server-side GZIP decompression errors
- **Graceful degradation** (iOS fullscreen fallback, MIME type detection, optional subsystems)
- **rAF-based animations** for opacity transitions, breathing, biometric theme lerp
- **Static class definitions** for frequency data (`BRAINWAVE_BANDS`, `SOLFEGGIO_FREQS`, `FREQUENCY_PRESETS`)
- **Preset-first audio configuration** with fallback to named bands then raw Hz
- **Sequential label injection** into text sequence rather than simultaneous overlay display
- **Session replay**: Session names are stable (no timestamps), so generated images persist in `~/Magic8/Generated/{sessionName}` and appear on re-launch. Gallery loads both parent and session-specific subgroups.
- **Audio mute safety**: Audio toggle stops all sources (binaural, isochronic, voice) AND zeros master gain for belt-and-suspenders muting
- **AudioContext resume**: `_initSession` calls `ctx.resume()` after creating the AudioEngine, since the AudioContext may be created in a `setTimeout` (not a direct user gesture) and browsers will suspend it until resumed
- **BiometricThemer redraw throttle**: `onThemeChange` callback throttles `m.redraw()` to once per 200ms via `setTimeout`, preventing the 60fps rAF animation loop from flooding mithril's vdom diffing (which caused DOMException Node.removeChild errors)
- **Escalating LLM prompts**: Interactive mode uses tick-counter-based state messages that progressively demand the LLM include `askUser` directives — necessary for local models that ignore passive suggestions

---

## Known Issues

### Recording — Session recording may fail silently
Session recording (`SessionRecorder`) relies on `MediaRecorder` with codec auto-detection. Some browser/OS combinations fail to produce valid WebM output, particularly when the AudioContext is in a transitional state. Recording finalization (blob → base64 → server save) happens during `_handleExit`, so a recording failure may prevent clean exit. **Workaround**: Disable recording if experiencing exit issues.

### Server-Side STT — Disabled (code preserved)
The interactive mode originally included server-side speech-to-text via WebSocket streaming (`navigator.mediaDevices.getUserMedia` → `MediaRecorder` → `page.wss.send("audio", b64)` → `page.audioStream.onaudiosttupdate`). This is currently disabled due to a bug where the auto-submit timeout causes the view to revert to the config screen. The STT code is preserved in `_startUserInteraction` as a block comment for future re-enablement. Users interact via the text input overlay only.

### AudioContext Suspension
Browsers suspend `AudioContext` instances created outside of user gesture handlers. Magic8 creates its AudioEngine from `_initSession` (called via `setTimeout`, not a direct click). A `ctx.resume()` call is made at session start, but some browsers may still require a user interaction (e.g., clicking the audio toggle) to fully activate audio.

### Local LLM Directive Compliance
Local LLMs (20-30GB parameter models) often ignore passive directive suggestions in the system prompt. Features like `askUser`, `suggestMood`, and `generateImage` require strong directive cues — examples with exact JSON, MUST language, and escalating tick-based reminders in the state message. The prompt template and `_formatStateMessage` are tuned for this, but smaller models may still underperform.

### Tailwind JIT Arbitrary Values
The project uses Tailwind CSS classes but does not have full JIT mode configured. Arbitrary value syntax like `z-[200]` generates no CSS rule and elements render without the intended z-index. All critical z-index values use inline `style: {zIndex: N}` instead.
