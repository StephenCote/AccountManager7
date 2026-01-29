# Magic8 / HypnoApp Consolidation Plan

## Overview

This document outlines the strategy for refactoring and consolidating the HypnoApp and Magic8 features from `chat.js` into a dedicated "game" or experience module. The new design features:

- **Full-screen immersive experience**
- **Real-time biometric-driven color/theme adaptation** from facial analysis
- **Session video recording** with server-side storage
- **AI image generation** using user camera captures as basis images via Stable Diffusion
- **Scripted hypnotic text sequences** loaded from note/data objects
- **Dynamic image splicing** - generated images integrated into gallery rotation

## Current Implementation Analysis

### Feature Inventory

| Feature | Current Location | Description |
|---------|-----------------|-------------|
| **Magic8Ball** | `chat.js`, `audioComponents.js` | Dual-hemisphere audio visualizer with image backgrounds |
| **HypnoApp** | `hyp.js` | Biometric-driven UI with particle animation and audio synthesis |
| **Binaural Sweeps** | `audio.js` | Stereo frequency sweep for meditation/therapy |
| **Streaming Audio Chat** | `chat.js`, `audioComponents.js` | Voice synthesis with waveform visualization |
| **Moving Image Gallery** | `hyp.js`, `chat.js`, `cardGame.js` | Rotating background images from media groups |
| **Camera Capture** | `camera.js` | Facial analysis and image capture (existing) |
| **SD Image Generation** | `cardGame.js` | Stable Diffusion integration (existing) |

---

## Current Architecture

### Magic8Ball (audioComponents.js:728-1047)

**Core Capabilities**:
- Split-circle design (top: assistant, bottom: user)
- Two simultaneous audio streams with synchronized visualization
- Background image modes:
  - Profile images (character portraits)
  - Gallery mode (crossfade transition every 3 seconds)
- Auto-play last message with hash-based tracking
- Click-to-play/pause per hemisphere

**State Management**:
```javascript
{
    audioSource1, audioSource2,        // Top/bottom audio sources
    bgImageIndex,                      // Current gallery position
    imageA_src, imageB_src,           // Crossfade image pair
    isA_onTop, isTransitioning,       // Crossfade state
    hasAutoPlayedForHash: {}          // Auto-play tracking
}
```

**Dependencies**:
- `AudioMotionAnalyzer` - Waveform visualization
- `createAudioSource()` from `audio.js`
- Image URL resolution from media API

### HypnoApp (hyp.js)

**Core Capabilities**:
- Biometric-driven theming (emotion → colors)
- Canvas particle system (100 particles)
- Content cycling (distractions, core messages, images)
- Tone.js audio synthesis with LFO modulation
- Camera/facial analysis integration

**Biometric Emotion → Theme Mapping**:
```javascript
emotions = {
    neutral: { bg: "gray/blue" },
    happy:   { bg: "yellow" },
    sad:     { bg: "indigo" },
    angry:   { bg: "red" },
    fear:    { bg: "purple" },
    surprise:{ bg: "pink" },
    disgust: { bg: "green" }
}
```

**State Management**:
```javascript
{
    enableAudio: boolean,
    isStarted: boolean,
    audioReady: boolean,
    synth, lfo: Tone.js objects,
    biometricData: { emotion, race, gender, age },
    biometricClues: string[],
    theme: { background, mainText, shadow },
    imageUrl: current background
}
```

**Dependencies**:
- `Tone.js` - Audio synthesis
- Canvas API - Particle animation
- Camera component - Facial analysis
- Image query API - Gallery loading

### Binaural Sweeps (audio.js:1276-1430)

**Core Capabilities**:
- Stereo oscillator pair (left: base, right: base + beat)
- Frequency sweep cycling (configurable duration)
- Volume ramping (fade in/out)
- DC blocking filter for clean audio

**Audio Graph**:
```
leftOsc  → leftGain  ─┐
                      ├→ merger → masterGain → dcBlocker → destination
rightOsc → rightGain ─┘
```

**Parameters**:
- `baseFreq`: Carrier frequency (default 440 Hz)
- `maxBeat`: Maximum beat frequency (e.g., 8 Hz)
- `minBeat`: Minimum beat frequency (e.g., 0 Hz)
- `sweepDurationMin`: Cycle duration in minutes

### Streaming Audio Chat (chat.js + audioComponents.js)

**Core Capabilities**:
- WebSocket streaming for real-time chat
- Voice synthesis via `/rest/voice/{name}` API
- Per-message audio with visualizer
- Role-based styling (assistant vs user)

**Voice Synthesis Flow**:
```
Content → pruneAll() → API call → base64 audio → AudioBuffer → playback
```

---

## New Features Design

### 1. Full-Screen Immersive Mode

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│                    FULL VIEWPORT CANVAS                      │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                                                       │  │
│  │              Background Image Layer                   │  │
│  │         (crossfade gallery + generated images)        │  │
│  │                                                       │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │           Particle Animation Overlay            │  │  │
│  │  │        (color-responsive to biometrics)         │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  │                                                       │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │              Hypnotic Text Layer                │  │  │
│  │  │          (scripted sequence display)            │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  │                                                       │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐        │
│  │ ● REC   │  │ Config  │  │ Audio   │  │  Exit   │        │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘        │
└─────────────────────────────────────────────────────────────┘
```

**Fullscreen API Integration**:
```javascript
class FullscreenManager {
    async enterFullscreen(element) {
        if (element.requestFullscreen) {
            await element.requestFullscreen();
        } else if (element.webkitRequestFullscreen) {
            await element.webkitRequestFullscreen();
        }
        this.isFullscreen = true;
    }

    async exitFullscreen() {
        if (document.exitFullscreen) {
            await document.exitFullscreen();
        }
        this.isFullscreen = false;
    }

    onFullscreenChange(callback) {
        document.addEventListener('fullscreenchange', callback);
    }
}
```

### 2. Real-Time Biometric Color Adaptation

**Enhanced BiometricThemer with Smooth Transitions**:
```javascript
class BiometricThemer {
    constructor() {
        this.currentTheme = null;
        this.targetTheme = null;
        this.transitionDuration = 2000;  // 2 second smooth transitions
        this.animationFrame = null;
    }

    // Emotion → Color mapping (expanded)
    static emotionThemes = {
        neutral:  { bg: [40, 45, 60],    accent: [80, 90, 120],   glow: [100, 110, 140] },
        happy:    { bg: [60, 50, 20],    accent: [255, 220, 100], glow: [255, 240, 150] },
        sad:      { bg: [30, 30, 70],    accent: [80, 80, 160],   glow: [100, 100, 200] },
        angry:    { bg: [70, 20, 20],    accent: [200, 80, 80],   glow: [255, 100, 100] },
        fear:     { bg: [50, 30, 60],    accent: [160, 80, 200],  glow: [200, 120, 255] },
        surprise: { bg: [60, 40, 50],    accent: [255, 180, 200], glow: [255, 200, 220] },
        disgust:  { bg: [30, 50, 30],    accent: [80, 160, 80],   glow: [100, 200, 100] }
    };

    // Continuous update from facial analysis
    updateFromBiometrics(biometricData) {
        if (!biometricData) return;

        // Primary emotion drives base theme
        const emotion = biometricData.dominant_emotion || 'neutral';
        const baseTheme = BiometricThemer.emotionThemes[emotion];

        // Blend with emotion intensity scores
        let blendedTheme = { ...baseTheme };
        if (biometricData.emotion_scores) {
            blendedTheme = this.blendByIntensity(biometricData.emotion_scores);
        }

        // Apply arousal/valence modulation if available
        if (biometricData.arousal !== undefined) {
            blendedTheme = this.modulateByArousal(blendedTheme, biometricData.arousal);
        }

        this.transitionTo(blendedTheme);
    }

