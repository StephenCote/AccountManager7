/**
 * Magic8App - Primary orchestrator for the Magic8 immersive experience
 * Coordinates all subsystems: audio, biometrics, images, text, recording, generation
 * Mithril.js component
 */
(function() {

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
        phase: 'config', // 'config' | 'running' | 'paused'
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
        _emotionHistory: [],   // [{emotion, timestamp}] — only stores actual changes

        // Mood suggestion state (test mode emojis + mood ring)
        _suggestedEmotion: null,
        _suggestedGender: null,
        moodRingEnabled: false,
        moodRingColor: null,
        _genderMalePct: 50,  // 0=fully female, 100=fully male (drives gender bar gradient)

        // Generated image emphasis animation
        _imageAnimation: null, // { transform, transition } for current image

        // Test mode thumbnails (last capture + last generated)
        _lastCaptureObj: null,    // {objectId, groupPath, name}
        _lastGeneratedObj: null,  // {objectId, groupPath, name}

        // Interactive director state (askUser)
        _pendingQuestion: null,     // {question: string} from LLM askUser directive
        _userResponse: null,        // captured text response (consumed on next tick)
        _userInputText: '',         // live text in input field (STT or typed)
        _sttMediaStream: null,      // getUserMedia stream for server-side STT
        _sttMediaRecorder: null,    // MediaRecorder instance for server-side STT
        _responseTimeoutId: null,   // auto-submit timeout handle
        _interactionIdleTimer: null, // idle-check interval for text-only interaction
        _lastTypingTime: 0,          // timestamp of last keystroke in interaction input
        _voicePausedForInteraction: false, // voice sequence paused while waiting for user
        _ticksSinceAskUser: 0,    // ticks since last askUser directive (for escalating prompts)

        // Debug console (test mode)
        debugMessages: [],
        _debugMinimized: false,

        // Ambient breathing state
        ambientOpacity: 0.15,
        _breatheTransitionMs: 120000,
        _breatheTimeout: null,

        // Layer opacity (director-controlled, gradual transitions)
        _layerOpacity: { labels: 1, images: 1, visualizer: 1, visuals: 1 },
        _layerOpacityTarget: { labels: 1, images: 1, visualizer: 1, visuals: 1 },
        _layerOpacityStart: { labels: 1, images: 1, visualizer: 1, visuals: 1 },
        _layerTransitionStart: {},
        _opacityTransitionMs: 20000,  // 20s gradual transition
        _opacityRafId: null,

        // Intervals
        biometricInterval: null,
        imageCycleInterval: null,
        imageGenInterval: null,

        // Lifecycle
        async oninit(vnode) {
            const sessionConfigId = vnode.attrs.sessionConfigId;

            // Phase 10c: Check for sessionId route param for server-side context lookup
            const sessionId = vnode.attrs.sessionId;
            if (sessionId) {
                this.chatSessionId = sessionId;
            }

            // Check for chat context handoff (sessionStorage fallback)
            const contextJson = sessionStorage.getItem('magic8Context');
            if (contextJson) {
                try {
                    this.chatContext = JSON.parse(contextJson);
                    sessionStorage.removeItem('magic8Context');
                } catch (e) {
                    console.error('Magic8App: Failed to parse chat context:', e);
                }
            }

            // Load saved session config
            if (sessionConfigId) {
                await this._loadSessionConfig(sessionConfigId);
                this.phase = 'running';
            } else {
                this.phase = 'config';
            }

            // Create fullscreen manager
            this.fullscreenManager = new Magic8.FullscreenManager();
            this.fullscreenManager.onFullscreenChange((isFS) => {
                this.isFullscreen = isFS;
                m.redraw();
            });
        },

        oncreate(vnode) {
            this.containerElement = vnode.dom;

            // Handle keyboard shortcuts
            this._keyHandler = (e) => {
                if (e.key === 'Escape') {
                    this._handleExit();
                } else if (e.key === ' ' && this.phase === 'running') {
                    e.preventDefault();
                    this._togglePause();
                }
            };
            document.addEventListener('keydown', this._keyHandler);

            // Start session if config was preloaded
            if (this.phase === 'running' && this.sessionConfig) {
                this._initSession();
            }
        },

        onremove(vnode) {
            document.removeEventListener('keydown', this._keyHandler);
            this._cleanup();
        },

        // Configuration Management
        async _loadSessionConfig(configId) {
            try {
                let q = am7view.viewQuery(am7model.newInstance("data.data"));
                q.entity.request.push("dataBytesStore");
                q.field("objectId", configId);
                let qr = await page.search(q);
                if (qr && qr.results && qr.results.length) {
                    am7model.updateListModel(qr.results);
                    let obj = qr.results[0];
                    if (obj.dataBytesStore && obj.dataBytesStore.length) {
                        this.sessionConfig = JSON.parse(uwm.base64Decode(obj.dataBytesStore));
                    } else {
                        this.sessionConfig = {};
                    }
                } else {
                    this.sessionConfig = {};
                }
            } catch (err) {
                console.error('Magic8App: Failed to load session config:', err);
                this.sessionConfig = null;
            }
        },

        async _saveSessionConfig(config) {
            try {
                let dir = await page.findObject("auth.group", "DATA", "~/Magic8/Configs");
                if (!dir || !dir.objectId) {
                    dir = await page.makePath("auth.group", "data", "~/Magic8/Configs");
                }

                const configName = config.name || 'Magic8 Session';
                const encoded = uwm.base64Encode(JSON.stringify(config));

                // Check for existing config with same name in the group
                let q = am7view.viewQuery(am7model.newInstance("data.data"));
                q.entity.request.push("dataBytesStore");
                q.field("name", configName);
                q.field("groupId", dir.id);
                let qr = await page.search(q);

                let saved;
                if (qr?.results?.length) {
                    // Update existing record with full object
                    let existing = qr.results[0];
                    existing.dataBytesStore = encoded;
                    existing.compressionType = "none";
                    saved = await page.patchObject(existing);
                    console.log('Magic8App: Session config updated:', existing.objectId);
                } else {
                    // Create new record
                    let obj = am7model.newPrimitive("data.data");
                    obj.name = configName;
                    obj.contentType = "application/json";
                    obj.compressionType = "none";
                    obj.groupId = dir.id;
                    obj.groupPath = dir.path;
                    obj.dataBytesStore = encoded;
                    saved = await page.createObject(obj);
                    if (saved && saved.objectId) {
                        console.log('Magic8App: Session config saved:', saved.objectId);
                    }
                }

                this.sessionConfig = config;
                return saved?.objectId || null;
            } catch (err) {
                console.error('Magic8App: Failed to save session config:', err);
                return null;
            }
        },

        // Session Initialization
        async _initSession() {
            if (!this.sessionConfig) return;
            const cfg = this.sessionConfig;

            // Enter fullscreen
            if (cfg.display?.fullscreen && this.containerElement) {
                try {
                    await this.fullscreenManager.enterFullscreen(this.containerElement);
                    this.isFullscreen = true;
                } catch (e) {
                    console.warn('Magic8App: Fullscreen not available');
                }
            }

            // Initialize audio engine
            this.audioEngine = new Magic8.AudioEngine();
            // Ensure AudioContext is resumed (browsers suspend until user gesture;
            // _initSession runs from setTimeout, not a direct click handler)
            try {
                const ctx = this.audioEngine.getContext();
                if (ctx.state === 'suspended') ctx.resume();
            } catch (e) { /* context resume is best-effort */ }

            if (cfg.audio?.binauralEnabled) {
                if (cfg.audio.preset && Magic8.AudioEngine.FREQUENCY_PRESETS[cfg.audio.preset]) {
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

            // Start isochronic tones if enabled
            if (cfg.audio?.isochronicEnabled) {
                this.audioEngine.startIsochronic(cfg.audio);
            }

            // Initialize biometric themer
            if (cfg.biometrics?.enabled) {
                this.biometricThemer = new Magic8.BiometricThemer();

                if (cfg.biometrics.smoothingFactor) {
                    this.biometricThemer.setSmoothingFactor(cfg.biometrics.smoothingFactor);
                }

                this._themeRedrawTimer = null;
                this.biometricThemer.onThemeChange = (theme) => {
                    this.currentTheme = theme;
                    // Throttle m.redraw() — CSS custom properties update at 60fps via _applyTheme,
                    // but mithril vdom diffing at 60fps causes Node.removeChild race conditions
                    if (!this._themeRedrawTimer) {
                        this._themeRedrawTimer = setTimeout(() => {
                            this._themeRedrawTimer = null;
                            m.redraw();
                        }, 200);
                    }
                };

                this._startBiometricCapture();
            }

            // Initialize image gallery
            console.log('Magic8App: Initializing image gallery with config:', JSON.stringify(cfg.images));
            this.imageGallery = new Magic8.DynamicImageGallery({
                cycleInterval: cfg.images?.cycleInterval || 5000,
                crossfadeDuration: cfg.images?.crossfadeDuration || 1000,
                includeGenerated: cfg.images?.includeGenerated !== false,
                maxGeneratedImages: cfg.imageGeneration?.maxGeneratedImages || 20
            });

            // Build group list, including generated images group when SD is enabled
            const imageGroups = cfg.images?.baseGroups ? [...cfg.images.baseGroups] : [];
            if (cfg.imageGeneration?.enabled) {
                try {
                    // Add session-specific generated subgroup (where images are actually saved)
                    const sessionName = cfg.name || 'Session';
                    const sessionGenDir = await page.findObject("auth.group", "DATA", "~/Magic8/Generated/" + sessionName);
                    if (sessionGenDir && sessionGenDir.objectId) {
                        imageGroups.push(sessionGenDir.objectId);
                        console.log('Magic8App: Added session generated group to gallery:', sessionGenDir.objectId);
                    }
                    // Also add parent ~/Magic8/Generated for any ungrouped images
                    const genDir = await page.findObject("auth.group", "DATA", "~/Magic8/Generated");
                    if (genDir && genDir.objectId && !imageGroups.includes(genDir.objectId)) {
                        imageGroups.push(genDir.objectId);
                        console.log('Magic8App: Added ~/Magic8/Generated group to image gallery:', genDir.objectId);
                    }
                } catch (e) {
                    console.warn('Magic8App: Could not add generated images group:', e);
                }
            }

            if (imageGroups.length > 0) {
                console.log('Magic8App: Loading base images from groups:', imageGroups);
                const count = await this.imageGallery.loadBaseImages(imageGroups);
                console.log('Magic8App: Loaded', count, 'base images');
            } else {
                console.warn('Magic8App: No base image groups configured');
            }

            this.imageGallery.onImageChange = (img) => {
                // Crossfade: move current to prev, set new as current
                this.prevImage = this.currentImage;
                this.currentImage = img;
                this.imageOpacity = 0;

                // Pick emphasis animation for generated images
                if (img && img.type === 'generated') {
                    const duration = this.imageGallery._getDurationForImage(img);
                    this._imageAnimation = this._pickImageAnimation(duration);
                } else {
                    this._imageAnimation = null;
                }

                m.redraw();

                // Fade in new image, then trigger animation start
                requestAnimationFrame(() => {
                    this.imageOpacity = 1;
                    if (this._imageAnimation) {
                        // Start with 'from' transform, then after a frame switch to 'to'
                        requestAnimationFrame(() => {
                            if (this._imageAnimation) {
                                this._imageAnimation._phase = 'to';
                                m.redraw();
                            }
                        });
                    }
                    m.redraw();
                });
            };

            // Show first image immediately
            const firstImg = this.imageGallery.getCurrent();
            if (firstImg) {
                this.currentImage = firstImg;
                this.imageOpacity = 1;
            }

            this.imageGallery.start();
            this._startBreathing();
            console.log('Magic8App: Gallery stats:', this.imageGallery.getStats());

            // Initialize image generation
            if (cfg.imageGeneration?.enabled) {
                this.imageGenerator = new Magic8.ImageGenerationManager(
                    cfg.imageGeneration.sdConfigId,
                    cfg.imageGeneration.sdConfigId ? null : cfg.imageGeneration.sdInline
                );
                // Name session subgroup after session name for replayability
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

            // Initialize text sequence
            console.log('Magic8App: Text config:', JSON.stringify(cfg.text));
            if (cfg.text?.enabled && cfg.text?.sourceObjectId) {
                this.textSequence = new Magic8.TextSequenceManager();
                this.textSequence.loop = cfg.text.loop !== false;
                this.textSequence.displayDuration = cfg.text.displayDuration || 5000;

                let lineCount = 0;
                if (cfg.text.sourceType === 'note') {
                    lineCount = await this.textSequence.loadFromNote(cfg.text.sourceObjectId);
                } else {
                    lineCount = await this.textSequence.loadFromDataObject(cfg.text.sourceObjectId);
                }
                console.log('Magic8App: Loaded', lineCount, 'text lines from', cfg.text.sourceType, cfg.text.sourceObjectId);

                this.textSequence.onTextChange = (text) => {
                    this.currentText = text;
                    m.redraw();
                };

                this.textSequence.start();
            } else if (cfg.text?.enabled) {
                console.warn('Magic8App: Text enabled but no sourceObjectId selected');
            }

            // LLM-only text mode: create empty manager when director is enabled
            if (!this.textSequence && cfg.director?.enabled) {
                this.textSequence = new Magic8.TextSequenceManager();
                this.textSequence.loop = true;
                this.textSequence.displayDuration = cfg.text?.displayDuration || 8000;
                this.textSequence.onTextChange = (text) => {
                    this.currentText = text;
                    m.redraw();
                };
                console.log('Magic8App: Created empty text manager for LLM-only mode');
            }

            // Initialize voice sequence
            if (cfg.voice?.enabled && cfg.voice?.sourceObjectId) {
                this.voiceSequence = new Magic8.VoiceSequenceManager(this.audioEngine);
                this.voiceSequence.loop = cfg.voice.loop !== false;

                let voiceLineCount = 0;
                if (cfg.voice.sourceType === 'note') {
                    voiceLineCount = await this.voiceSequence.loadFromNote(cfg.voice.sourceObjectId);
                } else {
                    voiceLineCount = await this.voiceSequence.loadFromDataObject(cfg.voice.sourceObjectId);
                }
                console.log('Magic8App: Loaded', voiceLineCount, 'voice lines');

                if (voiceLineCount > 0) {
                    // Pre-synthesize all lines
                    console.log('Magic8App: Synthesizing voice lines...');
                    if (cfg.director?.testMode) {
                        this._debugLog('Voice: synthesizing ' + voiceLineCount + ' lines...', 'voice');
                    }
                    this.voiceSequence.onSynthesisProgress = (done, total) => {
                        console.log(`Magic8App: Voice synthesis ${done}/${total}`);
                        if (cfg.director?.testMode && (done === total || done % 5 === 0)) {
                            this._debugLog('Voice synth: ' + done + '/' + total, 'voice');
                        }
                    };
                    await this.voiceSequence.synthesizeAll(cfg.voice.voiceProfileId);

                    // Log individual voice lines in test mode
                    if (cfg.director?.testMode && this.voiceSequence.lines) {
                        for (let i = 0; i < Math.min(this.voiceSequence.lines.length, 5); i++) {
                            this._debugLog('Voice line: "' + this.voiceSequence.lines[i] + '"', 'voice');
                        }
                        if (this.voiceSequence.lines.length > 5) {
                            this._debugLog('... +' + (this.voiceSequence.lines.length - 5) + ' more voice lines', 'voice');
                        }
                    }

                    this.voiceSequence.start();
                    console.log('Magic8App: Voice sequence playback started');
                }
            }

            // Create empty voice manager when voice is enabled but no source file
            // (allows LLM to inject voice lines, or manual use without pre-loaded content)
            if (!this.voiceSequence && cfg.voice?.enabled) {
                this.voiceSequence = new Magic8.VoiceSequenceManager(this.audioEngine);
                this.voiceSequence.loop = cfg.voice?.loop !== false;
                console.log('Magic8App: Created empty voice manager (no source file)');
            }

            // Initialize recording
            if (cfg.recording?.enabled) {
                this.sessionRecorder = new Magic8.SessionRecorder();
                if (cfg.recording.autoStart) {
                    // Delay to allow canvas to be rendered
                    setTimeout(() => this._startRecording(), 1000);
                }
            }

            // Initialize session director
            this._sessionStartTime = Date.now();
            if (cfg.director?.enabled && cfg.director?.command) {
                // Ensure camera is running for face data, even if biometric theming is disabled
                if (!cfg.biometrics?.enabled) {
                    console.log('Magic8App: Starting biometric capture for session director');
                    this._startBiometricCapture();
                }

                this.sessionDirector = new Magic8.SessionDirector();
                this.sessionDirector.stateProvider = () => this._gatherDirectorState();
                this.sessionDirector.onDirective = (d) => {
                    this._applyDirective(d);
                    if (this.sessionConfig?.director?.testMode) {
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
                        // Interactive mode tracking
                        if (this.sessionConfig?.director?.interactive) {
                            this._debugLog('Interactive tick #' + this._ticksSinceAskUser + (d.askUser ? ' → askUser FIRED' : ' → no askUser'), d.askUser ? 'pass' : 'warn');
                        }
                    }
                };
                this.sessionDirector.onError = (err) => {
                    console.warn('SessionDirector error:', err);
                    if (this.sessionConfig?.director?.testMode) {
                        this._debugLog('Error: ' + (err.message || err), 'error');
                    }
                };
                // Tick debug: log state message and raw LLM response in test mode
                this.sessionDirector.onTickDebug = (info) => {
                    if (!this.sessionConfig?.director?.testMode) return;
                    // Log the interactive-relevant lines from the state message
                    const stateLines = info.stateMessage.split('\n');
                    const interactiveLine = stateLines.find(l => l.startsWith('Interactive:'));
                    const responseLine = stateLines.find(l => l.startsWith('User Response:'));
                    if (interactiveLine) {
                        this._debugLog('State → ' + interactiveLine, 'info');
                    }
                    if (responseLine) {
                        this._debugLog('State → ' + responseLine, 'info');
                    }
                    // Log raw LLM output (truncated) so we can see what the LLM actually returned
                    if (info.rawContent) {
                        const raw = info.rawContent.length > 200 ? info.rawContent.substring(0, 200) + '...' : info.rawContent;
                        this._debugLog('Raw LLM [#' + info.tickNum + ']: ' + raw, 'info');
                    }
                    // Log parse result
                    if (!info.directive) {
                        this._debugLog('Parse: FAILED (no valid directive)', 'fail');
                    } else {
                        const keys = Object.keys(info.directive).filter(k => k !== 'commentary');
                        this._debugLog('Parse OK: {' + keys.join(', ') + '}', 'pass');
                    }
                };
                await this.sessionDirector.initialize(cfg.director.command, cfg.director.intervalMs || 60000, cfg.name, {
                    imageTags: cfg.director.imageTags || ''
                });

                if (cfg.director.testMode) {
                    // Test mode: run multi-pass behavioral diagnostics
                    // Each pass sends different biometric state to the LLM,
                    // applies the returned directive synchronously, then
                    // runDiagnostics verifies the state actually changed via stateProvider
                    this._debugLog('Starting behavioral diagnostics...', 'info');
                    const self = this;
                    let passCount = 0;

                    const results = await this.sessionDirector.runDiagnostics(
                        cfg.director.command,
                        (directive, passIndex, passName) => {
                            passCount++;
                            self._debugLog('Applying directive [' + passName + ']', 'directive');
                            if (directive.labels?.add?.length) {
                                self._debugLog('+ Labels: ' + directive.labels.add.join(', '), 'label');
                            }
                            if (directive.generateImage) {
                                self._debugLog('Image: ' + (directive.generateImage.description || '').substring(0, 80), 'directive');
                            }
                            if (directive.commentary) {
                                self._debugLog('LLM: ' + directive.commentary, 'info');
                            }
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
                    const total = results.length;
                    this._debugLog('Diagnostics complete: ' + passed + '/' + total + ' passed (' + passCount + ' directives applied)', passed === total ? 'pass' : 'fail');
                }

                this.sessionDirector.start();
            }

            m.redraw();
        },

        // Biometric capture loop
        _startBiometricCapture() {
            if (!page.components.camera) {
                console.warn('Magic8App: page.components.camera not available');
                return;
            }

            const captureCallback = (imageData) => {
                this._handleBiometricData(imageData);
            };

            // Apply configured capture interval (default camera is 5s)
            const captureIntervalSec = this.sessionConfig?.biometrics?.captureInterval;
            if (captureIntervalSec && captureIntervalSec > 0) {
                page.components.camera.setCaptureInterval(captureIntervalSec);
            }

            // Ensure video element is in DOM before requesting camera
            const tryStart = () => {
                const devices = page.components.camera.devices();
                console.log('Magic8App: Camera devices found:', devices.length);

                if (!devices.length) {
                    console.log('Magic8App: Initializing camera and finding devices...');
                    page.components.camera.initializeAndFindDevices(captureCallback);
                } else {
                    console.log('Magic8App: Starting capture on existing device');
                    page.components.camera.startCapture(captureCallback);
                }
            };

            // Defer to ensure #facecam video element is rendered in DOM
            if (!document.querySelector('#facecam')) {
                console.log('Magic8App: Waiting for #facecam element...');
                requestAnimationFrame(() => {
                    m.redraw.sync();
                    setTimeout(tryStart, 50);
                });
            } else {
                tryStart();
            }
        },

        _handleBiometricData(imageData) {
            console.log('Magic8App: Biometric capture data:', imageData);
            if (imageData && imageData.results?.length) {
                const data = imageData.results[0];
                console.log('Magic8App: Face analysis result:', data);
                this.biometricData = data;

                // Track emotion transitions (only store actual changes)
                const emotion = data.dominant_emotion;
                if (emotion) {
                    const last = this._emotionHistory.length > 0
                        ? this._emotionHistory[this._emotionHistory.length - 1]
                        : null;
                    if (!last || last.emotion !== emotion) {
                        this._emotionHistory.push({ emotion, timestamp: Date.now() });
                        // Keep only the last 10 transitions
                        if (this._emotionHistory.length > 10) {
                            this._emotionHistory = this._emotionHistory.slice(-10);
                        }
                    }
                }

                // Update gender percentage from face data (unless LLM has overridden)
                if (data.gender_scores) {
                    const malePct = data.gender_scores.Man || 0;
                    this._genderMalePct = Math.round(malePct);
                }

                if (this.biometricThemer) {
                    this.biometricThemer.updateFromBiometrics(data);
                }
            }
        },

        // Image generation from camera
        _startImageGeneration() {
            const interval = Math.max(this.sessionConfig?.imageGeneration?.captureInterval || 120000, 60000);
            console.log('Magic8App: Image generation interval:', interval, 'ms');

            // Poll for biometric data availability, then fire first generation
            this._initialGenCheck = setInterval(async () => {
                if (page.components.camera && this.imageGenerator && this.biometricData) {
                    clearInterval(this._initialGenCheck);
                    this._initialGenCheck = null;
                    console.log('Magic8App: Triggering initial image generation (face detected)');
                    await this.imageGenerator.captureAndGenerate(
                        page.components.camera,
                        this.biometricData
                    );
                }
            }, 5000);

            this.imageGenInterval = setInterval(async () => {
                if (page.components.camera && this.imageGenerator && this.biometricData) {
                    console.log('Magic8App: Triggering image generation capture (face detected)');
                    await this.imageGenerator.captureAndGenerate(
                        page.components.camera,
                        this.biometricData
                    );
                }
            }, interval);
        },

        // Pick a random emphasis animation for generated images (Ken Burns-style)
        _pickImageAnimation(durationMs) {
            const dur = (durationMs || 5000) / 1000;
            const animations = [
                // Slow zoom in
                { from: 'scale(1)', to: 'scale(1.15)' },
                // Slow zoom out
                { from: 'scale(1.15)', to: 'scale(1)' },
                // Pan right + slight zoom
                { from: 'scale(1.08) translateX(-3%)', to: 'scale(1.08) translateX(3%)' },
                // Pan left + slight zoom
                { from: 'scale(1.08) translateX(3%)', to: 'scale(1.08) translateX(-3%)' },
                // Pan up + zoom
                { from: 'scale(1.1) translateY(3%)', to: 'scale(1.1) translateY(-3%)' },
                // Pan down + zoom
                { from: 'scale(1.1) translateY(-3%)', to: 'scale(1.1) translateY(3%)' },
                // Diagonal drift
                { from: 'scale(1.1) translate(-2%, -2%)', to: 'scale(1.1) translate(2%, 2%)' },
                // Reverse diagonal drift
                { from: 'scale(1.1) translate(2%, -2%)', to: 'scale(1.1) translate(-2%, 2%)' }
            ];
            const pick = animations[Math.floor(Math.random() * animations.length)];
            return {
                from: { transform: pick.from, transition: 'none' },
                to: { transform: pick.to, transition: 'transform ' + dur + 's ease-in-out' }
            };
        },

        // Ambient opacity breathing
        _startBreathing() {
            this.ambientOpacity = 0.15;
            this._breatheStep(true);
        },

        _breatheStep(goingUp) {
            if (this.phase !== 'running' && this.phase !== 'paused') return;

            // Random half-cycle: 2-4 minutes
            const halfCycleMs = (120 + Math.random() * 120) * 1000;
            this._breatheTransitionMs = halfCycleMs;

            if (goingUp) {
                // Random upper bound between 0.5 and 0.85
                this.ambientOpacity = 0.5 + Math.random() * 0.35;
            } else {
                // Dim phase
                this.ambientOpacity = 0.1;
            }
            m.redraw();

            this._breatheTimeout = setTimeout(() => {
                this._breatheStep(!goingUp);
            }, halfCycleMs);
        },

        _stopBreathing() {
            if (this._breatheTimeout) {
                clearTimeout(this._breatheTimeout);
                this._breatheTimeout = null;
            }
        },

        // Recording controls
        async _startRecording() {
            if (!this.sessionRecorder) return;

            const container = this.containerElement;
            if (!container) {
                console.warn('Magic8App: Container not found for recording');
                this._debugLog('Recording: container not found', 'warn');
                return;
            }

            // Create compositing canvas at container size
            const w = container.clientWidth;
            const h = container.clientHeight;
            const compCanvas = document.createElement('canvas');
            compCanvas.width = w;
            compCanvas.height = h;
            this._recCanvas = compCanvas;
            this._recCtx = compCanvas.getContext('2d');

            // Pre-load current background image
            this._recImage = new Image();
            this._recImage.crossOrigin = 'anonymous';
            this._recImageUrl = '';
            this._recPrevImage = new Image();
            this._recPrevImage.crossOrigin = 'anonymous';
            if (this.currentImage?.url) {
                this._recImage.src = this.currentImage.url;
                this._recImageUrl = this.currentImage.url;
            }

            // Start compositing loop
            this._recCompositing = true;
            this._compositeFrame();

            // Capture stream from compositing canvas + audio
            const audioDestination = this.audioEngine?.getDestination();
            console.log('Magic8App: Starting recording, composite:', w + 'x' + h,
                'audio:', audioDestination ? 'yes' : 'no');

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
                this._debugLog('Recording failed: ' + err.message, 'error');
            }
        },

        /**
         * Compositing loop — draws all visual layers onto the recording canvas each frame
         * @private
         */
        _compositeFrame() {
            if (!this._recCompositing) return;

            const ctx = this._recCtx;
            const w = this._recCanvas.width;
            const h = this._recCanvas.height;

            // 1. Background color
            const bg = this.currentTheme?.bg || [20, 20, 30];
            ctx.fillStyle = `rgb(${bg[0]},${bg[1]},${bg[2]})`;
            ctx.fillRect(0, 0, w, h);

            // 2. Background image (cover-style with ambient + director opacity)
            const imgUrl = this.currentImage?.url || '';
            if (imgUrl && imgUrl !== this._recImageUrl) {
                // Image changed — shift current to prev for crossfade
                this._recPrevImage.src = this._recImageUrl;
                this._recImage.src = imgUrl;
                this._recImageUrl = imgUrl;
            }

            const imgAlpha = (this.ambientOpacity || 1) * (this._layerOpacity?.images || 1);
            // Draw previous image at full opacity during crossfade
            if (this._recPrevImage.complete && this._recPrevImage.naturalWidth > 0 && this.imageOpacity < 1) {
                ctx.globalAlpha = imgAlpha;
                this._drawCover(ctx, this._recPrevImage, w, h);
            }
            // Draw current image at crossfade opacity
            if (this._recImage.complete && this._recImage.naturalWidth > 0) {
                ctx.globalAlpha = imgAlpha * (this.imageOpacity != null ? this.imageOpacity : 1);
                this._drawCover(ctx, this._recImage, w, h);
            }
            ctx.globalAlpha = 1;

            // 3. HypnoCanvas visual effects
            const hypnoCanvas = this.containerElement?.querySelector('.hypno-canvas');
            if (hypnoCanvas && hypnoCanvas.width > 0) {
                const visAlpha = (this.ambientOpacity != null ? Math.min(1, this.ambientOpacity + 0.4) : 0.7)
                    * (this._layerOpacity?.visuals || 1);
                ctx.globalAlpha = visAlpha;
                ctx.drawImage(hypnoCanvas, 0, 0, w, h);
                ctx.globalAlpha = 1;
            }

            // 4. Audio visualizer canvas (screen blend)
            const vizCanvas = this.containerElement?.querySelector('.audio-visualizer-overlay canvas');
            if (vizCanvas && vizCanvas.width > 0) {
                const vizAlpha = (this.sessionConfig?.audio?.visualizerOpacity || 0.35)
                    * (this._layerOpacity?.visualizer || 1);
                ctx.globalAlpha = vizAlpha;
                ctx.globalCompositeOperation = 'screen';
                ctx.drawImage(vizCanvas, 0, 0, w, h);
                ctx.globalCompositeOperation = 'source-over';
                ctx.globalAlpha = 1;
            }

            // 5. Dark overlay for text readability
            ctx.fillStyle = 'rgba(0, 0, 0, 0.3)';
            ctx.fillRect(0, 0, w, h);

            // 6. Text labels — read from DOM to capture exact positions and styling
            const labelEls = this.containerElement?.querySelectorAll('.bio-label');
            if (labelEls && labelEls.length > 0) {
                const cRect = this.containerElement.getBoundingClientRect();
                const layerAlpha = this._layerOpacity?.labels || 1;
                labelEls.forEach(el => {
                    const rect = el.getBoundingClientRect();
                    const x = rect.left - cRect.left;
                    const y = rect.top - cRect.top;
                    const style = getComputedStyle(el);
                    const opacity = (parseFloat(style.opacity) || 0) * layerAlpha;
                    if (opacity < 0.01) return;

                    ctx.globalAlpha = opacity;
                    ctx.fillStyle = style.color || 'rgba(255,255,255,0.7)';
                    ctx.font = style.fontSize + ' ' + (style.fontFamily || 'Georgia, serif');

                    // Approximate text shadow as glow
                    const glow = this.currentTheme?.glow;
                    if (glow) {
                        ctx.shadowColor = `rgba(${glow[0]},${glow[1]},${glow[2]},0.5)`;
                        ctx.shadowBlur = 16;
                    }
                    ctx.fillText(el.textContent, x, y + rect.height * 0.75);
                    ctx.shadowBlur = 0;
                });
                ctx.globalAlpha = 1;
            }

            requestAnimationFrame(() => this._compositeFrame());
        },

        /**
         * Draw an image cover-style (CSS background-size: cover equivalent)
         * @private
         */
        _drawCover(ctx, img, cw, ch) {
            const imgRatio = img.naturalWidth / img.naturalHeight;
            const canvasRatio = cw / ch;
            let sx, sy, sw, sh;
            if (imgRatio > canvasRatio) {
                sh = img.naturalHeight;
                sw = sh * canvasRatio;
                sx = (img.naturalWidth - sw) / 2;
                sy = 0;
            } else {
                sw = img.naturalWidth;
                sh = sw / canvasRatio;
                sx = 0;
                sy = (img.naturalHeight - sh) / 2;
            }
            ctx.drawImage(img, sx, sy, sw, sh, 0, 0, cw, ch);
        },

        /**
         * Stop the compositing loop and release the recording canvas
         * @private
         */
        _stopCompositing() {
            this._recCompositing = false;
            this._recCanvas = null;
            this._recCtx = null;
            this._recImage = null;
            this._recPrevImage = null;
            this._recImageUrl = '';
        },

        async _stopRecording() {
            const recorder = this.sessionRecorder;
            if (!recorder || !this.isRecording) return;

            this._stopCompositing();

            try {
                const blob = await recorder.stopRecording();
                this.isRecording = false;

                if (blob && blob.size > 0) {
                    const sizeMB = (blob.size / (1024 * 1024)).toFixed(1);
                    if (page.toast) page.toast('info', `Saving recording (${sizeMB} MB)...`);
                    await recorder.saveToServer(blob, this.sessionConfig?.name);
                    console.log(`Magic8App: Recording saved to server (${sizeMB} MB)`);
                    if (page.toast) page.toast('info', `Recording saved (${sizeMB} MB)`);
                } else {
                    console.warn('Magic8App: Recording blob is empty');
                    if (page.toast) page.toast('warn', 'Recording was empty');
                }

                m.redraw();
            } catch (err) {
                console.error('Magic8App: Failed to stop recording:', err);
                this.isRecording = false;
                if (page.toast) page.toast('error', 'Recording failed: ' + err.message);
            }
        },

        /**
         * Stop recording and dispose the recorder (used during cleanup to avoid race)
         * @param {Object} recorder - SessionRecorder instance
         * @private
         */
        async _stopRecordingAndDispose(recorder) {
            this._stopCompositing();
            try {
                const blob = await recorder.stopRecording();
                this.isRecording = false;

                if (blob && blob.size > 0) {
                    const sizeMB = (blob.size / (1024 * 1024)).toFixed(1);
                    console.log(`Magic8App: Saving recording on exit (${sizeMB} MB)`);
                    await recorder.saveToServer(blob, this.sessionConfig?.name);
                    console.log(`Magic8App: Recording saved to server (${sizeMB} MB)`);
                }
            } catch (err) {
                console.warn('Magic8App: Could not save recording on exit:', err);
                this.isRecording = false;
            } finally {
                recorder.dispose();
            }
        },

        _toggleRecording() {
            if (this.isRecording) {
                this._stopRecording();
            } else {
                this._startRecording();
            }
        },

        _togglePause() {
            if (this.phase === 'running') {
                this.phase = 'paused';
                this.textSequence?.pause();
                this.voiceSequence?.pause();
                this.imageGallery?.stop();
                this.audioEngine?.stopIsochronic();
                this._stopBreathing();
                this.sessionDirector?.pause();
            } else if (this.phase === 'paused') {
                this.phase = 'running';
                this.textSequence?.resume();
                this.voiceSequence?.resume();
                this.imageGallery?.start();
                if (this.sessionConfig?.audio?.isochronicEnabled && this.audioEnabled) {
                    this.audioEngine?.startIsochronic(this.sessionConfig.audio);
                }
                this._startBreathing();
                this.sessionDirector?.resume();
            }
            m.redraw();
        },

        _toggleAudio() {
            this.audioEnabled = !this.audioEnabled;
            const cfg = this.sessionConfig;

            if (this.audioEnabled) {
                // Restore master volume
                this.audioEngine?.setVolume(1.0);
                // Restart binaural
                if (cfg?.audio?.binauralEnabled) {
                    if (cfg.audio.preset && Magic8.AudioEngine.FREQUENCY_PRESETS[cfg.audio.preset]) {
                        this.audioEngine?.startBinauralFromPreset(cfg.audio.preset);
                    } else if (cfg.audio.startBand) {
                        this.audioEngine?.startBinauralFromBands({
                            startBand: cfg.audio.startBand,
                            troughBand: cfg.audio.troughBand || cfg.audio.startBand,
                            endBand: cfg.audio.endBand || cfg.audio.startBand,
                            baseFreq: cfg.audio.baseFreq || 200,
                            sweepDurationMin: cfg.audio.sweepDurationMin || 5
                        });
                    } else {
                        this.audioEngine?.startBinaural(cfg.audio);
                    }
                }
                // Restart isochronic
                if (cfg?.audio?.isochronicEnabled) {
                    this.audioEngine?.startIsochronic(cfg.audio);
                }
                // Resume voice playback
                this.voiceSequence?.resume();
            } else {
                // Stop all audio sources
                this.audioEngine?.stopBinaural();
                this.audioEngine?.stopIsochronic();
                // Pause voice so it doesn't advance while muted
                this.voiceSequence?.pause();
                // Mute master gain as safety net
                this.audioEngine?.setVolume(0);
            }

            m.redraw();
        },

        _toggleFullscreen() {
            if (this.containerElement) {
                this.fullscreenManager.toggleFullscreen(this.containerElement);
            }
        },

        // Handle exit
        async _handleExit() {
            // Save recording BEFORE cleanup/navigation so the blob download completes
            await this._finalizeRecording();

            try {
                this._cleanup();
            } catch (err) {
                console.error('Magic8App: Cleanup error during exit:', err);
            }

            // Check if we came from chat
            if (this.chatContext) {
                const returnContext = {
                    sessionId: this.sessionConfig?.name,
                    timestamp: Date.now()
                };
                sessionStorage.setItem('magic8Return', JSON.stringify(returnContext));
                window.history.back();
            } else {
                // Navigate to home or previous view
                if (m.route && m.route.set) {
                    m.route.set('/main');
                } else {
                    window.history.back();
                }
            }
        },

        /**
         * Stop recording and download the file before exit.
         * Awaited by _handleExit so the blob download completes before navigation.
         * @private
         */
        async _finalizeRecording() {
            if (!this.isRecording || !this.sessionRecorder) return;

            this._stopCompositing();
            const recorder = this.sessionRecorder;
            this.sessionRecorder = null; // prevent _cleanup from re-processing
            this.isRecording = false;

            try {
                console.log('Magic8App: Finalizing recording before exit...');
                const blob = await recorder.stopRecording();

                if (blob && blob.size > 0) {
                    const sizeMB = (blob.size / (1024 * 1024)).toFixed(1);
                    console.log(`Magic8App: Saving recording before exit (${sizeMB} MB)`);
                    await recorder.saveToServer(blob, this.sessionConfig?.name);
                    console.log(`Magic8App: Recording saved to server (${sizeMB} MB)`);
                    if (page.toast) page.toast('info', `Recording saved (${sizeMB} MB)`);
                } else {
                    console.warn('Magic8App: Recording blob was empty on exit');
                }
            } catch (err) {
                console.warn('Magic8App: Could not save recording on exit:', err);
            } finally {
                recorder.dispose();
            }
        },

        // Cleanup (guarded against double-call from _handleExit + onremove)
        _cleanup() {
            if (this._cleaned) return;
            this._cleaned = true;

            clearInterval(this.biometricInterval);
            clearInterval(this.imageCycleInterval);
            clearInterval(this.imageGenInterval);
            if (this._initialGenCheck) {
                clearInterval(this._initialGenCheck);
            }
            this._stopBreathing();
            this._stopCompositing();
            if (this._opacityRafId) {
                cancelAnimationFrame(this._opacityRafId);
                this._opacityRafId = null;
            }

            // Stop recording FIRST (before audio disposal kills its tracks)
            // If _finalizeRecording already ran (via _handleExit), sessionRecorder is null
            if (this.isRecording && this.sessionRecorder) {
                const recorder = this.sessionRecorder;
                this.sessionRecorder = null;
                this._stopRecordingAndDispose(recorder);
            } else if (this.sessionRecorder) {
                this.sessionRecorder.dispose();
                this.sessionRecorder = null;
            }

            // Stop camera
            if (page.components.camera) {
                page.components.camera.stopCapture();
            }

            // Stop audio
            this.audioEngine?.dispose();

            // Stop text
            this.textSequence?.dispose();

            // Stop voice
            this.voiceSequence?.dispose();

            // Stop gallery
            this.imageGallery?.dispose();

            // Stop generation
            this.imageGenerator?.dispose();

            // Stop interactive STT if active
            if (this._sttMediaRecorder) {
                try { this._sttMediaRecorder.stop(); } catch (e) { /* ignore */ }
                this._sttMediaRecorder = null;
            }
            if (this._sttMediaStream) {
                this._sttMediaStream.getTracks().forEach(t => t.stop());
                this._sttMediaStream = null;
            }
            page.audioStream = undefined;
            if (this._responseTimeoutId) {
                clearTimeout(this._responseTimeoutId);
                this._responseTimeoutId = null;
            }
            if (this._interactionIdleTimer) {
                clearInterval(this._interactionIdleTimer);
                this._interactionIdleTimer = null;
            }
            this._pendingQuestion = null;
            this._voicePausedForInteraction = false;

            // Stop session director and clear session data
            this.sessionDirector?.dispose();
            this.sessionLabels = [];
            this.sessionVoiceLines = [];

            // Stop biometric themer
            if (this._themeRedrawTimer) {
                clearTimeout(this._themeRedrawTimer);
                this._themeRedrawTimer = null;
            }
            this.biometricThemer?.dispose();

            // Exit fullscreen
            this.fullscreenManager?.exitFullscreen();
            this.fullscreenManager?.dispose();
        },

        // View
        view(vnode) {
            // Config phase - show setup editor
            if (this.phase === 'config') {
                return m('.magic8-wrapper', [
                    m(Magic8.SessionConfigEditor, {
                        config: this.sessionConfig,
                        onSave: async (config) => {
                            this.sessionConfig = config;
                            this.phase = 'running';
                            m.redraw();

                            // Save session config to server
                            this._saveSessionConfig(config);

                            // Defer init to next frame so DOM is ready
                            setTimeout(() => this._initSession(), 100);
                        },
                        onCancel: () => this._handleExit()
                    }),
                    page.components.dialog && page.components.dialog.loadDialog()
                ]);
            }

            // Running / Paused phase - full immersive experience
            const bgColor = this.currentTheme?.bg
                ? `rgb(${this.currentTheme.bg.join(',')})`
                : 'rgb(20, 20, 30)';

            return m('.magic8-app.fixed.inset-0.overflow-hidden', {
                style: {
                    background: bgColor,
                    transition: 'background 1s ease'
                }
            }, [
                // Background image container - ambient breathing controls overall opacity, director can dim
                m('.magic8-bg-container.absolute.inset-0.overflow-hidden', {
                    style: {
                        opacity: this.ambientOpacity * this._layerOpacity.images,
                        transition: `opacity ${this._breatheTransitionMs}ms ease-in-out`,
                        zIndex: 1
                    }
                }, [
                    this.prevImage && m('.magic8-bg-prev.absolute.inset-0', {
                        style: {
                            backgroundImage: `url("${this.prevImage.url}")`,
                            backgroundSize: 'cover',
                            backgroundPosition: 'center'
                        }
                    }),
                    this.currentImage && (() => {
                        const anim = this._imageAnimation;
                        const phase = anim ? (anim._phase === 'to' ? anim.to : anim.from) : null;
                        const crossfade = (this.sessionConfig?.images?.crossfadeDuration || 1000);
                        const transitions = ['opacity ' + crossfade + 'ms ease-in-out'];
                        if (phase && phase.transition && phase.transition !== 'none') {
                            transitions.push(phase.transition);
                        }
                        return m('.magic8-bg-current.absolute.inset-0', {
                            style: {
                                backgroundImage: `url("${this.currentImage.url}")`,
                                backgroundSize: 'cover',
                                backgroundPosition: 'center',
                                opacity: this.imageOpacity,
                                transform: phase ? phase.transform : '',
                                transition: transitions.join(', ')
                            }
                        });
                    })()
                ]),

                // Visual effects canvas layer (breathing opacity synced with background, director can dim)
                m('.absolute.inset-0.z-5.pointer-events-none', {
                    style: {
                        opacity: (this.ambientOpacity != null ? Math.min(1, this.ambientOpacity + 0.4) : 0.7) * this._layerOpacity.visuals,
                        transition: `opacity ${this._breatheTransitionMs || 2000}ms ease-in-out`
                    }
                }, m(Magic8.HypnoCanvas, {
                    theme: this.currentTheme,
                    visuals: this.sessionConfig?.visuals,
                    class: 'absolute inset-0'
                })),

                // Audio visualizer overlay (between effects and dark overlay, director can dim)
                this.sessionConfig?.audio?.visualizerEnabled && this.audioEngine &&
                    m(Magic8.AudioVisualizerOverlay, {
                        audioEngine: this.audioEngine,
                        opacity: (this.sessionConfig.audio.visualizerOpacity || 0.35) * this._layerOpacity.visualizer,
                        gradient: this.sessionConfig.audio.visualizerGradient || 'prism',
                        mode: this.sessionConfig.audio.visualizerMode || 2
                    }),

                // Dark overlay for text readability
                m('.absolute.inset-0.z-10.bg-black', {
                    style: { opacity: 0.3 }
                }),

                // Gender confidence bar (only when mood ring is enabled)
                this.moodRingEnabled && m('.absolute.inset-x-0.pointer-events-none', {
                    style: {
                        height: '33vh',
                        top: '50%',
                        transform: 'translateY(-50%)',
                        opacity: 0.25,
                        zIndex: 11,
                        background: 'linear-gradient(to right, rgba(255,182,193,' + ((100 - this._genderMalePct) / 100) + '), rgba(135,206,235,' + (this._genderMalePct / 100) + '))',
                        transition: 'background 2s ease-in-out'
                    }
                }),

                // Animated floating text labels, styled by biometric theme (director can dim)
                m(Magic8.BiometricOverlay, {
                    text: this.currentText,
                    theme: this.currentTheme,
                    opacity: this._layerOpacity.labels
                }),

                // Paused overlay (tappable for mobile resume)
                this.phase === 'paused' && m('.absolute.inset-0.z-40.flex.items-center.justify-center.bg-black/50.cursor-pointer', {
                    onclick: () => this._togglePause()
                }, [
                    m('.text-center.text-white', [
                        m('.text-4xl.mb-4', 'Paused'),
                        m('.text-lg.text-gray-400', 'Tap or press SPACE to resume')
                    ])
                ]),

                // Recording indicator
                this.isRecording && m(Magic8.RecordingIndicator, {
                    recorder: this.sessionRecorder
                }),

                // Interactive director overlay (askUser)
                this._pendingQuestion && m('.fixed.inset-x-0.bottom-20.flex.justify-center.px-4', {
                    style: { zIndex: 200 }
                }, [
                    m('.bg-gray-900/95.backdrop-blur.rounded-2xl.p-5.max-w-lg.w-full.shadow-2xl.border.border-cyan-500/30', [
                        // Question text
                        m('.text-cyan-300.text-lg.font-medium.mb-4.text-center', this._pendingQuestion.question),
                        // Input hint
                        m('.flex.items-center.justify-center.gap-2.mb-3.text-sm.text-gray-400', [
                            m('span.material-symbols-outlined.text-cyan-400', 'chat'),
                            'Type your response below'
                        ]),
                        // Text input + submit
                        m('.flex.gap-2', [
                            m('input.flex-1.bg-gray-800.text-white.rounded-lg.px-4.py-3.border.border-gray-600.focus\\:border-cyan-400.outline-none', {
                                placeholder: 'Type your response...',
                                value: this._userInputText,
                                oninput: (e) => { this._userInputText = e.target.value; this._lastTypingTime = Date.now(); },
                                onkeydown: (e) => { if (e.key === 'Enter') this._submitUserResponse(); }
                            }),
                            m('button.bg-cyan-600.hover\\:bg-cyan-500.text-white.px-5.py-3.rounded-lg.font-medium.transition-colors', {
                                onclick: () => this._submitUserResponse()
                            }, 'Send')
                        ])
                    ])
                ]),

                // Control panel
                m(Magic8.ControlPanel, {
                    config: this.sessionConfig,
                    state: {
                        isRecording: this.isRecording,
                        audioEnabled: this.audioEnabled,
                        isFullscreen: this.isFullscreen,
                        moodRingEnabled: this.moodRingEnabled,
                        testMode: this.sessionConfig?.director?.testMode || false,
                        emotion: this.biometricData?.dominant_emotion || 'neutral',
                        gender: this.biometricData?.dominant_gender || null,
                        suggestedEmotion: this._suggestedEmotion,
                        suggestedGender: this._suggestedGender,
                        moodColor: this.moodRingColor
                    },
                    autoHideDelay: this.sessionConfig?.display?.controlsAutoHide || 5000,
                    onToggleRecording: () => this._toggleRecording(),
                    onToggleAudio: () => this._toggleAudio(),
                    onToggleFullscreen: () => this._toggleFullscreen(),
                    onToggleMoodRing: () => {
                        this.moodRingEnabled = !this.moodRingEnabled;
                        if (!this.moodRingEnabled) {
                            this.moodRingColor = null;
                        }
                    },
                    onToggleTestMode: () => {
                        if (!this.sessionConfig) return;
                        if (!this.sessionConfig.director) this.sessionConfig.director = {};
                        this.sessionConfig.director.testMode = !this.sessionConfig.director.testMode;
                        if (this.sessionConfig.director.testMode) {
                            this._debugLog('Test mode enabled via control panel', 'info');
                        }
                    },
                    onOpenConfig: () => {
                        this.phase = 'config';
                        m.redraw();
                    },
                    onExit: () => this._handleExit()
                }),

                // Hidden camera view (needed for biometrics AND director/image generation)
                page.components.camera && (this.sessionConfig?.biometrics?.enabled || this.sessionConfig?.director?.enabled || this.sessionConfig?.imageGeneration?.enabled)
                    ? page.components.camera.videoView()
                    : null,

                // Test mode emoji display (top-left)
                this.sessionConfig?.director?.testMode && this.biometricData &&
                    m(Magic8.MoodEmojiDisplay, {
                        emotion: this.biometricData?.dominant_emotion || 'neutral',
                        gender: this.biometricData?.dominant_gender || null,
                        suggestedEmotion: this._suggestedEmotion,
                        suggestedGender: this._suggestedGender
                    }),

                // On-screen debug console (test mode only)
                this.sessionConfig?.director?.testMode && this.debugMessages.length > 0 &&
                    m('.magic8-debug-console', {
                        style: {
                            position: 'fixed',
                            bottom: '10px',
                            right: '10px',
                            width: '440px',
                            maxHeight: this._debugMinimized ? '32px' : '40vh',
                            backgroundColor: 'rgba(0, 0, 0, 0.88)',
                            borderRadius: '6px',
                            border: '1px solid rgba(100, 255, 100, 0.2)',
                            zIndex: 200,
                            fontFamily: "'Consolas', 'Monaco', monospace",
                            fontSize: '11px',
                            overflow: 'hidden',
                            display: 'flex',
                            flexDirection: 'column',
                            pointerEvents: 'auto'
                        }
                    }, [
                        // Header bar
                        m('.debug-header', {
                            style: {
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'space-between',
                                padding: '4px 10px',
                                borderBottom: this._debugMinimized ? 'none' : '1px solid rgba(100, 255, 100, 0.15)',
                                cursor: 'pointer',
                                flexShrink: 0,
                                userSelect: 'none'
                            },
                            onclick: () => { this._debugMinimized = !this._debugMinimized; }
                        }, [
                            m('span', { style: { color: '#6f6', fontWeight: 'bold', letterSpacing: '0.5px' } }, 'DEBUG'),
                            m('span', { style: { color: '#666', fontSize: '10px' } },
                                this.debugMessages.length + ' msgs'),
                            m('span', { style: { color: '#888', fontSize: '14px' } },
                                this._debugMinimized ? '+' : '\u2212')
                        ]),
                        // Message list
                        !this._debugMinimized && m('.debug-messages', {
                            style: {
                                overflowY: 'auto',
                                padding: '4px 8px',
                                flex: 1
                            },
                            oncreate: (vn) => { vn.dom.scrollTop = vn.dom.scrollHeight; },
                            onupdate: (vn) => { vn.dom.scrollTop = vn.dom.scrollHeight; }
                        }, this.debugMessages.map((msg, i) => {
                            const colors = {
                                info: '#aaa',
                                directive: '#6bf',
                                label: '#fb4',
                                voice: '#d8f',
                                pass: '#6f6',
                                fail: '#f66',
                                error: '#f66'
                            };
                            const t = new Date(msg.time);
                            const ts = t.getHours().toString().padStart(2, '0') + ':'
                                + t.getMinutes().toString().padStart(2, '0') + ':'
                                + t.getSeconds().toString().padStart(2, '0');
                            return m('.debug-msg', {
                                key: i,
                                style: {
                                    color: colors[msg.type] || '#aaa',
                                    padding: '1px 0',
                                    lineHeight: '1.4',
                                    wordBreak: 'break-word'
                                }
                            }, '[' + ts + '] ' + msg.text);
                        }))
                    ]),

                // Test mode thumbnails (last capture + last generated)
                this.sessionConfig?.director?.testMode && (this._lastCaptureObj || this._lastGeneratedObj) &&
                    m('.magic8-test-thumbnails', {
                        style: {
                            position: 'fixed',
                            top: '60px',
                            right: '10px',
                            zIndex: 190,
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '8px',
                            pointerEvents: 'none'
                        }
                    }, [
                        this._lastCaptureObj && m('.test-thumb', {
                            style: {
                                width: '160px',
                                borderRadius: '8px',
                                border: '2px solid rgba(100, 200, 255, 0.5)',
                                overflow: 'hidden',
                                backgroundColor: 'rgba(0,0,0,0.6)'
                            }
                        }, [
                            m('img', {
                                src: g_application_path + '/thumbnail/' +
                                    am7client.dotPath(am7client.currentOrganization) +
                                    '/data.data' + this._lastCaptureObj.groupPath + '/' +
                                    this._lastCaptureObj.name + '/160x160',
                                style: { width: '100%', height: '160px', objectFit: 'cover', display: 'block' },
                                onerror: function() { this.style.display = 'none'; }
                            }),
                            m('div', {
                                style: {
                                    fontSize: '10px', textAlign: 'center',
                                    color: 'rgba(100,200,255,0.8)', padding: '2px 0',
                                    background: 'rgba(0,0,0,0.5)'
                                }
                            }, 'Capture')
                        ]),
                        this._lastGeneratedObj && m('.test-thumb', {
                            style: {
                                width: '160px',
                                borderRadius: '8px',
                                border: '2px solid rgba(100, 255, 100, 0.5)',
                                overflow: 'hidden',
                                backgroundColor: 'rgba(0,0,0,0.6)'
                            }
                        }, [
                            m('img', {
                                src: g_application_path + '/thumbnail/' +
                                    am7client.dotPath(am7client.currentOrganization) +
                                    '/data.data' + (this._lastGeneratedObj.groupPath || '') + '/' +
                                    (this._lastGeneratedObj.name || '') + '/160x160',
                                style: { width: '100%', height: '160px', objectFit: 'cover', display: 'block' },
                                onerror: function() { this.style.display = 'none'; }
                            }),
                            m('div', {
                                style: {
                                    fontSize: '10px', textAlign: 'center',
                                    color: 'rgba(100,255,100,0.8)', padding: '2px 0',
                                    background: 'rgba(0,0,0,0.5)'
                                }
                            }, 'Generated')
                        ])
                    ]),

                // Dialog support
                page.components.dialog ? page.components.dialog.loadDialog() : null
            ]);
        },

        /**
         * Gather current session state for the director LLM
         * @returns {Object}
         * @private
         */
        _gatherDirectorState() {
            const bio = this.biometricData;
            const audio = this.sessionConfig?.audio;
            const visuals = this.sessionConfig?.visuals;

            // Get recent voice lines
            let recentVoiceLines = [];
            if (this.voiceSequence) {
                const seqs = this.voiceSequence.sequences;
                const idx = this.voiceSequence.currentIndex;
                if (seqs && seqs.length > 0 && idx > 0) {
                    const start = Math.max(0, idx - 3);
                    recentVoiceLines = seqs.slice(start, idx);
                }
            }

            // Compute recent emotion transition (within last 30s)
            let emotionTransition = null;
            if (this._emotionHistory.length >= 2) {
                const now = Date.now();
                const current = this._emotionHistory[this._emotionHistory.length - 1];
                const previous = this._emotionHistory[this._emotionHistory.length - 2];
                const secsSinceChange = (now - current.timestamp) / 1000;
                if (secsSinceChange <= 30) {
                    emotionTransition = {
                        from: previous.emotion,
                        to: current.emotion,
                        secsAgo: Math.round(secsSinceChange)
                    };
                }
            }

            const state = {
                biometric: bio ? {
                    emotion: bio.dominant_emotion,
                    age: bio.age,
                    gender: bio.dominant_gender
                } : null,
                emotionTransition: emotionTransition,
                labels: [...this.sessionLabels],
                recentVoiceLines: recentVoiceLines,
                currentText: this.currentText,
                audio: audio ? {
                    sweepStart: audio.sweepStart,
                    sweepTrough: audio.sweepTrough,
                    sweepEnd: audio.sweepEnd,
                    baseFreq: audio.baseFreq,
                    bandLabel: this.audioEngine?.currentBandLabel || null,
                    isochronicEnabled: audio.isochronicEnabled,
                    isochronicRate: audio.isochronicRate
                } : null,
                visuals: visuals ? {
                    effects: visuals.effects,
                    mode: visuals.mode
                } : null,
                sdConfig: this.imageGenerator?.sdConfig ? {
                    style: this.imageGenerator.sdConfig.style,
                    description: this.imageGenerator.sdConfig.description,
                    imageAction: this.imageGenerator.sdConfig.imageAction,
                    denoisingStrength: this.imageGenerator.sdConfig.denoisingStrength,
                    cfg: this.imageGenerator.sdConfig.cfg
                } : null,
                layerOpacity: {
                    labels: Math.round(this._layerOpacity.labels * 100) / 100,
                    images: Math.round(this._layerOpacity.images * 100) / 100,
                    visualizer: Math.round(this._layerOpacity.visualizer * 100) / 100,
                    visuals: Math.round(this._layerOpacity.visuals * 100) / 100
                },
                injectedVoiceLines: this.sessionVoiceLines.length > 0
                    ? this.sessionVoiceLines.slice(-3)
                    : [],
                moodRingEnabled: this.moodRingEnabled,
                userResponse: this._userResponse || null,
                interactiveEnabled: !!(this.sessionConfig?.director?.interactive),
                ticksSinceAskUser: this._ticksSinceAskUser,
                elapsedMinutes: this._sessionStartTime
                    ? (Date.now() - this._sessionStartTime) / 60000
                    : 0
            };

            // Consume the user response after gathering (one-shot)
            this._userResponse = null;
            return state;
        },

        /**
         * Find images by tags using intersection (AND logic)
         * Pattern from dialog.js findImageForTags — no character scoping
         * @param {Array<string>} tags - Tag names to intersect
         * @returns {Promise<Object|null>} Random matching data.data object, or null
         * @private
         */
        async _findImagesByTags(tags) {
            if (!tags || !tags.length) return null;
            try {
                // Get tag objects for all requested tags
                const tagObjects = [];
                for (const tagName of tags) {
                    const tag = await page.getTag(tagName.trim(), 'data.data');
                    if (!tag) return null; // Tag doesn't exist — no match possible
                    tagObjects.push(tag);
                }

                // Start with members of the first tag
                const firstMembers = await am7client.members('data.tag', tagObjects[0].objectId, 'data.data', 0, 100);
                if (!firstMembers || !firstMembers.length) return null;

                let candidateIds = new Set(firstMembers.map(m => m.objectId));

                // Intersect with each additional tag's members
                for (let i = 1; i < tagObjects.length; i++) {
                    const members = await am7client.members('data.tag', tagObjects[i].objectId, 'data.data', 0, 100);
                    if (!members || !members.length) return null;
                    const memberIds = new Set(members.map(m => m.objectId));
                    for (const id of candidateIds) {
                        if (!memberIds.has(id)) candidateIds.delete(id);
                    }
                    if (candidateIds.size === 0) return null;
                }

                // Random pick from candidates — use count=1, sort="random()"
                const ids = Array.from(candidateIds);
                const pickedId = ids[Math.floor(Math.random() * ids.length)];

                const q = am7view.viewQuery(am7model.newInstance('data.data'));
                q.field('objectId', pickedId);
                const qr = await page.search(q);
                return (qr && qr.results && qr.results.length) ? qr.results[0] : null;
            } catch (err) {
                console.warn('Magic8App: Tag search failed:', err);
                return null;
            }
        },

        /**
         * Generate image from camera with temporary SD config overrides
         * @param {Object} directive - Directive with generateImage fields
         * @private
         */
        _generateImageFromCamera(directive) {
            const sdKeys = ['description', 'style', 'imageAction', 'denoisingStrength', 'cfg'];
            const origValues = {};

            if (this.imageGenerator.sdConfig) {
                for (const key of sdKeys) {
                    origValues[key] = this.imageGenerator.sdConfig[key];
                    if (directive.generateImage[key] != null) {
                        this.imageGenerator.sdConfig[key] = directive.generateImage[key];
                    }
                }
            }

            this.imageGenerator.captureAndGenerate(
                page.components.camera,
                this.biometricData || null
            ).then(() => {
                if (this.imageGenerator.sdConfig) {
                    for (const key of sdKeys) {
                        if (origValues[key] !== undefined) {
                            this.imageGenerator.sdConfig[key] = origValues[key];
                        }
                    }
                }
            });
        },

        /**
         * Apply a directive from the session director
         * @param {Object} directive - Parsed directive from LLM
         * @private
         */
        _applyDirective(directive) {
            if (!directive) return;
            const cfg = this.sessionConfig;

            // Director can enable audio if it was off
            if (directive.audio && cfg?.audio && this.audioEngine && !this.audioEnabled) {
                this.audioEnabled = true;
                this.audioEngine.setVolume(1.0);
                console.log('Magic8App: Director enabled audio');
                m.redraw();
            }

            // Director can adjust volume
            if (directive.audio?.volume != null && this.audioEngine) {
                this.audioEngine.setVolume(Math.max(0, Math.min(1, directive.audio.volume)));
            }

            // Apply audio changes (preset > band > raw Hz)
            if (directive.audio && cfg?.audio && this.audioEngine && this.audioEnabled) {
                let audioHandled = false;

                // Named preset takes priority
                if (directive.audio.preset) {
                    this.audioEngine.stopBinaural();
                    this.audioEngine.startBinauralFromPreset(directive.audio.preset);
                    audioHandled = true;
                }
                // Named band with optional transitions
                else if (directive.audio.band) {
                    this.audioEngine.stopBinaural();
                    this.audioEngine.startBinauralFromBands({
                        startBand: directive.audio.band,
                        troughBand: directive.audio.troughBand || directive.audio.band,
                        endBand: directive.audio.endBand || directive.audio.band,
                        secondTransition: directive.audio.secondTransition,
                        thirdTransition: directive.audio.thirdTransition,
                        baseFreq: directive.audio.baseFreq || cfg.audio.baseFreq || 200,
                        sweepDurationMin: cfg.audio.sweepDurationMin || 5
                    });
                    audioHandled = true;
                }

                // Legacy raw Hz sweep (backwards compatible)
                if (!audioHandled) {
                    let audioChanged = false;
                    for (const key of ['sweepStart', 'sweepTrough', 'sweepEnd']) {
                        if (directive.audio[key] != null && directive.audio[key] !== cfg.audio[key]) {
                            cfg.audio[key] = directive.audio[key];
                            audioChanged = true;
                        }
                    }
                    if (audioChanged) {
                        this.audioEngine.stopBinaural();
                        this.audioEngine.startBinaural(cfg.audio);
                    }
                }

                if (directive.audio.isochronicEnabled != null &&
                    directive.audio.isochronicEnabled !== cfg.audio.isochronicEnabled) {
                    cfg.audio.isochronicEnabled = directive.audio.isochronicEnabled;
                    if (cfg.audio.isochronicEnabled) {
                        this.audioEngine.startIsochronic(cfg.audio);
                    } else {
                        this.audioEngine.stopIsochronic();
                    }
                }
                // Resolve isochronicBand to isochronicRate
                if (directive.audio.isochronicBand) {
                    const bands = Magic8.AudioEngine.BRAINWAVE_BANDS;
                    const band = bands[directive.audio.isochronicBand];
                    if (band) {
                        directive.audio.isochronicRate = band.mid;
                    }
                }
                if (directive.audio.isochronicRate != null) {
                    cfg.audio.isochronicRate = directive.audio.isochronicRate;
                    if (cfg.audio.isochronicEnabled) {
                        this.audioEngine.stopIsochronic();
                        this.audioEngine.startIsochronic(cfg.audio);
                    }
                }
            }

            // Apply visual changes
            if (directive.visuals && cfg?.visuals) {
                if (directive.visuals.effect) {
                    // Create new object to trigger HypnoCanvas identity check
                    this.sessionConfig.visuals = {
                        ...cfg.visuals,
                        effects: [directive.visuals.effect],
                        mode: 'single'
                    };
                    if (directive.visuals.transitionDuration != null) {
                        this.sessionConfig.visuals.transitionDuration = directive.visuals.transitionDuration;
                    }
                }
            }

            // Apply label changes — inject into text sequence for sequential display
            if (directive.labels) {
                if (directive.labels.add && directive.labels.add.length > 0) {
                    for (const label of directive.labels.add) {
                        // Track in sessionLabels for LLM state reporting
                        if (!this.sessionLabels.includes(label)) {
                            this.sessionLabels.push(label);
                        }
                        // Inject into text sequence at current position
                        if (this.textSequence) {
                            this.textSequence.injectText(label, this.textSequence.currentIndex);
                        }
                    }
                    // Cap sessionLabels at 10 for state reporting
                    if (this.sessionLabels.length > 10) {
                        this.sessionLabels = this.sessionLabels.slice(-10);
                    }
                    // Auto-start text sequence if it was empty (LLM-only mode)
                    if (this.textSequence && !this.textSequence.isPlaying && this.textSequence.sequences.length > 0) {
                        this.textSequence.start();
                    }
                }
                if (directive.labels.remove && directive.labels.remove.length > 0) {
                    this.sessionLabels = this.sessionLabels.filter(
                        l => !directive.labels.remove.includes(l)
                    );
                }
            }

            // Apply image generation — tag search first, camera-based generation as fallback
            if (directive.generateImage && this.imageGenerator && page.components.camera) {
                const tags = directive.generateImage.tags;
                let tagImageHandled = false;

                // Try tag-based image search if tags provided
                if (tags && tags.length > 0 && window.am7imageTokens) {
                    this._findImagesByTags(tags).then(found => {
                        if (found) {
                            const url = window.am7imageTokens.thumbnailUrl
                                ? window.am7imageTokens.thumbnailUrl(found, '512x512')
                                : (g_application_path + '/media/Public/data.data' + encodeURI(found.groupPath || '') + '/' + encodeURIComponent(found.name));
                            if (this.imageGallery) {
                                this.imageGallery.showImmediate(url);
                            }
                            if (this.sessionConfig?.director?.testMode) {
                                this._debugLog('Tag image found: ' + tags.join('+') + ' → ' + found.name, 'pass');
                            }
                        } else {
                            // No tag match — fall back to camera generation
                            if (this.sessionConfig?.director?.testMode) {
                                this._debugLog('No tag match for ' + tags.join('+') + ', generating from camera', 'info');
                            }
                            this._generateImageFromCamera(directive);
                        }
                    });
                    tagImageHandled = true;
                }

                // Direct camera-based generation (no tags)
                if (!tagImageHandled) {
                    this._generateImageFromCamera(directive);
                }
            }

            // Apply voice line injection
            if (directive.voiceLine && this.voiceSequence) {
                const insertAt = this.voiceSequence.currentIndex;
                this.sessionVoiceLines.push(directive.voiceLine);
                if (this.sessionConfig?.director?.testMode) {
                    this._debugLog('Voice inject at idx=' + insertAt +
                        ', sequences=' + this.voiceSequence.sequences.length +
                        ', hashes=' + this.voiceSequence.synthesizedHashes.size, 'voice');
                }
                this.voiceSequence.injectLine(
                    directive.voiceLine,
                    insertAt,
                    this.sessionConfig?.voice?.voiceProfileId
                ).then(ok => {
                    if (ok) {
                        console.log('Magic8App: Injected voice line at index', insertAt, ':', directive.voiceLine);
                        if (this.sessionConfig?.director?.testMode) {
                            this._debugLog('Voice injected OK, total=' + this.voiceSequence.sequences.length, 'pass');
                        }
                        // Auto-start voice sequence if it was empty (LLM-only mode) and audio is on
                        if (!this.voiceSequence.isPlaying && this.voiceSequence.sequences.length > 0 && this.audioEnabled) {
                            this.voiceSequence.start();
                        }
                    } else {
                        console.warn('Magic8App: Failed to inject voice line');
                        if (this.sessionConfig?.director?.testMode) {
                            this._debugLog('Voice inject FAILED', 'fail');
                        }
                    }
                });
            }

            // Apply layer opacity changes (gradual transition)
            if (directive.opacity && typeof directive.opacity === 'object') {
                for (const layer of ['labels', 'images', 'visualizer', 'visuals']) {
                    if (typeof directive.opacity[layer] === 'number') {
                        this._setLayerOpacity(layer, directive.opacity[layer]);
                    }
                }
            }

            // Apply mood suggestion
            if (directive.suggestMood) {
                if (directive.suggestMood.emotion) {
                    this._suggestedEmotion = directive.suggestMood.emotion;
                }
                if (directive.suggestMood.gender) {
                    this._suggestedGender = directive.suggestMood.gender;
                }
                // LLM gender push — override face data percentage
                if (directive.suggestMood.genderPct != null) {
                    this._genderMalePct = Math.max(0, Math.min(100, directive.suggestMood.genderPct));
                }
                // Update mood ring color when enabled
                if (this.moodRingEnabled && this._suggestedEmotion) {
                    const theme = Magic8.BiometricThemer.emotionThemes[this._suggestedEmotion];
                    if (theme) {
                        this.moodRingColor = theme.accent;
                    }
                }
            }

            // Handle askUser directive (interactive mode)
            const hasAskUser = !!(directive.askUser);
            const interactiveCfg = !!(this.sessionConfig?.director?.interactive);
            if (hasAskUser || interactiveCfg) {
                console.log('Magic8: askUser check — directive.askUser:', hasAskUser,
                    ', interactive config:', interactiveCfg,
                    ', pendingQuestion:', !!(this._pendingQuestion));
                if (hasAskUser && !interactiveCfg) {
                    this._debugLog('askUser BLOCKED: interactive mode is OFF in config', 'warn');
                }
                if (hasAskUser && interactiveCfg && this._pendingQuestion) {
                    this._debugLog('askUser BLOCKED: already waiting for response', 'warn');
                }
            }
            if (hasAskUser && interactiveCfg) {
                this._startUserInteraction(directive.askUser.question);
                this._ticksSinceAskUser = 0;
            } else if (interactiveCfg) {
                this._ticksSinceAskUser++;
            }

            if (directive.commentary) {
                console.log('SessionDirector commentary:', directive.commentary);
            }

            m.redraw();
        },

        /**
         * Start interactive user interaction (askUser directive)
         * Displays question on screen, speaks it via voice synth,
         * pauses subsequent voice playback, starts STT, shows input overlay.
         * @param {string} question - The question from the LLM
         * @private
         */
        _startUserInteraction(question) {
            if (this._pendingQuestion) {
                console.log('Magic8: _startUserInteraction SKIPPED — already pending:', this._pendingQuestion.question);
                return;
            }

            this._pendingQuestion = { question };
            this._userInputText = '';
            this._voicePausedForInteraction = false;
            console.log('Magic8: _startUserInteraction SET _pendingQuestion:', question);

            // Pause director tick cycle while waiting
            this.sessionDirector?.pause();
            this._debugLog('askUser: "' + question + '"', 'directive');

            // Display question as on-screen text
            if (this.textSequence) {
                this.textSequence.injectText(question, this.textSequence.currentIndex);
                if (!this.textSequence.isPlaying && this.textSequence.sequences.length > 0) {
                    this.textSequence.start();
                }
            }
            this.currentText = question;

            // Speak question via voice synth, then pause voice sequence
            if (this.voiceSequence && this.audioEnabled) {
                const insertAt = this.voiceSequence.currentIndex;
                this.voiceSequence.injectLine(
                    question,
                    insertAt,
                    this.sessionConfig?.voice?.voiceProfileId
                ).then(ok => {
                    if (!ok || !this._pendingQuestion) return;

                    // Pause the voice sequence so it doesn't auto-advance
                    this.voiceSequence.pause();
                    this._voicePausedForInteraction = true;

                    // Manually play just the question clip
                    const hash = this.voiceSequence.synthesizedHashes.get(insertAt);
                    if (hash) {
                        const source = this.audioEngine?.sources.get(hash);
                        if (source && source.buffer) {
                            source.play();
                        }
                    }
                });
            }

            // Test mode: simulate STT with canned response after delay
            if (this.sessionConfig?.director?.testMode) {
                const cannedResponses = [
                    'I feel calm and centered',
                    'Things are going well, thank you',
                    'I would like to go deeper',
                    'That sounds good to me',
                    'I am feeling relaxed and open'
                ];
                const canned = cannedResponses[Math.floor(Math.random() * cannedResponses.length)];
                this._debugLog('Test mode: will simulate STT → "' + canned + '"', 'info');

                // Simulate typing effect over 2s, then auto-submit after 5s
                let charIdx = 0;
                const typeInterval = setInterval(() => {
                    if (!this._pendingQuestion || charIdx >= canned.length) {
                        clearInterval(typeInterval);
                        return;
                    }
                    charIdx += 2;
                    this._userInputText = canned.substring(0, charIdx);
                    m.redraw();
                }, 80);

                this._responseTimeoutId = setTimeout(() => {
                    clearInterval(typeInterval);
                    this._userInputText = canned;
                    this._submitUserResponse();
                }, 5000);
            } else {
                // Production mode: text input only (STT recording disabled for now)
                // User types response and clicks Send or presses Enter.
                // Idle timeout: after 30s of no typing, auto-submit "No response from user".
                this._debugLog('Interactive: text input active (STT disabled)', 'info');

                this._lastTypingTime = Date.now();
                if (this._interactionIdleTimer) clearInterval(this._interactionIdleTimer);
                this._interactionIdleTimer = setInterval(() => {
                    if (!this._pendingQuestion) {
                        clearInterval(this._interactionIdleTimer);
                        this._interactionIdleTimer = null;
                        return;
                    }
                    const idle = Date.now() - this._lastTypingTime;
                    if (idle >= 30000) {
                        this._debugLog('Interaction idle timeout (30s) — auto-submitting', 'info');
                        this._userInputText = 'No response from user';
                        this._submitUserResponse();
                    }
                }, 2000);

                /* --- STT recording (disabled — re-enable when server-side STT bug is fixed) ---
                // Use server-side STT via WebSocket streaming
                // Capture microphone, stream audio chunks to server, receive transcription
                navigator.mediaDevices.getUserMedia({
                    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
                }).then(mediaStream => {
                    if (!this._pendingQuestion) {
                        mediaStream.getTracks().forEach(t => t.stop());
                        return;
                    }

                    let mediaRecorder;
                    try {
                        mediaRecorder = new MediaRecorder(mediaStream, { mimeType: 'audio/webm;codecs=opus' });
                    } catch (e) {
                        try {
                            mediaRecorder = new MediaRecorder(mediaStream);
                        } catch (e2) {
                            console.warn('Magic8: MediaRecorder not supported:', e2);
                            mediaStream.getTracks().forEach(t => t.stop());
                            return;
                        }
                    }

                    this._sttMediaStream = mediaStream;
                    this._sttMediaRecorder = mediaRecorder;

                    page.audioStream = {
                        onaudioupdate: () => {},
                        onaudiosttupdate: (text) => {
                            if (this._pendingQuestion && text) {
                                this._userInputText = text;
                                m.redraw();
                            }
                        },
                        onaudiouerror: () => {}
                    };

                    const blobToBase64 = async (blob) => {
                        try {
                            const buff = await blob.arrayBuffer();
                            return btoa(new Uint8Array(buff).reduce((data, val) => data + String.fromCharCode(val), ''));
                        } catch (e) {
                            console.error('Magic8: Error converting audio to base64:', e);
                            return null;
                        }
                    };

                    mediaRecorder.ondataavailable = async (event) => {
                        if (event.data && event.data.size > 0 && page.wss) {
                            const b64 = await blobToBase64(event.data);
                            if (b64) {
                                page.wss.send("audio", b64);
                            }
                        }
                    };

                    try {
                        mediaRecorder.start(2000);
                        this._debugLog('STT: microphone recording started (server-side)', 'info');
                    } catch (e) {
                        console.warn('Magic8: Error starting recorder:', e);
                    }
                }).catch(err => {
                    console.warn('Magic8: Microphone access error:', err.message);
                });

                // Auto-submit timeout: max(director interval, 30s)
                const directorInterval = this.sessionDirector?.intervalMs || 60000;
                const timeout = Math.max(directorInterval, 30000);
                this._responseTimeoutId = setTimeout(() => {
                    this._submitUserResponse();
                }, timeout);
                --- end disabled STT --- */
            }

            m.redraw();
        },

        /**
         * Submit the user's response (called by button, Enter key, or timeout)
         * Stores response, stops STT, resumes director with immediate tick
         * @private
         */
        _submitUserResponse() {
            if (!this._pendingQuestion) return;

            // Capture response
            this._userResponse = this._userInputText.trim() || '(no response)';
            this._debugLog('User response: "' + this._userResponse + '"', 'info');

            // Stop server-side STT recording
            if (this._sttMediaRecorder) {
                try { this._sttMediaRecorder.stop(); } catch (e) { /* ignore */ }
                this._sttMediaRecorder = null;
            }
            if (this._sttMediaStream) {
                this._sttMediaStream.getTracks().forEach(t => t.stop());
                this._sttMediaStream = null;
            }
            page.audioStream = undefined;

            // Clear timeouts
            if (this._responseTimeoutId) {
                clearTimeout(this._responseTimeoutId);
                this._responseTimeoutId = null;
            }
            if (this._interactionIdleTimer) {
                clearInterval(this._interactionIdleTimer);
                this._interactionIdleTimer = null;
            }

            // Resume voice sequence if we paused it
            if (this._voicePausedForInteraction && this.voiceSequence) {
                this.voiceSequence.resume();
                this._voicePausedForInteraction = false;
            }

            // Clear UI state
            this._pendingQuestion = null;
            this._userInputText = '';

            // Resume director and force immediate tick
            if (this.sessionDirector) {
                this.sessionDirector.resume();
                this.sessionDirector._tick();
            }

            m.redraw();
        },

        /**
         * Log a message to the on-screen debug console (test mode only)
         * @param {string} text - Message text
         * @param {string} [type='info'] - Message type: info, directive, label, voice, pass, fail, error
         * @private
         */
        _debugLog(text, type) {
            this.debugMessages.push({
                time: Date.now(),
                type: type || 'info',
                text: text
            });
            if (this.debugMessages.length > 300) {
                this.debugMessages = this.debugMessages.slice(-300);
            }
            m.redraw();
        },

        /**
         * Set a layer opacity target and start the gradual transition.
         * @param {string} layer - 'labels' | 'images' | 'visualizer' | 'visuals'
         * @param {number} value - Target opacity (clamped to 0.15–1.0)
         * @private
         */
        _setLayerOpacity(layer, value) {
            const clamped = Math.max(0.15, Math.min(1, value));
            if (Math.abs(clamped - this._layerOpacityTarget[layer]) < 0.01) return;

            this._layerOpacityStart[layer] = this._layerOpacity[layer];
            this._layerOpacityTarget[layer] = clamped;
            this._layerTransitionStart[layer] = Date.now();
            this._startOpacityAnimation();
        },

        /**
         * Start the rAF loop that gradually transitions layer opacities.
         * @private
         */
        _startOpacityAnimation() {
            if (this._opacityRafId) return;
            this._opacityRafId = requestAnimationFrame(() => this._animateOpacity());
        },

        /**
         * rAF tick: ease each transitioning layer toward its target.
         * @private
         */
        _animateOpacity() {
            const now = Date.now();
            let anyActive = false;

            for (const layer of ['labels', 'images', 'visualizer', 'visuals']) {
                const startTime = this._layerTransitionStart[layer];
                if (startTime == null) continue;

                const progress = Math.min(1, (now - startTime) / this._opacityTransitionMs);
                // Ease in-out quad
                const eased = progress < 0.5
                    ? 2 * progress * progress
                    : 1 - Math.pow(-2 * progress + 2, 2) / 2;

                this._layerOpacity[layer] = this._layerOpacityStart[layer] +
                    (this._layerOpacityTarget[layer] - this._layerOpacityStart[layer]) * eased;

                if (progress >= 1) {
                    this._layerOpacity[layer] = this._layerOpacityTarget[layer];
                    delete this._layerTransitionStart[layer];
                } else {
                    anyActive = true;
                }
            }

            m.redraw();

            if (anyActive) {
                this._opacityRafId = requestAnimationFrame(() => this._animateOpacity());
            } else {
                this._opacityRafId = null;
            }
        },

        /**
         * Generate biometric clue strings
         * @returns {Array<string>}
         * @private
         */
        _getBiometricClues() {
            const data = this.biometricData;
            if (!data) return [];

            const clues = [];

            if (data.dominant_emotion) {
                clues.push(`A state of ${data.dominant_emotion}.`);
            }
            if (data.age) {
                clues.push(`${data.age} years of experience.`);
            }
            if (data.dominant_gender) {
                clues.push(`An expression of ${data.dominant_gender.toLowerCase()} energy.`);
            }

            return clues;
        }
    };

    // Register as a page view
    if (typeof page !== 'undefined') {
        page.views = page.views || {};
        page.views.magic8 = Magic8App;
    }

    // Export
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = Magic8App;
    }
    if (typeof window !== 'undefined') {
        window.Magic8 = window.Magic8 || {};
        window.Magic8.App = Magic8App;
    }

}());
