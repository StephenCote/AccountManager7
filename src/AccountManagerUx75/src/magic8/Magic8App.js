/**
 * Magic8App.js — Primary orchestrator for Magic8 immersive experience (ESM)
 * Port of Ux7 client/view/magic8/Magic8App.js (2,390 lines)
 *
 * Coordinates all subsystems: audio, biometrics, images, text, recording, generation
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { am7view } from '../core/view.js';
import { uwm } from '../core/config.js';

// Subsystem imports
import { FullscreenManager } from './utils/FullscreenManager.js';
import { AudioEngine } from './audio/AudioEngine.js';
import { BiometricThemer } from './state/BiometricThemer.js';
import { DynamicImageGallery } from './utils/DynamicImageGallery.js';
import { TextSequenceManager } from './text/TextSequenceManager.js';
import { VoiceSequenceManager } from './audio/VoiceSequenceManager.js';
import { ImageGenerationManager } from './generation/ImageGenerationManager.js';
import { SessionRecorder } from './video/SessionRecorder.js';
import { SessionDirector } from './ai/SessionDirector.js';

// UI component imports
import { SessionConfigEditor } from './components/SessionConfigEditor.js';
import { HypnoCanvas } from './components/HypnoCanvas.js';
import { AudioVisualizerOverlay } from './components/AudioVisualizerOverlay.js';
import { BiometricOverlay } from './components/BiometricOverlay.js';
import { ControlPanel } from './components/ControlPanel.js';
import { MoodEmojiDisplay } from './components/MoodEmojiDisplay.js';

function getPage() { return am7model._page; }

// Recording indicator sub-component
const RecordingIndicator = {
    view(vnode) {
        const recorder = vnode.attrs.recorder;
        return m('.fixed.top-4.left-4.flex.items-center.gap-2.z-50', [
            m('.w-3.h-3.rounded-full.bg-red-500', {
                style: { animation: 'pulse 1s infinite' }
            }),
            m('.text-red-400.text-xs.font-mono', 'REC')
        ]);
    }
};

const Magic8App = {
    // Session state
    sessionConfig: null,
    chatContext: null,
    containerElement: null,

    // Subsystem instances
    fullscreenManager: null,
    audioEngine: null,
    biometricThemer: null,
    imageGallery: null,
    textSequence: null,
    voiceSequence: null,
    imageGenerator: null,
    sessionRecorder: null,

    // UI state
    phase: 'config',
    isFullscreen: false,
    isRecording: false,
    audioEnabled: false,
    showConfig: false,
    currentTheme: null,
    currentImage: null,
    prevImage: null,
    imageOpacity: 1,
    currentText: '',
    biometricData: null,

    // Session director
    sessionDirector: null,
    sessionLabels: [],
    sessionVoiceLines: [],
    _sessionStartTime: null,
    _emotionHistory: [],

    // Mood suggestion state
    _suggestedEmotion: null,
    _suggestedGender: null,
    moodRingEnabled: false,
    moodRingColor: null,
    _genderMalePct: 50,

    // Image animation
    _imageAnimation: null,

    // Test mode thumbnails
    _lastCaptureObj: null,
    _lastGeneratedObj: null,

    // Interactive director state
    _pendingQuestion: null,
    _userResponse: null,
    _userInputText: '',
    _sttMediaStream: null,
    _sttMediaRecorder: null,
    _responseTimeoutId: null,
    _interactionIdleTimer: null,
    _lastTypingTime: 0,
    _voicePausedForInteraction: false,
    _ticksSinceAskUser: 0,

    // Debug console
    debugMessages: [],
    _debugMinimized: false,

    // Ambient breathing
    ambientOpacity: 0.15,
    _breatheTransitionMs: 120000,
    _breatheTimeout: null,

    // Layer opacity
    _layerOpacity: { labels: 1, images: 1, visualizer: 1, visuals: 1 },
    _layerOpacityTarget: { labels: 1, images: 1, visualizer: 1, visuals: 1 },
    _layerOpacityStart: { labels: 1, images: 1, visualizer: 1, visuals: 1 },
    _layerTransitionStart: {},
    _opacityTransitionMs: 20000,
    _opacityRafId: null,

    // Intervals
    biometricInterval: null,
    imageCycleInterval: null,
    imageGenInterval: null,

    // ── Lifecycle ────────────────────────────────────────────────────
    async oninit(vnode) {
        const sessionConfigId = vnode.attrs.sessionConfigId;
        const sessionId = vnode.attrs.sessionId;
        if (sessionId) this.chatSessionId = sessionId;

        const contextJson = sessionStorage.getItem('magic8Context');
        if (contextJson) {
            try {
                this.chatContext = JSON.parse(contextJson);
                sessionStorage.removeItem('magic8Context');
            } catch (e) {
                console.error('Magic8App: Failed to parse chat context:', e);
            }
        }

        if (sessionConfigId) {
            await this._loadSessionConfig(sessionConfigId);
            this.phase = 'running';
        } else {
            this.phase = 'config';
        }

        this.fullscreenManager = new FullscreenManager();
        this.fullscreenManager.onFullscreenChange((isFS) => {
            this.isFullscreen = isFS;
            m.redraw();
        });
    },

    oncreate(vnode) {
        this.containerElement = vnode.dom;
        this._keyHandler = (e) => {
            if (e.key === 'Escape') this._handleExit();
            else if (e.key === ' ' && this.phase === 'running') {
                e.preventDefault();
                this._togglePause();
            }
        };
        document.addEventListener('keydown', this._keyHandler);
        if (this.phase === 'running' && this.sessionConfig) this._initSession();
    },

    onremove() {
        document.removeEventListener('keydown', this._keyHandler);
        this._cleanup();
    },

    // ── Configuration Management ────────────────────────────────────
    async _loadSessionConfig(configId) {
        const page = getPage();
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("objectId", configId);
            let qr = await page.search(q);
            if (qr?.results?.length) {
                am7model.updateListModel(qr.results);
                let obj = qr.results[0];
                this.sessionConfig = obj.dataBytesStore?.length
                    ? JSON.parse(uwm.base64Decode(obj.dataBytesStore))
                    : {};
            } else {
                this.sessionConfig = {};
            }
        } catch (err) {
            console.error('Magic8App: Failed to load session config:', err);
            this.sessionConfig = null;
        }
    },

    async _saveSessionConfig(config) {
        const page = getPage();
        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/Configs");
            if (!dir?.objectId) dir = await page.makePath("auth.group", "data", "~/Magic8/Configs");

            const configName = config.name || 'Magic8 Session';
            const encoded = uwm.base64Encode(JSON.stringify(config));

            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("name", configName);
            q.field("groupId", dir.id);
            let qr = await page.search(q);

            let saved;
            if (qr?.results?.length) {
                let existing = qr.results[0];
                existing.dataBytesStore = encoded;
                existing.compressionType = "none";
                saved = await page.patchObject(existing);
            } else {
                let obj = am7model.newPrimitive("data.data");
                obj.name = configName;
                obj.contentType = "application/json";
                obj.compressionType = "none";
                obj.groupId = dir.id;
                obj.groupPath = dir.path;
                obj.dataBytesStore = encoded;
                saved = await page.createObject(obj);
            }
            this.sessionConfig = config;
            return saved?.objectId || null;
        } catch (err) {
            console.error('Magic8App: Failed to save session config:', err);
            return null;
        }
    },

    // ── Session Initialization ──────────────────────────────────────
    async _initSession() {
        if (!this.sessionConfig) return;
        const cfg = this.sessionConfig;
        const page = getPage();

        // Fullscreen
        if (cfg.display?.fullscreen && this.containerElement) {
            try {
                await this.fullscreenManager.enterFullscreen(this.containerElement);
                this.isFullscreen = true;
            } catch (e) { console.warn('Magic8App: Fullscreen not available'); }
        }

        // Audio engine
        this.audioEngine = new AudioEngine();
        try {
            const ctx = this.audioEngine.getContext();
            if (ctx.state === 'suspended') ctx.resume();
        } catch (e) { /* best-effort */ }

        if (cfg.audio?.binauralEnabled) {
            if (cfg.audio.preset && AudioEngine.FREQUENCY_PRESETS?.[cfg.audio.preset]) {
                this.audioEngine.startBinauralFromPreset(cfg.audio.preset);
            } else if (cfg.audio.startBand) {
                this.audioEngine.startBinauralFromBands({
                    startBand: cfg.audio.startBand,
                    troughBand: cfg.audio.troughBand || cfg.audio.startBand,
                    endBand: cfg.audio.endBand || cfg.audio.startBand,
                    baseFreq: cfg.audio.baseFreq || 200,
                    sweepDurationMin: cfg.audio.sweepDurationMin || 5
                });
            } else {
                this.audioEngine.startBinaural(cfg.audio);
            }
            this.audioEnabled = true;
        }
        if (cfg.audio?.isochronicEnabled) this.audioEngine.startIsochronic(cfg.audio);

        // Biometric themer
        if (cfg.biometrics?.enabled) {
            this.biometricThemer = new BiometricThemer();
            if (cfg.biometrics.smoothingFactor) this.biometricThemer.setSmoothingFactor(cfg.biometrics.smoothingFactor);
            this._themeRedrawTimer = null;
            this.biometricThemer.onThemeChange = (theme) => {
                this.currentTheme = theme;
                if (!this._themeRedrawTimer) {
                    this._themeRedrawTimer = setTimeout(() => {
                        this._themeRedrawTimer = null;
                        m.redraw();
                    }, 200);
                }
            };
            this._startBiometricCapture();
        }

        // Image gallery
        this.imageGallery = new DynamicImageGallery({
            cycleInterval: cfg.images?.cycleInterval || 5000,
            crossfadeDuration: cfg.images?.crossfadeDuration || 1000,
            includeGenerated: cfg.images?.includeGenerated !== false,
            maxGeneratedImages: cfg.imageGeneration?.maxGeneratedImages || 20
        });

        const imageGroups = cfg.images?.baseGroups ? [...cfg.images.baseGroups] : [];
        if (cfg.imageGeneration?.enabled) {
            try {
                const sessionName = cfg.name || 'Session';
                const sessionGenDir = await page.findObject("auth.group", "DATA", "~/Magic8/Generated/" + sessionName);
                if (sessionGenDir?.objectId) imageGroups.push(sessionGenDir.objectId);
                const genDir = await page.findObject("auth.group", "DATA", "~/Magic8/Generated");
                if (genDir?.objectId && !imageGroups.includes(genDir.objectId)) imageGroups.push(genDir.objectId);
            } catch (e) { console.warn('Magic8App: Could not add generated images group:', e); }
        }

        if (imageGroups.length > 0) {
            await this.imageGallery.loadBaseImages(imageGroups);
        }

        this.imageGallery.onImageChange = (img) => {
            this.prevImage = this.currentImage;
            this.currentImage = img;
            this.imageOpacity = 0;
            this._imageAnimation = (img?.type === 'generated')
                ? this._pickImageAnimation(this.imageGallery._getDurationForImage?.(img))
                : null;
            m.redraw();
            requestAnimationFrame(() => {
                this.imageOpacity = 1;
                if (this._imageAnimation) {
                    requestAnimationFrame(() => {
                        if (this._imageAnimation) { this._imageAnimation._phase = 'to'; m.redraw(); }
                    });
                }
                m.redraw();
            });
        };

        const firstImg = this.imageGallery.getCurrent?.();
        if (firstImg) { this.currentImage = firstImg; this.imageOpacity = 1; }
        this.imageGallery.start();
        this._startBreathing();

        // Image generation
        if (cfg.imageGeneration?.enabled) {
            this.imageGenerator = new ImageGenerationManager(
                cfg.imageGeneration.sdConfigId,
                cfg.imageGeneration.sdConfigId ? null : cfg.imageGeneration.sdInline
            );
            this.imageGenerator.sessionName = cfg.name || 'Session';
            await this.imageGenerator.loadConfig();
            this.imageGenerator.onImageGenerated = (img) => {
                this.imageGallery.showImmediate(img);
                this._lastGeneratedObj = img;
            };
            this.imageGenerator.onCaptureCreated = (capture) => {
                this._lastCaptureObj = capture;
                m.redraw();
            };
            this._startImageGeneration();
        }

        // Text sequence
        if (cfg.text?.enabled && cfg.text?.sourceObjectId) {
            this.textSequence = new TextSequenceManager();
            this.textSequence.loop = cfg.text.loop !== false;
            this.textSequence.displayDuration = cfg.text.displayDuration || 5000;
            if (cfg.text.sourceType === 'note') {
                await this.textSequence.loadFromNote(cfg.text.sourceObjectId);
            } else {
                await this.textSequence.loadFromDataObject(cfg.text.sourceObjectId);
            }
            this.textSequence.onTextChange = (text) => { this.currentText = text; m.redraw(); };
            this.textSequence.start();
        }

        // LLM-only text mode
        if (!this.textSequence && cfg.director?.enabled) {
            this.textSequence = new TextSequenceManager();
            this.textSequence.loop = true;
            this.textSequence.displayDuration = cfg.text?.displayDuration || 8000;
            this.textSequence.onTextChange = (text) => { this.currentText = text; m.redraw(); };
        }

        // Voice sequence
        if (cfg.voice?.enabled && cfg.voice?.sourceObjectId) {
            this.voiceSequence = new VoiceSequenceManager(this.audioEngine);
            this.voiceSequence.loop = cfg.voice.loop !== false;
            let voiceLineCount = 0;
            if (cfg.voice.sourceType === 'note') {
                voiceLineCount = await this.voiceSequence.loadFromNote(cfg.voice.sourceObjectId);
            } else {
                voiceLineCount = await this.voiceSequence.loadFromDataObject(cfg.voice.sourceObjectId);
            }
            if (voiceLineCount > 0) {
                this.voiceSequence.onSynthesisProgress = (done, total) => {
                    if (cfg.director?.testMode && (done === total || done % 5 === 0))
                        this._debugLog('Voice synth: ' + done + '/' + total, 'voice');
                };
                await this.voiceSequence.synthesizeAll(cfg.voice.voiceProfileId);
                this.voiceSequence.start();
            }
        }
        if (!this.voiceSequence && cfg.voice?.enabled) {
            this.voiceSequence = new VoiceSequenceManager(this.audioEngine);
            this.voiceSequence.loop = cfg.voice?.loop !== false;
        }

        // Recording
        if (cfg.recording?.enabled) {
            this.sessionRecorder = new SessionRecorder();
            if (cfg.recording.autoStart) setTimeout(() => this._startRecording(), 1000);
        }

        // Session director
        this._sessionStartTime = Date.now();
        if (cfg.director?.enabled && cfg.director?.command) {
            if (!cfg.biometrics?.enabled) this._startBiometricCapture();

            this.sessionDirector = new SessionDirector();
            this.sessionDirector.stateProvider = () => this._gatherDirectorState();
            this.sessionDirector.onDirective = (d) => {
                this._applyDirective(d);
                if (cfg.director?.testMode) {
                    if (d.audio) this._debugLog('Audio: ' + JSON.stringify(d.audio), 'directive');
                    if (d.visuals) this._debugLog('Visual: ' + (d.visuals.effect || JSON.stringify(d.visuals)), 'directive');
                    if (d.labels?.add?.length) this._debugLog('+ Labels: ' + d.labels.add.join(', '), 'label');
                    if (d.labels?.remove?.length) this._debugLog('- Labels: ' + d.labels.remove.join(', '), 'label');
                    if (d.generateImage) this._debugLog('Image: ' + (d.generateImage.description || '').substring(0, 80), 'directive');
                    if (d.voiceLine) this._debugLog('Voice inject: "' + d.voiceLine + '"', 'voice');
                    if (d.opacity) this._debugLog('Opacity: ' + JSON.stringify(d.opacity), 'directive');
                    if (d.suggestMood) this._debugLog('Mood: ' + JSON.stringify(d.suggestMood), 'directive');
                    if (d.progressPct != null) this._debugLog('Progress: ' + d.progressPct + '%', 'info');
                    if (d.askUser) this._debugLog('askUser: "' + d.askUser.question + '"', 'pass');
                    if (d.commentary) this._debugLog('LLM: ' + d.commentary, 'info');
                    if (cfg.director?.interactive) {
                        this._debugLog('Interactive tick #' + this._ticksSinceAskUser + (d.askUser ? ' → askUser FIRED' : ' → no askUser'), d.askUser ? 'pass' : 'warn');
                    }
                }
            };
            this.sessionDirector.onError = (err) => {
                console.warn('SessionDirector error:', err);
                if (cfg.director?.testMode) this._debugLog('Error: ' + (err.message || err), 'error');
            };
            this.sessionDirector.onTickDebug = (info) => {
                if (!cfg.director?.testMode) return;
                const stateLines = info.stateMessage.split('\n');
                const interactiveLine = stateLines.find(l => l.startsWith('Interactive:'));
                const responseLine = stateLines.find(l => l.startsWith('User Response:'));
                if (interactiveLine) this._debugLog('State → ' + interactiveLine, 'info');
                if (responseLine) this._debugLog('State → ' + responseLine, 'info');
                if (info.rawContent) {
                    const raw = info.rawContent.length > 200 ? info.rawContent.substring(0, 200) + '...' : info.rawContent;
                    this._debugLog('Raw LLM [#' + info.tickNum + ']: ' + raw, 'info');
                }
                if (!info.directive) this._debugLog('Parse: FAILED (no valid directive)', 'fail');
                else {
                    const keys = Object.keys(info.directive).filter(k => k !== 'commentary');
                    this._debugLog('Parse OK: {' + keys.join(', ') + '}', 'pass');
                }
            };

            await this.sessionDirector.initialize(cfg.director.command, cfg.director.intervalMs || 60000, cfg.name, {
                imageTags: cfg.director.imageTags || ''
            });

            if (cfg.director.testMode) {
                this._debugLog('Starting behavioral diagnostics...', 'info');
                const self = this;
                let passCount = 0;
                const results = await this.sessionDirector.runDiagnostics(
                    cfg.director.command,
                    (directive, passIndex, passName) => {
                        passCount++;
                        self._debugLog('Applying directive [' + passName + ']', 'directive');
                        if (directive.labels?.add?.length) self._debugLog('+ Labels: ' + directive.labels.add.join(', '), 'label');
                        if (directive.generateImage) self._debugLog('Image: ' + (directive.generateImage.description || '').substring(0, 80), 'directive');
                        if (directive.commentary) self._debugLog('LLM: ' + directive.commentary, 'info');
                        self._applyDirective(directive);
                    },
                    (entry) => {
                        self._debugLog(
                            (entry.pass ? 'PASS' : 'FAIL') + ': ' + entry.name + (entry.detail ? ' - ' + entry.detail : ''),
                            entry.pass ? 'pass' : 'fail'
                        );
                    }
                );
                const passed = results.filter(r => r.pass).length;
                this._debugLog('Diagnostics complete: ' + passed + '/' + results.length + ' passed (' + passCount + ' directives applied)', passed === results.length ? 'pass' : 'fail');
            }

            this.sessionDirector.start();
        }

        m.redraw();
    },

    // ── Biometric Capture ───────────────────────────────────────────
    _startBiometricCapture() {
        const page = getPage();
        if (!page.components.camera) { console.warn('Magic8App: camera not available'); return; }

        const captureCallback = (imageData) => this._handleBiometricData(imageData);
        const captureIntervalSec = this.sessionConfig?.biometrics?.captureInterval;
        if (captureIntervalSec > 0) page.components.camera.setCaptureInterval(captureIntervalSec);

        const tryStart = () => {
            const devices = page.components.camera.devices();
            if (!devices.length) page.components.camera.initializeAndFindDevices(captureCallback);
            else page.components.camera.startCapture(captureCallback);
        };

        if (!document.querySelector('#facecam')) {
            requestAnimationFrame(() => { m.redraw.sync(); setTimeout(tryStart, 50); });
        } else tryStart();
    },

    _handleBiometricData(imageData) {
        if (!imageData?.results?.length) return;
        const data = imageData.results[0];
        this.biometricData = data;

        const emotion = data.dominant_emotion;
        if (emotion) {
            const last = this._emotionHistory.length > 0 ? this._emotionHistory[this._emotionHistory.length - 1] : null;
            if (!last || last.emotion !== emotion) {
                this._emotionHistory.push({ emotion, timestamp: Date.now() });
                if (this._emotionHistory.length > 10) this._emotionHistory = this._emotionHistory.slice(-10);
            }
        }
        if (data.gender_scores) this._genderMalePct = Math.round(data.gender_scores.Man || 0);
        if (this.biometricThemer) this.biometricThemer.updateFromBiometrics(data);
    },

    // ── Image Generation ────────────────────────────────────────────
    _startImageGeneration() {
        const page = getPage();
        const interval = Math.max(this.sessionConfig?.imageGeneration?.captureInterval || 120000, 60000);

        this._initialGenCheck = setInterval(async () => {
            if (page.components.camera && this.imageGenerator && this.biometricData) {
                clearInterval(this._initialGenCheck);
                this._initialGenCheck = null;
                await this.imageGenerator.captureAndGenerate(page.components.camera, this.biometricData);
            }
        }, 5000);

        this.imageGenInterval = setInterval(async () => {
            if (page.components.camera && this.imageGenerator && this.biometricData) {
                await this.imageGenerator.captureAndGenerate(page.components.camera, this.biometricData);
            }
        }, interval);
    },

    _pickImageAnimation(durationMs) {
        const dur = (durationMs || 5000) / 1000;
        const animations = [
            { from: 'scale(1)', to: 'scale(1.15)' },
            { from: 'scale(1.15)', to: 'scale(1)' },
            { from: 'scale(1.08) translateX(-3%)', to: 'scale(1.08) translateX(3%)' },
            { from: 'scale(1.08) translateX(3%)', to: 'scale(1.08) translateX(-3%)' },
            { from: 'scale(1.1) translateY(3%)', to: 'scale(1.1) translateY(-3%)' },
            { from: 'scale(1.1) translateY(-3%)', to: 'scale(1.1) translateY(3%)' },
            { from: 'scale(1.1) translate(-2%, -2%)', to: 'scale(1.1) translate(2%, 2%)' },
            { from: 'scale(1.1) translate(2%, -2%)', to: 'scale(1.1) translate(-2%, 2%)' }
        ];
        const pick = animations[Math.floor(Math.random() * animations.length)];
        return {
            from: { transform: pick.from, transition: 'none' },
            to: { transform: pick.to, transition: 'transform ' + dur + 's ease-in-out' }
        };
    },

    // ── Ambient Breathing ───────────────────────────────────────────
    _startBreathing() {
        this.ambientOpacity = 0.15;
        this._breatheStep(true);
    },

    _breatheStep(goingUp) {
        if (this.phase !== 'running' && this.phase !== 'paused') return;
        const halfCycleMs = (120 + Math.random() * 120) * 1000;
        this._breatheTransitionMs = halfCycleMs;
        this.ambientOpacity = goingUp ? (0.5 + Math.random() * 0.35) : 0.1;
        m.redraw();
        this._breatheTimeout = setTimeout(() => this._breatheStep(!goingUp), halfCycleMs);
    },

    _stopBreathing() {
        if (this._breatheTimeout) { clearTimeout(this._breatheTimeout); this._breatheTimeout = null; }
    },

    // ── Recording ───────────────────────────────────────────────────
    async _startRecording() {
        if (!this.sessionRecorder) return;
        const container = this.containerElement;
        if (!container) return;

        const w = container.clientWidth, h = container.clientHeight;
        const compCanvas = document.createElement('canvas');
        compCanvas.width = w; compCanvas.height = h;
        this._recCanvas = compCanvas;
        this._recCtx = compCanvas.getContext('2d');
        this._recImage = new Image(); this._recImage.crossOrigin = 'anonymous';
        this._recImageUrl = '';
        this._recPrevImage = new Image(); this._recPrevImage.crossOrigin = 'anonymous';
        if (this.currentImage?.url) { this._recImage.src = this.currentImage.url; this._recImageUrl = this.currentImage.url; }

        this._recCompositing = true;
        this._compositeFrame();

        const audioDestination = this.audioEngine?.getDestination();
        try {
            await this.sessionRecorder.startRecording(compCanvas, audioDestination, {
                maxDurationMin: this.sessionConfig?.recording?.maxDurationMin || 30
            });
            this.isRecording = true;
            this._debugLog('Recording started (composite)', 'info');
            m.redraw();
        } catch (err) {
            this._recCompositing = false;
            console.error('Magic8App: Failed to start recording:', err);
        }
    },

    _compositeFrame() {
        if (!this._recCompositing) return;
        const ctx = this._recCtx, w = this._recCanvas.width, h = this._recCanvas.height;

        const bg = this.currentTheme?.bg || [20, 20, 30];
        ctx.fillStyle = `rgb(${bg[0]},${bg[1]},${bg[2]})`;
        ctx.fillRect(0, 0, w, h);

        const imgUrl = this.currentImage?.url || '';
        if (imgUrl && imgUrl !== this._recImageUrl) {
            this._recPrevImage.src = this._recImageUrl;
            this._recImage.src = imgUrl;
            this._recImageUrl = imgUrl;
        }

        const imgAlpha = (this.ambientOpacity || 1) * (this._layerOpacity?.images || 1);
        if (this._recPrevImage.complete && this._recPrevImage.naturalWidth > 0 && this.imageOpacity < 1) {
            ctx.globalAlpha = imgAlpha;
            this._drawCover(ctx, this._recPrevImage, w, h);
        }
        if (this._recImage.complete && this._recImage.naturalWidth > 0) {
            ctx.globalAlpha = imgAlpha * (this.imageOpacity != null ? this.imageOpacity : 1);
            this._drawCover(ctx, this._recImage, w, h);
        }
        ctx.globalAlpha = 1;

        const hypnoCanvas = this.containerElement?.querySelector('.hypno-canvas');
        if (hypnoCanvas?.width > 0) {
            ctx.globalAlpha = (this.ambientOpacity != null ? Math.min(1, this.ambientOpacity + 0.4) : 0.7) * (this._layerOpacity?.visuals || 1);
            ctx.drawImage(hypnoCanvas, 0, 0, w, h);
            ctx.globalAlpha = 1;
        }

        const vizCanvas = this.containerElement?.querySelector('.audio-visualizer-overlay canvas');
        if (vizCanvas?.width > 0) {
            ctx.globalAlpha = (this.sessionConfig?.audio?.visualizerOpacity || 0.35) * (this._layerOpacity?.visualizer || 1);
            ctx.globalCompositeOperation = 'screen';
            ctx.drawImage(vizCanvas, 0, 0, w, h);
            ctx.globalCompositeOperation = 'source-over';
            ctx.globalAlpha = 1;
        }

        ctx.fillStyle = 'rgba(0, 0, 0, 0.3)';
        ctx.fillRect(0, 0, w, h);

        const labelEls = this.containerElement?.querySelectorAll('.bio-label');
        if (labelEls?.length > 0) {
            const cRect = this.containerElement.getBoundingClientRect();
            const layerAlpha = this._layerOpacity?.labels || 1;
            labelEls.forEach(el => {
                const rect = el.getBoundingClientRect();
                const x = rect.left - cRect.left, y = rect.top - cRect.top;
                const style = getComputedStyle(el);
                const opacity = (parseFloat(style.opacity) || 0) * layerAlpha;
                if (opacity < 0.01) return;
                ctx.globalAlpha = opacity;
                ctx.fillStyle = style.color || 'rgba(255,255,255,0.7)';
                ctx.font = style.fontSize + ' ' + (style.fontFamily || 'Georgia, serif');
                const glow = this.currentTheme?.glow;
                if (glow) { ctx.shadowColor = `rgba(${glow[0]},${glow[1]},${glow[2]},0.5)`; ctx.shadowBlur = 16; }
                ctx.fillText(el.textContent, x, y + rect.height * 0.75);
                ctx.shadowBlur = 0;
            });
            ctx.globalAlpha = 1;
        }

        requestAnimationFrame(() => this._compositeFrame());
    },

    _drawCover(ctx, img, cw, ch) {
        const imgRatio = img.naturalWidth / img.naturalHeight;
        const canvasRatio = cw / ch;
        let sx, sy, sw, sh;
        if (imgRatio > canvasRatio) { sh = img.naturalHeight; sw = sh * canvasRatio; sx = (img.naturalWidth - sw) / 2; sy = 0; }
        else { sw = img.naturalWidth; sh = sw / canvasRatio; sx = 0; sy = (img.naturalHeight - sh) / 2; }
        ctx.drawImage(img, sx, sy, sw, sh, 0, 0, cw, ch);
    },

    _stopCompositing() {
        this._recCompositing = false;
        this._recCanvas = null; this._recCtx = null;
        this._recImage = null; this._recPrevImage = null;
        this._recImageUrl = '';
    },

    async _stopRecording() {
        const recorder = this.sessionRecorder;
        if (!recorder || !this.isRecording) return;
        this._stopCompositing();
        try {
            const blob = await recorder.stopRecording();
            this.isRecording = false;
            if (blob?.size > 0) {
                const sizeMB = (blob.size / (1024 * 1024)).toFixed(1);
                const page = getPage();
                if (page.toast) page.toast('info', `Saving recording (${sizeMB} MB)...`);
                await recorder.saveToServer(blob, this.sessionConfig?.name);
                if (page.toast) page.toast('info', `Recording saved (${sizeMB} MB)`);
            }
            m.redraw();
        } catch (err) {
            console.error('Magic8App: Failed to stop recording:', err);
            this.isRecording = false;
        }
    },

    async _stopRecordingAndDispose(recorder) {
        this._stopCompositing();
        try {
            const blob = await recorder.stopRecording();
            this.isRecording = false;
            if (blob?.size > 0) await recorder.saveToServer(blob, this.sessionConfig?.name);
        } catch (err) {
            console.warn('Magic8App: Could not save recording on exit:', err);
            this.isRecording = false;
        } finally {
            recorder.dispose();
        }
    },

    _toggleRecording() { this.isRecording ? this._stopRecording() : this._startRecording(); },

    // ── Controls ────────────────────────────────────────────────────
    _togglePause() {
        if (this.phase === 'running') {
            this.phase = 'paused';
            this.textSequence?.pause(); this.voiceSequence?.pause();
            this.imageGallery?.stop(); this.audioEngine?.stopIsochronic();
            this._stopBreathing(); this.sessionDirector?.pause();
        } else if (this.phase === 'paused') {
            this.phase = 'running';
            this.textSequence?.resume(); this.voiceSequence?.resume();
            this.imageGallery?.start();
            if (this.sessionConfig?.audio?.isochronicEnabled && this.audioEnabled) this.audioEngine?.startIsochronic(this.sessionConfig.audio);
            this._startBreathing(); this.sessionDirector?.resume();
        }
        m.redraw();
    },

    _toggleAudio() {
        this.audioEnabled = !this.audioEnabled;
        const cfg = this.sessionConfig;
        if (this.audioEnabled) {
            this.audioEngine?.setVolume(1.0);
            if (cfg?.audio?.binauralEnabled) {
                if (cfg.audio.preset && AudioEngine.FREQUENCY_PRESETS?.[cfg.audio.preset]) {
                    this.audioEngine?.startBinauralFromPreset(cfg.audio.preset);
                } else if (cfg.audio.startBand) {
                    this.audioEngine?.startBinauralFromBands({
                        startBand: cfg.audio.startBand, troughBand: cfg.audio.troughBand || cfg.audio.startBand,
                        endBand: cfg.audio.endBand || cfg.audio.startBand, baseFreq: cfg.audio.baseFreq || 200,
                        sweepDurationMin: cfg.audio.sweepDurationMin || 5
                    });
                } else this.audioEngine?.startBinaural(cfg.audio);
            }
            if (cfg?.audio?.isochronicEnabled) this.audioEngine?.startIsochronic(cfg.audio);
            this.voiceSequence?.resume();
        } else {
            this.audioEngine?.stopBinaural(); this.audioEngine?.stopIsochronic();
            this.voiceSequence?.pause(); this.audioEngine?.setVolume(0);
        }
        m.redraw();
    },

    _toggleFullscreen() {
        if (this.containerElement) this.fullscreenManager.toggleFullscreen(this.containerElement);
    },

    // ── Exit + Cleanup ──────────────────────────────────────────────
    async _handleExit() {
        await this._finalizeRecording();
        try { this._cleanup(); } catch (err) { console.error('Magic8App: Cleanup error:', err); }

        if (this.chatContext) {
            sessionStorage.setItem('magic8Return', JSON.stringify({ sessionId: this.sessionConfig?.name, timestamp: Date.now() }));
            window.history.back();
        } else {
            if (m.route?.set) m.route.set('/main');
            else window.history.back();
        }
    },

    async _finalizeRecording() {
        if (!this.isRecording || !this.sessionRecorder) return;
        this._stopCompositing();
        const recorder = this.sessionRecorder;
        this.sessionRecorder = null;
        this.isRecording = false;
        try {
            const blob = await recorder.stopRecording();
            if (blob?.size > 0) {
                await recorder.saveToServer(blob, this.sessionConfig?.name);
                const page = getPage();
                if (page.toast) page.toast('info', `Recording saved (${(blob.size / (1024 * 1024)).toFixed(1)} MB)`);
            }
        } catch (err) {
            console.warn('Magic8App: Could not save recording on exit:', err);
        } finally {
            recorder.dispose();
        }
    },

    _cleanup() {
        if (this._cleaned) return;
        this._cleaned = true;
        const page = getPage();

        clearInterval(this.biometricInterval); clearInterval(this.imageCycleInterval); clearInterval(this.imageGenInterval);
        if (this._initialGenCheck) clearInterval(this._initialGenCheck);
        this._stopBreathing(); this._stopCompositing();
        if (this._opacityRafId) { cancelAnimationFrame(this._opacityRafId); this._opacityRafId = null; }

        if (this.isRecording && this.sessionRecorder) {
            const recorder = this.sessionRecorder; this.sessionRecorder = null;
            this._stopRecordingAndDispose(recorder);
        } else if (this.sessionRecorder) { this.sessionRecorder.dispose(); this.sessionRecorder = null; }

        if (page.components.camera) page.components.camera.stopCapture();
        this.audioEngine?.dispose(); this.textSequence?.dispose();
        this.voiceSequence?.dispose(); this.imageGallery?.dispose();
        this.imageGenerator?.dispose();

        if (this._sttMediaRecorder) { try { this._sttMediaRecorder.stop(); } catch (e) { /* ignore */ } this._sttMediaRecorder = null; }
        if (this._sttMediaStream) { this._sttMediaStream.getTracks().forEach(t => t.stop()); this._sttMediaStream = null; }
        page.audioStream = undefined;
        if (this._responseTimeoutId) { clearTimeout(this._responseTimeoutId); this._responseTimeoutId = null; }
        if (this._interactionIdleTimer) { clearInterval(this._interactionIdleTimer); this._interactionIdleTimer = null; }
        this._pendingQuestion = null; this._voicePausedForInteraction = false;

        this.sessionDirector?.dispose();
        this.sessionLabels = []; this.sessionVoiceLines = [];
        if (this._themeRedrawTimer) { clearTimeout(this._themeRedrawTimer); this._themeRedrawTimer = null; }
        this.biometricThemer?.dispose();
        this.fullscreenManager?.exitFullscreen();
        this.fullscreenManager?.dispose();
    },

    // ── View ────────────────────────────────────────────────────────
    view(vnode) {
        const page = getPage();

        // Config phase
        if (this.phase === 'config') {
            return m('.magic8-wrapper', [
                m(SessionConfigEditor, {
                    config: this.sessionConfig,
                    onSave: async (config) => {
                        this.sessionConfig = config;
                        this.phase = 'running';
                        m.redraw();
                        this._saveSessionConfig(config);
                        setTimeout(() => this._initSession(), 100);
                    },
                    onCancel: () => this._handleExit()
                }),
                page.components.dialog?.loadDialog?.()
            ]);
        }

        // Running / Paused
        const bgColor = this.currentTheme?.bg ? `rgb(${this.currentTheme.bg.join(',')})` : 'rgb(20, 20, 30)';

        return m('.magic8-app.fixed.inset-0.overflow-hidden', {
            style: { background: bgColor, transition: 'background 1s ease' }
        }, [
            // Background image container
            m('.magic8-bg-container.absolute.inset-0.overflow-hidden', {
                style: {
                    opacity: this.ambientOpacity * this._layerOpacity.images,
                    transition: `opacity ${this._breatheTransitionMs}ms ease-in-out`,
                    zIndex: 1
                }
            }, [
                this.prevImage && m('.magic8-bg-prev.absolute.inset-0', {
                    style: { backgroundImage: `url("${this.prevImage.url}")`, backgroundSize: 'cover', backgroundPosition: 'center' }
                }),
                this.currentImage && (() => {
                    const anim = this._imageAnimation;
                    const phase = anim ? (anim._phase === 'to' ? anim.to : anim.from) : null;
                    const crossfade = this.sessionConfig?.images?.crossfadeDuration || 1000;
                    const transitions = ['opacity ' + crossfade + 'ms ease-in-out'];
                    if (phase?.transition && phase.transition !== 'none') transitions.push(phase.transition);
                    return m('.magic8-bg-current.absolute.inset-0', {
                        style: {
                            backgroundImage: `url("${this.currentImage.url}")`, backgroundSize: 'cover', backgroundPosition: 'center',
                            opacity: this.imageOpacity, transform: phase?.transform || '', transition: transitions.join(', ')
                        }
                    });
                })()
            ]),

            // Visual effects canvas
            m('.absolute.inset-0.pointer-events-none', {
                style: {
                    opacity: (this.ambientOpacity != null ? Math.min(1, this.ambientOpacity + 0.4) : 0.7) * this._layerOpacity.visuals,
                    transition: `opacity ${this._breatheTransitionMs || 2000}ms ease-in-out`,
                    zIndex: 5
                }
            }, m(HypnoCanvas, { theme: this.currentTheme, visuals: this.sessionConfig?.visuals, class: 'absolute inset-0' })),

            // Audio visualizer
            this.sessionConfig?.audio?.visualizerEnabled && this.audioEngine &&
                m(AudioVisualizerOverlay, {
                    audioEngine: this.audioEngine,
                    opacity: (this.sessionConfig.audio.visualizerOpacity || 0.35) * this._layerOpacity.visualizer,
                    gradient: this.sessionConfig.audio.visualizerGradient || 'prism',
                    mode: this.sessionConfig.audio.visualizerMode || 2
                }),

            // Dark overlay
            m('.absolute.inset-0.bg-black', { style: { opacity: 0.3, zIndex: 10 } }),

            // Gender confidence bar
            this.moodRingEnabled && m('.absolute.inset-x-0.pointer-events-none', {
                style: {
                    height: '33vh', top: '50%', transform: 'translateY(-50%)', opacity: 0.25, zIndex: 11,
                    background: `linear-gradient(to right, rgba(255,182,193,${(100 - this._genderMalePct) / 100}), rgba(135,206,235,${this._genderMalePct / 100}))`,
                    transition: 'background 2s ease-in-out'
                }
            }),

            // Biometric text overlay
            m(BiometricOverlay, { text: this.currentText, theme: this.currentTheme, opacity: this._layerOpacity.labels }),

            // Paused overlay
            this.phase === 'paused' && m('.absolute.inset-0.flex.items-center.justify-center.bg-black\\/50.cursor-pointer', {
                style: { zIndex: 40 }, onclick: () => this._togglePause()
            }, m('.text-center.text-white', [m('.text-4xl.mb-4', 'Paused'), m('.text-lg.text-gray-400', 'Tap or press SPACE to resume')])),

            // Recording indicator
            this.isRecording && m(RecordingIndicator, { recorder: this.sessionRecorder }),

            // Interactive overlay
            this._pendingQuestion && m('.fixed.inset-x-0.bottom-20.flex.justify-center.px-4', { style: { zIndex: 200 } }, [
                m('.bg-gray-900\\/95.backdrop-blur.rounded-2xl.p-5.max-w-lg.w-full.shadow-2xl.border.border-cyan-500\\/30', [
                    m('.text-cyan-300.text-lg.font-medium.mb-4.text-center', this._pendingQuestion.question),
                    m('.flex.items-center.justify-center.gap-2.mb-3.text-sm.text-gray-400', [
                        m('span.material-symbols-outlined.text-cyan-400', 'chat'), 'Type your response below'
                    ]),
                    m('.flex.gap-2', [
                        m('input.flex-1.bg-gray-800.text-white.rounded-lg.px-4.py-3.border.border-gray-600.outline-none', {
                            placeholder: 'Type your response...', value: this._userInputText,
                            oninput: (e) => { this._userInputText = e.target.value; this._lastTypingTime = Date.now(); },
                            onkeydown: (e) => { if (e.key === 'Enter') this._submitUserResponse(); }
                        }),
                        m('button.bg-cyan-600.text-white.px-5.py-3.rounded-lg.font-medium', {
                            onclick: () => this._submitUserResponse()
                        }, 'Send')
                    ])
                ])
            ]),

            // Control panel
            m(ControlPanel, {
                config: this.sessionConfig,
                state: {
                    isRecording: this.isRecording, audioEnabled: this.audioEnabled,
                    isFullscreen: this.isFullscreen, moodRingEnabled: this.moodRingEnabled,
                    testMode: this.sessionConfig?.director?.testMode || false,
                    emotion: this.biometricData?.dominant_emotion || 'neutral',
                    gender: this.biometricData?.dominant_gender || null,
                    suggestedEmotion: this._suggestedEmotion, suggestedGender: this._suggestedGender,
                    moodColor: this.moodRingColor
                },
                autoHideDelay: this.sessionConfig?.display?.controlsAutoHide || 5000,
                onToggleRecording: () => this._toggleRecording(),
                onToggleAudio: () => this._toggleAudio(),
                onToggleFullscreen: () => this._toggleFullscreen(),
                onToggleMoodRing: () => { this.moodRingEnabled = !this.moodRingEnabled; if (!this.moodRingEnabled) this.moodRingColor = null; },
                onToggleTestMode: () => {
                    if (!this.sessionConfig) return;
                    if (!this.sessionConfig.director) this.sessionConfig.director = {};
                    this.sessionConfig.director.testMode = !this.sessionConfig.director.testMode;
                    if (this.sessionConfig.director.testMode) this._debugLog('Test mode enabled via control panel', 'info');
                },
                onOpenConfig: () => { this.phase = 'config'; m.redraw(); },
                onExit: () => this._handleExit()
            }),

            // Hidden camera
            page.components.camera && (this.sessionConfig?.biometrics?.enabled || this.sessionConfig?.director?.enabled || this.sessionConfig?.imageGeneration?.enabled)
                ? page.components.camera.videoView() : null,

            // Test mode emoji display
            this.sessionConfig?.director?.testMode && this.biometricData &&
                m(MoodEmojiDisplay, {
                    emotion: this.biometricData?.dominant_emotion || 'neutral',
                    gender: this.biometricData?.dominant_gender || null,
                    suggestedEmotion: this._suggestedEmotion, suggestedGender: this._suggestedGender
                }),

            // Debug console
            this.sessionConfig?.director?.testMode && this.debugMessages.length > 0 &&
                m('.magic8-debug-console', {
                    style: {
                        position: 'fixed', bottom: '10px', right: '10px', width: '440px',
                        maxHeight: this._debugMinimized ? '32px' : '40vh',
                        backgroundColor: 'rgba(0, 0, 0, 0.88)', borderRadius: '6px',
                        border: '1px solid rgba(100, 255, 100, 0.2)', zIndex: 200,
                        fontFamily: "'Consolas', 'Monaco', monospace", fontSize: '11px',
                        overflow: 'hidden', display: 'flex', flexDirection: 'column', pointerEvents: 'auto'
                    }
                }, [
                    m('.debug-header', {
                        style: {
                            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                            padding: '4px 10px', borderBottom: this._debugMinimized ? 'none' : '1px solid rgba(100, 255, 100, 0.15)',
                            cursor: 'pointer', flexShrink: 0, userSelect: 'none'
                        },
                        onclick: () => { this._debugMinimized = !this._debugMinimized; }
                    }, [
                        m('span', { style: { color: '#6f6', fontWeight: 'bold' } }, 'DEBUG'),
                        m('span', { style: { color: '#666', fontSize: '10px' } }, this.debugMessages.length + ' msgs'),
                        m('span', { style: { color: '#888', fontSize: '14px' } }, this._debugMinimized ? '+' : '\u2212')
                    ]),
                    !this._debugMinimized && m('.debug-messages', {
                        style: { overflowY: 'auto', padding: '4px 8px', flex: 1 },
                        oncreate: (vn) => { vn.dom.scrollTop = vn.dom.scrollHeight; },
                        onupdate: (vn) => { vn.dom.scrollTop = vn.dom.scrollHeight; }
                    }, this.debugMessages.map((msg, i) => {
                        const colors = { info: '#aaa', directive: '#6bf', label: '#fb4', voice: '#d8f', pass: '#6f6', fail: '#f66', error: '#f66' };
                        const t = new Date(msg.time);
                        const ts = String(t.getHours()).padStart(2, '0') + ':' + String(t.getMinutes()).padStart(2, '0') + ':' + String(t.getSeconds()).padStart(2, '0');
                        return m('.debug-msg', { key: i, style: { color: colors[msg.type] || '#aaa', padding: '1px 0', lineHeight: '1.4', wordBreak: 'break-word' } },
                            '[' + ts + '] ' + msg.text);
                    }))
                ]),

            // Test mode thumbnails
            this.sessionConfig?.director?.testMode && (this._lastCaptureObj || this._lastGeneratedObj) &&
                m('.magic8-test-thumbnails', {
                    style: { position: 'fixed', top: '60px', right: '10px', zIndex: 190, display: 'flex', flexDirection: 'column', gap: '8px', pointerEvents: 'none' }
                }, [
                    this._lastCaptureObj && m('.test-thumb', {
                        style: { width: '160px', borderRadius: '8px', border: '2px solid rgba(100, 200, 255, 0.5)', overflow: 'hidden', backgroundColor: 'rgba(0,0,0,0.6)' }
                    }, [
                        m('img', {
                            src: am7client.base() + '/thumbnail/' + am7client.dotPath(am7client.currentOrganization) +
                                '/data.data' + this._lastCaptureObj.groupPath + '/' + this._lastCaptureObj.name + '/160x160',
                            style: { width: '100%', height: '160px', objectFit: 'cover', display: 'block' },
                            onerror: function() { this.style.display = 'none'; }
                        }),
                        m('div', { style: { fontSize: '10px', textAlign: 'center', color: 'rgba(100,200,255,0.8)', padding: '2px 0', background: 'rgba(0,0,0,0.5)' } }, 'Capture')
                    ]),
                    this._lastGeneratedObj && m('.test-thumb', {
                        style: { width: '160px', borderRadius: '8px', border: '2px solid rgba(100, 255, 100, 0.5)', overflow: 'hidden', backgroundColor: 'rgba(0,0,0,0.6)' }
                    }, [
                        m('img', {
                            src: am7client.base() + '/thumbnail/' + am7client.dotPath(am7client.currentOrganization) +
                                '/data.data' + (this._lastGeneratedObj.groupPath || '') + '/' + (this._lastGeneratedObj.name || '') + '/160x160',
                            style: { width: '100%', height: '160px', objectFit: 'cover', display: 'block' },
                            onerror: function() { this.style.display = 'none'; }
                        }),
                        m('div', { style: { fontSize: '10px', textAlign: 'center', color: 'rgba(100,255,100,0.8)', padding: '2px 0', background: 'rgba(0,0,0,0.5)' } }, 'Generated')
                    ])
                ]),

            // Dialog
            page.components.dialog?.loadDialog?.()
        ]);
    },

    // ── Director State ──────────────────────────────────────────────
    _gatherDirectorState() {
        const bio = this.biometricData;
        const audio = this.sessionConfig?.audio;
        const visuals = this.sessionConfig?.visuals;

        let recentVoiceLines = [];
        if (this.voiceSequence) {
            const seqs = this.voiceSequence.sequences;
            const idx = this.voiceSequence.currentIndex;
            if (seqs?.length > 0 && idx > 0) recentVoiceLines = seqs.slice(Math.max(0, idx - 3), idx);
        }

        let emotionTransition = null;
        if (this._emotionHistory.length >= 2) {
            const now = Date.now();
            const current = this._emotionHistory[this._emotionHistory.length - 1];
            const previous = this._emotionHistory[this._emotionHistory.length - 2];
            const secsSinceChange = (now - current.timestamp) / 1000;
            if (secsSinceChange <= 30) emotionTransition = { from: previous.emotion, to: current.emotion, secsAgo: Math.round(secsSinceChange) };
        }

        const state = {
            biometric: bio ? { emotion: bio.dominant_emotion, age: bio.age, gender: bio.dominant_gender } : null,
            emotionTransition,
            labels: [...this.sessionLabels],
            recentVoiceLines,
            currentText: this.currentText,
            audio: audio ? {
                sweepStart: audio.sweepStart, sweepTrough: audio.sweepTrough, sweepEnd: audio.sweepEnd,
                baseFreq: audio.baseFreq, bandLabel: this.audioEngine?.currentBandLabel || null,
                isochronicEnabled: audio.isochronicEnabled, isochronicRate: audio.isochronicRate
            } : null,
            visuals: visuals ? { effects: visuals.effects, mode: visuals.mode } : null,
            sdConfig: this.imageGenerator?.sdConfig ? {
                style: this.imageGenerator.sdConfig.style, description: this.imageGenerator.sdConfig.description,
                imageAction: this.imageGenerator.sdConfig.imageAction,
                denoisingStrength: this.imageGenerator.sdConfig.denoisingStrength, cfg: this.imageGenerator.sdConfig.cfg
            } : null,
            layerOpacity: {
                labels: Math.round(this._layerOpacity.labels * 100) / 100,
                images: Math.round(this._layerOpacity.images * 100) / 100,
                visualizer: Math.round(this._layerOpacity.visualizer * 100) / 100,
                visuals: Math.round(this._layerOpacity.visuals * 100) / 100
            },
            injectedVoiceLines: this.sessionVoiceLines.length > 0 ? this.sessionVoiceLines.slice(-3) : [],
            moodRingEnabled: this.moodRingEnabled,
            userResponse: this._userResponse || null,
            interactiveEnabled: !!(this.sessionConfig?.director?.interactive),
            ticksSinceAskUser: this._ticksSinceAskUser,
            elapsedMinutes: this._sessionStartTime ? (Date.now() - this._sessionStartTime) / 60000 : 0
        };

        this._userResponse = null;
        return state;
    },

    // ── Tag Image Search ────────────────────────────────────────────
    async _findImagesByTags(tags) {
        if (!tags?.length) return null;
        const page = getPage();
        try {
            const tagObjects = [];
            for (const tagName of tags) {
                const tag = await page.getTag(tagName.trim(), 'data.data');
                if (!tag) return null;
                tagObjects.push(tag);
            }
            const firstMembers = await am7client.members('data.tag', tagObjects[0].objectId, 'data.data', 0, 100);
            if (!firstMembers?.length) return null;

            let candidateIds = new Set(firstMembers.map(m => m.objectId));
            for (let i = 1; i < tagObjects.length; i++) {
                const members = await am7client.members('data.tag', tagObjects[i].objectId, 'data.data', 0, 100);
                if (!members?.length) return null;
                const memberIds = new Set(members.map(m => m.objectId));
                for (const id of candidateIds) { if (!memberIds.has(id)) candidateIds.delete(id); }
                if (candidateIds.size === 0) return null;
            }

            const ids = Array.from(candidateIds);
            const pickedId = ids[Math.floor(Math.random() * ids.length)];
            const q = am7view.viewQuery(am7model.newInstance('data.data'));
            q.field('objectId', pickedId);
            const qr = await page.search(q);
            return qr?.results?.length ? qr.results[0] : null;
        } catch (err) {
            console.warn('Magic8App: Tag search failed:', err);
            return null;
        }
    },

    _generateImageFromCamera(directive) {
        const page = getPage();
        const sdKeys = ['description', 'style', 'imageAction', 'denoisingStrength', 'cfg'];
        const origValues = {};
        if (this.imageGenerator.sdConfig) {
            for (const key of sdKeys) {
                origValues[key] = this.imageGenerator.sdConfig[key];
                if (directive.generateImage[key] != null) this.imageGenerator.sdConfig[key] = directive.generateImage[key];
            }
        }
        this.imageGenerator.captureAndGenerate(page.components.camera, this.biometricData || null).then(() => {
            if (this.imageGenerator.sdConfig) {
                for (const key of sdKeys) { if (origValues[key] !== undefined) this.imageGenerator.sdConfig[key] = origValues[key]; }
            }
        });
    },

    // ── Apply Directive ─────────────────────────────────────────────
    _applyDirective(directive) {
        if (!directive) return;
        const cfg = this.sessionConfig;
        const page = getPage();

        // Enable audio if director says so
        if (directive.audio && cfg?.audio && this.audioEngine && !this.audioEnabled) {
            this.audioEnabled = true; this.audioEngine.setVolume(1.0); m.redraw();
        }
        if (directive.audio?.volume != null && this.audioEngine) {
            this.audioEngine.setVolume(Math.max(0, Math.min(1, directive.audio.volume)));
        }

        // Audio changes
        if (directive.audio && cfg?.audio && this.audioEngine && this.audioEnabled) {
            let audioHandled = false;
            if (directive.audio.preset) {
                this.audioEngine.stopBinaural(); this.audioEngine.startBinauralFromPreset(directive.audio.preset); audioHandled = true;
            } else if (directive.audio.band) {
                this.audioEngine.stopBinaural();
                this.audioEngine.startBinauralFromBands({
                    startBand: directive.audio.band, troughBand: directive.audio.troughBand || directive.audio.band,
                    endBand: directive.audio.endBand || directive.audio.band,
                    secondTransition: directive.audio.secondTransition, thirdTransition: directive.audio.thirdTransition,
                    baseFreq: directive.audio.baseFreq || cfg.audio.baseFreq || 200,
                    sweepDurationMin: cfg.audio.sweepDurationMin || 5
                });
                audioHandled = true;
            }
            if (!audioHandled) {
                let audioChanged = false;
                for (const key of ['sweepStart', 'sweepTrough', 'sweepEnd']) {
                    if (directive.audio[key] != null && directive.audio[key] !== cfg.audio[key]) { cfg.audio[key] = directive.audio[key]; audioChanged = true; }
                }
                if (audioChanged) { this.audioEngine.stopBinaural(); this.audioEngine.startBinaural(cfg.audio); }
            }
            if (directive.audio.isochronicEnabled != null && directive.audio.isochronicEnabled !== cfg.audio.isochronicEnabled) {
                cfg.audio.isochronicEnabled = directive.audio.isochronicEnabled;
                cfg.audio.isochronicEnabled ? this.audioEngine.startIsochronic(cfg.audio) : this.audioEngine.stopIsochronic();
            }
            if (directive.audio.isochronicBand) {
                const bands = AudioEngine.BRAINWAVE_BANDS;
                const band = bands?.[directive.audio.isochronicBand];
                if (band) directive.audio.isochronicRate = band.mid;
            }
            if (directive.audio.isochronicRate != null) {
                cfg.audio.isochronicRate = directive.audio.isochronicRate;
                if (cfg.audio.isochronicEnabled) { this.audioEngine.stopIsochronic(); this.audioEngine.startIsochronic(cfg.audio); }
            }
        }

        // Visual changes
        if (directive.visuals && cfg?.visuals) {
            if (directive.visuals.effect) {
                this.sessionConfig.visuals = { ...cfg.visuals, effects: [directive.visuals.effect], mode: 'single' };
                if (directive.visuals.transitionDuration != null) this.sessionConfig.visuals.transitionDuration = directive.visuals.transitionDuration;
            }
        }

        // Label changes
        if (directive.labels) {
            if (directive.labels.add?.length > 0) {
                for (const label of directive.labels.add) {
                    if (!this.sessionLabels.includes(label)) this.sessionLabels.push(label);
                    if (this.textSequence) this.textSequence.injectText(label, this.textSequence.currentIndex);
                }
                if (this.sessionLabels.length > 10) this.sessionLabels = this.sessionLabels.slice(-10);
                if (this.textSequence && !this.textSequence.isPlaying && this.textSequence.sequences.length > 0) this.textSequence.start();
            }
            if (directive.labels.remove?.length > 0) {
                this.sessionLabels = this.sessionLabels.filter(l => !directive.labels.remove.includes(l));
            }
        }

        // Image generation
        if (directive.generateImage && this.imageGenerator && page.components.camera) {
            const tags = directive.generateImage.tags;
            let tagImageHandled = false;
            if (tags?.length > 0) {
                this._findImagesByTags(tags).then(found => {
                    if (found) {
                        const url = am7client.base() + '/media/Public/data.data' + encodeURI(found.groupPath || '') + '/' + encodeURIComponent(found.name);
                        if (this.imageGallery) this.imageGallery.showImmediate(url);
                        if (cfg.director?.testMode) this._debugLog('Tag image found: ' + tags.join('+') + ' → ' + found.name, 'pass');
                    } else {
                        if (cfg.director?.testMode) this._debugLog('No tag match for ' + tags.join('+') + ', generating from camera', 'info');
                        this._generateImageFromCamera(directive);
                    }
                });
                tagImageHandled = true;
            }
            if (!tagImageHandled) this._generateImageFromCamera(directive);
        }

        // Voice line injection
        if (directive.voiceLine && this.voiceSequence) {
            const insertAt = this.voiceSequence.currentIndex;
            this.sessionVoiceLines.push(directive.voiceLine);
            this.voiceSequence.injectLine(directive.voiceLine, insertAt, this.sessionConfig?.voice?.voiceProfileId).then(ok => {
                if (ok && !this.voiceSequence.isPlaying && this.voiceSequence.sequences.length > 0 && this.audioEnabled) this.voiceSequence.start();
            });
        }

        // Layer opacity
        if (directive.opacity && typeof directive.opacity === 'object') {
            for (const layer of ['labels', 'images', 'visualizer', 'visuals']) {
                if (typeof directive.opacity[layer] === 'number') this._setLayerOpacity(layer, directive.opacity[layer]);
            }
        }

        // Mood suggestion
        if (directive.suggestMood) {
            if (directive.suggestMood.emotion) this._suggestedEmotion = directive.suggestMood.emotion;
            if (directive.suggestMood.gender) this._suggestedGender = directive.suggestMood.gender;
            if (directive.suggestMood.genderPct != null) this._genderMalePct = Math.max(0, Math.min(100, directive.suggestMood.genderPct));
            if (this.moodRingEnabled && this._suggestedEmotion) {
                const theme = BiometricThemer.emotionThemes?.[this._suggestedEmotion];
                if (theme) this.moodRingColor = theme.accent;
            }
        }

        // askUser
        const hasAskUser = !!(directive.askUser);
        const interactiveCfg = !!(cfg?.director?.interactive);
        if (hasAskUser && interactiveCfg) {
            this._startUserInteraction(directive.askUser.question);
            this._ticksSinceAskUser = 0;
        } else if (interactiveCfg) {
            this._ticksSinceAskUser++;
        }

        if (directive.commentary) console.log('SessionDirector commentary:', directive.commentary);
        m.redraw();
    },

    // ── Interactive User Interaction ────────────────────────────────
    _startUserInteraction(question) {
        if (this._pendingQuestion) return;
        this._pendingQuestion = { question };
        this._userInputText = '';
        this._voicePausedForInteraction = false;
        this.sessionDirector?.pause();
        this._debugLog('askUser: "' + question + '"', 'directive');

        if (this.textSequence) {
            this.textSequence.injectText(question, this.textSequence.currentIndex);
            if (!this.textSequence.isPlaying && this.textSequence.sequences.length > 0) this.textSequence.start();
        }
        this.currentText = question;

        if (this.voiceSequence && this.audioEnabled) {
            const insertAt = this.voiceSequence.currentIndex;
            this.voiceSequence.injectLine(question, insertAt, this.sessionConfig?.voice?.voiceProfileId).then(ok => {
                if (!ok || !this._pendingQuestion) return;
                this.voiceSequence.pause();
                this._voicePausedForInteraction = true;
                const hash = this.voiceSequence.synthesizedHashes?.get(insertAt);
                if (hash) {
                    const source = this.audioEngine?.sources?.get(hash);
                    if (source?.buffer) source.play();
                }
            });
        }

        // Test mode: simulate response
        if (this.sessionConfig?.director?.testMode) {
            const cannedResponses = ['I feel calm and centered', 'Things are going well, thank you', 'I would like to go deeper', 'That sounds good to me', 'I am feeling relaxed and open'];
            const canned = cannedResponses[Math.floor(Math.random() * cannedResponses.length)];
            this._debugLog('Test mode: will simulate STT → "' + canned + '"', 'info');
            let charIdx = 0;
            const typeInterval = setInterval(() => {
                if (!this._pendingQuestion || charIdx >= canned.length) { clearInterval(typeInterval); return; }
                charIdx += 2; this._userInputText = canned.substring(0, charIdx); m.redraw();
            }, 80);
            this._responseTimeoutId = setTimeout(() => { clearInterval(typeInterval); this._userInputText = canned; this._submitUserResponse(); }, 5000);
        } else {
            // Production: text input with idle timeout
            this._lastTypingTime = Date.now();
            if (this._interactionIdleTimer) clearInterval(this._interactionIdleTimer);
            this._interactionIdleTimer = setInterval(() => {
                if (!this._pendingQuestion) { clearInterval(this._interactionIdleTimer); this._interactionIdleTimer = null; return; }
                if (Date.now() - this._lastTypingTime >= 30000) {
                    this._debugLog('Interaction idle timeout (30s)', 'info');
                    this._userInputText = 'No response from user'; this._submitUserResponse();
                }
            }, 2000);
        }

        m.redraw();
    },

    _submitUserResponse() {
        if (!this._pendingQuestion) return;
        this._userResponse = this._userInputText.trim() || '(no response)';
        this._debugLog('User response: "' + this._userResponse + '"', 'info');

        if (this._sttMediaRecorder) { try { this._sttMediaRecorder.stop(); } catch (e) {} this._sttMediaRecorder = null; }
        if (this._sttMediaStream) { this._sttMediaStream.getTracks().forEach(t => t.stop()); this._sttMediaStream = null; }
        const page = getPage();
        page.audioStream = undefined;

        if (this._responseTimeoutId) { clearTimeout(this._responseTimeoutId); this._responseTimeoutId = null; }
        if (this._interactionIdleTimer) { clearInterval(this._interactionIdleTimer); this._interactionIdleTimer = null; }

        if (this._voicePausedForInteraction && this.voiceSequence) { this.voiceSequence.resume(); this._voicePausedForInteraction = false; }
        this._pendingQuestion = null; this._userInputText = '';
        if (this.sessionDirector) { this.sessionDirector.resume(); this.sessionDirector._tick(); }
        m.redraw();
    },

    // ── Utility ─────────────────────────────────────────────────────
    _debugLog(text, type) {
        this.debugMessages.push({ time: Date.now(), type: type || 'info', text });
        if (this.debugMessages.length > 300) this.debugMessages = this.debugMessages.slice(-300);
        m.redraw();
    },

    _setLayerOpacity(layer, value) {
        const clamped = Math.max(0.15, Math.min(1, value));
        if (Math.abs(clamped - this._layerOpacityTarget[layer]) < 0.01) return;
        this._layerOpacityStart[layer] = this._layerOpacity[layer];
        this._layerOpacityTarget[layer] = clamped;
        this._layerTransitionStart[layer] = Date.now();
        this._startOpacityAnimation();
    },

    _startOpacityAnimation() {
        if (this._opacityRafId) return;
        this._opacityRafId = requestAnimationFrame(() => this._animateOpacity());
    },

    _animateOpacity() {
        const now = Date.now();
        let anyActive = false;
        for (const layer of ['labels', 'images', 'visualizer', 'visuals']) {
            const startTime = this._layerTransitionStart[layer];
            if (startTime == null) continue;
            const progress = Math.min(1, (now - startTime) / this._opacityTransitionMs);
            const eased = progress < 0.5 ? 2 * progress * progress : 1 - Math.pow(-2 * progress + 2, 2) / 2;
            this._layerOpacity[layer] = this._layerOpacityStart[layer] + (this._layerOpacityTarget[layer] - this._layerOpacityStart[layer]) * eased;
            if (progress >= 1) { this._layerOpacity[layer] = this._layerOpacityTarget[layer]; delete this._layerTransitionStart[layer]; }
            else anyActive = true;
        }
        m.redraw();
        if (anyActive) this._opacityRafId = requestAnimationFrame(() => this._animateOpacity());
        else this._opacityRafId = null;
    },

    _getBiometricClues() {
        const data = this.biometricData;
        if (!data) return [];
        const clues = [];
        if (data.dominant_emotion) clues.push(`A state of ${data.dominant_emotion}.`);
        if (data.age) clues.push(`${data.age} years of experience.`);
        if (data.dominant_gender) clues.push(`An expression of ${data.dominant_gender.toLowerCase()} energy.`);
        return clues;
    }
};

export { Magic8App };
export default Magic8App;