    blendByIntensity(emotionScores) {
        // Weighted blend of all emotion colors by their scores
        let result = { bg: [0,0,0], accent: [0,0,0], glow: [0,0,0] };
        let totalWeight = 0;

        for (const [emotion, score] of Object.entries(emotionScores)) {
            const theme = BiometricThemer.emotionThemes[emotion];
            if (!theme) continue;

            const weight = parseFloat(score) || 0;
            totalWeight += weight;

            ['bg', 'accent', 'glow'].forEach(key => {
                result[key] = result[key].map((v, i) => v + theme[key][i] * weight);
            });
        }

        if (totalWeight > 0) {
            ['bg', 'accent', 'glow'].forEach(key => {
                result[key] = result[key].map(v => Math.round(v / totalWeight));
            });
        }

        return result;
    }

    transitionTo(targetTheme) {
        this.targetTheme = targetTheme;
        if (!this.animationFrame) {
            this.animateTransition();
        }
    }

    animateTransition() {
        // Smooth lerp between current and target
        if (!this.currentTheme) {
            this.currentTheme = this.targetTheme;
        } else {
            const factor = 0.05;  // Smoothing factor
            ['bg', 'accent', 'glow'].forEach(key => {
                this.currentTheme[key] = this.currentTheme[key].map((v, i) =>
                    Math.round(v + (this.targetTheme[key][i] - v) * factor)
                );
            });
        }

        this.applyTheme(this.currentTheme);
        this.animationFrame = requestAnimationFrame(() => this.animateTransition());
    }

    applyTheme(theme) {
        // Update CSS custom properties for immediate effect
        const root = document.documentElement;
        root.style.setProperty('--hypno-bg', `rgb(${theme.bg.join(',')})`);
        root.style.setProperty('--hypno-accent', `rgb(${theme.accent.join(',')})`);
        root.style.setProperty('--hypno-glow', `rgb(${theme.glow.join(',')})`);

        // Emit for canvas/particle updates
        this.onThemeChange?.(theme);
    }
}
```

### 3. Session Video Recording

**VideoRecorder Component**:
```javascript
class SessionRecorder {
    constructor() {
        this.mediaRecorder = null;
        this.recordedChunks = [];
        this.isRecording = false;
        this.canvas = null;
        this.audioContext = null;
    }

    async startRecording(canvasElement, audioDestination) {
        this.canvas = canvasElement;
        this.recordedChunks = [];

        // Capture canvas stream
        const canvasStream = canvasElement.captureStream(30);  // 30 FPS

        // Combine with audio if available
        let combinedStream = canvasStream;
        if (audioDestination) {
            const audioStream = audioDestination.stream;
            combinedStream = new MediaStream([
                ...canvasStream.getVideoTracks(),
                ...audioStream.getAudioTracks()
            ]);
        }

        // Create recorder
        this.mediaRecorder = new MediaRecorder(combinedStream, {
            mimeType: 'video/webm;codecs=vp9',
            videoBitsPerSecond: 5000000  // 5 Mbps
        });

        this.mediaRecorder.ondataavailable = (event) => {
            if (event.data.size > 0) {
                this.recordedChunks.push(event.data);
            }
        };

        this.mediaRecorder.start(1000);  // Chunk every second
        this.isRecording = true;
    }

    stopRecording() {
        return new Promise((resolve) => {
            this.mediaRecorder.onstop = () => {
                const blob = new Blob(this.recordedChunks, { type: 'video/webm' });
                this.isRecording = false;
                resolve(blob);
            };
            this.mediaRecorder.stop();
        });
    }

    async saveToServer(blob, sessionConfig) {
        const formData = new FormData();
        formData.append('file', blob, `session-${Date.now()}.webm`);
        formData.append('sessionConfig', JSON.stringify(sessionConfig));

        const response = await fetch(`${g_application_path}/rest/media/upload`, {
            method: 'POST',
            body: formData,
            headers: {
                'Authorization': `Bearer ${am7client.token}`
            }
        });

        return response.json();
    }
}
```

**Server-Side Endpoint** (REST):
```java
@POST @Path("/media/upload")
@Consumes(MediaType.MULTIPART_FORM_DATA)
public Response uploadSessionVideo(
    @FormDataParam("file") InputStream fileStream,
    @FormDataParam("file") FormDataContentDisposition fileDetail,
    @FormDataParam("sessionConfig") String sessionConfig,
    @Context HttpServletRequest request
) {
    BaseRecord user = ServiceUtil.getPrincipalUser(request);

    // Create data.data record for video
    BaseRecord videoRecord = RecordFactory.newInstance(ModelNames.MODEL_DATA);
    videoRecord.set(FieldNames.FIELD_NAME, fileDetail.getFileName());
    videoRecord.set(FieldNames.FIELD_CONTENT_TYPE, "video/webm");
    videoRecord.set(FieldNames.FIELD_BYTE_STORE, IOUtils.toByteArray(fileStream));

    // Store in user's session recordings folder
    String groupPath = "~/Magic8/Recordings";
    BaseRecord group = PathUtil.findPath(user, ModelNames.MODEL_GROUP, groupPath, "DATA");
    videoRecord.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));

    // Save
    BaseRecord saved = IOSystem.getActiveContext().getAccessPoint().create(user, videoRecord);

    return Response.ok(saved.toFilteredString()).build();
}
```

### 4. AI Image Generation from Camera Captures

**ImageGenerationManager**:
```javascript
class ImageGenerationManager {
    constructor(sdConfigObjectId) {
        this.sdConfigId = sdConfigObjectId;
        this.sdConfig = null;
        this.pendingGenerations = [];
        this.generatedImages = [];
    }

    async loadConfig() {
        // Load custom SD config from data.data object
        const response = await m.request({
            method: 'GET',
            url: `${g_application_path}/rest/model/data.data/${this.sdConfigId}`
        });
        this.sdConfig = JSON.parse(response.detailsString || '{}');
    }

    async captureAndGenerate(cameraComponent) {
        // Capture current frame from camera
        const imageData = await cameraComponent.captureFrame();
        const base64Image = imageData.split(',')[1];  // Remove data:image/... prefix

        // Build generation request with user image as init_image
        const genRequest = {
            prompt: this.sdConfig.prompt || "ethereal dreamlike portrait",
            negative_prompt: this.sdConfig.negative_prompt || "",
            init_image: base64Image,
            strength: this.sdConfig.strength || 0.65,  // How much to transform
            steps: this.sdConfig.steps || 30,
            cfg_scale: this.sdConfig.cfg_scale || 7.5,
            width: this.sdConfig.width || 512,
            height: this.sdConfig.height || 512,
            sampler: this.sdConfig.sampler || "euler_a",
            seed: this.sdConfig.seed || -1  // Random
        };

        // Queue for background generation
        this.queueGeneration(genRequest);
    }

    async queueGeneration(request) {
        // Add to pending queue
        const jobId = `gen-${Date.now()}`;
        this.pendingGenerations.push({ id: jobId, request, status: 'pending' });

        // Start background generation
        this.processInBackground(jobId, request);
    }

