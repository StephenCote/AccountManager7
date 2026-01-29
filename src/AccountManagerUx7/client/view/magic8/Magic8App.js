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

        // Ambient breathing state
        ambientOpacity: 0.15,
        _breatheTransitionMs: 120000,
        _breatheTimeout: null,

        // Intervals
        biometricInterval: null,
        imageCycleInterval: null,
        imageGenInterval: null,

        // Lifecycle
        async oninit(vnode) {
            const sessionConfigId = vnode.attrs.sessionConfigId;

            // Check for chat context handoff
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

                let obj = am7model.newPrimitive("data.data");
                obj.name = config.name || 'Magic8 Session';
                obj.contentType = "application/json";
                obj.groupId = dir.id;
                obj.groupPath = dir.path;
                obj.dataBytesStore = uwm.base64Encode(JSON.stringify(config));
                let saved = await page.createObject(obj);

                if (saved && saved.objectId) {
                    console.log('Magic8App: Session config saved:', saved.objectId);
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

            if (cfg.audio?.binauralEnabled) {
                this.audioEngine.startBinaural(cfg.audio);
                this.audioEnabled = true;
            }

            // Initialize biometric themer
            if (cfg.biometrics?.enabled) {
                this.biometricThemer = new Magic8.BiometricThemer();

                if (cfg.biometrics.smoothingFactor) {
                    this.biometricThemer.setSmoothingFactor(cfg.biometrics.smoothingFactor);
                }

                this.biometricThemer.onThemeChange = (theme) => {
                    this.currentTheme = theme;
                    m.redraw();
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

            if (cfg.images?.baseGroups?.length > 0) {
                console.log('Magic8App: Loading base images from groups:', cfg.images.baseGroups);
                const count = await this.imageGallery.loadBaseImages(cfg.images.baseGroups);
                console.log('Magic8App: Loaded', count, 'base images');
            } else {
                console.warn('Magic8App: No base image groups configured');
            }

            this.imageGallery.onImageChange = (img) => {
                // Crossfade: move current to prev, set new as current
                this.prevImage = this.currentImage;
                this.currentImage = img;
                this.imageOpacity = 0;
                m.redraw();

                // Fade in new image
                requestAnimationFrame(() => {
                    this.imageOpacity = 1;
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
                await this.imageGenerator.loadConfig();

                this.imageGenerator.onImageGenerated = (img) => {
                    this.imageGallery.spliceGeneratedImage(img);
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
                    this.voiceSequence.onSynthesisProgress = (done, total) => {
                        console.log(`Magic8App: Voice synthesis ${done}/${total}`);
                    };
                    await this.voiceSequence.synthesizeAll(cfg.voice.voiceProfileId);

                    // Connect line change to overlay text display
                    this.voiceSequence.onLineChange = (text) => {
                        this.currentText = text;
                        m.redraw();
                    };

                    this.voiceSequence.start();
                    console.log('Magic8App: Voice sequence playback started');
                }
            }

            // Initialize recording
            if (cfg.recording?.enabled) {
                this.sessionRecorder = new Magic8.SessionRecorder();
                if (cfg.recording.autoStart) {
                    // Delay to allow canvas to be rendered
                    setTimeout(() => this._startRecording(), 1000);
                }
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

            const devices = page.components.camera.devices();
            console.log('Magic8App: Camera devices found:', devices.length);

            if (!devices.length) {
                console.log('Magic8App: Initializing camera and finding devices...');
                page.components.camera.initializeAndFindDevices(captureCallback);
            } else {
                console.log('Magic8App: Starting capture on existing device');
                page.components.camera.startCapture(captureCallback);
            }
        },

        _handleBiometricData(imageData) {
            console.log('Magic8App: Biometric capture data:', imageData);
            if (imageData && imageData.results?.length) {
                const data = imageData.results[0];
                console.log('Magic8App: Face analysis result:', data);
                this.biometricData = data;

                if (this.biometricThemer) {
                    this.biometricThemer.updateFromBiometrics(data);
                }
            }
        },

        // Image generation from camera
        _startImageGeneration() {
            const interval = Math.max(this.sessionConfig?.imageGeneration?.captureInterval || 120000, 60000);
            console.log('Magic8App: Image generation interval:', interval, 'ms');

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
                // Random upper bound between 0.3 and 0.6
                this.ambientOpacity = 0.3 + Math.random() * 0.3;
            } else {
                this.ambientOpacity = 0.15;
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

            const canvas = this.containerElement?.querySelector('.hypno-canvas');
            if (!canvas) {
                console.warn('Magic8App: Canvas not found for recording');
                return;
            }

            const audioDestination = this.audioEngine?.getDestination();

            try {
                await this.sessionRecorder.startRecording(canvas, audioDestination, {
                    maxDurationMin: this.sessionConfig?.recording?.maxDurationMin || 30
                });
                this.isRecording = true;
                m.redraw();
            } catch (err) {
                console.error('Magic8App: Failed to start recording:', err);
            }
        },

        async _stopRecording() {
            if (!this.sessionRecorder || !this.isRecording) return;

            try {
                const blob = await this.sessionRecorder.stopRecording();
                await this.sessionRecorder.saveToServer(blob, this.sessionConfig);
                this.isRecording = false;
                m.redraw();
            } catch (err) {
                console.error('Magic8App: Failed to stop recording:', err);
                this.isRecording = false;
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
                this._stopBreathing();
            } else if (this.phase === 'paused') {
                this.phase = 'running';
                this.textSequence?.resume();
                this.voiceSequence?.resume();
                this.imageGallery?.start();
                this._startBreathing();
            }
            m.redraw();
        },

        _toggleAudio() {
            this.audioEnabled = !this.audioEnabled;

            if (this.audioEnabled) {
                if (this.sessionConfig?.audio?.binauralEnabled) {
                    this.audioEngine?.startBinaural(this.sessionConfig.audio);
                }
            } else {
                this.audioEngine?.stopBinaural();
            }

            m.redraw();
        },

        _toggleFullscreen() {
            if (this.containerElement) {
                this.fullscreenManager.toggleFullscreen(this.containerElement);
            }
        },

        // Handle exit
        _handleExit() {
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

        // Cleanup (guarded against double-call from _handleExit + onremove)
        _cleanup() {
            if (this._cleaned) return;
            this._cleaned = true;

            clearInterval(this.biometricInterval);
            clearInterval(this.imageCycleInterval);
            clearInterval(this.imageGenInterval);
            this._stopBreathing();

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

            // Stop recording
            if (this.isRecording && this.sessionRecorder) {
                this._stopRecording();
            }
            this.sessionRecorder?.dispose();

            // Stop biometric themer
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
                // Background image container - ambient breathing controls overall opacity
                m('.magic8-bg-container.absolute.inset-0', {
                    style: {
                        opacity: this.ambientOpacity,
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
                    this.currentImage && m('.magic8-bg-current.absolute.inset-0', {
                        style: {
                            backgroundImage: `url("${this.currentImage.url}")`,
                            backgroundSize: 'cover',
                            backgroundPosition: 'center',
                            opacity: this.imageOpacity,
                            transition: `opacity ${(this.sessionConfig?.images?.crossfadeDuration || 1000)}ms ease-in-out`
                        }
                    })
                ]),

                // Particle canvas layer
                m(Magic8.HypnoCanvas, {
                    theme: this.currentTheme,
                    class: 'absolute inset-0 z-5 pointer-events-none'
                }),

                // Dark overlay for text readability
                m('.absolute.inset-0.z-10.bg-black', {
                    style: { opacity: 0.3 }
                }),

                // Animated floating text labels, styled by biometric theme
                m(Magic8.BiometricOverlay, {
                    text: this.currentText,
                    theme: this.currentTheme
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

                // Control panel
                m(Magic8.ControlPanel, {
                    config: this.sessionConfig,
                    state: {
                        isRecording: this.isRecording,
                        audioEnabled: this.audioEnabled,
                        isFullscreen: this.isFullscreen
                    },
                    autoHideDelay: this.sessionConfig?.display?.controlsAutoHide || 5000,
                    onToggleRecording: () => this._toggleRecording(),
                    onToggleAudio: () => this._toggleAudio(),
                    onToggleFullscreen: () => this._toggleFullscreen(),
                    onOpenConfig: () => {
                        this.phase = 'config';
                        m.redraw();
                    },
                    onExit: () => this._handleExit()
                }),

                // Hidden camera view
                page.components.camera && this.sessionConfig?.biometrics?.enabled
                    ? page.components.camera.videoView()
                    : null,

                // Dialog support
                page.components.dialog ? page.components.dialog.loadDialog() : null
            ]);
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
