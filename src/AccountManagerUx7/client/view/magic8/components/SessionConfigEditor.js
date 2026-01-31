/**
 * SessionConfigEditor - Configuration UI for Magic8 sessions
 * Mithril.js component for setting up session parameters
 */
const SessionConfigEditor = {
    oninit(vnode) {
        this.config = vnode.attrs.config || this._getDefaultConfig();
        this.sdConfigs = [];
        this.textSources = [];
        this.savedSessions = [];
        this.selectedImageGroups = [];
        this.isLoading = true;
        this.createNewSdConfig = false;
        this.createNewTextSource = false;
        this.newTextName = '';
        this.newTextContent = '';
        this.newTextSourceType = 'note';
        this.voiceSources = [];
        this.voiceProfiles = [];
        this.createNewVoiceSource = false;
        this.newVoiceName = '';
        this.newVoiceContent = '';
        this.newVoiceSourceType = 'note';
        this.isSaving = false;
        this.loadingSession = false;
        this.directorTestRunning = false;
        this.directorTestResults = null;
        this.selectedSdDetails = null;
        this.loadingSdDetails = false;
        this.fetchingRandomSd = false;
        this.randomSdExtras = null;  // style-specific fields from randomImageConfig

        this._loadOptions();
    },

    /**
     * Get default session configuration
     * @returns {Object}
     * @private
     */
    _getDefaultConfig() {
        return {
            name: 'New Session',
            display: {
                fullscreen: false,
                showControls: true,
                controlsAutoHide: 5000
            },
            biometrics: {
                enabled: true,
                updateInterval: 500,
                smoothingFactor: 0.1
            },
            audio: {
                binauralEnabled: true,
                baseFreq: 440,
                minBeat: 2,
                maxBeat: 8,
                sweepStart: 8,
                sweepTrough: 2,
                sweepEnd: 8,
                sweepDurationMin: 5,
                fadeInSec: 2,
                fadeOutSec: 3,
                panEnabled: false,
                panSpeed: 0.1,
                panDepth: 0.8,
                isochronicEnabled: false,
                isochronicFreq: 200,
                isochronicRate: 6,
                isochronicVolume: 0.15,
                visualizerEnabled: false,
                visualizerOpacity: 0.35,
                visualizerMode: 2,
                visualizerGradient: 'prism'
            },
            images: {
                baseGroups: [],
                cycleInterval: 5000,
                crossfadeDuration: 1000,
                includeGenerated: true
            },
            visuals: {
                effects: ['particles'],
                mode: 'single',
                transitionInterval: 30000,
                transitionDuration: 2000,
                particleCount: 100,
                spiralSpeed: 0.01,
                mandalaLayers: 4,
                tunnelRings: 12
            },
            imageGeneration: {
                enabled: false,
                sdConfigId: null,
                captureInterval: 120000,
                maxGeneratedImages: 20,
                sdInline: {
                    model: '',
                    refinerModel: '',
                    style: 'art',
                    description: 'ethereal dreamlike portrait, soft lighting, mystical atmosphere',
                    imageAction: 'posing in a surreal setting',
                    denoisingStrength: 0.65,
                    steps: 30,
                    cfg: 7,
                    sampler: 'dpmpp_2m',
                    width: 512,
                    height: 512
                }
            },
            text: {
                enabled: false,
                sourceObjectId: null,
                sourceType: 'note',
                displayDuration: 5000,
                loop: true
            },
            voice: {
                enabled: false,
                sourceObjectId: null,
                sourceType: 'note',
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
                command: '',
                intervalMs: 60000,
                testMode: false
            }
        };
    },

    /**
     * Load available options from server
     * @private
     */
    async _loadOptions() {
        try {
            // Ensure Magic8 group paths exist
            await page.makePath("auth.group", "data", "~/Magic8/SDConfigs");
            await page.makePath("auth.group", "data", "~/Magic8/TextSequences");
            await page.makePath("auth.group", "data", "~/Magic8/Configs");
            await page.makePath("auth.group", "data", "~/Magic8/VoiceSequences");

            // Load saved sessions from ~/Magic8/Configs
            this.savedSessions = await this._listDataInGroup('~/Magic8/Configs');

            // Load SD configs from ~/Magic8/SDConfigs
            this.sdConfigs = await this._listDataInGroup('~/Magic8/SDConfigs');

            // Load text sources from notes and data objects
            const notes = await this._listNotesInGroup('~/Magic8/TextSequences');
            const textObjects = await this._listDataInGroup('~/Magic8/TextSequences');
            this.textSources = [
                ...notes.map(n => ({ ...n, sourceType: 'note' })),
                ...textObjects.map(t => ({ ...t, sourceType: 'data' }))
            ];

            // Load voice sources from ~/Magic8/VoiceSequences
            const voiceNotes = await this._listNotesInGroup('~/Magic8/VoiceSequences');
            const voiceData = await this._listDataInGroup('~/Magic8/VoiceSequences');
            this.voiceSources = [
                ...voiceNotes.map(n => ({ ...n, sourceType: 'note' })),
                ...voiceData.map(t => ({ ...t, sourceType: 'data' }))
            ];

            // Load voice profiles
            this.voiceProfiles = await this._listVoiceProfiles();

            // Pre-populate selected image groups from blender working set
            if (page.components.dnd && page.components.dnd.workingSet) {
                const groups = page.components.dnd.workingSet.filter(
                    w => w[am7model.jsonModelKey] === 'auth.group'
                );
                for (const grp of groups) {
                    if (this.config.images.baseGroups.indexOf(grp.objectId) === -1) {
                        this.config.images.baseGroups.push(grp.objectId);
                    }
                    if (!this.selectedImageGroups.find(g => g.objectId === grp.objectId)) {
                        this.selectedImageGroups.push({ id: grp.id, objectId: grp.objectId, name: grp.name });
                    }
                }
            }

            this.isLoading = false;
            m.redraw();
        } catch (err) {
            console.error('SessionConfigEditor: Failed to load options:', err);
            this.isLoading = false;
            m.redraw();
        }
    },

    /**
     * List data objects in a group path
     * @param {string} groupPath - Group path to list from
     * @returns {Promise<Array>}
     * @private
     */
    async _listDataInGroup(groupPath) {
        try {
            let dir = await page.findObject("auth.group", "DATA", groupPath);
            if (!dir || !dir.objectId) return [];
            return await am7client.list("data.data", dir.objectId, null, 0, 0) || [];
        } catch (err) {
            return [];
        }
    },

    /**
     * List notes in a group path
     * @param {string} groupPath - Group path to list from
     * @returns {Promise<Array>}
     * @private
     */
    async _listNotesInGroup(groupPath) {
        try {
            let dir = await page.findObject("auth.group", "DATA", groupPath);
            if (!dir || !dir.objectId) return [];
            return await am7client.list("data.note", dir.objectId, null, 0, 0) || [];
        } catch (err) {
            return [];
        }
    },

    /**
     * List available voice profiles (identity.voice objects)
     * @returns {Promise<Array>}
     * @private
     */
    async _listVoiceProfiles() {
        try {
            let q = am7view.viewQuery(am7model.newInstance("identity.voice"));
            let qr = await page.search(q);
            return qr?.results || [];
        } catch (err) {
            console.warn('SessionConfigEditor: Failed to list voice profiles:', err);
            return [];
        }
    },

    /**
     * Load full SD config details for preview
     * @param {string} objectId - SD config objectId
     * @private
     */
    async _loadSdConfigDetails(objectId) {
        if (!objectId) {
            this.selectedSdDetails = null;
            return;
        }
        this.loadingSdDetails = true;
        m.redraw();
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("objectId", objectId);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length) {
                am7model.updateListModel(qr.results);
                const obj = qr.results[0];
                if (obj.dataBytesStore && obj.dataBytesStore.length) {
                    this.selectedSdDetails = JSON.parse(uwm.base64Decode(obj.dataBytesStore));
                } else {
                    this.selectedSdDetails = null;
                }
            } else {
                this.selectedSdDetails = null;
            }
        } catch (err) {
            console.warn('SessionConfigEditor: Failed to load SD config details:', err);
            this.selectedSdDetails = null;
        }
        this.loadingSdDetails = false;
        m.redraw();
    },

    /**
     * Fetch a randomized SD config from the server and populate inline fields.
     * Style-specific fields (camera, artStyle, etc.) are stored in randomSdExtras
     * and passed through to the generation config.
     * @private
     */
    async _fetchRandomSdConfig() {
        this.fetchingRandomSd = true;
        m.redraw();
        try {
            const cfg = await m.request({
                method: 'GET',
                url: `${g_application_path}/rest/olio/randomImageConfig`,
                withCredentials: true
            });
            if (cfg) {
                const sd = this.config.imageGeneration.sdInline;
                // Map core fields into inline config
                if (cfg.style) sd.style = cfg.style;
                if (cfg.description) sd.description = cfg.description;
                if (cfg.imageAction) sd.imageAction = cfg.imageAction;
                if (cfg.model) sd.model = cfg.model;
                if (cfg.refinerModel) sd.refinerModel = cfg.refinerModel;
                if (cfg.sampler) sd.sampler = cfg.sampler;
                if (cfg.steps) sd.steps = cfg.steps;
                if (cfg.cfg) sd.cfg = cfg.cfg;
                if (cfg.denoisingStrength) sd.denoisingStrength = cfg.denoisingStrength;
                if (cfg.width) sd.width = cfg.width;
                if (cfg.height) sd.height = cfg.height;

                // Collect style-specific fields for preview and passthrough
                const coreKeys = new Set([
                    'schema', 'objectId', 'id', 'ownerId', 'groupId', 'groupPath', 'organizationId',
                    'name', 'description', 'imageAction', 'model', 'refinerModel', 'sampler',
                    'scheduler', 'refinerSampler', 'refinerScheduler', 'steps', 'refinerSteps',
                    'cfg', 'refinerCfg', 'seed', 'width', 'height', 'denoisingStrength',
                    'style', 'imageCount', 'hires', 'refinerMethod', 'refinerUpscaleMethod',
                    'refinerUpscale', 'refinerControlPercentage', 'bodyStyle', 'customPrompt',
                    'shared', 'referenceImageId', 'imageSetting', 'contentType', 'compressionType'
                ]);
                const extras = {};
                for (const [key, value] of Object.entries(cfg)) {
                    if (!coreKeys.has(key) && value != null && value !== '') {
                        extras[key] = value;
                    }
                }
                this.randomSdExtras = Object.keys(extras).length > 0 ? extras : null;

                // Merge style-specific fields into sdInline so they pass through to generation
                if (this.randomSdExtras) {
                    Object.assign(sd, this.randomSdExtras);
                }

                console.log('SessionConfigEditor: Fetched random SD config, style:', cfg.style,
                    'extras:', this.randomSdExtras);
            }
        } catch (err) {
            console.warn('SessionConfigEditor: Failed to fetch random SD config:', err);
        }
        this.fetchingRandomSd = false;
        m.redraw();
    },

    /**
     * Save a new voice source object and select it
     * @private
     */
    async _saveNewVoiceSource() {
        if (this.isSaving || !this.newVoiceName.trim() || !this.newVoiceContent.trim()) return;
        this.isSaving = true;
        m.redraw();

        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/VoiceSequences");
            if (!dir || !dir.objectId) {
                dir = await page.makePath("auth.group", "data", "~/Magic8/VoiceSequences");
            }

            let saved;
            if (this.newVoiceSourceType === 'note') {
                let obj = am7model.newPrimitive("data.note");
                obj.name = this.newVoiceName.trim();
                obj.groupId = dir.id;
                obj.groupPath = dir.path;
                obj.text = this.newVoiceContent.trim();
                saved = await page.createObject(obj);
            } else {
                let obj = am7model.newPrimitive("data.data");
                obj.name = this.newVoiceName.trim();
                obj.contentType = "text/plain";
                obj.groupId = dir.id;
                obj.groupPath = dir.path;
                obj.dataBytesStore = uwm.base64Encode(this.newVoiceContent.trim());
                saved = await page.createObject(obj);
            }

            if (saved && saved.objectId) {
                this.voiceSources.push({ ...saved, sourceType: this.newVoiceSourceType });
                this.config.voice.sourceObjectId = saved.objectId;
                this.config.voice.sourceType = this.newVoiceSourceType;
                this.createNewVoiceSource = false;
                this.newVoiceName = '';
                this.newVoiceContent = '';
            }
        } catch (err) {
            console.error('SessionConfigEditor: Failed to save voice source:', err);
        }

        this.isSaving = false;
        m.redraw();
    },

    /**
     * Load a previously saved session config by objectId
     * @param {string} objectId
     * @private
     */
    async _loadSavedSession(objectId) {
        if (!objectId) return;
        try {
            // Fetch full object with dataBytesStore
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("objectId", objectId);
            let qr = await page.search(q);
            if (!qr || !qr.results || !qr.results.length) return;
            am7model.updateListModel(qr.results);
            const obj = qr.results[0];
            if (!obj.dataBytesStore || !obj.dataBytesStore.length) {
                console.warn('SessionConfigEditor: Saved session has no data');
                return;
            }
            let decoded = uwm.base64Decode(obj.dataBytesStore);
            // JSON.stringify produces single-line output, so any literal control
            // characters in the decoded string are encode/decode corruption.
            // Re-escape them so JSON.parse doesn't choke.
            decoded = decoded.replace(/[\x00-\x1f]/g, (ch) => {
                if (ch === '\n') return '\\n';
                if (ch === '\r') return '\\r';
                if (ch === '\t') return '\\t';
                return '';
            });
            const loaded = JSON.parse(decoded);
            // Merge with defaults to ensure all keys exist
            const defaults = this._getDefaultConfig();
            this.config = Object.assign({}, defaults, loaded);
            this.config.display = Object.assign({}, defaults.display, loaded.display || {});
            this.config.biometrics = Object.assign({}, defaults.biometrics, loaded.biometrics || {});
            this.config.audio = Object.assign({}, defaults.audio, loaded.audio || {});
            this.config.images = Object.assign({}, defaults.images, loaded.images || {});
            this.config.visuals = Object.assign({}, defaults.visuals, loaded.visuals || {});
            if (loaded.visuals?.effects) {
                this.config.visuals.effects = [...loaded.visuals.effects];
            }
            this.config.imageGeneration = Object.assign({}, defaults.imageGeneration, loaded.imageGeneration || {});
            if (loaded.imageGeneration?.sdInline) {
                this.config.imageGeneration.sdInline = Object.assign({}, defaults.imageGeneration.sdInline, loaded.imageGeneration.sdInline);
            }
            // Restore SD config UI mode: if no saved config ID but inline values were customized, show inline editor
            if (!this.config.imageGeneration.sdConfigId && loaded.imageGeneration?.sdInline) {
                this.createNewSdConfig = true;
                this.selectedSdDetails = null;
                // Restore style-specific extras preview from saved sdInline
                const defaultKeys = new Set(Object.keys(defaults.imageGeneration.sdInline));
                const extras = {};
                for (const [key, value] of Object.entries(this.config.imageGeneration.sdInline)) {
                    if (!defaultKeys.has(key) && value != null && value !== '') {
                        extras[key] = value;
                    }
                }
                this.randomSdExtras = Object.keys(extras).length > 0 ? extras : null;
            } else {
                this.createNewSdConfig = false;
                // Load details for the selected config
                if (this.config.imageGeneration.sdConfigId) {
                    this._loadSdConfigDetails(this.config.imageGeneration.sdConfigId);
                }
            }
            this.config.text = Object.assign({}, defaults.text, loaded.text || {});
            this.config.voice = Object.assign({}, defaults.voice, loaded.voice || {});
            this.config.recording = Object.assign({}, defaults.recording, loaded.recording || {});
            this.config.director = Object.assign({}, defaults.director, loaded.director || {});

            // Rebuild selectedImageGroups from loaded baseGroups
            this.selectedImageGroups = [];
            if (this.config.images.baseGroups?.length > 0) {
                for (const oid of this.config.images.baseGroups) {
                    // Try to find the group name from blender or just show the id
                    let name = oid;
                    if (page.components.dnd && page.components.dnd.workingSet) {
                        const found = page.components.dnd.workingSet.find(w => w.objectId === oid);
                        if (found) name = found.name;
                    }
                    this.selectedImageGroups.push({ objectId: oid, name: name });
                }
            }

            m.redraw();
        } catch (err) {
            console.error('SessionConfigEditor: Failed to load saved session:', err);
        }
    },

    /**
     * Save inline SD config as a named object and select it
     * @private
     */
    async _saveNewSdConfig() {
        if (this.isSaving) return;
        const sdInline = this.config.imageGeneration.sdInline;
        if (!sdInline) return;

        this.isSaving = true;
        m.redraw();

        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/SDConfigs");
            if (!dir || !dir.objectId) {
                dir = await page.makePath("auth.group", "data", "~/Magic8/SDConfigs");
            }

            let obj = am7model.newPrimitive("data.data");
            obj.name = this.config.name + ' SD Config';
            obj.contentType = "application/json";
            obj.groupId = dir.id;
            obj.groupPath = dir.path;
            obj.dataBytesStore = uwm.base64Encode(JSON.stringify(sdInline));
            let saved = await page.createObject(obj);

            if (saved && saved.objectId) {
                this.sdConfigs.push(saved);
                this.config.imageGeneration.sdConfigId = saved.objectId;
                this.createNewSdConfig = false;
            }
        } catch (err) {
            console.error('SessionConfigEditor: Failed to save SD config:', err);
        }

        this.isSaving = false;
        m.redraw();
    },

    /**
     * Save a new text source object and select it
     * @private
     */
    async _saveNewTextSource() {
        if (this.isSaving || !this.newTextName.trim() || !this.newTextContent.trim()) return;
        this.isSaving = true;
        m.redraw();

        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/TextSequences");
            if (!dir || !dir.objectId) {
                dir = await page.makePath("auth.group", "data", "~/Magic8/TextSequences");
            }

            let saved;
            if (this.newTextSourceType === 'note') {
                let obj = am7model.newPrimitive("data.note");
                obj.name = this.newTextName.trim();
                obj.groupId = dir.id;
                obj.groupPath = dir.path;
                obj.text = this.newTextContent.trim();
                saved = await page.createObject(obj);
            } else {
                let obj = am7model.newPrimitive("data.data");
                obj.name = this.newTextName.trim();
                obj.contentType = "text/plain";
                obj.groupId = dir.id;
                obj.groupPath = dir.path;
                obj.dataBytesStore = uwm.base64Encode(this.newTextContent.trim());
                saved = await page.createObject(obj);
            }

            if (saved && saved.objectId) {
                // Add to list and select it
                this.textSources.push({ ...saved, sourceType: this.newTextSourceType });
                this.config.text.sourceObjectId = saved.objectId;
                this.config.text.sourceType = this.newTextSourceType;
                // Switch back to select mode
                this.createNewTextSource = false;
                this.newTextName = '';
                this.newTextContent = '';
            }
        } catch (err) {
            console.error('SessionConfigEditor: Failed to save text source:', err);
        }

        this.isSaving = false;
        m.redraw();
    },

    view(vnode) {
        const { onSave, onCancel } = vnode.attrs;

        if (this.isLoading) {
            return m('.session-config-editor', {
                class: 'flex items-center justify-center h-screen bg-gray-900 text-white'
            }, [
                m('.text-center', [
                    m('.animate-spin.w-8.h-8.border-4.border-blue-500.border-t-transparent.rounded-full.mx-auto.mb-4'),
                    m('p', 'Loading configuration options...')
                ])
            ]);
        }

        return m('.session-config-editor', {
            class: 'min-h-screen bg-gray-900 text-white p-4 sm:p-6 overflow-auto',
            style: {
                paddingBottom: 'max(24px, env(safe-area-inset-bottom))'
            }
        }, [
            m('.max-w-2xl.mx-auto', [
                // Header
                m('.text-center.mb-8', [
                    m('h1.text-3xl.font-bold.mb-2', 'Magic8 Session'),
                    m('p.text-gray-400', 'Configure your immersive experience')
                ]),

                // Load Saved Session
                this.savedSessions.length > 0 && m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-3', 'Load Saved Session'),
                    m('select.w-full.p-3.bg-gray-700.rounded-lg.text-white', {
                        disabled: this.loadingSession,
                        onchange: async (e) => {
                            const objectId = e.target.value;
                            if (!objectId) return;
                            this.loadingSession = true;
                            m.redraw();
                            await this._loadSavedSession(objectId);
                            this.loadingSession = false;
                            m.redraw();
                        }
                    }, [
                        m('option', { value: '', selected: true }, this.loadingSession ? 'Loading...' : '-- Select a saved session --'),
                        ...this.savedSessions.map(s =>
                            m('option', { value: s.objectId }, s.name)
                        )
                    ])
                ]),

                // Session Name
                m('.config-section.mb-6', [
                    m('label.block.text-sm.font-medium.mb-2', 'Session Name'),
                    m('input.w-full.p-3.bg-gray-800.rounded-lg.text-white', {
                        type: 'text',
                        value: this.config.name,
                        oninput: (e) => this.config.name = e.target.value
                    })
                ]),

                // Biometrics Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Biometric Adaptation'),
                    m('label.flex.items-center.gap-3.cursor-pointer', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.biometrics.enabled,
                            onchange: (e) => this.config.biometrics.enabled = e.target.checked
                        }),
                        m('span', 'Enable facial analysis color adaptation')
                    ])
                ]),

                // Audio Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Audio'),

                    // Binaural beats
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.audio.binauralEnabled,
                            onchange: (e) => this.config.audio.binauralEnabled = e.target.checked
                        }),
                        m('span', 'Enable binaural beats')
                    ]),
                    this.config.audio.binauralEnabled && m('.ml-8.space-y-4.mb-6', [
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Base Frequency'),
                            m('input.flex-1', {
                                type: 'range', min: 200, max: 600,
                                value: this.config.audio.baseFreq,
                                oninput: (e) => this.config.audio.baseFreq = parseInt(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.baseFreq} Hz`)
                        ]),
                        m('.text-sm.text-gray-400.mb-2', 'Sweep Shape (beat frequency over each cycle)'),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Sweep Start'),
                            m('input.flex-1', {
                                type: 'range', min: 1, max: 15, step: 0.5,
                                value: this.config.audio.sweepStart,
                                oninput: (e) => this.config.audio.sweepStart = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.sweepStart} Hz`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Sweep Trough'),
                            m('input.flex-1', {
                                type: 'range', min: 1, max: 15, step: 0.5,
                                value: this.config.audio.sweepTrough,
                                oninput: (e) => this.config.audio.sweepTrough = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.sweepTrough} Hz`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Sweep End'),
                            m('input.flex-1', {
                                type: 'range', min: 1, max: 15, step: 0.5,
                                value: this.config.audio.sweepEnd,
                                oninput: (e) => this.config.audio.sweepEnd = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.sweepEnd} Hz`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Sweep Duration'),
                            m('input.flex-1', {
                                type: 'range', min: 1, max: 20, step: 1,
                                value: this.config.audio.sweepDurationMin,
                                oninput: (e) => this.config.audio.sweepDurationMin = parseInt(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.sweepDurationMin} min`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Fade In'),
                            m('input.flex-1', {
                                type: 'range', min: 0.5, max: 10, step: 0.5,
                                value: this.config.audio.fadeInSec,
                                oninput: (e) => this.config.audio.fadeInSec = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.fadeInSec}s`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Fade Out'),
                            m('input.flex-1', {
                                type: 'range', min: 0.5, max: 10, step: 0.5,
                                value: this.config.audio.fadeOutSec,
                                oninput: (e) => this.config.audio.fadeOutSec = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.fadeOutSec}s`)
                        ])
                    ]),

                    // Q-Sound Panning
                    this.config.audio.binauralEnabled && [
                        m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                            m('input.w-5.h-5.rounded', {
                                type: 'checkbox',
                                checked: this.config.audio.panEnabled,
                                onchange: (e) => this.config.audio.panEnabled = e.target.checked
                            }),
                            m('span', 'Q-Sound spatial panning')
                        ]),
                        this.config.audio.panEnabled && m('.ml-8.space-y-4.mb-6', [
                            m('.flex.items-center.gap-4', [
                                m('label.w-36', 'Pan Speed'),
                                m('input.flex-1', {
                                    type: 'range', min: 0.02, max: 0.5, step: 0.02,
                                    value: this.config.audio.panSpeed,
                                    oninput: (e) => this.config.audio.panSpeed = parseFloat(e.target.value)
                                }),
                                m('span.w-16.text-right', this.config.audio.panSpeed.toFixed(2))
                            ]),
                            m('.flex.items-center.gap-4', [
                                m('label.w-36', 'Pan Depth'),
                                m('input.flex-1', {
                                    type: 'range', min: 0.1, max: 1.0, step: 0.1,
                                    value: this.config.audio.panDepth,
                                    oninput: (e) => this.config.audio.panDepth = parseFloat(e.target.value)
                                }),
                                m('span.w-16.text-right', this.config.audio.panDepth.toFixed(1))
                            ])
                        ])
                    ],

                    // Isochronic Tones
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.audio.isochronicEnabled,
                            onchange: (e) => this.config.audio.isochronicEnabled = e.target.checked
                        }),
                        m('span', 'Isochronic tones')
                    ]),
                    this.config.audio.isochronicEnabled && m('.ml-8.space-y-4.mb-6', [
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Tone Frequency'),
                            m('input.flex-1', {
                                type: 'range', min: 100, max: 500, step: 10,
                                value: this.config.audio.isochronicFreq,
                                oninput: (e) => this.config.audio.isochronicFreq = parseInt(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.isochronicFreq} Hz`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Pulse Rate'),
                            m('input.flex-1', {
                                type: 'range', min: 2, max: 15, step: 0.5,
                                value: this.config.audio.isochronicRate,
                                oninput: (e) => this.config.audio.isochronicRate = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.isochronicRate} Hz`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Volume'),
                            m('input.flex-1', {
                                type: 'range', min: 0.05, max: 0.5, step: 0.05,
                                value: this.config.audio.isochronicVolume,
                                oninput: (e) => this.config.audio.isochronicVolume = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', this.config.audio.isochronicVolume.toFixed(2))
                        ])
                    ]),

                    // Audio Visualizer Overlay
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.audio.visualizerEnabled,
                            onchange: (e) => this.config.audio.visualizerEnabled = e.target.checked
                        }),
                        m('span', 'Audio visualizer overlay')
                    ]),
                    this.config.audio.visualizerEnabled && m('.ml-8.space-y-4', [
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Opacity'),
                            m('input.flex-1', {
                                type: 'range', min: 0.1, max: 0.6, step: 0.05,
                                value: this.config.audio.visualizerOpacity,
                                oninput: (e) => this.config.audio.visualizerOpacity = parseFloat(e.target.value)
                            }),
                            m('span.w-16.text-right', `${Math.round(this.config.audio.visualizerOpacity * 100)}%`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Gradient'),
                            m('select.flex-1.p-2.bg-gray-700.rounded.text-sm', {
                                value: this.config.audio.visualizerGradient,
                                onchange: (e) => this.config.audio.visualizerGradient = e.target.value
                            }, [
                                m('option', { value: 'prism' }, 'Prism'),
                                m('option', { value: 'rainbow' }, 'Rainbow'),
                                m('option', { value: 'classic' }, 'Classic')
                            ])
                        ])
                    ])
                ]),

                // Background Images Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Background Images'),
                    m('p.text-sm.text-gray-400.mb-3', 'Groups are imported from the blender tray automatically'),

                    // Selected groups list
                    this.selectedImageGroups.length > 0
                        ? [
                            m('.space-y-1.mb-4', this.selectedImageGroups.map(grp =>
                                m('.flex.items-center.justify-between.p-2.bg-gray-700.rounded', [
                                    m('.flex.items-center.gap-2', [
                                        m('span.material-icons.text-base.text-gray-400', 'folder'),
                                        m('span.text-sm', grp.name)
                                    ]),
                                    m('button.text-gray-400.hover:text-red-400.p-1', {
                                        onclick: () => {
                                            this.selectedImageGroups = this.selectedImageGroups.filter(g => g.objectId !== grp.objectId);
                                            this.config.images.baseGroups = this.config.images.baseGroups.filter(oid => oid !== grp.objectId);
                                        }
                                    }, m('span.material-icons.text-sm', 'close'))
                                ])
                            )),
                            m('.text-xs.text-gray-500',
                                `${this.selectedImageGroups.length} group(s) selected`
                            )
                        ]
                        : m('.p-3.bg-gray-700.rounded.text-gray-500.text-sm',
                            'No groups selected - no background images will cycle'
                        ),

                    // Cycle settings (shown when groups are selected)
                    this.config.images.baseGroups.length > 0 && m('.space-y-4.mt-4', [
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Cycle Interval'),
                            m('input.flex-1', {
                                type: 'range',
                                min: 2000,
                                max: 30000,
                                step: 1000,
                                value: this.config.images.cycleInterval,
                                oninput: (e) => this.config.images.cycleInterval = parseInt(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.images.cycleInterval / 1000}s`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Crossfade'),
                            m('input.flex-1', {
                                type: 'range',
                                min: 200,
                                max: 3000,
                                step: 200,
                                value: this.config.images.crossfadeDuration,
                                oninput: (e) => this.config.images.crossfadeDuration = parseInt(e.target.value)
                            }),
                            m('span.w-16.text-right', `${(this.config.images.crossfadeDuration / 1000).toFixed(1)}s`)
                        ]),
                        m('label.flex.items-center.gap-3.cursor-pointer', [
                            m('input.w-4.h-4.rounded', {
                                type: 'checkbox',
                                checked: this.config.images.includeGenerated,
                                onchange: (e) => this.config.images.includeGenerated = e.target.checked
                            }),
                            m('span.text-sm', 'Include AI-generated images in rotation')
                        ])
                    ])
                ]),

                // Visual Effects Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Visual Effects'),
                    m('.text-sm.text-gray-400.mb-3', 'Select which effects to render on the canvas overlay'),

                    // Effect checkboxes
                    m('.grid.gap-2.mb-4', { class: 'grid-cols-2 sm:grid-cols-4' },
                        ['particles', 'spiral', 'mandala', 'tunnel'].map(effect =>
                            m('label.flex.items-center.gap-2.cursor-pointer.p-2.bg-gray-700.rounded', [
                                m('input.w-4.h-4.rounded', {
                                    type: 'checkbox',
                                    checked: this.config.visuals.effects.includes(effect),
                                    onchange: (e) => {
                                        if (e.target.checked) {
                                            if (!this.config.visuals.effects.includes(effect)) {
                                                this.config.visuals.effects.push(effect);
                                            }
                                        } else {
                                            this.config.visuals.effects = this.config.visuals.effects.filter(ef => ef !== effect);
                                            if (this.config.visuals.effects.length === 0) {
                                                this.config.visuals.effects.push('particles');
                                                e.target.checked = true;
                                            }
                                        }
                                    }
                                }),
                                m('span.text-sm.capitalize', effect)
                            ])
                        )
                    ),

                    // Mode selector
                    m('.flex.items-center.gap-4.mb-4', [
                        m('label.w-24', 'Mode'),
                        m('select.flex-1.p-2.bg-gray-700.rounded.text-sm', {
                            value: this.config.visuals.mode,
                            onchange: (e) => this.config.visuals.mode = e.target.value
                        }, [
                            m('option', { value: 'single' }, 'Single (first selected)'),
                            m('option', { value: 'cycle' }, 'Cycle (rotate through selected)'),
                            m('option', { value: 'combined' }, 'Combined (overlay all selected)')
                        ])
                    ]),

                    // Cycle mode settings
                    this.config.visuals.mode === 'cycle' && m('.ml-8.space-y-4.mb-4', [
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Transition Interval'),
                            m('input.flex-1', {
                                type: 'range', min: 10, max: 120, step: 5,
                                value: this.config.visuals.transitionInterval / 1000,
                                oninput: (e) => this.config.visuals.transitionInterval = parseInt(e.target.value) * 1000
                            }),
                            m('span.w-16.text-right', `${this.config.visuals.transitionInterval / 1000}s`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-36', 'Transition Duration'),
                            m('input.flex-1', {
                                type: 'range', min: 1, max: 5, step: 0.5,
                                value: this.config.visuals.transitionDuration / 1000,
                                oninput: (e) => this.config.visuals.transitionDuration = parseFloat(e.target.value) * 1000
                            }),
                            m('span.w-16.text-right', `${(this.config.visuals.transitionDuration / 1000).toFixed(1)}s`)
                        ])
                    ]),

                    // Per-effect settings
                    this.config.visuals.effects.includes('particles') && m('.flex.items-center.gap-4.mb-2', [
                        m('label.w-36.text-sm.text-gray-400', 'Particle Count'),
                        m('input.flex-1', {
                            type: 'range', min: 50, max: 200, step: 10,
                            value: this.config.visuals.particleCount,
                            oninput: (e) => this.config.visuals.particleCount = parseInt(e.target.value)
                        }),
                        m('span.w-16.text-right.text-sm', this.config.visuals.particleCount)
                    ]),
                    this.config.visuals.effects.includes('spiral') && m('.flex.items-center.gap-4.mb-2', [
                        m('label.w-36.text-sm.text-gray-400', 'Spiral Speed'),
                        m('input.flex-1', {
                            type: 'range', min: 0.005, max: 0.03, step: 0.001,
                            value: this.config.visuals.spiralSpeed,
                            oninput: (e) => this.config.visuals.spiralSpeed = parseFloat(e.target.value)
                        }),
                        m('span.w-16.text-right.text-sm', this.config.visuals.spiralSpeed.toFixed(3))
                    ]),
                    this.config.visuals.effects.includes('mandala') && m('.flex.items-center.gap-4.mb-2', [
                        m('label.w-36.text-sm.text-gray-400', 'Mandala Layers'),
                        m('input.flex-1', {
                            type: 'range', min: 2, max: 6, step: 1,
                            value: this.config.visuals.mandalaLayers,
                            oninput: (e) => this.config.visuals.mandalaLayers = parseInt(e.target.value)
                        }),
                        m('span.w-16.text-right.text-sm', this.config.visuals.mandalaLayers)
                    ]),
                    this.config.visuals.effects.includes('tunnel') && m('.flex.items-center.gap-4.mb-2', [
                        m('label.w-36.text-sm.text-gray-400', 'Tunnel Rings'),
                        m('input.flex-1', {
                            type: 'range', min: 8, max: 20, step: 1,
                            value: this.config.visuals.tunnelRings,
                            oninput: (e) => this.config.visuals.tunnelRings = parseInt(e.target.value)
                        }),
                        m('span.w-16.text-right.text-sm', this.config.visuals.tunnelRings)
                    ])
                ]),

                // Image Generation Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'AI Image Generation'),
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.imageGeneration.enabled,
                            onchange: (e) => this.config.imageGeneration.enabled = e.target.checked
                        }),
                        m('span', 'Enable SD image generation from camera')
                    ]),
                    this.config.imageGeneration.enabled && m('.ml-8.space-y-4', [
                        // Source toggle: existing config vs create new
                        m('.flex.gap-2.mb-4', [
                            m('button', {
                                class: 'px-3 py-1 rounded text-sm ' +
                                    (!this.createNewSdConfig ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'),
                                onclick: () => { this.createNewSdConfig = false; }
                            }, 'Select Existing'),
                            m('button', {
                                class: 'px-3 py-1 rounded text-sm ' +
                                    (this.createNewSdConfig ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'),
                                onclick: () => {
                                    this.createNewSdConfig = true;
                                    this.config.imageGeneration.sdConfigId = null;
                                }
                            }, 'Create New')
                        ]),

                        // Existing config selector
                        !this.createNewSdConfig && [
                            m('label.block.mb-1', 'SD Configuration'),
                            this.sdConfigs.length > 0
                                ? m('select.w-full.p-2.bg-gray-700.rounded', {
                                    value: this.config.imageGeneration.sdConfigId || '',
                                    onchange: (e) => {
                                        this.config.imageGeneration.sdConfigId = e.target.value || null;
                                        this._loadSdConfigDetails(e.target.value || null);
                                    }
                                }, [
                                    m('option', { value: '' }, '-- Default Config --'),
                                    ...this.sdConfigs.map(c =>
                                        m('option', { value: c.objectId }, c.name)
                                    )
                                ])
                                : m('.p-3.bg-gray-700.rounded.text-gray-400.text-sm', [
                                    m('span', 'No saved configs found in ~/Magic8/SDConfigs.'),
                                    m('br'),
                                    m('span', 'Using default config, or switch to '),
                                    m('a.text-indigo-400.cursor-pointer', {
                                        onclick: () => { this.createNewSdConfig = true; this.config.imageGeneration.sdConfigId = null; }
                                    }, 'Create New'),
                                    m('span', '.')
                                ]),

                            // SD config detail preview
                            this.loadingSdDetails && m('.mt-3.p-3.bg-gray-700.rounded.text-sm.text-gray-400', 'Loading config details...'),
                            !this.loadingSdDetails && this.selectedSdDetails && this.config.imageGeneration.sdConfigId && m('.mt-3.p-3.bg-gray-700.rounded.text-sm.space-y-2', [
                                m('.text-gray-400.font-medium.mb-2', 'Config Preview'),
                                this.selectedSdDetails.style && m('.flex.gap-2', [
                                    m('span.text-gray-500.w-28', 'Style:'),
                                    m('span', this.selectedSdDetails.style)
                                ]),
                                this.selectedSdDetails.description && m('.flex.gap-2', [
                                    m('span.text-gray-500.w-28', 'Description:'),
                                    m('span.text-gray-300', this.selectedSdDetails.description.length > 80
                                        ? this.selectedSdDetails.description.substring(0, 80) + '...'
                                        : this.selectedSdDetails.description)
                                ]),
                                this.selectedSdDetails.imageAction && m('.flex.gap-2', [
                                    m('span.text-gray-500.w-28', 'Image Action:'),
                                    m('span', this.selectedSdDetails.imageAction)
                                ]),
                                this.selectedSdDetails.model && m('.flex.gap-2', [
                                    m('span.text-gray-500.w-28', 'Model:'),
                                    m('span.text-gray-300', this.selectedSdDetails.model)
                                ]),
                                m('.flex.flex-wrap.gap-x-6.gap-y-1.mt-1', [
                                    this.selectedSdDetails.denoisingStrength != null && m('span.text-gray-400',
                                        `Denoising: ${this.selectedSdDetails.denoisingStrength}`),
                                    this.selectedSdDetails.cfg != null && m('span.text-gray-400',
                                        `CFG: ${this.selectedSdDetails.cfg}`),
                                    this.selectedSdDetails.steps != null && m('span.text-gray-400',
                                        `Steps: ${this.selectedSdDetails.steps}`),
                                    this.selectedSdDetails.sampler && m('span.text-gray-400',
                                        `Sampler: ${this.selectedSdDetails.sampler}`),
                                    (this.selectedSdDetails.width || this.selectedSdDetails.height) && m('span.text-gray-400',
                                        `Size: ${this.selectedSdDetails.width || '?'}x${this.selectedSdDetails.height || '?'}`)
                                ])
                            ])
                        ],

                        // Inline new config editor
                        this.createNewSdConfig && [
                            m('.grid.gap-4.mb-3', { class: 'grid-cols-1 sm:grid-cols-2' }, [
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Model'),
                                    m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                        value: this.config.imageGeneration.sdInline.model,
                                        onchange: (e) => this.config.imageGeneration.sdInline.model = e.target.value
                                    }, [
                                        m('option', { value: '' }, '-- Select Model --'),
                                        ...['juggernautXL_ragnarokBy.safetensors','dreamshaperXL_v21TurboDPMSDE','chilloutmix_Ni','realismFromHadesXL_lightningV3','realmixXL_V10.safetensors','lustifySDXLNSFW_endgame.safetensors','ponyRealism_V22.safetensors','sdXL_v10VAEFix'].map(v =>
                                            m('option', { value: v }, v)
                                        )
                                    ])
                                ]),
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Refiner'),
                                    m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                        value: this.config.imageGeneration.sdInline.refinerModel,
                                        onchange: (e) => this.config.imageGeneration.sdInline.refinerModel = e.target.value
                                    }, [
                                        m('option', { value: '' }, '-- None --'),
                                        ...['juggernautXL_ragnarokBy.safetensors','dreamshaperXL_v21TurboDPMSDE','chilloutmix_Ni','realismFromHadesXL_lightningV3','realmixXL_V10.safetensors','lustifySDXLNSFW_endgame.safetensors','ponyRealism_V22.safetensors','sdXL_v10VAEFix'].map(v =>
                                            m('option', { value: v }, v)
                                        )
                                    ])
                                ])
                            ]),

                            m('.flex.items-end.gap-3.mb-3', [
                                m('.flex-1', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Style'),
                                    m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                        value: this.config.imageGeneration.sdInline.style,
                                        onchange: (e) => {
                                            this.config.imageGeneration.sdInline.style = e.target.value;
                                            this.randomSdExtras = null; // clear stale extras on manual style change
                                        }
                                    }, [
                                        ...['art','photograph','movie','selfie','anime','portrait','comic','digitalArt','fashion','vintage','custom'].map(s =>
                                            m('option', { value: s }, s.charAt(0).toUpperCase() + s.slice(1))
                                        )
                                    ])
                                ]),
                                m('button', {
                                    class: 'px-3 py-2 rounded text-sm font-medium whitespace-nowrap ' +
                                        (this.fetchingRandomSd ? 'bg-gray-600 text-gray-400' : 'bg-indigo-600 text-white hover:bg-indigo-500'),
                                    disabled: this.fetchingRandomSd,
                                    onclick: () => this._fetchRandomSdConfig(),
                                    title: 'Fetch a randomized config from the server with style-specific fields'
                                }, this.fetchingRandomSd ? 'Fetching...' : 'Randomize')
                            ]),

                            // Style-specific fields preview (from randomize)
                            this.randomSdExtras && m('.mb-3.p-3.bg-gray-700/50.rounded.text-sm', [
                                m('.text-gray-400.font-medium.mb-2', 'Style Details (' + this.config.imageGeneration.sdInline.style + ')'),
                                ...Object.entries(this.randomSdExtras).map(([key, value]) =>
                                    m('.flex.gap-2', { key: key }, [
                                        m('span.text-gray-500.w-36', key.replace(/([A-Z])/g, ' $1').trim() + ':'),
                                        m('span.text-gray-300', String(value))
                                    ])
                                )
                            ]),

                            m('label.block.text-sm.text-gray-400.mb-1', 'Description'),
                            m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                rows: 2,
                                value: this.config.imageGeneration.sdInline.description,
                                oninput: (e) => this.config.imageGeneration.sdInline.description = e.target.value
                            }),

                            m('label.block.text-sm.text-gray-400.mb-1.mt-3', 'Image Action'),
                            m('input.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                type: 'text',
                                value: this.config.imageGeneration.sdInline.imageAction,
                                oninput: (e) => this.config.imageGeneration.sdInline.imageAction = e.target.value
                            }),

                            m('.grid.gap-4.mt-3', { class: 'grid-cols-1 sm:grid-cols-2' }, [
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Denoising Strength'),
                                    m('.flex.items-center.gap-2', [
                                        m('input.flex-1', {
                                            type: 'range', min: 0.1, max: 1.0, step: 0.05,
                                            value: this.config.imageGeneration.sdInline.denoisingStrength,
                                            oninput: (e) => this.config.imageGeneration.sdInline.denoisingStrength = parseFloat(e.target.value)
                                        }),
                                        m('span.text-sm.w-10.text-right', this.config.imageGeneration.sdInline.denoisingStrength.toFixed(2))
                                    ])
                                ]),
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'CFG'),
                                    m('.flex.items-center.gap-2', [
                                        m('input.flex-1', {
                                            type: 'range', min: 1, max: 20, step: 0.5,
                                            value: this.config.imageGeneration.sdInline.cfg,
                                            oninput: (e) => this.config.imageGeneration.sdInline.cfg = parseFloat(e.target.value)
                                        }),
                                        m('span.text-sm.w-10.text-right', this.config.imageGeneration.sdInline.cfg)
                                    ])
                                ]),
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Steps'),
                                    m('.flex.items-center.gap-2', [
                                        m('input.flex-1', {
                                            type: 'range', min: 10, max: 80, step: 5,
                                            value: this.config.imageGeneration.sdInline.steps,
                                            oninput: (e) => this.config.imageGeneration.sdInline.steps = parseInt(e.target.value)
                                        }),
                                        m('span.text-sm.w-10.text-right', this.config.imageGeneration.sdInline.steps)
                                    ])
                                ]),
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Sampler'),
                                    m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                        value: this.config.imageGeneration.sdInline.sampler,
                                        onchange: (e) => this.config.imageGeneration.sdInline.sampler = e.target.value
                                    }, [
                                        m('option', { value: 'dpmpp_2m' }, 'DPM++ 2M'),
                                        m('option', { value: 'euler_a' }, 'Euler A'),
                                        m('option', { value: 'euler' }, 'Euler'),
                                        m('option', { value: 'dpm_2' }, 'DPM 2'),
                                        m('option', { value: 'dpm_2_a' }, 'DPM 2 A'),
                                        m('option', { value: 'lms' }, 'LMS'),
                                        m('option', { value: 'heun' }, 'Heun'),
                                        m('option', { value: 'ddim' }, 'DDIM')
                                    ])
                                ])
                            ]),

                            m('.grid.gap-4.mt-3', { class: 'grid-cols-1 sm:grid-cols-2' }, [
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Width'),
                                    m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                        value: this.config.imageGeneration.sdInline.width,
                                        onchange: (e) => this.config.imageGeneration.sdInline.width = parseInt(e.target.value)
                                    }, [256, 384, 512, 640, 768, 1024].map(v =>
                                        m('option', { value: v }, v + 'px')
                                    ))
                                ]),
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Height'),
                                    m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                        value: this.config.imageGeneration.sdInline.height,
                                        onchange: (e) => this.config.imageGeneration.sdInline.height = parseInt(e.target.value)
                                    }, [256, 384, 512, 640, 768, 1024].map(v =>
                                        m('option', { value: v }, v + 'px')
                                    ))
                                ])
                            ]),

                            m('.flex.gap-2.mt-3', [
                                m('button', {
                                    class: 'px-4 py-2 rounded text-sm font-medium ' +
                                        (this.isSaving ? 'bg-gray-600 text-gray-400' : 'bg-green-600 text-white hover:bg-green-500'),
                                    disabled: this.isSaving,
                                    onclick: () => this._saveNewSdConfig()
                                }, this.isSaving ? 'Saving...' : 'Save Config'),
                                m('.text-xs.text-gray-500.self-center', 'Save to ~/Magic8/SDConfigs for reuse, or use inline without saving')
                            ])
                        ],

                        // Capture interval (always shown)
                        m('.flex.items-center.gap-4.mt-4', [
                            m('label.w-32', 'Capture Interval'),
                            m('input.flex-1', {
                                type: 'range',
                                min: 60000,
                                max: 600000,
                                step: 30000,
                                value: this.config.imageGeneration.captureInterval,
                                oninput: (e) => this.config.imageGeneration.captureInterval = parseInt(e.target.value)
                            }),
                            m('span.w-20.text-right', `${(this.config.imageGeneration.captureInterval / 60000).toFixed(1)} min`)
                        ])
                    ])
                ]),

                // Text Sequence Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Hypnotic Text'),
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.text.enabled,
                            onchange: (e) => this.config.text.enabled = e.target.checked
                        }),
                        m('span', 'Enable text sequence')
                    ]),
                    this.config.text.enabled && m('.ml-8.space-y-4', [
                        // Source toggle: existing vs create new
                        m('.flex.gap-2.mb-4', [
                            m('button', {
                                class: 'px-3 py-1 rounded text-sm ' +
                                    (!this.createNewTextSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'),
                                onclick: () => { this.createNewTextSource = false; }
                            }, 'Select Existing'),
                            m('button', {
                                class: 'px-3 py-1 rounded text-sm ' +
                                    (this.createNewTextSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'),
                                onclick: () => {
                                    this.createNewTextSource = true;
                                    this.config.text.sourceObjectId = null;
                                }
                            }, 'Create New')
                        ]),

                        // Existing source selector
                        !this.createNewTextSource && [
                            m('label.block.mb-1', 'Text Source'),
                            this.textSources.length > 0
                                ? m('select.w-full.p-2.bg-gray-700.rounded', {
                                    value: this.config.text.sourceObjectId || '',
                                    onchange: (e) => {
                                        const selected = this.textSources.find(t => t.objectId === e.target.value);
                                        this.config.text.sourceObjectId = e.target.value || null;
                                        this.config.text.sourceType = selected?.sourceType || 'note';
                                    }
                                }, [
                                    m('option', { value: '' }, '-- Select Text Source --'),
                                    ...this.textSources.map(t =>
                                        m('option', { value: t.objectId }, `${t.name} (${t.sourceType})`)
                                    )
                                ])
                                : m('.p-3.bg-gray-700.rounded.text-gray-400.text-sm', [
                                    m('span', 'No text sources found in ~/Magic8/TextSequences.'),
                                    m('br'),
                                    m('span', 'Switch to '),
                                    m('a.text-indigo-400.cursor-pointer', {
                                        onclick: () => { this.createNewTextSource = true; this.config.text.sourceObjectId = null; }
                                    }, 'Create New'),
                                    m('span', ' to add one.')
                                ])
                        ],

                        // Inline new text source editor
                        this.createNewTextSource && [
                            m('label.block.text-sm.text-gray-400.mb-1', 'Name'),
                            m('input.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                type: 'text',
                                placeholder: 'e.g. Relaxation Sequence',
                                value: this.newTextName,
                                oninput: (e) => this.newTextName = e.target.value
                            }),

                            m('label.block.text-sm.text-gray-400.mb-1.mt-3', 'Type'),
                            m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                value: this.newTextSourceType,
                                onchange: (e) => this.newTextSourceType = e.target.value
                            }, [
                                m('option', { value: 'note' }, 'Note (plain text lines)'),
                                m('option', { value: 'data' }, 'Data (JSON array)')
                            ]),

                            m('label.block.text-sm.text-gray-400.mb-1.mt-3',
                                this.newTextSourceType === 'note'
                                    ? 'Text (one phrase per line)'
                                    : 'Text (JSON array of strings)'
                            ),
                            m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm.font-mono', {
                                rows: 6,
                                placeholder: this.newTextSourceType === 'note'
                                    ? 'Relax and breathe deeply\nLet go of all tension\nFeel the calm wash over you'
                                    : '["Relax and breathe deeply", "Let go of all tension", "Feel the calm wash over you"]',
                                value: this.newTextContent,
                                oninput: (e) => this.newTextContent = e.target.value
                            }),

                            m('button', {
                                class: 'mt-3 px-4 py-2 rounded text-sm font-medium ' +
                                    (this.isSaving ? 'bg-gray-600 text-gray-400' : 'bg-green-600 text-white hover:bg-green-500'),
                                disabled: this.isSaving || !this.newTextName.trim() || !this.newTextContent.trim(),
                                onclick: () => this._saveNewTextSource()
                            }, this.isSaving ? 'Saving...' : 'Save & Select')
                        ],

                        // Options (always shown)
                        m('label.flex.items-center.gap-3.cursor-pointer.mt-4', [
                            m('input.w-5.h-5.rounded', {
                                type: 'checkbox',
                                checked: this.config.text.loop,
                                onchange: (e) => this.config.text.loop = e.target.checked
                            }),
                            m('span', 'Loop text sequence')
                        ]),
                        m('.flex.items-center.gap-4.mt-2', [
                            m('label.w-36', 'Display Duration'),
                            m('input.flex-1', {
                                type: 'range',
                                min: 2000,
                                max: 15000,
                                step: 1000,
                                value: this.config.text.displayDuration,
                                oninput: (e) => this.config.text.displayDuration = parseInt(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.text.displayDuration / 1000}s`)
                        ])
                    ])
                ]),

                // Voice Sequence Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Voice Sequence'),
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.voice.enabled,
                            onchange: (e) => this.config.voice.enabled = e.target.checked
                        }),
                        m('span', 'Enable voice sequence playback')
                    ]),
                    this.config.voice.enabled && m('.ml-8.space-y-4', [
                        // Source toggle: existing vs create new
                        m('.flex.gap-2.mb-4', [
                            m('button', {
                                class: 'px-3 py-1 rounded text-sm ' +
                                    (!this.createNewVoiceSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'),
                                onclick: () => { this.createNewVoiceSource = false; }
                            }, 'Select Existing'),
                            m('button', {
                                class: 'px-3 py-1 rounded text-sm ' +
                                    (this.createNewVoiceSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'),
                                onclick: () => {
                                    this.createNewVoiceSource = true;
                                    this.config.voice.sourceObjectId = null;
                                }
                            }, 'Create New')
                        ]),

                        // Existing source selector
                        !this.createNewVoiceSource && [
                            m('label.block.mb-1', 'Voice Source'),
                            this.voiceSources.length > 0
                                ? m('select.w-full.p-2.bg-gray-700.rounded', {
                                    value: this.config.voice.sourceObjectId || '',
                                    onchange: (e) => {
                                        const selected = this.voiceSources.find(t => t.objectId === e.target.value);
                                        this.config.voice.sourceObjectId = e.target.value || null;
                                        this.config.voice.sourceType = selected?.sourceType || 'note';
                                    }
                                }, [
                                    m('option', { value: '' }, '-- Select Voice Source --'),
                                    ...this.voiceSources.map(t =>
                                        m('option', { value: t.objectId }, `${t.name} (${t.sourceType})`)
                                    )
                                ])
                                : m('.p-3.bg-gray-700.rounded.text-gray-400.text-sm', [
                                    m('span', 'No voice sources found in ~/Magic8/VoiceSequences.'),
                                    m('br'),
                                    m('span', 'Switch to '),
                                    m('a.text-indigo-400.cursor-pointer', {
                                        onclick: () => { this.createNewVoiceSource = true; this.config.voice.sourceObjectId = null; }
                                    }, 'Create New'),
                                    m('span', ' to add one.')
                                ])
                        ],

                        // Inline new voice source editor
                        this.createNewVoiceSource && [
                            m('label.block.text-sm.text-gray-400.mb-1', 'Name'),
                            m('input.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                type: 'text',
                                placeholder: 'e.g. Guided Meditation',
                                value: this.newVoiceName,
                                oninput: (e) => this.newVoiceName = e.target.value
                            }),

                            m('label.block.text-sm.text-gray-400.mb-1.mt-3', 'Type'),
                            m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                value: this.newVoiceSourceType,
                                onchange: (e) => this.newVoiceSourceType = e.target.value
                            }, [
                                m('option', { value: 'note' }, 'Note (plain text lines)'),
                                m('option', { value: 'data' }, 'Data (base64 text)')
                            ]),

                            m('label.block.text-sm.text-gray-400.mb-1.mt-3', 'Text (one phrase per line, each line synthesized separately)'),
                            m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm.font-mono', {
                                rows: 6,
                                placeholder: 'Close your eyes and relax\nTake a deep breath in\nHold it for a moment\nNow slowly exhale',
                                value: this.newVoiceContent,
                                oninput: (e) => this.newVoiceContent = e.target.value
                            }),

                            m('button', {
                                class: 'mt-3 px-4 py-2 rounded text-sm font-medium ' +
                                    (this.isSaving ? 'bg-gray-600 text-gray-400' : 'bg-green-600 text-white hover:bg-green-500'),
                                disabled: this.isSaving || !this.newVoiceName.trim() || !this.newVoiceContent.trim(),
                                onclick: () => this._saveNewVoiceSource()
                            }, this.isSaving ? 'Saving...' : 'Save & Select')
                        ],

                        // Voice Profile selector
                        m('label.block.mb-1.mt-4', 'Voice Profile'),
                        this.voiceProfiles.length > 0
                            ? m('select.w-full.p-2.bg-gray-700.rounded', {
                                value: this.config.voice.voiceProfileId || '',
                                onchange: (e) => this.config.voice.voiceProfileId = e.target.value || null
                            }, [
                                m('option', { value: '' }, '-- Default Voice (Piper Alba) --'),
                                ...this.voiceProfiles.map(vp =>
                                    m('option', { value: vp.objectId }, vp.name || `${vp.engine || 'piper'} - ${vp.speaker || 'default'}`)
                                )
                            ])
                            : m('.p-2.bg-gray-700.rounded.text-gray-400.text-sm',
                                'No voice profiles found. Default Piper voice will be used.'
                            ),

                        // Loop option
                        m('label.flex.items-center.gap-3.cursor-pointer.mt-4', [
                            m('input.w-5.h-5.rounded', {
                                type: 'checkbox',
                                checked: this.config.voice.loop,
                                onchange: (e) => this.config.voice.loop = e.target.checked
                            }),
                            m('span', 'Loop voice sequence')
                        ])
                    ])
                ]),

                // AI Session Director Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'AI Session Director'),
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-3', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.director.enabled,
                            onchange: (e) => this.config.director.enabled = e.target.checked
                        }),
                        m('span', 'Enable AI Session Director')
                    ]),
                    m('p.text-xs.text-gray-400.mb-3', 'LLM-powered orchestration that adjusts audio, visuals, and labels based on session state. Requires \'Open Chat\' config in ~/Chat.'),
                    this.config.director.enabled && m('.space-y-4.mt-3.pl-2.border-l-2.border-gray-700', [
                        m('.config-field', [
                            m('label.block.text-sm.font-medium.mb-1', 'Session Command / Intent'),
                            m('textarea.w-full.bg-gray-900.rounded.px-3.py-2.text-sm', {
                                rows: 3,
                                placeholder: 'Guide me through a deep relaxation journey...',
                                value: this.config.director.command,
                                oninput: (e) => this.config.director.command = e.target.value
                            })
                        ]),
                        m('.config-field', [
                            m('label.block.text-sm.font-medium.mb-1',
                                'Check-in Interval: ' + Math.round(this.config.director.intervalMs / 1000) + 's'
                            ),
                            m('input.w-full', {
                                type: 'range',
                                min: 30000,
                                max: 300000,
                                step: 15000,
                                value: this.config.director.intervalMs,
                                oninput: (e) => this.config.director.intervalMs = parseInt(e.target.value)
                            })
                        ]),

                        // Test mode toggle
                        m('label.flex.items-center.gap-3.cursor-pointer', [
                            m('input.w-4.h-4.rounded', {
                                type: 'checkbox',
                                checked: this.config.director.testMode,
                                onchange: (e) => this.config.director.testMode = e.target.checked
                            }),
                            m('span.text-sm', 'Test Mode'),
                            m('span.text-xs.text-gray-500.ml-1', '(run diagnostics on session start, apply test directive)')
                        ]),

                        // Test button
                        m('.config-field.mt-2', [
                            m('button', {
                                class: 'px-4 py-2 rounded text-sm font-medium ' +
                                    (this.directorTestRunning
                                        ? 'bg-gray-600 text-gray-400'
                                        : 'bg-yellow-600 text-white hover:bg-yellow-500'),
                                disabled: this.directorTestRunning,
                                onclick: async () => {
                                    this.directorTestRunning = true;
                                    this.directorTestResults = null;
                                    m.redraw();
                                    try {
                                        const director = new Magic8.SessionDirector();
                                        this.directorTestResults = await director.runDiagnostics(
                                            this.config.director.command || 'Diagnostic test'
                                        );
                                        director.dispose();
                                    } catch (err) {
                                        this.directorTestResults = [{ name: 'Test runner', pass: false, detail: err.message }];
                                    }
                                    this.directorTestRunning = false;
                                    m.redraw();
                                }
                            }, this.directorTestRunning ? 'Testing...' : 'Test Director'),
                            m('span.text-xs.text-gray-500.ml-2', 'Runs all checks including a live LLM call')
                        ]),

                        // Test results
                        this.directorTestResults && m('.mt-3.p-3.bg-gray-900.rounded.text-sm.font-mono.space-y-1',
                            this.directorTestResults.map(r =>
                                m('.flex.items-start.gap-2', [
                                    m('span', { style: { color: r.pass ? '#4ade80' : '#f87171', minWidth: '20px' } },
                                        r.pass ? 'OK' : 'XX'),
                                    m('span.text-gray-300', r.name),
                                    r.detail && m('span.text-gray-500', ' - ' + r.detail)
                                ])
                            )
                        )
                    ])
                ]),

                // Recording Section
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Session Recording'),
                    m('label.flex.items-center.gap-3.cursor-pointer', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.recording.enabled,
                            onchange: (e) => this.config.recording.enabled = e.target.checked
                        }),
                        m('span', 'Enable video recording')
                    ]),

                    // Sub-options shown when recording is enabled
                    this.config.recording.enabled && m('.mt-4.ml-2.space-y-4', [
                        m('label.flex.items-center.gap-3.cursor-pointer', [
                            m('input.w-5.h-5.rounded', {
                                type: 'checkbox',
                                checked: this.config.recording.autoStart,
                                onchange: (e) => this.config.recording.autoStart = e.target.checked
                            }),
                            m('span', 'Auto-start when session begins')
                        ]),
                        m('.space-y-1', [
                            m('label.text-sm.text-gray-400', 'Max duration: ' + this.config.recording.maxDurationMin + ' min'),
                            m('input.w-full', {
                                type: 'range',
                                min: 5,
                                max: 120,
                                step: 5,
                                value: this.config.recording.maxDurationMin,
                                oninput: (e) => this.config.recording.maxDurationMin = parseInt(e.target.value)
                            })
                        ]),
                        m('p.text-xs.text-gray-500', 'Recording can also be toggled via the control bar during a session.')
                    ])
                ]),

                // Action Buttons
                m('.flex.flex-col.gap-3.mt-8', { class: 'sm:flex-row sm:gap-4' }, [
                    m('button.flex-1.py-3.px-6.bg-gray-700.rounded-lg.font-medium.transition', {
                        class: 'hover:bg-gray-600 active:bg-gray-500',
                        style: { minHeight: '44px' },
                        onclick: onCancel
                    }, 'Cancel'),
                    m('button.flex-1.py-3.px-6.bg-indigo-600.rounded-lg.font-medium.transition', {
                        class: 'hover:bg-indigo-500 active:bg-indigo-400',
                        style: { minHeight: '44px' },
                        onclick: () => onSave && onSave(this.config)
                    }, 'Start Session')
                ])
            ])
        ]);
    }
};

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = SessionConfigEditor;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.SessionConfigEditor = SessionConfigEditor;
}