    async processInBackground(jobId, request) {
        try {
            // Call SD generation endpoint
            const response = await m.request({
                method: 'POST',
                url: `${g_application_path}/rest/sd/img2img`,
                body: request
            });

            if (response.images && response.images.length > 0) {
                // Save generated image to server
                const savedImage = await this.saveGeneratedImage(response.images[0], jobId);

                // Add to generated images pool
                this.generatedImages.push({
                    id: jobId,
                    url: savedImage.url,
                    objectId: savedImage.objectId,
                    timestamp: Date.now()
                });

                // Notify gallery to splice in new image
                this.onImageGenerated?.(savedImage);
            }

            // Update job status
            const job = this.pendingGenerations.find(j => j.id === jobId);
            if (job) job.status = 'complete';

        } catch (error) {
            console.error('Image generation failed:', error);
            const job = this.pendingGenerations.find(j => j.id === jobId);
            if (job) job.status = 'failed';
        }
    }

    async saveGeneratedImage(base64Image, jobId) {
        const response = await m.request({
            method: 'POST',
            url: `${g_application_path}/rest/media/upload/base64`,
            body: {
                data: base64Image,
                name: `generated-${jobId}.png`,
                contentType: 'image/png',
                groupPath: '~/Magic8/Generated'
            }
        });
        return response;
    }
}
```

**SD Config Schema** (stored in data.data):
```json
{
  "name": "HypnoSession SD Config",
  "type": "magic8.sdConfig",
  "config": {
    "prompt": "ethereal dreamlike portrait, soft lighting, mystical atmosphere, {emotion} mood",
    "negative_prompt": "blurry, distorted, ugly, deformed",
    "strength": 0.65,
    "steps": 30,
    "cfg_scale": 7.5,
    "width": 768,
    "height": 768,
    "sampler": "euler_a",
    "seed": -1,
    "captureInterval": 30000,
    "emotionPromptMapping": {
      "happy": "joyful radiant golden",
      "sad": "melancholic blue ethereal",
      "angry": "intense fiery dramatic",
      "fear": "mysterious shadowy surreal",
      "neutral": "serene peaceful calm"
    }
  }
}
```

### 5. Hypnotic Text Sequences

**TextSequenceManager**:
```javascript
class TextSequenceManager {
    constructor() {
        this.sequences = [];
        this.currentIndex = 0;
        this.isPlaying = false;
        this.displayDuration = 5000;  // ms per text
        this.fadeDuration = 1000;     // ms for fade transition
    }

    async loadFromDataObject(objectId) {
        // Load note or data object containing text
        const response = await m.request({
            method: 'GET',
            url: `${g_application_path}/rest/model/data.data/${objectId}`
        });

        // Split on line returns
        const content = response.detailsString || response.description || '';
        this.sequences = content
            .split(/\r?\n/)
            .map(line => line.trim())
            .filter(line => line.length > 0);

        return this.sequences.length;
    }

    async loadFromNote(noteObjectId) {
        const response = await m.request({
            method: 'GET',
            url: `${g_application_path}/rest/model/data.note/${noteObjectId}`
        });

        const content = response.text || '';
        this.sequences = content
            .split(/\r?\n/)
            .map(line => line.trim())
            .filter(line => line.length > 0);

        return this.sequences.length;
    }

    start() {
        this.isPlaying = true;
        this.currentIndex = 0;
        this.displayNext();
    }

    stop() {
        this.isPlaying = false;
        if (this.timeoutId) {
            clearTimeout(this.timeoutId);
        }
    }

    displayNext() {
        if (!this.isPlaying || this.currentIndex >= this.sequences.length) {
            // Loop or stop
            if (this.loop && this.sequences.length > 0) {
                this.currentIndex = 0;
            } else {
                this.onComplete?.();
                return;
            }
        }

        const text = this.sequences[this.currentIndex];
        this.onTextChange?.(text, this.currentIndex, this.sequences.length);

        this.currentIndex++;
        this.timeoutId = setTimeout(() => this.displayNext(), this.displayDuration);
    }

