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
                sweepDurationMin: 5,
                toneEnabled: false
            },
            images: {
                baseGroups: [],
                cycleInterval: 5000,
                crossfadeDuration: 1000,
                includeGenerated: true
            },
            imageGeneration: {
                enabled: false,
                sdConfigId: null,
                captureInterval: 120000,
                maxGeneratedImages: 20,
                sdInline: {
                    model: '',
                    refiner: '',
                    prompt: 'ethereal dreamlike portrait, soft lighting, mystical atmosphere',
                    negative_prompt: 'blurry, distorted, ugly, deformed, nsfw',
                    strength: 0.65,
                    steps: 30,
                    cfg_scale: 7.5,
                    width: 512,
                    height: 512,
                    sampler: 'euler_a'
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
                autoStart: false,
                maxDurationMin: 30
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
            const loaded = JSON.parse(uwm.base64Decode(obj.dataBytesStore));
            // Merge with defaults to ensure all keys exist
            const defaults = this._getDefaultConfig();
            this.config = Object.assign({}, defaults, loaded);
            this.config.display = Object.assign({}, defaults.display, loaded.display || {});
            this.config.biometrics = Object.assign({}, defaults.biometrics, loaded.biometrics || {});
            this.config.audio = Object.assign({}, defaults.audio, loaded.audio || {});
            this.config.images = Object.assign({}, defaults.images, loaded.images || {});
            this.config.imageGeneration = Object.assign({}, defaults.imageGeneration, loaded.imageGeneration || {});
            if (loaded.imageGeneration?.sdInline) {
                this.config.imageGeneration.sdInline = Object.assign({}, defaults.imageGeneration.sdInline, loaded.imageGeneration.sdInline);
            }
            this.config.text = Object.assign({}, defaults.text, loaded.text || {});
            this.config.voice = Object.assign({}, defaults.voice, loaded.voice || {});
            this.config.recording = Object.assign({}, defaults.recording, loaded.recording || {});

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
                        value: '',
                        onchange: (e) => {
                            if (e.target.value) {
                                this._loadSavedSession(e.target.value);
                                e.target.value = '';
                            }
                        }
                    }, [
                        m('option', { value: '' }, '-- Select a saved session --'),
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
                    m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                        m('input.w-5.h-5.rounded', {
                            type: 'checkbox',
                            checked: this.config.audio.binauralEnabled,
                            onchange: (e) => this.config.audio.binauralEnabled = e.target.checked
                        }),
                        m('span', 'Enable binaural beats')
                    ]),
                    this.config.audio.binauralEnabled && m('.ml-8.space-y-4', [
                        m('.flex.items-center.gap-4', [
                            m('label.w-32', 'Base Frequency'),
                            m('input.flex-1', {
                                type: 'range',
                                min: 200,
                                max: 600,
                                value: this.config.audio.baseFreq,
                                oninput: (e) => this.config.audio.baseFreq = parseInt(e.target.value)
                            }),
                            m('span.w-16.text-right', `${this.config.audio.baseFreq} Hz`)
                        ]),
                        m('.flex.items-center.gap-4', [
                            m('label.w-32', 'Beat Range'),
                            m('span', `${this.config.audio.minBeat} - ${this.config.audio.maxBeat} Hz`)
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
                                    onchange: (e) => this.config.imageGeneration.sdConfigId = e.target.value || null
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
                                        value: this.config.imageGeneration.sdInline.refiner,
                                        onchange: (e) => this.config.imageGeneration.sdInline.refiner = e.target.value
                                    }, [
                                        m('option', { value: '' }, '-- None --'),
                                        ...['juggernautXL_ragnarokBy.safetensors','dreamshaperXL_v21TurboDPMSDE','chilloutmix_Ni','realismFromHadesXL_lightningV3','realmixXL_V10.safetensors','lustifySDXLNSFW_endgame.safetensors','ponyRealism_V22.safetensors','sdXL_v10VAEFix'].map(v =>
                                            m('option', { value: v }, v)
                                        )
                                    ])
                                ])
                            ]),

                            m('label.block.text-sm.text-gray-400.mb-1', 'Prompt'),
                            m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                rows: 2,
                                value: this.config.imageGeneration.sdInline.prompt,
                                oninput: (e) => this.config.imageGeneration.sdInline.prompt = e.target.value
                            }),

                            m('label.block.text-sm.text-gray-400.mb-1.mt-3', 'Negative Prompt'),
                            m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                rows: 2,
                                value: this.config.imageGeneration.sdInline.negative_prompt,
                                oninput: (e) => this.config.imageGeneration.sdInline.negative_prompt = e.target.value
                            }),

                            m('.grid.gap-4.mt-3', { class: 'grid-cols-1 sm:grid-cols-2' }, [
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Strength'),
                                    m('.flex.items-center.gap-2', [
                                        m('input.flex-1', {
                                            type: 'range', min: 0.1, max: 1.0, step: 0.05,
                                            value: this.config.imageGeneration.sdInline.strength,
                                            oninput: (e) => this.config.imageGeneration.sdInline.strength = parseFloat(e.target.value)
                                        }),
                                        m('span.text-sm.w-10.text-right', this.config.imageGeneration.sdInline.strength.toFixed(2))
                                    ])
                                ]),
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'CFG Scale'),
                                    m('.flex.items-center.gap-2', [
                                        m('input.flex-1', {
                                            type: 'range', min: 1, max: 20, step: 0.5,
                                            value: this.config.imageGeneration.sdInline.cfg_scale,
                                            oninput: (e) => this.config.imageGeneration.sdInline.cfg_scale = parseFloat(e.target.value)
                                        }),
                                        m('span.text-sm.w-10.text-right', this.config.imageGeneration.sdInline.cfg_scale)
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
                                    }, [256, 384, 512, 640, 768].map(v =>
                                        m('option', { value: v }, v + 'px')
                                    ))
                                ]),
                                m('div', [
                                    m('label.block.text-sm.text-gray-400.mb-1', 'Height'),
                                    m('select.w-full.p-2.bg-gray-700.rounded.text-sm', {
                                        value: this.config.imageGeneration.sdInline.height,
                                        onchange: (e) => this.config.imageGeneration.sdInline.height = parseInt(e.target.value)
                                    }, [256, 384, 512, 640, 768].map(v =>
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