    getCurrent() {
        return this.sequences[this.currentIndex] || '';
    }
}
```

**Text Display Component**:
```javascript
const HypnoticTextDisplay = {
    oninit(vnode) {
        this.opacity = 0;
        this.text = '';
        this.transitioning = false;
    },

    showText(text) {
        // Fade out current
        this.transitioning = true;
        this.opacity = 0;

        setTimeout(() => {
            this.text = text;
            this.opacity = 1;
            this.transitioning = false;
            m.redraw();
        }, 500);  // Half of fade duration

        m.redraw();
    },

    view(vnode) {
        const { theme } = vnode.attrs;

        return m(".hypnotic-text-container.absolute.inset-0.flex.items-center.justify-center.pointer-events-none", [
            m(".hypnotic-text.text-4xl.md:text-6xl.font-light.text-center.px-8.transition-opacity.duration-500", {
                style: {
                    opacity: this.opacity,
                    color: `rgb(${theme?.accent?.join(',') || '255,255,255'})`,
                    textShadow: `0 0 30px rgb(${theme?.glow?.join(',') || '255,255,255'}),
                                 0 0 60px rgb(${theme?.glow?.join(',') || '255,255,255'})`,
                    fontFamily: "'Playfair Display', serif"
                }
            }, this.text)
        ]);
    }
};
```

### 6. Session Configuration

**Session Config Schema** (stored in data.data):
```json
{
  "schema": "magic8.sessionConfig",
  "name": "My Hypno Session",
  "version": "1.0",
  "config": {
    "display": {
      "fullscreen": true,
      "showControls": true,
      "controlsAutoHide": 5000
    },
    "biometrics": {
      "enabled": true,
      "updateInterval": 500,
      "smoothingFactor": 0.1
    },
    "audio": {
      "binauralEnabled": true,
      "baseFreq": 440,
      "minBeat": 2,
      "maxBeat": 8,
      "sweepDurationMin": 5,
      "toneEnabled": false
    },
    "images": {
      "baseGroups": [218, 220, 130],
      "cycleInterval": 5000,
      "crossfadeDuration": 1000,
      "includeGenerated": true
    },
    "imageGeneration": {
      "enabled": true,
      "sdConfigId": "abc-123-sd-config",
      "captureInterval": 30000,
      "maxGeneratedImages": 20
    },
    "text": {
      "enabled": true,
      "sourceObjectId": "xyz-789-text-sequence",
      "sourceType": "note",
      "displayDuration": 5000,
      "loop": true
    },
    "recording": {
      "enabled": false,
      "autoStart": false,
      "maxDurationMin": 30
    }
  }
}
```

**Session Config UI**:
```javascript
const SessionConfigEditor = {
    oninit(vnode) {
        this.config = vnode.attrs.config || this.defaultConfig();
        this.sdConfigs = [];
        this.textSources = [];
        this.loadOptions();
    },

    async loadOptions() {
        // Load available SD configs
        this.sdConfigs = await this.queryDataObjects('magic8.sdConfig');
        // Load available text sequences
        this.textSources = await this.queryDataObjects(['data.note', 'magic8.textSequence']);
        m.redraw();
    },

    async queryDataObjects(types) {
        // Query user's Magic8 folder for config objects
        const response = await m.request({
            method: 'POST',
            url: `${g_application_path}/rest/model/search`,
            body: {
                type: 'data.data',
                groupPath: '~/Magic8',
                fields: [{ name: 'schema', comparator: 'in', value: types }]
            }
        });
        return response.results || [];
    },

    view(vnode) {
        return m(".session-config-editor.p-6.bg-gray-900.rounded-lg", [
            m("h2.text-xl.mb-4", "Session Configuration"),

            // Biometrics section
            m(".config-section.mb-4", [
                m("h3.text-lg.mb-2", "Biometric Adaptation"),
                m("label.flex.items-center.gap-2", [
                    m("input[type=checkbox]", {
                        checked: this.config.biometrics.enabled,
                        onchange: (e) => this.config.biometrics.enabled = e.target.checked
                    }),
                    "Enable facial analysis color adaptation"
                ])
            ]),

            // Audio section
            m(".config-section.mb-4", [
                m("h3.text-lg.mb-2", "Audio"),
                m("label.flex.items-center.gap-2", [
                    m("input[type=checkbox]", {
                        checked: this.config.audio.binauralEnabled,
                        onchange: (e) => this.config.audio.binauralEnabled = e.target.checked
                    }),
                    "Enable binaural beats"
                ]),
                this.config.audio.binauralEnabled && m(".ml-4.mt-2", [
                    m("label.block", "Base Frequency: " + this.config.audio.baseFreq + " Hz"),
                    m("input[type=range][min=200][max=600]", {
                        value: this.config.audio.baseFreq,
                        oninput: (e) => this.config.audio.baseFreq = parseInt(e.target.value)
                    })
                ])
            ]),

            // Image Generation section
            m(".config-section.mb-4", [
                m("h3.text-lg.mb-2", "AI Image Generation"),
                m("label.flex.items-center.gap-2", [
                    m("input[type=checkbox]", {
                        checked: this.config.imageGeneration.enabled,
                        onchange: (e) => this.config.imageGeneration.enabled = e.target.checked
                    }),
                    "Enable SD image generation from camera"
                ]),
                this.config.imageGeneration.enabled && m(".ml-4.mt-2", [
                    m("label.block.mb-1", "SD Configuration:"),
                    m("select.w-full.p-2.bg-gray-800.rounded", {
                        value: this.config.imageGeneration.sdConfigId,
                        onchange: (e) => this.config.imageGeneration.sdConfigId = e.target.value
                    }, [
                        m("option", { value: "" }, "-- Select SD Config --"),
                        ...this.sdConfigs.map(c =>
                            m("option", { value: c.objectId }, c.name)
                        )
                    ]),
                    m("label.block.mt-2", "Capture Interval: " + (this.config.imageGeneration.captureInterval / 1000) + "s"),
                    m("input[type=range][min=10000][max=120000][step=5000]", {
                        value: this.config.imageGeneration.captureInterval,
                        oninput: (e) => this.config.imageGeneration.captureInterval = parseInt(e.target.value)
                    })
                ])
            ]),

            // Text Sequence section
            m(".config-section.mb-4", [
                m("h3.text-lg.mb-2", "Hypnotic Text"),
                m("label.flex.items-center.gap-2", [
                    m("input[type=checkbox]", {
                        checked: this.config.text.enabled,
                        onchange: (e) => this.config.text.enabled = e.target.checked
                    }),
                    "Enable text sequence"
                ]),
                this.config.text.enabled && m(".ml-4.mt-2", [
                    m("label.block.mb-1", "Text Source:"),
                    m("select.w-full.p-2.bg-gray-800.rounded", {
                        value: this.config.text.sourceObjectId,
                        onchange: (e) => this.config.text.sourceObjectId = e.target.value
                    }, [
                        m("option", { value: "" }, "-- Select Text Source --"),
                        ...this.textSources.map(t =>
                            m("option", { value: t.objectId }, t.name)
                        )
                    ]),
                    m("label.flex.items-center.gap-2.mt-2", [
                        m("input[type=checkbox]", {
                            checked: this.config.text.loop,
                            onchange: (e) => this.config.text.loop = e.target.checked
                        }),
                        "Loop text sequence"
                    ])
                ])
            ]),

            // Recording section
            m(".config-section.mb-4", [
                m("h3.text-lg.mb-2", "Session Recording"),
                m("label.flex.items-center.gap-2", [
                    m("input[type=checkbox]", {
                        checked: this.config.recording.enabled,
                        onchange: (e) => this.config.recording.enabled = e.target.checked
                    }),
                    "Enable video recording"
                ])
            ]),

            // Save button
            m("button.w-full.p-3.bg-indigo-600.rounded.mt-4", {
                onclick: () => vnode.attrs.onSave?.(this.config)
            }, "Save & Start Session")
        ]);
    }
};
```

### 7. Dynamic Image Gallery with Splicing

**Enhanced ImageGallery**:
```javascript
class DynamicImageGallery {
    constructor(config) {
        this.baseImages = [];          // Pre-loaded from groups
        this.generatedImages = [];     // From SD generation
        this.allImages = [];           // Combined pool
        this.currentIndex = 0;
        this.cycleInterval = config.cycleInterval || 5000;
        this.crossfadeDuration = config.crossfadeDuration || 1000;
        this.includeGenerated = config.includeGenerated !== false;
    }

    async loadBaseImages(groupIds) {
        const query = am7client.newQuery("data.data");
        const qg = query.field(null, null);
        qg.comparator = "group_or";
        qg.fields = groupIds.map(id => ({
            name: "groupId",
            comparator: "equals",
            value: id
        }));
        query.range(0, 0);

        const res = await am7client.search(query);
        this.baseImages = res.results.map(r => ({
            url: `${g_application_path}/media/Public/data.data${r.groupPath}/${r.name}`,
            objectId: r.objectId,
            type: 'base'
        }));

        this.rebuildPool();
    }

    // Called when new image is generated
    spliceGeneratedImage(imageData) {
        this.generatedImages.push({
            url: imageData.url,
            objectId: imageData.objectId,
            type: 'generated',
            timestamp: Date.now()
        });

        // Trim old generated images if over limit
        const maxGenerated = 20;
        if (this.generatedImages.length > maxGenerated) {
            this.generatedImages = this.generatedImages.slice(-maxGenerated);
        }

        this.rebuildPool();
    }

    rebuildPool() {
        if (this.includeGenerated) {
            // Interleave generated images with base images
            this.allImages = [];
            const genRatio = 0.3;  // 30% generated images

            let baseIdx = 0;
            let genIdx = 0;

            while (baseIdx < this.baseImages.length || genIdx < this.generatedImages.length) {
                // Add base images
                if (baseIdx < this.baseImages.length) {
                    this.allImages.push(this.baseImages[baseIdx++]);
                }

                // Occasionally insert generated
                if (genIdx < this.generatedImages.length && Math.random() < genRatio) {
                    this.allImages.push(this.generatedImages[genIdx++]);
                }
            }

            // Add remaining generated
            while (genIdx < this.generatedImages.length) {
                this.allImages.push(this.generatedImages[genIdx++]);
            }
        } else {
            this.allImages = [...this.baseImages];
        }

        // Shuffle for variety
        this.shuffle(this.allImages);
    }

    shuffle(array) {
        for (let i = array.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [array[i], array[j]] = [array[j], array[i]];
        }
    }

    getNext() {
        if (this.allImages.length === 0) return null;
        this.currentIndex = (this.currentIndex + 1) % this.allImages.length;
        return this.allImages[this.currentIndex];
    }

    getCurrent() {
        return this.allImages[this.currentIndex] || null;
    }
}
```

---

## Proposed Architecture

### Module Structure

```
AccountManagerUx7/client/view/
└── magic8/
    ├── index.js                    # Main entry, exports consolidated API
    ├── Magic8App.js                # Primary orchestration component
    │
    ├── components/
    │   ├── Magic8Ball.js           # Dual-hemisphere visualizer (extracted)
    │   ├── HypnoCanvas.js          # Full-screen particle animation canvas
    │   ├── HypnoticTextDisplay.js  # Scripted text overlay component
    │   ├── ControlPanel.js         # Floating controls (auto-hide)
    │   ├── SessionConfigEditor.js  # Session setup UI
    │   └── RecordingIndicator.js   # Recording status badge
    │
    ├── audio/
    │   ├── AudioEngine.js          # Unified audio context management
    │   ├── BinauralEngine.js       # Binaural beats/sweeps
    │   ├── ToneSynth.js            # Tone.js synthesis wrapper
    │   └── VoiceSource.js          # Voice synthesis integration
    │
    ├── video/
    │   ├── SessionRecorder.js      # Canvas + audio recording
    │   └── VideoUploader.js        # Server upload handler
    │
    ├── generation/
    │   ├── ImageGenerationManager.js  # SD img2img orchestration
    │   ├── CameraCapture.js           # Frame capture from camera
    │   └── PromptBuilder.js           # Dynamic prompt from emotion
    │
    ├── text/
    │   ├── TextSequenceManager.js  # Load/cycle hypnotic text
    │   └── TextSourceLoader.js     # Load from note/data objects
    │
    ├── state/
    │   ├── AppState.js             # Centralized state management
    │   ├── BiometricThemer.js      # Emotion-to-theme with smooth transitions
    │   └── SessionConfig.js        # Config load/save
    │
    └── utils/
        ├── DynamicImageGallery.js  # Gallery with generated image splicing
        ├── FullscreenManager.js    # Fullscreen API wrapper
        └── ContentCycler.js        # Timed content rotation
```

### Consolidated Component: Magic8App

```javascript
// magic8/Magic8App.js
const Magic8App = {
    // Session Configuration (loaded from data.data)
    sessionConfig: null,

    // Unified State
    state: {
        phase: 'config' | 'running' | 'paused',
        isFullscreen: false,
        isRecording: false,

        // Subsystems
        audioEngine: null,
        biometricThemer: null,
        imageGallery: null,
        textSequence: null,
        imageGenerator: null,
        sessionRecorder: null,

        // Live data
        currentTheme: null,
        currentImage: null,
        currentText: '',
        biometricData: null
    },

    // Lifecycle
    async oninit(vnode) {
        this.sessionConfigId = vnode.attrs.sessionConfigId;

        if (this.sessionConfigId) {
            await this.loadSessionConfig();
            this.state.phase = 'running';
            await this.initSession();
        } else {
            this.state.phase = 'config';
        }
    },

    onremove() {
        this.cleanup();
    },

    // Configuration
    async loadSessionConfig() {
        const response = await m.request({
            method: 'GET',
            url: `${g_application_path}/rest/model/data.data/${this.sessionConfigId}`
        });
        this.sessionConfig = JSON.parse(response.detailsString || '{}');
    },

    async saveSessionConfig(config) {
        // Save to data.data object
        const record = {
            schema: 'data.data',
            name: config.name || 'Magic8 Session',
            groupPath: '~/Magic8/Configs',
            detailsString: JSON.stringify(config)
        };

        const response = await m.request({
            method: 'POST',
            url: `${g_application_path}/rest/model`,
            body: record
        });

        this.sessionConfig = config;
        return response.objectId;
    },

    // Session Initialization
    async initSession() {
        const cfg = this.sessionConfig;

        // Enter fullscreen
        if (cfg.display?.fullscreen) {
            await this.fullscreenManager.enterFullscreen(this.containerElement);
        }

        // Initialize audio engine
        this.state.audioEngine = new AudioEngine();
        if (cfg.audio?.binauralEnabled) {
            this.state.audioEngine.startBinaural(cfg.audio);
        }

        // Initialize biometric themer
        if (cfg.biometrics?.enabled) {
            this.state.biometricThemer = new BiometricThemer();
            this.state.biometricThemer.onThemeChange = (theme) => {
                this.state.currentTheme = theme;
                m.redraw();
            };
            this.startBiometricCapture();
        }

        // Initialize image gallery
        this.state.imageGallery = new DynamicImageGallery(cfg.images || {});
        await this.state.imageGallery.loadBaseImages(cfg.images?.baseGroups || []);
        this.startImageCycling();

        // Initialize image generation
        if (cfg.imageGeneration?.enabled) {
            this.state.imageGenerator = new ImageGenerationManager(cfg.imageGeneration.sdConfigId);
            await this.state.imageGenerator.loadConfig();
            this.state.imageGenerator.onImageGenerated = (img) => {
                this.state.imageGallery.spliceGeneratedImage(img);
            };
            this.startImageGeneration();
        }

        // Initialize text sequence
        if (cfg.text?.enabled && cfg.text?.sourceObjectId) {
            this.state.textSequence = new TextSequenceManager();
            this.state.textSequence.loop = cfg.text.loop;
            this.state.textSequence.displayDuration = cfg.text.displayDuration || 5000;

            if (cfg.text.sourceType === 'note') {
                await this.state.textSequence.loadFromNote(cfg.text.sourceObjectId);
            } else {
                await this.state.textSequence.loadFromDataObject(cfg.text.sourceObjectId);
            }

            this.state.textSequence.onTextChange = (text) => {
                this.state.currentText = text;
                m.redraw();
            };
            this.state.textSequence.start();
        }

        // Initialize recording
        if (cfg.recording?.enabled) {
            this.state.sessionRecorder = new SessionRecorder();
            if (cfg.recording.autoStart) {
                this.startRecording();
            }
        }
    },

    // Biometric capture loop
    startBiometricCapture() {
        const interval = this.sessionConfig.biometrics?.updateInterval || 500;

        this.biometricInterval = setInterval(async () => {
            const data = await page.components.camera.captureAnalysis();
            if (data) {
                this.state.biometricData = data;
                this.state.biometricThemer.updateFromBiometrics(data);
            }
        }, interval);
    },

    // Image cycling
    startImageCycling() {
        const interval = this.sessionConfig.images?.cycleInterval || 5000;

        this.imageCycleInterval = setInterval(() => {
            const next = this.state.imageGallery.getNext();
            if (next) {
                this.state.currentImage = next;
                m.redraw();
            }
        }, interval);
    },

    // Image generation from camera
    startImageGeneration() {
        const interval = this.sessionConfig.imageGeneration?.captureInterval || 30000;

        this.imageGenInterval = setInterval(async () => {
            await this.state.imageGenerator.captureAndGenerate(page.components.camera);
        }, interval);
    },

    // Recording controls
    async startRecording() {
        const canvas = document.querySelector('.hypno-canvas');
        const audioDestination = this.state.audioEngine?.getDestination();

        await this.state.sessionRecorder.startRecording(canvas, audioDestination);
        this.state.isRecording = true;
        m.redraw();
    },

    async stopRecording() {
        const blob = await this.state.sessionRecorder.stopRecording();
        await this.state.sessionRecorder.saveToServer(blob, this.sessionConfig);
        this.state.isRecording = false;
        m.redraw();
    },

    // Cleanup
    cleanup() {
        clearInterval(this.biometricInterval);
        clearInterval(this.imageCycleInterval);
        clearInterval(this.imageGenInterval);

        this.state.audioEngine?.dispose();
        this.state.textSequence?.stop();

        if (this.state.isRecording) {
            this.stopRecording();
        }

        this.fullscreenManager?.exitFullscreen();
    },

    // View
    view(vnode) {
        // Config phase - show editor
        if (this.state.phase === 'config') {
            return m(SessionConfigEditor, {
                config: this.sessionConfig,
                onSave: async (config) => {
                    await this.saveSessionConfig(config);
                    this.state.phase = 'running';
                    await this.initSession();
                    m.redraw();
                }
            });
        }

        // Running phase - full experience
        return m(".magic8-app.fixed.inset-0.overflow-hidden", {
            style: {
                background: `rgb(${this.state.currentTheme?.bg?.join(',') || '20,20,30'})`
            }
        }, [
            // Background image layer
            m(".absolute.inset-0.transition-opacity.duration-1000", {
                style: {
                    backgroundImage: this.state.currentImage ?
                        `url(${this.state.currentImage.url})` : 'none',
                    backgroundSize: 'cover',
                    backgroundPosition: 'center',
                    opacity: 0.6
                }
            }),

            // Particle canvas layer
            m(HypnoCanvas, {
                theme: this.state.currentTheme,
                class: 'hypno-canvas absolute inset-0'
            }),

            // Hypnotic text layer
            this.sessionConfig?.text?.enabled && m(HypnoticTextDisplay, {
                text: this.state.currentText,
                theme: this.state.currentTheme
            }),

            // Recording indicator
            this.state.isRecording && m(RecordingIndicator),

            // Control panel (auto-hide)
            m(ControlPanel, {
                config: this.sessionConfig,
                state: this.state,
                onToggleRecording: () => {
                    this.state.isRecording ? this.stopRecording() : this.startRecording();
                },
                onExit: () => this.cleanup()
            })
        ]);
    }
};
```

### Audio Engine Consolidation

```javascript
// magic8/audio/AudioEngine.js
class AudioEngine {
    constructor() {
        this.context = null;
        this.sources = new Map();      // Active audio sources
        this.binauralEngine = null;
        this.toneSynth = null;
    }

    // Singleton context management
    getContext() {
        if (!this.context || this.context.state === 'closed') {
            this.context = new AudioContext();
        }
        return this.context;
    }

    // Voice synthesis
    async createVoiceSource(content, profileId) {
        const hash = this.hashContent(content);
        if (this.sources.has(hash)) {
            return this.sources.get(hash);
        }

        const audio = await this.synthesizeVoice(content, profileId);
        const source = new VoiceSource(this.context, audio);
        this.sources.set(hash, source);
        return source;
    }

    // Binaural control
    startBinaural(config) {
        this.binauralEngine = new BinauralEngine(this.context, config);
        this.binauralEngine.start();
    }

    stopBinaural() {
        this.binauralEngine?.stop();
    }

    // Tone synthesis (for HypnoApp)
    startTone(config) {
        this.toneSynth = new ToneSynth(config);
        this.toneSynth.start();
    }

    // Cleanup
    stopAll() {
        this.sources.forEach(s => s.stop());
        this.sources.clear();
        this.binauralEngine?.stop();
        this.toneSynth?.stop();
    }

    dispose() {
        this.stopAll();
        this.context?.close();
    }
}
```

### Biometric Theme Engine

```javascript
// magic8/state/BiometricState.js
class BiometricThemer {
    static emotionThemes = {
        neutral:  { bg: [128, 128, 160], accent: [200, 200, 220] },
        happy:    { bg: [255, 220, 100], accent: [255, 240, 180] },
        sad:      { bg: [80, 80, 160],   accent: [120, 120, 200] },
        angry:    { bg: [200, 80, 80],   accent: [255, 120, 120] },
        fear:     { bg: [160, 80, 200],  accent: [200, 140, 230] },
        surprise: { bg: [255, 180, 200], accent: [255, 220, 230] },
        disgust:  { bg: [80, 160, 80],   accent: [140, 200, 140] }
    };

    static genderColors = {
        Man:   [180, 200, 255],
        Woman: [255, 200, 220]
    };

    static computeTheme(biometricData) {
        if (!biometricData) return this.emotionThemes.neutral;

        const baseTheme = this.emotionThemes[biometricData.dominant_emotion]
                       || this.emotionThemes.neutral;

        // Blend with gender if available
        const genderColor = this.genderColors[biometricData.dominant_gender];
        if (genderColor) {
            return this.blendTheme(baseTheme, genderColor, 0.2);
        }

        return baseTheme;
    }

    static blendTheme(theme, color, factor) {
        return {
            bg: theme.bg.map((c, i) => Math.round(c * (1 - factor) + color[i] * factor)),
            accent: theme.accent.map((c, i) => Math.round(c * (1 - factor) + color[i] * factor))
        };
    }
}
```

### Image Gallery Manager

```javascript
// magic8/utils/ImageLoader.js
class ImageGallery {
    constructor(groupIds, cycleInterval = 5000) {
        this.groupIds = groupIds;
        this.cycleInterval = cycleInterval;
        this.images = [];
        this.currentIndex = 0;
        this.intervalId = null;
        this.onImageChange = null;
    }

    async load() {
        const query = am7client.newQuery("data.data");
        const qg = query.field(null, null);
        qg.comparator = "group_or";
        qg.fields = this.groupIds.map(id => ({
            name: "groupId",
            comparator: "equals",
            value: id
        }));
        query.range(0, 0);

        const res = await am7client.search(query);
        this.images = res.results.map(r =>
            `${g_application_path}/media/Public/data.data${r.groupPath}/${r.name}`
        );

        return this.images.length;
    }

    start() {
        if (this.intervalId) return;
        this.intervalId = setInterval(() => this.cycle(), this.cycleInterval);
    }

    stop() {
        if (this.intervalId) {
            clearInterval(this.intervalId);
            this.intervalId = null;
        }
    }

    cycle() {
        this.currentIndex = Math.floor(Math.random() * this.images.length);
        this.onImageChange?.(this.images[this.currentIndex]);
    }

    getCurrent() {
        return this.images[this.currentIndex];
    }
}
```

---

## Migration Strategy

### Phase 1: Extract Shared Utilities

1. **Create `magic8/audio/AudioEngine.js`**
   - Extract audio context management from `audio.js`
   - Consolidate source caching logic
   - Unify cleanup/disposal

2. **Create `magic8/utils/ImageLoader.js`**
   - Extract image loading from `hyp.js`
   - Extract crossfade logic from `audioComponents.js`
   - Create unified gallery API

3. **Create `magic8/state/BiometricState.js`**
   - Extract biometric processing from `hyp.js`
   - Create theme computation utilities

### Phase 2: Refactor Components

1. **Extract `HypnoCanvas` from `hyp.js`**
   - Separate canvas animation into own component
   - Keep particle system self-contained
   - Allow theme injection

2. **Create `ControlPanel` component**
   - Unified controls for all modes
   - Audio toggles, binaural controls, camera toggle

3. **Build core Magic8 components**
   - `HypnoticTextDisplay.js`
   - `SessionConfigEditor.js`
   - `RecordingIndicator.js`

### Phase 3: Create Orchestrator

1. **Build `Magic8App.js`**
   - Integrate all extracted components
   - Implement session lifecycle
   - Manage shared state

2. **Deprecate standalone `hyp.js`**
   - Redirect to `magic8/` module
   - Maintain API compatibility during transition

### Phase 4: Refactor chat.js Integration

**IMPORTANT**: Remove inline Magic8Ball entirely from chat.js

1. **Remove trinary audio toggle**
   - Current: off → audio on → magic8 → off (3-state cycle)
   - New: Simple binary toggle (audio off ↔ audio on)

2. **Remove Magic8Ball component from chat view**
   - Delete all `audioMagic8` state and logic
   - Remove Magic8Ball rendering from message display

3. **Add dedicated "Send to Magic8" button**
   - New button in chat toolbar
   - Launches full-screen Magic8 experience
   - Passes current chat context

### Phase 5: Testing & Cleanup

1. **Create test harness**
   - Test Magic8App independently
   - Test chat → Magic8 handoff
   - Test audio/visual synchronization

2. **Remove deprecated code**
   - Delete Magic8Ball from `audioComponents.js`
   - Remove `audioMagic8` state from `chat.js`
   - Clean up unused code from `audio.js`

---

## Chat.js Refactor Details

### Current Implementation (to be removed)

```javascript
// REMOVE: Trinary toggle state
let audio = false;
let audioMagic8 = false;

// REMOVE: Trinary toggle function
function toggleAudio() {
    if (audio && !audioMagic8) {
        audio = false; audioMagic8 = true;  // Standard → Magic8
    } else if (audioMagic8) {
        audio = false; audioMagic8 = false; // Magic8 → Off
    } else {
        audio = true; audioMagic8 = false;  // Off → Standard
    }
}

// REMOVE: Magic8Ball rendering in chat
if (audioMagic8) {
    aud = m(page.components.audioComponents.Magic8Ball, {
        instanceId: inst.model.objectId,
        messages: chatCfg.history.messages,
        // ... many props
    });
}
```

### New Implementation

```javascript
// NEW: Simple binary audio state
let audioEnabled = false;

// NEW: Simple audio toggle
function toggleAudio() {
    audioEnabled = !audioEnabled;
}

// NEW: Send to Magic8 function
function sendToMagic8() {
    // Serialize current chat context
    const magic8Context = {
        chatConfigId: chatCfg?.objectId,
        chatHistory: chatCfg?.history?.messages || [],
        systemCharacter: chatCfg?.system,
        userCharacter: chatCfg?.user,
        instanceId: inst?.model?.objectId
    };

    // Store context for Magic8 to retrieve
    sessionStorage.setItem('magic8Context', JSON.stringify(magic8Context));

    // Navigate to Magic8 view (or open in modal/overlay)
    m.route.set('/magic8', { context: 'chat' });
}

// NEW: Chat toolbar buttons
function renderToolbar() {
    return m(".chat-toolbar.flex.gap-2", [
        // Simple audio toggle (binary)
        page.iconButton("button",
            audioEnabled ? "volume_up" : "volume_off",
            audioEnabled ? "Audio On" : "Audio Off",
            toggleAudio
        ),

        // Dedicated Magic8 button
        page.iconButton("button",
            "magic_button",  // or "auto_awesome" or custom icon
            "Send to Magic8",
            sendToMagic8
        ),

        // ... other toolbar buttons
    ]);
}
```

### Magic8 Context Reception

```javascript
// magic8/Magic8App.js - Handle chat context handoff

async oninit(vnode) {
    // Check for chat context from sessionStorage
    const contextJson = sessionStorage.getItem('magic8Context');
    if (contextJson) {
        this.chatContext = JSON.parse(contextJson);
        sessionStorage.removeItem('magic8Context');  // Clear after reading

        // Pre-populate session config with chat info
        this.sessionConfig = this.sessionConfig || {};
        this.sessionConfig.chat = {
            enabled: true,
            historyMessages: this.chatContext.chatHistory,
            systemCharacter: this.chatContext.systemCharacter,
            userCharacter: this.chatContext.userCharacter
        };
    }

    // Continue with normal initialization
    if (this.sessionConfigId) {
        await this.loadSessionConfig();
    }

    this.state.phase = this.sessionConfig ? 'running' : 'config';
    if (this.state.phase === 'running') {
        await this.initSession();
    }
}
```

### Return to Chat from Magic8

```javascript
// In Magic8 ControlPanel - "Return to Chat" button
function returnToChat() {
    // Optionally pass back any new messages or state
    const returnContext = {
        newMessages: this.state.generatedMessages || [],
        sessionId: this.sessionId
    };
    sessionStorage.setItem('magic8Return', JSON.stringify(returnContext));

    // Navigate back
    window.history.back();
    // Or: m.route.set('/chat/' + this.chatContext.instanceId);
}
```

---

## Integration Points

### Chat → Magic8 Handoff

```javascript
// In chat.js - The "Send to Magic8" button click handler
function sendToMagic8() {
    const context = {
        // Chat identification
        chatConfigId: chatCfg?.objectId,
        instanceId: inst?.model?.objectId,

        // Chat history for continuity
        messages: chatCfg?.history?.messages?.slice(-20) || [],  // Last 20 messages

        // Character info for voice profiles
        systemCharacter: {
            objectId: chatCfg?.system?.objectId,
            name: chatCfg?.system?.name,
            profileId: chatCfg?.system?.profile?.objectId
        },
        userCharacter: {
            objectId: chatCfg?.user?.objectId,
            name: chatCfg?.user?.name,
            profileId: chatCfg?.user?.profile?.objectId
        },

        // Current audio state
        wasAudioEnabled: audioEnabled
    };

    // Store and navigate
    sessionStorage.setItem('magic8Context', JSON.stringify(context));
    m.route.set('/magic8');
}
```

### Standalone Usage (No Chat)

```javascript
// Direct navigation to Magic8 without chat context
m.route.set('/magic8');

// Or with a saved session config
m.route.set('/magic8', { sessionConfigId: 'abc-123' });
```

### Route Configuration

```javascript
// In app routes configuration
m.route(document.body, '/home', {
    '/home': HomeView,
    '/chat/:id': ChatView,
    '/magic8': {
        onmatch: (args) => {
            return import('./magic8/Magic8App.js').then(m => m.default);
        },
        render: (vnode) => m(vnode.tag, {
            sessionConfigId: m.route.param('sessionConfigId'),
            fromChat: m.route.param('context') === 'chat'
        })
    }
});
```

---

## Configuration Options

### Mode Presets

```javascript
const PRESETS = {
    // Classic Magic8Ball chat experience
    magic8Chat: {
        mode: 'magic8',
        audioEnabled: true,
        binauralEnabled: false,
        cameraEnabled: false,
        useProfileImages: true
    },

    // HypnoApp meditation experience
    hypnoMeditation: {
        mode: 'hypno',
        audioEnabled: true,
        binauralEnabled: true,
        cameraEnabled: true,
        imageGroups: [218, 220, 130, 172, 173],
        cycleInterval: 5000,
        binauralConfig: {
            baseFreq: 440,
            minBeat: 0,
            maxBeat: 8,
            sweepDurationMin: 5
        }
    },

    // Hybrid mode with all features
    fullExperience: {
        mode: 'hybrid',
        audioEnabled: true,
        binauralEnabled: true,
        cameraEnabled: true,
        useProfileImages: false,
        imageGroups: [218, 220, 130, 172, 173],
        cycleInterval: 3000
    }
};
```

---

## Implementation Checklist

### Phase 1: Foundation
- [ ] Create `magic8/` directory structure
- [ ] Implement `AudioEngine.js` with unified context management
- [ ] Implement `DynamicImageGallery.js` with loading, cycling, and splicing
- [ ] Implement `BiometricThemer.js` with smooth transition animations
- [ ] Implement `FullscreenManager.js` for fullscreen API
- [ ] Add `index.js` with public exports

### Phase 2: Component Extraction
- [ ] Extract `Magic8Ball` to `magic8/components/Magic8Ball.js`
- [ ] Extract particle canvas to `magic8/components/HypnoCanvas.js`
- [ ] Create `ControlPanel.js` with auto-hide behavior
- [ ] Create `BinauralEngine.js` wrapper
- [ ] Create `ToneSynth.js` wrapper for Tone.js
- [ ] Create `HypnoticTextDisplay.js` for scripted text overlay
- [ ] Create `RecordingIndicator.js` badge component

### Phase 3: Session Configuration
- [ ] Implement `SessionConfig.js` for load/save
- [ ] Create `SessionConfigEditor.js` UI component
- [ ] Define session config JSON schema
- [ ] Create default config templates
- [ ] Implement config persistence to `data.data`

### Phase 4: Video Recording
- [ ] Implement `SessionRecorder.js` with MediaRecorder API
- [ ] Add canvas + audio stream capture
- [ ] Implement `VideoUploader.js` for server upload
- [ ] Create server-side upload endpoint
- [ ] Add recording controls to ControlPanel

### Phase 5: AI Image Generation
- [ ] Implement `ImageGenerationManager.js`
- [ ] Implement `CameraCapture.js` for frame extraction
- [ ] Implement `PromptBuilder.js` for emotion-based prompts
- [ ] Create SD config JSON schema
- [ ] Integrate with existing SD endpoints
- [ ] Implement background generation queue
- [ ] Wire generated images to gallery splicing

### Phase 6: Hypnotic Text Sequences
- [ ] Implement `TextSequenceManager.js`
- [ ] Implement `TextSourceLoader.js` for note/data loading
- [ ] Add text display timing and fade transitions
- [ ] Support loop/no-loop modes
- [ ] Create sample text sequence data objects

### Phase 7: Orchestration
- [ ] Implement `Magic8App.js` orchestrator
- [ ] Wire all subsystems together
- [ ] Implement session lifecycle (init/run/cleanup)
- [ ] Add state management
- [ ] Implement fullscreen mode

### Phase 8: Chat.js Refactor
- [ ] Remove `audioMagic8` state variable from `chat.js`
- [ ] Remove trinary audio toggle logic
- [ ] Implement simple binary audio toggle (on/off)
- [ ] Add "Send to Magic8" button to chat toolbar
- [ ] Implement `sendToMagic8()` context serialization
- [ ] Remove Magic8Ball rendering from chat message display
- [ ] Delete Magic8Ball component from `audioComponents.js`
- [ ] Add route for `/magic8` view
- [ ] Implement context handoff via sessionStorage
- [ ] Add "Return to Chat" functionality in Magic8

### Phase 9: Server-Side
- [ ] Add video upload REST endpoint
- [ ] Add session config CRUD endpoints
- [ ] Create `~/Magic8/` folder structure on first use
- [ ] Add generated image storage handling

### Phase 10: Cleanup & Polish
- [ ] Remove deprecated code from `audioComponents.js`
- [ ] Remove deprecated code from `audio.js`
- [ ] Add keyboard shortcuts (ESC to exit, SPACE to pause)
- [ ] Add touch gestures for mobile
- [ ] Performance optimization
- [ ] Update documentation
- [ ] Final testing of all modes

---

## API Reference

### Magic8App

```javascript
// Props
{
    sessionConfigId: string,     // ObjectId of saved session config (optional)
    onSessionEnd: function       // Callback when session ends
}

// Methods (via vnode.state)
loadSessionConfig()              // Load config from server
saveSessionConfig(config)        // Save config to server
initSession()                    // Initialize all subsystems
startRecording()                 // Begin video recording
stopRecording()                  // Stop and save recording
cleanup()                        // Full teardown
```

### BiometricThemer

```javascript
// Constructor
new BiometricThemer()

// Methods
updateFromBiometrics(data)       // Process facial analysis data
transitionTo(theme)              // Smooth transition to new theme
applyTheme(theme)                // Apply theme immediately

// Events
onThemeChange(theme)             // Callback when theme updates

// Theme structure
{
    bg: [r, g, b],               // Background color
    accent: [r, g, b],           // Accent/text color
    glow: [r, g, b]              // Glow/shadow color
}
```

### DynamicImageGallery

```javascript
// Constructor
new DynamicImageGallery(config)

// Methods
loadBaseImages(groupIds)         // Load images from media groups
spliceGeneratedImage(imageData)  // Add generated image to pool
rebuildPool()                    // Rebuild combined image list
getNext()                        // Get next image in rotation
getCurrent()                     // Get current image

// Events
onImageChange(imageData)         // Callback for image changes
```

### SessionRecorder

```javascript
// Constructor
new SessionRecorder()

// Methods
startRecording(canvas, audioDestination)  // Begin recording
stopRecording()                           // Stop and return blob
saveToServer(blob, sessionConfig)         // Upload to server

// Properties
isRecording: boolean
```

### ImageGenerationManager

```javascript
// Constructor
new ImageGenerationManager(sdConfigObjectId)

// Methods
loadConfig()                     // Load SD config from server
captureAndGenerate(camera)       // Capture frame and queue generation
queueGeneration(request)         // Add to generation queue

// Events
onImageGenerated(imageData)      // Callback when image ready
```

### TextSequenceManager

```javascript
// Constructor
new TextSequenceManager()

// Methods
loadFromDataObject(objectId)     // Load text from data.data
loadFromNote(objectId)           // Load text from note
start()                          // Begin sequence playback
stop()                           // Stop playback
getCurrent()                     // Get current text

// Properties
displayDuration: number          // ms per text
loop: boolean                    // Loop when complete

// Events
onTextChange(text, index, total) // Callback for text changes
onComplete()                     // Callback when sequence ends
```

### AudioEngine

```javascript
// Static
AudioEngine.getInstance()        // Get singleton

// Instance methods
getContext()                     // Get/create AudioContext
getDestination()                 // Get audio destination for recording
createVoiceSource(content, profileId)  // Create voice audio
startBinaural(config)            // Start binaural beats
stopBinaural()                   // Stop binaural
startTone(config)                // Start Tone.js synthesis
stopAll()                        // Stop all audio
dispose()                        // Full cleanup
```

---

## Data Models

### Session Config (data.data)
```json
{
  "schema": "magic8.sessionConfig",
  "name": "string",
  "groupPath": "~/Magic8/Configs",
  "detailsString": "{ ... serialized config ... }"
}
```

### SD Config (data.data)
```json
{
  "schema": "magic8.sdConfig",
  "name": "string",
  "groupPath": "~/Magic8/SDConfigs",
  "detailsString": "{ prompt, negative_prompt, strength, steps, ... }"
}
```

### Text Sequence (data.note or data.data)
```json
{
  "schema": "data.note",
  "name": "Hypnotic Sequence 1",
  "groupPath": "~/Magic8/TextSequences",
  "text": "Line 1\nLine 2\nLine 3\n..."
}
```

### Generated Image (data.data)
```json
{
  "schema": "data.data",
  "name": "generated-{jobId}.png",
  "groupPath": "~/Magic8/Generated",
  "contentType": "image/png",
  "byteStore": "... binary ..."
}
```

### Session Recording (data.data)
```json
{
  "schema": "data.data",
  "name": "session-{timestamp}.webm",
  "groupPath": "~/Magic8/Recordings",
  "contentType": "video/webm",
  "byteStore": "... binary ..."
}
```

---

## Folder Structure (Server-Side)

```
~/Magic8/
├── Configs/           # Session configuration objects
├── SDConfigs/         # Stable Diffusion configuration objects
├── TextSequences/     # Hypnotic text note objects
├── Generated/         # AI-generated images
└── Recordings/        # Session video recordings
```

---

## Conclusion

The consolidated Magic8 module provides a full-featured immersive experience platform:

1. **Full-screen immersion** - Distraction-free environment
2. **Biometric responsiveness** - Real-time color adaptation from facial analysis
3. **AI image generation** - Dynamic content creation from user's own image
4. **Scripted text sequences** - Customizable hypnotic text display
5. **Session recording** - Capture and save the experience
6. **Unified architecture** - Shared audio, image, and theming systems
7. **Persistent configuration** - Save/load session settings

The modular design allows each feature to be enabled/disabled independently while sharing core infrastructure for audio, visuals, and state management.
